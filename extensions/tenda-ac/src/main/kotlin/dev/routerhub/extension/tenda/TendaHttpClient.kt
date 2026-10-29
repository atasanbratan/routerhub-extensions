package dev.routerhub.extension.tenda

import dev.routerhub.api.CommandFailedException
import dev.routerhub.api.ConnectionConfig
import dev.routerhub.api.FirmwareParseException
import dev.routerhub.api.FirmwareTimeoutException
import dev.routerhub.api.InvalidConfigurationException
import dev.routerhub.api.RouterBusyException
import dev.routerhub.api.RouterException
import dev.routerhub.api.RouterSessionExpiredException
import dev.routerhub.api.RouterUnreachableException
import dev.routerhub.api.WrongCredentialsException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * HTTP layer for Tenda's AC-series web UI (observed on an AC10 V6.0, firmware V16.03.61.08).
 *
 * How the firmware's own pages talk to it, which is all there is to reproduce:
 *  - **Login**: `POST /login/Auth` with `username` and the MD5 of the password. Success is a redirect
 *    to `main.html` that sets the cookie `password=<token>`. A wrong password is a plain `200` whose
 *    body is a code: `1`/`2` wrong password, `3` logged in elsewhere, `4` locked.
 *  - **Key**: after login `GET /goform/stokCfg` returns `{"sign": "<16 characters>"}`, the AES key
 *    for everything else (see [TendaCrypto]). Every call to it replaces the key, so a key is only
 *    good until somebody, anybody, asks for the next one.
 *  - **Reads** are `GET /goform/<Name>`, answered with scrambled JSON. **Writes** are
 *    `POST /goform/<Name>` with the scrambled body and answer `{"errCode": 0}` when they worked.
 *
 * Session quirks found on real hardware, and handled here:
 *  - A request without a valid cookie to a protected page is redirected to `login.html` and ends
 *    the session that the address had. So every request carries the cookie, and redirects are
 *    never followed (they are how "not logged in" is told).
 *  - A login while the address still has a session fails once, with that same redirect, and ends
 *    it. The second attempt works.
 *  - Answers use bare line feeds in their headers; OkHttp accepts that.
 */
internal class TendaHttpClient(config: ConnectionConfig.Web) : TendaApi {

    private val baseUrl: HttpUrl = HttpUrl.Builder()
        .scheme(if (config.useHttps) "https" else "http")
        .host(config.host)
        .port(config.port)
        .build()

    // The login page has no user name field: it posts a hidden "admin".
    private val username = config.username.ifBlank { "admin" }

    @Volatile
    private var password = config.password

    /** After the login password was changed on the router, later logins must use the new one. */
    fun updatePassword(value: String) {
        password = value
    }

    // OkHttp's classes come from the host app (shared platform); this instance is still ours.
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(config.timeoutMillis, TimeUnit.MILLISECONDS)
        .readTimeout(config.timeoutMillis, TimeUnit.MILLISECONDS)
        // Bounds the whole exchange, body included, which coroutine cancellation alone can't interrupt.
        .callTimeout(config.timeoutMillis * 2, TimeUnit.MILLISECONDS)
        // The router says "you are not logged in" with a redirect. Following it would hide that.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    @Volatile
    private var cookie: String? = null

    @Volatile
    private var sign: String? = null

    private val nonce = AtomicLong(System.nanoTime())

    private class Answer(val code: Int, val body: String, val location: String?, val setCookies: List<String>) {
        val isRedirect: Boolean get() = code in 300..399
        val sendsToLogin: Boolean get() = isRedirect && location.orEmpty().contains("login.html")
    }

