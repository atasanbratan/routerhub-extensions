package dev.routerhub.extension.tplink

import dev.routerhub.api.ConnectionConfig
import dev.routerhub.api.FirmwareTimeoutException
import dev.routerhub.api.RouterBusyException
import dev.routerhub.api.RouterSessionExpiredException
import dev.routerhub.api.RouterUnreachableException
import dev.routerhub.api.WrongCredentialsException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Minimal telnet client for Broadcom's restricted router CLI (`Login:` / `Password:` / ` > `).
 *
 * Deliberately tiny instead of pulling in a telnet library: the CLI needs no terminal features,
 * so every option the server proposes is refused and the stream is treated as plain text.
 *
 * Cancellation: sockets can't be interrupted, so reads use a short SO_TIMEOUT and the coroutine
 * is checked between polls. A cancelled call (the host's timeout) therefore returns within
 * [POLL_MILLIS] instead of hanging on a silent router.
 */
internal class TelnetCli(private val config: ConnectionConfig.Telnet) {

    private var socket: Socket? = null
    private var input: InputStream? = null
    private val buffer = StringBuilder()
    private val negotiation = TelnetNegotiation()

    suspend fun connect() = withContext(Dispatchers.IO) {
        close()
        val newSocket = Socket()
        try {
            newSocket.connect(InetSocketAddress(config.host, config.port), config.timeoutMillis.toInt())
            newSocket.soTimeout = POLL_MILLIS
        } catch (e: SocketTimeoutException) {
            newSocket.close()
            throw FirmwareTimeoutException("Telnet connection to ${config.host} timed out", e)
        } catch (e: IOException) {
            newSocket.close()
            throw RouterUnreachableException("Cannot reach ${config.host}:${config.port}", e)
        }
        socket = newSocket
        input = newSocket.getInputStream()
        buffer.clear()

        try {
            val banner = readUntil { it.contains(LOGIN_PROMPT) || isBusy(it) }
            if (isBusy(banner)) {
                throw RouterBusyException("The router's telnet slot is taken; close other telnet sessions and retry")
            }
            send(config.username)
            readUntil { it.contains(PASSWORD_PROMPT) }
            send(config.password)
            val afterPassword = readUntil { endsWithPrompt(it) || it.contains(LOGIN_PROMPT) || it.contains("incorrect", true) }
            if (!endsWithPrompt(afterPassword)) throw WrongCredentialsException()
        } catch (e: RouterSessionExpiredException) {
            // The router hung up during the login dialogue: typically after a rejected password.
            close()
            throw WrongCredentialsException("Router closed the telnet connection during login", e)
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    /** Runs one CLI command and returns its output without the echoed command line and prompt. */
    suspend fun exec(command: String): String = withContext(Dispatchers.IO) {
        if (socket == null) throw RouterSessionExpiredException("Telnet session is not connected")
        buffer.clear()
        send(command)
        val raw = readUntil(::endsWithPrompt).replace("\r", "")
        raw.removeSuffix(PROMPT_SUFFIX)
            .lines()
            .dropWhile { it.isBlank() }
            .let { lines -> if (lines.firstOrNull()?.trim() == command) lines.drop(1) else lines }
            .joinToString("\n")
            .trim()
    }

    /** Sends a command that ends the connection (e.g. `reboot`) without waiting for a prompt. */
    suspend fun sendAndHangUp(command: String) = withContext(Dispatchers.IO) {
        if (socket == null) throw RouterSessionExpiredException("Telnet session is not connected")
        send(command)
        Thread.sleep(500) // let the router read the line before we close
        closeSocket()
    }

    /**
     * Leaves cleanly. The firmware allows a single telnet session, and one that is merely dropped
     * keeps the slot taken for a while; after `exit` the router closes its side within about a
     * second and the next session works immediately (observed on a TD-W8960N). Never throws.
     */
    suspend fun close() {
        withContext(Dispatchers.IO) {
            val current = socket ?: return@withContext
            try {
                current.getOutputStream().apply { write("exit\r\n".toByteArray()); flush() }
                val deadline = System.currentTimeMillis() + EXIT_WAIT_MILLIS
                val sink = ByteArray(256)
                while (System.currentTimeMillis() < deadline) {
                    try {
                        if (current.getInputStream().read(sink) < 0) break // router closed: slot released
                    } catch (_: SocketTimeoutException) {
                        // keep waiting until the deadline
                    }
                }
            } catch (_: IOException) {
                // already gone
            } finally {
                closeSocket()
            }
        }
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
        input = null
    }

    private fun send(line: String) {
        val out = socket?.getOutputStream() ?: throw RouterSessionExpiredException("Telnet session is not connected")
        try {
            out.write("$line\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
        } catch (e: IOException) {
            throw RouterSessionExpiredException("Telnet connection lost", e)
        }
    }

    /** Reads until [done] accepts the accumulated text, which is then returned and cleared. */
    private suspend fun readUntil(done: (String) -> Boolean): String {
        val stream = input ?: throw RouterSessionExpiredException("Telnet session is not connected")
        val socketOut = socket?.getOutputStream() ?: throw RouterSessionExpiredException("Telnet session is not connected")
        val chunk = ByteArray(1024)
        val deadline = System.currentTimeMillis() + config.timeoutMillis
        while (true) {
            if (done(buffer.toString())) return buffer.toString().also { buffer.clear() }
            currentCoroutineContext().ensureActive()
            if (System.currentTimeMillis() > deadline) {
                throw FirmwareTimeoutException("Router stopped answering on telnet")
            }
            val n = try {
                stream.read(chunk)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (e: IOException) {
                throw RouterSessionExpiredException("Telnet connection lost", e)
            }
            if (n < 0) throw RouterSessionExpiredException("Router closed the telnet connection")
            negotiation.feed(chunk, n, buffer) { reply -> socketOut.write(reply); socketOut.flush() }
        }
    }

    private fun endsWithPrompt(text: String) = text.replace("\r", "").endsWith(PROMPT_SUFFIX)

    private fun isBusy(text: String) = BUSY_MARKERS.any { text.contains(it, ignoreCase = true) }

    private companion object {
        const val POLL_MILLIS = 250
        const val EXIT_WAIT_MILLIS = 3_000L
        const val LOGIN_PROMPT = "Login:"
        const val PASSWORD_PROMPT = "Password:"
        const val PROMPT_SUFFIX = "\n > "

        // "Error! The number of connections execeeds maximum loading, reject!" (sic)
        val BUSY_MARKERS = listOf("execeeds maximum", "exceeds maximum")
    }
}

/**
 * Strips telnet protocol bytes (RFC 854) from the stream and refuses every option: DO is answered
 * with WONT and WILL with DONT. Keeps state across reads, because a command sequence can be split
 * between two TCP segments.
 */
internal class TelnetNegotiation {
    private var state = State.DATA
    private var command = 0

    private enum class State { DATA, IAC, OPTION, SUBNEGOTIATION, SUBNEGOTIATION_IAC }

    fun feed(bytes: ByteArray, length: Int, text: StringBuilder, reply: (ByteArray) -> Unit) {
        for (i in 0 until length) {
            val b = bytes[i].toInt() and 0xFF
            when (state) {
                State.DATA -> if (b == IAC) state = State.IAC else text.append(b.toChar())
                State.IAC -> state = when (b) {
                    IAC -> State.DATA.also { text.append(IAC.toChar()) } // escaped 0xFF data byte
                    DO, DONT, WILL, WONT -> State.OPTION.also { command = b }
                    SB -> State.SUBNEGOTIATION
                    else -> State.DATA // NOP, GA and friends carry no option byte
                }
                State.OPTION -> {
                    when (command) {
                        DO -> reply(byteArrayOf(IAC.toByte(), WONT.toByte(), b.toByte()))
                        WILL -> reply(byteArrayOf(IAC.toByte(), DONT.toByte(), b.toByte()))
                    }
                    state = State.DATA
                }
                State.SUBNEGOTIATION -> if (b == IAC) state = State.SUBNEGOTIATION_IAC
                State.SUBNEGOTIATION_IAC -> state = if (b == SE) State.DATA else State.SUBNEGOTIATION
            }
        }
    }

    private companion object {
        const val IAC = 255
        const val DONT = 254
        const val DO = 253
        const val WONT = 252
        const val WILL = 251
        const val SB = 250
        const val SE = 240
    }
}
