package dev.routerhub.extension.tplink

import dev.routerhub.api.ConnectionConfig
import dev.routerhub.api.RouterBusyException
import dev.routerhub.api.WrongCredentialsException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Drives [TelnetCli] against a fake router that replays the dialogue observed on a TD-W8960N:
 * IAC negotiation, `Login:` / `Password:`, the ` > ` prompt, echoed commands, and closing the
 * connection on `exit`.
 */
class TelnetCliTest {

    private val server = ServerSocket(0)
    private val received = CopyOnWriteArrayList<String>()
    private val negotiationReplies = CopyOnWriteArrayList<List<Int>>()

    @After
    fun tearDown() = server.close()

    private fun config() = ConnectionConfig.Telnet(
        host = "127.0.0.1",
        username = "admin",
        password = "secret",
        port = server.localPort,
        timeoutMillis = 3_000,
    )

    private fun fakeRouter(script: (Socket) -> Unit) = thread(isDaemon = true) {
        server.accept().use(script)
    }

    private fun Socket.say(text: String) = getOutputStream().apply { write(text.toByteArray()); flush() }

    private fun InputStream.readLine(): String {
        val line = StringBuilder()
        while (true) {
            val b = read()
            if (b < 0 || b == '\n'.code) return line.toString().trimEnd('\r')
            if (b == 255) { // record the client's negotiation answer (IAC, verb, option)
                negotiationReplies += listOf(255, read(), read())
                continue
            }
            line.append(b.toChar())
        }
    }

    private fun Socket.loginDialogue(password: String = "secret"): Boolean {
        val input = getInputStream()
        // IAC DO ECHO, IAC WILL SUPPRESS-GO-AHEAD: both must be refused.
        getOutputStream().apply { write(byteArrayOf(-1, -3, 1, -1, -5, 3)); flush() }
        say("Login: ")
        received += input.readLine()
        say("admin\r\nPassword: ")
        val given = input.readLine().also { received += it }
        if (given != password) {
            say("\r\nLogin incorrect\r\nLogin: ")
            return false
        }
        say("\r\n > ")
        return true
    }

    @Test
    fun `logs in, runs commands, and exits cleanly`() = runBlocking {
        val router = fakeRouter { socket ->
            check(socket.loginDialogue())
            val input = socket.getInputStream()
            while (true) {
                val command = input.readLine().also { received += it }
                when (command) {
                    "swversion" -> socket.say("swversion\r\n1.1.0 Build 171106 Rel.72267\r\n > ")
                    "wl ssid" -> socket.say("wl ssid\r\nCurrent SSID: \"HomeNet\"\r\n > ")
                    "exit" -> return@fakeRouter // closing the socket = slot released
                }
            }
        }

        val cli = TelnetCli(config())
        cli.connect()
        assertEquals("1.1.0 Build 171106 Rel.72267", cli.exec("swversion"))
        assertEquals("Current SSID: \"HomeNet\"", cli.exec("wl ssid"))
        cli.close()
        router.join(2_000)

        assertEquals(listOf("admin", "secret", "swversion", "wl ssid", "exit"), received)
        // DO ECHO answered with WONT ECHO, WILL SGA answered with DONT SGA.
        assertTrue(listOf(255, 252, 1) in negotiationReplies)
        assertTrue(listOf(255, 254, 3) in negotiationReplies)
    }

    @Test(expected = RouterBusyException::class)
    fun `reports a taken telnet slot as busy`() = runBlocking {
        fakeRouter { it.say("Error! The number of connections execeeds maximum loading, reject!\r\n") }
        TelnetCli(config()).connect()
    }

    @Test(expected = WrongCredentialsException::class)
    fun `rejects a wrong password`() = runBlocking {
        fakeRouter { socket ->
            socket.loginDialogue(password = "something-else")
            Thread.sleep(500)
        }
        TelnetCli(config()).connect()
    }
}
