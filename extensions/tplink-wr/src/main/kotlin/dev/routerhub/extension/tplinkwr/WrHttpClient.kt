package dev.routerhub.extension.tplinkwr

import dev.routerhub.api.ConnectionConfig
import dev.routerhub.api.FirmwareParseException
import dev.routerhub.api.FirmwareTimeoutException
import dev.routerhub.api.InvalidConfigurationException
import dev.routerhub.api.RouterBusyException
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The router's pages, by name: `page("StatusRpm.htm")`. Tests supply a fake. */
internal interface WrApi {
    suspend fun login()

    /** A page under the session's `/userRpm/`, with an optional query like `Reboot=Reboot`. */
    suspend fun page(name: String, query: String = ""): String

    suspend fun logout()
}

/**
 * HTTP for TP-Link's classic firmware (TL-WR940N v4/v5, 3.17.1 Build 170207), as its own login page
 * does it:
 *  1. The cookie `Authorization=Basic base64(user:md5(password))`, URL-encoded, and
 *     `GET /userRpm/LoginRpm.htm?Save=Save`.
 *  2. Right credentials answer with a script going to `http://host/<16 capitals>/userRpm/Index.htm`;
 *     that path is the session. Wrong ones answer with the login page again.
 *  3. Every page is under `/<session>/userRpm/` and needs the same cookie and a Referer from the
 *     router itself. A stale session answers with a script that clears the cookie.
 *
 * The router keeps one login at a time: logging in here ends a session open in a browser.
 */
internal class WrHttpClient(config: ConnectionConfig.Web) : WrApi {
    private val baseUrl: HttpUrl = HttpUrl.Builder()
        .scheme(if (config.useHttps) "https" else "http")
        .host(config.host)
        .port(config.port)
        .build()

    private val cookie = "Authorization=" + URLEncoder.encode(
        "Basic " + Base64.getEncoder().encodeToString("${config.username}:${md5(config.password)}".toByteArray()),
        "UTF-8",
    ).replace("+", "%20")

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(config.timeoutMillis, TimeUnit.MILLISECONDS)
        .readTimeout(config.timeoutMillis, TimeUnit.MILLISECONDS)
        .callTimeout(config.timeoutMillis * 2, TimeUnit.MILLISECONDS)
        .build()

    @Volatile
    private var session: String? = null

    override suspend fun login() {
        session = null
        repeat(LOGIN_ATTEMPTS) {
            val answer = get("/userRpm/LoginRpm.htm?Save=Save", referer = baseUrl.toString())
            checkNotBusy(answer)
            WrPages.sessionToken(answer)?.let {
                session = it
                return
            }
            // Right after a logout the router answers the first login by sending the browser home;
            // the next one works. A wrong password gets the login page instead.
            if (!WrPages.isSentHome(answer)) throw WrongCredentialsException()
            delay(RETRY_DELAY_MILLIS)
        }
        throw WrongCredentialsException()
    }

    override suspend fun page(name: String, query: String): String {
        val token = session ?: throw RouterSessionExpiredException("Not logged in")
        val path = "/$token/userRpm/$name" + if (query.isEmpty()) "" else "?$query"
        val body = get(path, referer = baseUrl.resolve("/$token/userRpm/Index.htm").toString())
        checkNotBusy(body)
        if (WrPages.isSessionGone(body)) {
            session = null
            throw RouterSessionExpiredException()
        }
        return body
    }

    /** Frees the router's single login for the browser. */
    override suspend fun logout() {
        val token = session ?: return
        session = null
        try {
            get("/$token/userRpm/LogoutRpm.htm", referer = baseUrl.resolve("/$token/userRpm/Index.htm").toString())
        } catch (_: IOException) {
        } catch (_: RouterUnreachableException) {
        } catch (_: FirmwareTimeoutException) {
        }
    }

    fun close() {
        session = null
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private suspend fun get(path: String, referer: String): String {
        val url = baseUrl.resolve(path) ?: throw InvalidConfigurationException("Invalid path: $path")
        val request = Request.Builder().url(url).header("Cookie", cookie).header("Referer", referer).build()
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).await().use { response ->
                    if (!response.isSuccessful) throw FirmwareParseException("Unexpected HTTP ${response.code} for ${url.encodedPath}")
                    // Pages declare UTF-8 in a meta tag only; everything that matters to us is ASCII.
                    response.body?.string().orEmpty()
                }
            } catch (e: SocketTimeoutException) {
                throw FirmwareTimeoutException("${baseUrl.host} timed out", e)
            } catch (e: IOException) {
                throw RouterUnreachableException("Cannot reach ${baseUrl.host}: ${e.message}", e)
            }
        }
    }

    private fun checkNotBusy(body: String) {
        if (BUSY_MARKERS.any { body.contains(it, ignoreCase = true) }) {
            throw RouterBusyException("Too many sessions on the router; wait a minute and try again")
        }
    }

    private companion object {
        const val LOGIN_ATTEMPTS = 3
        const val RETRY_DELAY_MILLIS = 500L

        // The firmware misspells "exceeds"; match both in case a later build fixes it.
        val BUSY_MARKERS = listOf("execeeds maximum", "exceeds maximum")

        fun md5(text: String): String =
            MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/** Suspends on an OkHttp call and cancels the request when the coroutine is cancelled. */
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
