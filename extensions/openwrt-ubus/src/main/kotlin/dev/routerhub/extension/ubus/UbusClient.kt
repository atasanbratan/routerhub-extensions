package dev.routerhub.extension.ubus

import dev.routerhub.api.CommandFailedException
import dev.routerhub.api.ConnectionConfig
import dev.routerhub.api.FirmwareParseException
import dev.routerhub.api.FirmwareTimeoutException
import dev.routerhub.api.RouterException
import dev.routerhub.api.RouterSessionExpiredException
import dev.routerhub.api.RouterUnreachableException
import dev.routerhub.api.WrongCredentialsException
import kotlinx.coroutines.Dispatchers
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
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The JSON-RPC endpoint of OpenWrt's web server, `POST /ubus`.
 *
 *  - **Login**: `session login` with the zero session id. It answers `[0, {"ubus_rpc_session": ...}]`,
 *    or `[6]` for a wrong password.
 *  - **Calls**: `{"method":"call","params":[session, object, method, arguments]}`, answered with
 *    `{"result":[status, data]}` where the status is ubus's own, or a JSON-RPC `error` when rpcd
 *    refuses before calling.
 *  - **Access denied** (-32002) means either that the session has ended or that its ACLs don't
 *    cover the call. rpcd doesn't say which, so [denied] asks.
 *
 * A self-signed certificate is refused like any other: this contract has no way to pin one.
 */
internal class UbusClient(config: ConnectionConfig.Web) : UbusApi {

    private val url: HttpUrl = HttpUrl.Builder()
        .scheme(if (config.useHttps) "https" else "http")
        .host(config.host)
        .port(config.port)
        .addPathSegment("ubus")
        .build()

    private val username = config.username.ifBlank { "root" }

    @Volatile
    private var password = config.password

    @Volatile
    private var session = NO_SESSION

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
        // A redirect to https would otherwise silently resend the login.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val quickClient: OkHttpClient = client.newBuilder()
        .connectTimeout(QUICK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        .readTimeout(QUICK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        .callTimeout(QUICK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        .build()

    private val ids = AtomicLong()

    suspend fun login() {
        session = NO_SESSION
        val answer = try {
            call("session", "login", mapOf("username" to username, "password" to password))
        } catch (e: UbusCallFailedException) {
            if (e.status == UbusStatus.PERMISSION_DENIED) throw WrongCredentialsException(cause = e)
            throw e
        }
        session = answer.str("ubus_rpc_session").ifEmpty {
            throw FirmwareParseException("The router accepted the login but issued no session")
        }
    }

    override suspend fun call(target: String, method: String, args: JsonObject, quick: Boolean): JsonObject {
        val name = "$target $method"
        val answer = post(target, method, args, quick)
        answer["error"]?.let { error -> rpcError(name, error.asObject("The error"), quick) }
        val result = answer["result"].asList("The result of $name")
        val status = (result.firstOrNull() as? Number)?.toInt()
            ?: throw FirmwareParseException("The result of $name has no status")
        if (status != UbusStatus.OK) throw UbusCallFailedException(name, status)
        return result.getOrNull(1)?.asObject("The result of $name").orEmpty()
    }

    private suspend fun rpcError(name: String, error: JsonObject, quick: Boolean): Nothing {
        when (error.num("code")) {
            ACCESS_DENIED -> denied(name, quick)
            OBJECT_NOT_FOUND, METHOD_NOT_FOUND -> throw CommandFailedException(name, null, "This router doesn't offer this call")
            else -> throw CommandFailedException(name, null, error.str("message"))
        }
    }

    private suspend fun denied(name: String, quick: Boolean): Nothing {
        // Every session may ask what it is allowed, so this call only fails when the session is gone.
        val alive = session != NO_SESSION && post("session", "access", emptyMap(), quick)["result"] != null
        if (alive) throw CommandFailedException(name, null, "The router's access rules for this user don't allow $name")
        throw RouterSessionExpiredException()
    }

    private suspend fun post(target: String, method: String, args: JsonObject, quick: Boolean): JsonObject {
        val body = Json.write(
            mapOf(
                "jsonrpc" to "2.0",
                "id" to ids.incrementAndGet(),
                "method" to "call",
                "params" to listOf(session, target, method, args),
            ),
        )
        // uhttpd ends an idle connection by sending a stray "0" chunk, which a reused connection would
        // read as the next answer. Saving a settings page after typing for 20 seconds runs into that.
        val request = Request.Builder().url(url).header("Connection", "close").post(body.toRequestBody(JSON)).build()
        val (code, text) = send(if (quick) quickClient else client, request)
        return when (code) {
            200 -> Json.parse(text).asObject("The answer")
            in 300..399 -> throw RouterUnreachableException("${url.host} only accepts HTTPS. Turn on HTTPS for this router")
            404 -> throw FirmwareParseException("${url.host} has no /ubus. Is this OpenWrt with LuCI and rpcd installed?")
            else -> throw FirmwareParseException("${url.host} answered /ubus with HTTP $code")
        }
    }

    private suspend fun send(http: OkHttpClient, request: Request): Pair<Int, String> = withContext(Dispatchers.IO) {
        try {
            http.newCall(request).await().use { it.code to it.body?.string().orEmpty() }
        } catch (e: SocketTimeoutException) {
            throw FirmwareTimeoutException("${url.host} timed out", e)
        } catch (e: SSLException) {
            throw RouterUnreachableException(
                "${url.host} has an HTTPS certificate this phone doesn't trust, which is normal for a router. Turn off HTTPS for it",
                e,
            )
        } catch (e: IOException) {
            throw RouterUnreachableException("Cannot reach ${url.host}: ${e.message}", e)
        }
    }

    /** Ends the session and frees the sockets. Never throws. */
    suspend fun close() {
        try {
            if (session != NO_SESSION) call("session", "destroy", quick = true)
        } catch (_: RouterException) {
            // Sessions end by themselves too; nothing to clean up after.
        }
        session = NO_SESSION
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private companion object {
        const val NO_SESSION = "00000000000000000000000000000000"
        const val ACCESS_DENIED = -32002L
        const val OBJECT_NOT_FOUND = -32000L
        const val METHOD_NOT_FOUND = -32601L
        const val QUICK_TIMEOUT_MILLIS = 3_000L
        val JSON = "application/json".toMediaType()
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
