package dev.routerhub.extension.tenda

import dev.routerhub.api.Capability
import dev.routerhub.api.ConnectionConfig
import dev.routerhub.api.FirmwareParseException
import dev.routerhub.api.InvalidConfigurationException
import dev.routerhub.api.InvalidSettingException
import dev.routerhub.api.Protocol
import dev.routerhub.api.RouterFirmware
import dev.routerhub.api.RouterSessionExpiredException
import dev.routerhub.api.SettingField
import dev.routerhub.api.SshAuth
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TendaFirmwareTest {
    private val router = FakeTendaRouter(password = "secret")
    private val open = mutableListOf<RouterFirmware>()

    @After
    fun tearDown() {
        runBlocking { open.forEach { it.close() } }
        router.close()
    }

    private val factory = TendaAcFactory(TendaModel.AC10_V6)

    private fun firmware(password: String = "secret"): RouterFirmware =
        factory.create(ConnectionConfig.Web(host = "127.0.0.1", username = "admin", password = password, port = router.port, timeoutMillis = 5_000))
            .also { open += it }

    private fun loggedIn(): RouterFirmware = firmware().also { runBlocking { it.login() } }

    // --- the factory -------------------------------------------------------------------------

    @Test
    fun `the factory describes the router and what it can do`() {
        assertEquals("tenda.ac.ac10v6", factory.id)
        assertEquals("Tenda", factory.vendor)
        assertEquals(Protocol.WEB, factory.protocol)
        assertEquals(80, factory.defaultPort)
        assertEquals(
            setOf(Capability.STATUS, Capability.CONNECTED_CLIENTS, Capability.REBOOT, Capability.WIFI_TOGGLE, Capability.SETTINGS),
            factory.capabilities,
        )
        assertEquals(listOf("tenda.ac.ac10v6"), TendaAcExtension().factories.map { it.id })
    }

    @Test
    fun `only a web configuration is accepted`() {
        try {
            factory.create(ConnectionConfig.Ssh(host = "192.168.0.1", username = "root", auth = SshAuth.Password("x")))
            fail("expected an InvalidConfigurationException")
        } catch (_: InvalidConfigurationException) {
        }
    }

    // --- status and clients ------------------------------------------------------------------

    @Test
    fun `status and clients come from the router's answers`() = runBlocking {
        router.answers["GetSystemStatus"] = Fixtures.SYSTEM_STATUS
        router.answers["GetRouterStatus"] = Fixtures.ROUTER_STATUS
        router.answers["getOnlineList"] = Fixtures.ONLINE_LIST
        val f = loggedIn()

        val status = f.getStatus()
        assertEquals("AC10 V6.0", status.model)
        assertEquals(true, status.wanConnected)
        assertEquals(3, f.getConnectedClients().size)
    }

    // --- Wi-Fi switch and settings -----------------------------------------------------------

    @Test
    fun `the wifi switch writes the whole wifi page with one radio changed`() = runBlocking {
        router.answers["WifiBasicGet"] = Fixtures.WIFI_BASIC
        val f = loggedIn()
        f.setWifiEnabled("5g", false)
        val (name, body) = router.writes.single()
        assertEquals("WifiBasicSet", name)
        assertTrue(body, body.startsWith("doubleBand=0&wrlEn=1&wrlEn_5g=0&"))
    }

    @Test
    fun `settings are listed, read and written through the pages`() = runBlocking {
        router.answers["GetUpnpCfg"] = Fixtures.UPNP
        val f = loggedIn()

        val pages = f.listSettingsPages()
        assertEquals(TendaSettings.pages.map { it.info.id }, pages.map { it.id })
        assertTrue(pages.any { it.id == "wifi" })

        val page = f.readSettings("upnp")
        assertEquals(true, (page.fields.single() as SettingField.Toggle).value)
        f.writeSettings("upnp", mapOf("enable" to "false"))
        assertEquals(listOf("SetUpnpCfg" to "upnpEn=0"), router.writes)
    }

    @Test
    fun `an unknown settings page is refused by its id`() {
        val f = loggedIn()
        try {
            runBlocking { f.readSettings("nope") }
            fail("expected an InvalidSettingException")
        } catch (e: InvalidSettingException) {
            assertEquals("page", e.key)
        }
        try {
            runBlocking { f.writeSettings("nope", emptyMap()) }
            fail("expected an InvalidSettingException")
        } catch (e: InvalidSettingException) {
            assertEquals("page", e.key)
        }
    }

    // --- reboot ------------------------------------------------------------------------------

    @Test
    fun `a reboot is a plain form post after proving the session is alive`() = runBlocking {
        router.answers["GetSysStatus"] = "{}"
        val f = loggedIn()
        f.reboot()
        assertEquals(listOf("SysToolReboot" to "action=0"), router.plainPosts)
        val log = router.log
        assertTrue("the session was proven first", log.indexOf("GET /goform/GetSysStatus") < log.indexOf("POST /goform/SysToolReboot"))
    }

    @Test
    fun `a reboot with a dead session does nothing and says so`() {
        router.answers["GetSysStatus"] = "{}"
        val f = loggedIn()
        router.dropSession()
        try {
            runBlocking { f.reboot() }
            fail("expected a RouterSessionExpiredException")
        } catch (_: RouterSessionExpiredException) {
        }
        assertTrue("the router was not asked to reboot", router.plainPosts.isEmpty())
    }

    // --- login password ----------------------------------------------------------------------

    @Test
    fun `changing the login password follows it and keeps working`() = runBlocking {
        router.answers["SysToolpassword"] = Fixtures.PASSWORD_INFO
        router.answers["GetSysStatus"] = "{}"
        val f = loggedIn()

        f.writeSettings("login-password", mapOf("password" to "n3w-secret"))

        val post = router.plainPosts.single().second
        assertTrue(post, post.contains("SYSOPS=${TendaCrypto.md5("secret")}"))
        assertTrue(post, post.contains("SYSPS=${TendaCrypto.md5("n3w-secret")}"))
        assertTrue("the session was renewed with the new password", router.hasSession)

        // the firmware kept the new password: when the session expires, the host logs in again with it
        router.dropSession()
        f.login()
        f.reboot()
        assertEquals(2, router.plainPosts.size)
    }

    @Test
    fun `a password change the router did not take is reported and the old password stays`() = runBlocking {
        router.answers["SysToolpassword"] = Fixtures.PASSWORD_INFO
        router.answers["GetSysStatus"] = "{}"
        router.rejectPasswordChange = true
        val f = loggedIn()

        try {
            f.writeSettings("login-password", mapOf("password" to "n3w-secret"))
            fail("expected a FirmwareParseException")
        } catch (e: FirmwareParseException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("did not accept"))
        }

        // the host logs in again, as it does after an expired session, and the old password still works
        f.login()
        assertTrue(router.hasSession)
        f.reboot()
        assertFalse(router.plainPosts.isEmpty())
    }

    @Test
    fun `closing logs out of the router`() = runBlocking {
        val f = loggedIn()
        f.close()
        assertFalse(router.hasSession)
        assertTrue(router.log.contains("GET /goform/exit"))
    }
}
