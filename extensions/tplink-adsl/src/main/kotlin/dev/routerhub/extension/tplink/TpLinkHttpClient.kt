package dev.routerhub.extension.tplink

import dev.routerhub.api.ConnectionConfig
import dev.routerhub.api.FirmwareParseException
import dev.routerhub.api.FirmwareTimeoutException
import dev.routerhub.api.InvalidConfigurationException
import dev.routerhub.api.RouterBusyException
import dev.routerhub.api.RouterSessionExpiredException
import dev.routerhub.api.RouterUnreachableException
import dev.routerhub.api.WrongCredentialsException
import kotlinx.coroutines.Dispatchers
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
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * HTTP layer for TP-Link's Broadcom-based ADSL firmwares (observed on a TD-W8960N).
 *
 * Login protocol, as implemented by the firmware's own login page:
 *  1. The browser sets the cookie `Authorization=Basic base64(user:pass)` and reloads `/`.
 *  2. If the credentials are right, the response swaps it for a session token:
 *     `Set-Cookie: Authorization=-0f9b6aceba82349e`. Every later request sends that token.
 *  3. If they're wrong, `/` serves the login page again with `authTimes = N`. After 5 failures the
 *     web UI locks for a while.
 */
internal class TpLinkHttpClient(config: ConnectionConfig.Web) {

    private val baseUrl: HttpUrl = HttpUrl.Builder()
        .scheme(if (config.useHttps) "https" else "http")
        .host(config.host)
        .port(config.port)
        .build()

    private val username = config.username

    @Volatile
    private var basicCredential = basicCredentialFor(config.password)

    /** After the admin password was changed on the router, later logins must use the new one. */
    fun updatePassword(password: String) {
        basicCredential = basicCredentialFor(password)
    }

    private fun basicCredentialFor(password: String) =
        "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray())

    // OkHttp's classes come from the host app (shared platform); this instance is still ours.
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(config.timeoutMillis, TimeUnit.MILLISECONDS)
        .readTimeout(config.timeoutMillis, TimeUnit.MILLISECONDS)
        // Bounds the whole exchange, body included. That also covers the response-body read
        // after await() returns, which coroutine cancellation alone can't interrupt.
        .callTimeout(config.timeoutMillis * 2, TimeUnit.MILLISECONDS)
        .build()

    @Volatile
    private var sessionToken: String? = null

    private class Page(val body: String, val sessionCookie: String?)

    suspend fun login() {
        sessionToken = null
        val page = get("/", cookie = basicCredential)
        checkNotBusy(page.body)
        if (isLoginPage(page.body)) {
            throw if (failedAttempts(page.body) >= MAX_ATTEMPTS) {
                WrongCredentialsException("Too many failed logins: the router has locked its web UI for a while")
            } else {
                WrongCredentialsException()
            }
        }
        sessionToken = page.sessionCookie
            ?: throw FirmwareParseException("Router accepted the login but issued no session cookie")
    }

    suspend fun fetchHtml(path: String): String {
        val token = sessionToken ?: throw RouterSessionExpiredException("Not logged in")
        val body = get(path, cookie = token).body
        checkNotBusy(body)
        if (isLoginPage(body)) {
            sessionToken = null
            throw RouterSessionExpiredException()
        }
        return body
    }

    fun close() {
        sessionToken = null
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private suspend fun get(path: String, cookie: String): Page {
        val url = baseUrl.resolve(path) ?: throw InvalidConfigurationException("Invalid path: $path")
        val request = Request.Builder()
            .url(url)
            .header("Cookie", "Authorization=$cookie")
            .header("Referer", baseUrl.toString())
            .build()
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).await().use { response ->
                    if (!response.isSuccessful) {
                        throw FirmwareParseException("Unexpected HTTP ${response.code} for ${url.encodedPath}")
                    }
                    Page(
                        // Pages declare no charset and contain GBK punctuation; only ASCII matters to us.
                        body = response.body?.string().orEmpty(),
                        sessionCookie = response.headers("Set-Cookie")
                            .firstOrNull { it.startsWith("Authorization=") }
                            ?.substringAfter('=')?.substringBefore(';')
                            ?.takeIf { it.isNotBlank() },
                    )
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
            throw RouterBusyException("Another administrator session is active on the router")
        }
    }

    private fun isLoginPage(body: String) = body.contains("id=\"pcPassword\"")

    private fun failedAttempts(body: String): Int =
        AUTH_TIMES.findAll(body).lastOrNull()?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private companion object {
        const val MAX_ATTEMPTS = 5
        val AUTH_TIMES = Regex("""authTimes\s*=\s*(\d+)""")

        // The firmware misspells "exceeds"; match both in case a later build fixes it.
        val BUSY_MARKERS = listOf("execeeds maximum", "exceeds maximum")
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
