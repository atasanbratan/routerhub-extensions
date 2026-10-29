package dev.routerhub.extension.tenda

import dev.routerhub.api.CommandFailedException
import dev.routerhub.api.ConnectionConfig
import dev.routerhub.api.FirmwareParseException
import dev.routerhub.api.RouterSessionExpiredException
import dev.routerhub.api.RouterUnreachableException
import dev.routerhub.api.WrongCredentialsException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ServerSocket

class TendaHttpClientTest {
    private val router = FakeTendaRouter(password = "secret")
    private val clients = mutableListOf<TendaHttpClient>()

    @After
    fun tearDown() {
        runBlocking { clients.forEach { it.close() } }
        router.close()
    }

    private fun client(password: String = "secret", port: Int = router.port) = TendaHttpClient(
        ConnectionConfig.Web(host = "127.0.0.1", username = "admin", password = password, port = port, timeoutMillis = 5_000),
    ).also { clients += it }

    private fun loggedIn(): TendaHttpClient = client().also { runBlocking { it.login() } }

    private fun readName(client: TendaHttpClient, name: String) = runBlocking { client.read(name) }.asObject(name)

    @Test
    fun `it logs in and reads a scrambled answer, whatever the line endings of the headers`() {
        router.answers["GetSysStatus"] = """{"adv_firm_ver":"V16.03.61.08_cn","adv_run_time":694}"""
        val c = loggedIn()
        val status = readName(c, "GetSysStatus")
        assertEquals("V16.03.61.08_cn", status.str("adv_firm_ver"))
        assertEquals(694L, status.num("adv_run_time"))
        // login, then the key, then the read
        assertEquals(listOf("POST /login/Auth", "GET /goform/stokCfg", "GET /goform/GetSysStatus"), router.log)
    }

    @Test
    fun `a login while the router still holds a session is retried once`() {
        loggedIn() // leaves a session open on the router, like a browser tab or an earlier run
        assertTrue(router.hasSession)

        val second = client()
        runBlocking { second.login() }

        assertEquals("the second client was refused once, then let in", 3, router.log.count { it == "POST /login/Auth" })
        router.answers["GetSysStatus"] = "{}"
        readName(second, "GetSysStatus")
    }

    @Test
    fun `a wrong password says how many tries are left and does not retry`() {
        val c = client(password = "wrong")
        try {
            runBlocking { c.login() }
            fail("expected a WrongCredentialsException")
        } catch (e: WrongCredentialsException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("4 tries left"))
        }
        assertEquals("one attempt only: the router locks its login after five", 1, router.log.count { it == "POST /login/Auth" })
        assertFalse(router.hasSession)
    }

    @Test
    fun `a read survives the key having been replaced by somebody else`() {
        router.answers["GetSysStatus"] = """{"adv_run_time":1}"""
        val c = loggedIn()
        router.rotateSign()

        assertEquals(1L, readName(c, "GetSysStatus").num("adv_run_time"))
        assertEquals("one more key request", 2, router.log.count { it == "GET /goform/stokCfg" })
    }

    @Test
    fun `answers with a stray byte after them are read, and so are the answers to writes`() {
        router.answers["GetSysStatus"] = """{"adv_run_time":9}"""
        router.junk = "!"
        val c = loggedIn()
        assertEquals(9L, readName(c, "GetSysStatus").num("adv_run_time"))
        runBlocking { c.write("SetUpnpCfg", "upnpEn=1") }
        assertEquals(listOf("SetUpnpCfg" to "upnpEn=1"), router.writes)
    }

    @Test
    fun `a read survives a router that was too busy to answer once`() {
        router.answers["GetSysStatus"] = """{"adv_run_time":7}"""
        val c = loggedIn()
        router.emptyReads = 1
        assertEquals(7L, readName(c, "GetSysStatus").num("adv_run_time"))
    }

    @Test
    fun `a router that keeps sending nothing is reported with what was received`() {
        router.answers["GetSysStatus"] = "{}"
        val c = loggedIn()
        router.emptyReads = 10
        try {
            readName(c, "GetSysStatus")
            fail("expected a FirmwareParseException")
        } catch (e: FirmwareParseException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("0 bytes received"))
        }
    }

    @Test
    fun `a write asks for a fresh key and arrives readable`() {
        val c = loggedIn()
        router.rotateSign()

        runBlocking { c.write("SetUpnpCfg", "upnpEn=1&name=Caf%C3%A9") }

        assertEquals(listOf("SetUpnpCfg" to "upnpEn=1&name=Caf%C3%A9"), router.writes)
    }

    @Test
    fun `a write the router refuses is reported with its error code`() {
        val c = loggedIn()
        router.writeAnswer = """{"errCode":3}"""
        try {
            runBlocking { c.write("SetUpnpCfg", "upnpEn=1") }
            fail("expected a CommandFailedException")
        } catch (e: CommandFailedException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("error code 3"))
        }
    }

    @Test
    fun `an answer without an error code is not taken for success`() {
        val c = loggedIn()
        router.writeAnswer = "{}"
        try {
            runBlocking { c.write("SetUpnpCfg", "upnpEn=1") }
            fail("expected a FirmwareParseException")
        } catch (_: FirmwareParseException) {
        }
    }

    @Test
    fun `an ended session is reported as expired, and a later login works`() {
        router.answers["GetSysStatus"] = "{}"
        val c = loggedIn()
        router.dropSession()
        try {
            readName(c, "GetSysStatus")
            fail("expected a RouterSessionExpiredException")
        } catch (_: RouterSessionExpiredException) {
        }
        // as the host does: log in again and repeat
        runBlocking { c.login() }
        readName(c, "GetSysStatus")
    }

    @Test
    fun `a form the router does not know is a parse error that names it`() {
        val c = loggedIn()
        try {
            readName(c, "WifiAtfGet")
            fail("expected a FirmwareParseException")
        } catch (e: FirmwareParseException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("WifiAtfGet is not defined"))
        }
    }

    @Test
    fun `nothing can be done before logging in`() {
        try {
            readName(client(), "GetSysStatus")
            fail("expected a RouterSessionExpiredException")
        } catch (_: RouterSessionExpiredException) {
        }
        assertTrue("no request was made", router.log.isEmpty())
    }

    @Test
    fun `closing logs out`() {
        router.answers["GetSysStatus"] = "{}"
        val c = loggedIn()
        runBlocking { c.close() }
        assertTrue(router.log.contains("GET /goform/exit"))
        assertFalse(router.hasSession)
        try {
            readName(c, "GetSysStatus")
            fail("expected a RouterSessionExpiredException")
        } catch (_: RouterSessionExpiredException) {
        }
    }

    @Test
    fun `a plain form is posted as it is, not scrambled`() {
        val c = loggedIn()
        val answer = runBlocking { c.postPlain("SysToolReboot", "action=0") }
        assertEquals(listOf("SysToolReboot" to "action=0"), router.plainPosts)
        assertEquals(302, answer.code)
        assertFalse("the redirect leads to a page, not to the login", answer.endsSession)
    }

    @Test
    fun `a router that is not there is unreachable`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        try {
            runBlocking { client(port = closedPort).login() }
            fail("expected a RouterUnreachableException")
        } catch (_: RouterUnreachableException) {
        }
    }
}
