package dev.routerhub.extension.tenda

import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A pretend Tenda router on a local socket, behaving as the real one was measured to:
 *  - HTTP/1.0, header lines ended by a bare line feed, the connection closed after each answer;
 *  - login by `POST /login/Auth` (a redirect that sets the cookie, or a plain "1" for a wrong password);
 *  - a request without the valid cookie is redirected to `login.html` and ends the session, and so
 *    does a login while a session exists (every client here has the same address);
 *  - `goform/stokCfg` hands out a new AES key each time, answers use the latest key, and a
 *    write must arrive scrambled with it.
 */
internal class FakeTendaRouter(password: String = "secret") : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort

    @Volatile
    private var passwordHash = TendaCrypto.md5(password)

    /** Pretend the router ignores a password change, as it would if the form were refused. */
    @Volatile
    var rejectPasswordChange = false

    @Volatile
    private var sessionCookie: String? = null

    @Volatile
    var sign: String = newSign()
        private set

    private var signCount = 0
    private var logins = 0
    private var triesLeft = 5

    /** `goform` reads: name to the JSON text it answers. */
    val answers = ConcurrentHashMap<String, String>()

    @Volatile
    var writeAnswer = """{"errCode":0}"""

    /** Sent after every scrambled answer, like the stray byte the real router sometimes adds. */
    @Volatile
    var junk = ""

    /** How many `goform` reads still get an empty answer, as from a router that is busy for a moment. */
    @Volatile
    var emptyReads = 0

    /** One line per request, like `GET /goform/stokCfg`. */
    val log = CopyOnWriteArrayList<String>()

    /** Scrambled writes, after decrypting: name to body. */
    val writes = CopyOnWriteArrayList<Pair<String, String>>()
    val plainPosts = CopyOnWriteArrayList<Pair<String, String>>()

    init {
        thread(isDaemon = true, name = "fake-tenda") {
            while (!server.isClosed) {
                try {
                    server.accept().use(::handle)
                } catch (_: IOException) {
                    // closed, or a client went away
                }
            }
        }
    }

    /** Somebody else (another admin page, another client) asked for a key. */
    fun rotateSign() {
        sign = newSign()
    }

    /** The router timed the session out. */
    fun dropSession() {
        sessionCookie = null
    }

    val hasSession: Boolean get() = sessionCookie != null

    override fun close() {
        server.close()
    }

    private fun newSign(): String = "key${(++signCount).toString().padStart(13, '0')}"

    private class Response(val code: Int, val body: String = "", val headers: List<String> = emptyList())

    private fun redirect(to: String, vararg more: String) =
        Response(302, "<html><body>moved</body></html>", listOf("Location: http://127.0.0.1:$port$to") + more)

    private fun handle(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(input) ?: return
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (length > 0) String(input.readNBytes(length), Charsets.UTF_8) else ""
        val (method, target) = requestLine.split(' ')
        val path = target.substringBefore('?')
        log += "$method $path"

        val response = route(method, path, headers["cookie"], body)
        val text = buildString {
            append("HTTP/1.0 ${response.code} ${if (response.code == 200) "OK" else "Redirect"}\n")
            append("Server: Http Server\n")
            response.headers.forEach { append(it).append('\n') }
            append("Content-length: ${response.body.toByteArray().size}\n")
            append('\n')
            append(response.body)
        }
        socket.getOutputStream().apply {
            write(text.toByteArray(Charsets.UTF_8))
            flush()
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val out = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.isEmpty()) null else out.toString()
            if (b == '\n'.code) return out.toString().trimEnd('\r')
            out.append(b.toChar())
        }
    }

    private fun route(method: String, path: String, cookie: String?, body: String): Response = when {
        path == "/login/Auth" -> login(cookie, body)
        path == "/goform/getLoginInfo" -> Response(200, """{"isLocked":"0","time":"-1","leftTimes":$triesLeft}""")
        else -> protectedRoute(method, path, cookie, body)
    }

    private fun login(cookie: String?, body: String): Response {
        // A login while the address has a session is refused once, and that ends the session.
        if (sessionCookie != null && cookie != sessionCookie) {
            sessionCookie = null
            return redirect("/login.html")
        }
        val form = body.split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
        if (form["username"] != "admin" || form["password"] != passwordHash) {
            triesLeft--
            return Response(200, "1")
        }
        sessionCookie = "password=${passwordHash}tok${++logins}"
        return redirect("/main.html", "Set-Cookie: $sessionCookie; path=/")
    }

    private fun protectedRoute(method: String, path: String, cookie: String?, body: String): Response {
        if (cookie == null || cookie != sessionCookie) {
            sessionCookie = null
            return redirect("/login.html")
        }
        return when {
            path == "/goform/stokCfg" -> {
                sign = newSign()
                Response(200, """{"sign":"$sign"}""")
            }
            path == "/goform/exit" -> {
                sessionCookie = null
                redirect("/login.html")
            }
            path == "/goform/SysToolChangePwd" -> {
                plainPosts += "SysToolChangePwd" to body
                val newHash = body.split('&').firstOrNull { it.startsWith("SYSPS=") }?.removePrefix("SYSPS=")
                if (!rejectPasswordChange && newHash != null) passwordHash = newHash
                // either way the router ends the session and sends the browser to the login page
                sessionCookie = null
                redirect("/login.html")
            }
            path in PLAIN_FORMS -> {
                plainPosts += path.removePrefix("/goform/") to body
                redirect("/system_reboot.html")
            }
            path.startsWith("/goform/") -> goform(path.removePrefix("/goform/"), method, body)
            else -> Response(404)
        }
    }

    private fun goform(name: String, method: String, body: String): Response {
        if (method == "GET") {
            if (emptyReads > 0) {
                emptyReads--
                return Response(200, "")
            }
            val json = answers[name] ?: return Response(
                200,
                "<html><head><title>Document Error: Data follows</title></head>\n" +
                    "<body><h2>Access Error: Data follows</h2>\n<p>Form $name is not defined</p></body></html>",
            )
            return Response(200, TendaCrypto.encrypt(json, sign) + junk)
        }
        val plain = TendaCrypto.decrypt(body, sign) ?: return Response(200, """{"errCode":-1}""")
        writes += name to plain
        return Response(200, TendaCrypto.encrypt(writeAnswer, sign) + junk)
    }

    private companion object {
        val PLAIN_FORMS = setOf("/goform/SysToolReboot", "/goform/SysToolRestoreSet")
    }
}
