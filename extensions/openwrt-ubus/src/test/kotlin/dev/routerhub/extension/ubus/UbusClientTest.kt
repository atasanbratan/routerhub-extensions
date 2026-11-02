package dev.routerhub.extension.ubus

import dev.routerhub.api.Capability
import dev.routerhub.api.CommandFailedException
import dev.routerhub.api.ConnectionConfig
import dev.routerhub.api.FirmwareParseException
import dev.routerhub.api.InvalidConfigurationException
import dev.routerhub.api.InvalidSettingException
import dev.routerhub.api.RouterSessionExpiredException
import dev.routerhub.api.RouterUnreachableException
import dev.routerhub.api.WrongCredentialsException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** The client over real HTTP against a stand-in for `/ubus`. */
class UbusClientTest {
    private val requests = mutableListOf<JsonObject>()
    private val headers = mutableListOf<String>()

    /** Answers a call's JSON-RPC params: session, object, method, arguments. */
    private var answer: (List<Any?>) -> String = { """{"jsonrpc":"2.0","id":1,"result":[0,{}]}""" }
    private var status = 200

    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())

    init {
        thread(isDaemon = true) {
            while (true) {
                val socket = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                socket.use(::serve)
            }
        }
    }

    private fun serve(socket: Socket) {
        val input = socket.getInputStream().buffered()
        var length = 0
        while (true) {
            val line = generateSequence { input.read().takeIf { it >= 0 } }
                .takeWhile { it != '\n'.code }.map { it.toChar() }.joinToString("").trim()
            if (line.isEmpty()) break
            synchronized(requests) { headers += line.lowercase() }
            if (line.lowercase().startsWith("content-length:")) length = line.substringAfter(':').trim().toInt()
        }
        val request = Json.parse(input.readNBytes(length).decodeToString()).asObject("request")
        synchronized(requests) { requests += request }
        val body = answer(request["params"].asList("params")).toByteArray()
        val head = "HTTP/1.1 $status X\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(head.toByteArray())
            write(body)
            flush()
        }
    }

    private val config = ConnectionConfig.Web("127.0.0.1", "", "secret", server.localPort, timeoutMillis = 2_000)
    private val client = UbusClient(config)

    @After
    fun stop() = server.close()

    private fun result(json: String) = """{"jsonrpc":"2.0","id":1,"result":$json}"""
    private fun error(code: Int) = """{"jsonrpc":"2.0","id":1,"error":{"code":$code,"message":"Refused"}}"""

    private val loggedIn: (List<Any?>) -> String = { params ->
        if (params[2] == "login") result("""[0,{"ubus_rpc_session":"abc123","timeout":300}]""") else result("""[0,{"value":1}]""")
    }

    @Test
    fun `a login sends the zero session and later calls carry the new one`() = runBlocking {
        answer = loggedIn
        client.login()
        assertEquals(mapOf("value" to 1L), client.call("system", "info"))

        val login = requests[0]["params"].asList("params")
        assertEquals("00000000000000000000000000000000", login[0])
        assertEquals(listOf("session", "login"), login.subList(1, 3))
        assertEquals(mapOf("username" to "root", "password" to "secret"), login[3])
        assertEquals("2.0", requests[0]["jsonrpc"])
        assertEquals("call", requests[0]["method"])
        assertEquals("abc123", requests[1]["params"].asList("params")[0])
    }

    @Test
    fun `a refused login is a wrong password`() {
        answer = { result("[6]") }
        assertThrows(WrongCredentialsException::class.java) { runBlocking { client.login() } }
    }

    @Test
    fun `a failure status is reported with its name`() {
        answer = { result("[4]") }
        val e = assertThrows(UbusCallFailedException::class.java) { runBlocking { client.call("uci", "get") } }
        assertEquals(UbusStatus.NOT_FOUND, e.status)
        assertTrue(e.message!!.contains("not found"))
    }

    @Test
    fun `a call without data is an empty answer`() = runBlocking {
        answer = { result("[0]") }
        assertEquals(emptyMap<String, Any?>(), client.call("uci", "commit"))
    }

    @Test
    fun `access denied on a dead session asks for a new login`() {
        answer = { error(-32002) }
        assertThrows(RouterSessionExpiredException::class.java) { runBlocking { client.call("system", "info") } }
    }

    @Test
    fun `access denied on a live session is a missing permission, not worth a new login`() = runBlocking {
        answer = loggedIn
        client.login()
        answer = { params -> if (params[2] == "access") result("[0,{}]") else error(-32002) }
        val e = assertThrows(CommandFailedException::class.java) { runBlocking { client.call("file", "read") } }
        assertTrue(e.message!!.contains("access rules"))
    }

    @Test
    fun `a call the router doesn't have says so`() {
        answer = { error(-32601) }
        assertThrows(CommandFailedException::class.java) { runBlocking { client.call("iwinfo", "nothing") } }
    }

    @Test
    fun `a router without ubus or with a broken answer is told apart`() {
        status = 404
        assertThrows(FirmwareParseException::class.java) { runBlocking { client.call("system", "info") } }
        status = 200
        answer = { "<html>" }
        assertThrows(FirmwareParseException::class.java) { runBlocking { client.call("system", "info") } }
    }

    @Test
    fun `a redirect to HTTPS is explained and not followed`() {
        status = 301
        val e = assertThrows(RouterUnreachableException::class.java) { runBlocking { client.call("system", "info") } }
        assertTrue(e.message!!.contains("HTTPS"))
        assertEquals(1, requests.size)
    }

    @Test
    fun `a closed port is unreachable`() {
        val port = ServerSocket(0).use { it.localPort }
        val closed = UbusClient(ConnectionConfig.Web("127.0.0.1", "root", "x", port, timeoutMillis = 1_000))
        assertThrows(RouterUnreachableException::class.java) { runBlocking { closed.login() } }
    }

    @Test
    fun `no connection is kept for reuse, because the router ends idle ones with a stray chunk`() = runBlocking {
        answer = loggedIn
        client.login()
        assertTrue("connection: close" in synchronized(requests) { headers.toList() })
    }

    @Test
    fun `closing ends the session once, and never throws`() = runBlocking {
        answer = loggedIn
        client.login()
        client.close()
        client.close()
        assertEquals(1, requests.count { it["params"].asList("params")[2] == "destroy" })
    }

    @Test
    fun `text survives the JSON round trip`() {
        val text = "quote \" slash \\ tab \t line \n ünï \u0001"
        assertEquals(text, Json.parse(Json.write(text)))
        assertEquals(
            mapOf("a" to listOf(1L, true, null), "b" to mapOf("c" to "d")),
            Json.parse(Json.write(mapOf("a" to listOf(1, true, null), "b" to mapOf("c" to "d")))),
        )
    }

    @Test
    fun `the factory takes web settings and describes what it can do`() {
        val factory = UbusFactory()
        assertEquals("openwrt.ubus", factory.id)
        assertTrue(Capability.SETTINGS in factory.capabilities)
        assertThrows(InvalidConfigurationException::class.java) {
            factory.create(ConnectionConfig.Telnet("192.0.2.1", "root", "x"))
        }
        assertFalse(factory.create(config).capabilities.isEmpty())
    }

    @Test
    fun `an unknown page is refused`() {
        answer = { params -> if (params[3].toString().contains("wireless") || params[3].toString().contains("network")) result("[4]") else result("[0,{}]") }
        val firmware = UbusFactory().create(config)
        assertThrows(InvalidSettingException::class.java) { runBlocking { firmware.readSettings("nope") } }
    }
}