    suspend fun login() {
        cookie = null
        sign = null
        // A login while the router still holds a session for this address is refused once, and the
        // refusal clears that session, so a single retry is enough.
        for (attempt in 1..LOGIN_ATTEMPTS) {
            val body = "username=${Form.component(username)}&password=${TendaCrypto.md5(password)}"
            val answer = send(request("login/Auth", body = body, withCookie = false))
            when {
                answer.sendsToLogin -> continue
                answer.isRedirect -> {
                    cookie = answer.setCookies.firstNotNullOfOrNull(::sessionCookie)
                        ?: throw FirmwareParseException("The router accepted the login but issued no session cookie")
                    fetchSign()
                    return
                }
                else -> throw refusal(answer)
            }
        }
        throw RouterBusyException("The router keeps refusing the login while it holds another session for this address")
    }

    override suspend fun read(name: String): Any? {
        requireLoggedIn()
        var key = sign ?: fetchSign()
        var received = 0
        // An answer we cannot read has two likely causes: the key was replaced (another admin page or
        // client asked for a new one), or the router was too busy to send anything useful, as it can be
        // for a moment after a save. Reads are safe to repeat, so try again with the current key.
        for (attempt in 1..READ_ATTEMPTS) {
            val answer = send(request("goform/$name?${nonce.incrementAndGet()}"))
            checkSession(answer)
            unwrap(name, answer.body, key)?.let { return it.value }
            received = answer.body.length
            if (attempt < READ_ATTEMPTS) {
                delay(RETRY_PAUSE_MILLIS * attempt)
                key = fetchSign()
            }
        }
        throw FirmwareParseException("Cannot read the answer of goform/$name: it does not decrypt with the session key ($received bytes received)")
    }

    override suspend fun write(name: String, body: String) {
        requireLoggedIn()
        // The web UI asks for a fresh key right before every save. So do we, which keeps the window
        // in which somebody else could replace it down to this one request. A write is never repeated.
        val key = fetchSign()
        val answer = send(request("goform/$name", body = TendaCrypto.encrypt(body, key)))
        checkSession(answer)
        val result = unwrap(name, answer.body, key)
            ?: throw FirmwareParseException("The answer to goform/$name could not be read")
        val fields = result.value.asObject("The answer to goform/$name")
        if ("errCode" !in fields) throw FirmwareParseException("The answer to goform/$name carries no errCode")
        val code = fields.str("errCode")
        if (code != "0") throw CommandFailedException("goform/$name", null, "the router refused it (error code $code)")
    }

    override suspend fun postPlain(name: String, body: String): PlainAnswer {
        requireLoggedIn()
        val answer = send(request("goform/$name", body = body))
        return PlainAnswer(answer.code, answer.location, answer.body)
    }

    /** Logs out, so the router does not keep a session open for nobody, and releases the sockets. Never throws. */
    suspend fun close() {
        try {
            if (cookie != null) send(request("goform/exit"))
        } catch (_: RouterException) {
            // Best effort: the session ends by itself anyway.
        }
        cookie = null
        sign = null
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun requireLoggedIn() {
        if (cookie == null) throw RouterSessionExpiredException("Not logged in")
    }

    private suspend fun fetchSign(): String {
        val answer = send(request("goform/stokCfg?${nonce.incrementAndGet()}"))
        checkSession(answer)
        val key = try {
            Json.parse(answer.body).asObject("The session key answer").str("sign")
        } catch (e: FirmwareParseException) {
            throw FirmwareParseException("The router did not hand out a session key", e)
        }
        if (key.isEmpty()) throw FirmwareParseException("The router handed out an empty session key")
        sign = key
        return key
    }

    /** A redirect to the login page means the session is gone; anything else unexpected is not ours to guess at. */
    private fun checkSession(answer: Answer) {
        when {
            answer.sendsToLogin -> {
                cookie = null
                throw RouterSessionExpiredException()
            }
            answer.isRedirect -> throw FirmwareParseException(
                "The router redirected to ${answer.location}: has its first-time setup been completed?",
            )
            answer.code != 200 -> throw FirmwareParseException("Unexpected HTTP ${answer.code} from the router")
        }
    }

    private class Unwrapped(val value: Any?)

    /**
     * The answer as parsed JSON. Plain JSON is taken as it is (some answers are not scrambled), anything
     * else must decrypt with [key] to JSON, otherwise this is `null`: most likely the key has been replaced.
     */
    private fun unwrap(name: String, body: String, key: String): Unwrapped? {
        val text = body.trim()
        if (text.startsWith("<")) {
            // The firmware's error page, e.g. "Form WifiAtfGet is not defined".
            val problem = HTML_PROBLEM.find(text)?.groupValues?.get(1)?.trim()
            throw FirmwareParseException("goform/$name: ${problem ?: "the router answered with a web page"}")
        }
        // A plain answer can carry the same stray trailing byte as a scrambled one (see TendaCrypto), so it ends at its last bracket.
        val plain = when {
            text.startsWith("{") -> text.substring(0, text.lastIndexOf('}') + 1)
            text.startsWith("[") -> text.substring(0, text.lastIndexOf(']') + 1)
            else -> TendaCrypto.decrypt(text, key) ?: return null
        }
        return try {
            Unwrapped(Json.parse(plain))
        } catch (_: FirmwareParseException) {
            null
        }
    }

    private suspend fun refusal(answer: Answer): RouterException {
        val code = answer.body.trim()
        return when {
            answer.code != 200 -> FirmwareParseException("Unexpected HTTP ${answer.code} to the login")
            code == "1" || code == "2" -> WrongCredentialsException(wrongPasswordMessage())
            code == "3" -> RouterBusyException("Another administrator is logged in to the router")
            code == "4" -> WrongCredentialsException(wrongPasswordMessage())
            else -> FirmwareParseException("Unexpected answer to the login: ${code.take(60)}")
        }
    }

    /** Says how many tries are left, or that the login is locked: five wrong passwords lock the router for a while. */
    private suspend fun wrongPasswordMessage(): String {
        val info = try {
            Json.parse(send(request("goform/getLoginInfo?${nonce.incrementAndGet()}", withCookie = false)).body)
                .asObject("The login info")
        } catch (_: RouterException) {
            return "Wrong password"
        }
        return when {
            info.str("isLocked") == "1" -> "Too many wrong passwords: the router has locked its login for ${info.str("time")} seconds"
            info.str("leftTimes").isNotEmpty() -> "Wrong password (${info.str("leftTimes")} tries left before the router locks its login)"
            else -> "Wrong password"
        }
    }

    private fun sessionCookie(header: String): String? =
        header.takeIf { it.startsWith("password=") }?.substringBefore(';')?.takeIf { it.length > "password=".length }

    private fun request(path: String, body: String? = null, withCookie: Boolean = true): Request {
        val url = baseUrl.resolve("/$path") ?: throw InvalidConfigurationException("Invalid path: $path")
        val builder = Request.Builder().url(url).header("Referer", baseUrl.toString())
        if (withCookie) cookie?.let { builder.header("Cookie", it) }
        if (body != null) builder.post(body.toRequestBody(FORM))
        return builder.build()
    }

    private suspend fun send(request: Request): Answer = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).await().use { response ->
                Answer(
                    code = response.code,
                    body = response.body?.string().orEmpty(),
                    location = response.header("Location"),
                    setCookies = response.headers("Set-Cookie"),
                )
            }
        } catch (e: SocketTimeoutException) {
            throw FirmwareTimeoutException("${baseUrl.host} timed out", e)
        } catch (e: IOException) {
            throw RouterUnreachableException("Cannot reach ${baseUrl.host}: ${e.message}", e)
        }
    }

    private companion object {
        const val LOGIN_ATTEMPTS = 2
        const val READ_ATTEMPTS = 3
        const val RETRY_PAUSE_MILLIS = 400L
        val FORM = "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()
        val HTML_PROBLEM = Regex("""(?is)<p>(.*?)</p>""")
    }
}

/**
 * Suspends on an OkHttp call without blocking a thread, and cancels the HTTP request when the
 * coroutine is cancelled. This is what lets the host's per-call timeout actually stop the work.
 */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, _, _ -> response.close() }
        }
    })
}
