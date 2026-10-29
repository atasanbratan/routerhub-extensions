package dev.routerhub.extension.demo

import dev.routerhub.api.ConnectionConfig
import dev.routerhub.api.InvalidConfigurationException
import dev.routerhub.api.InvalidSettingException
import dev.routerhub.api.RouterFirmware
import dev.routerhub.api.SettingField
import dev.routerhub.api.SettingsPage
import dev.routerhub.api.TableCodec
import dev.routerhub.api.WrongCredentialsException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DemoFirmwareTest {
    private var counter = 0

    /** Every test gets its own pretend router: they are shared per host name. */
    private fun firmware(password: String = "secret"): RouterFirmware {
        val host = "demo-${System.nanoTime()}-${counter++}.test"
        return DemoFactory().create(ConnectionConfig.Web(host = host, username = "admin", password = password))
    }

    private fun SettingsPage.field(key: String): SettingField = fields.first { it.key == key }

    private fun SettingsPage.text(key: String) = (field(key) as SettingField.Text).value

    private fun SettingsPage.rows(key: String) = (field(key) as SettingField.Table).rows

    private fun rejected(key: String, block: suspend () -> Unit) {
        try {
            runBlocking { block() }
            fail("expected an InvalidSettingException for $key")
        } catch (e: InvalidSettingException) {
            assertEquals(key, e.key)
        }
    }

    @Test
    fun `a config for another protocol is refused`() {
        val telnet = ConnectionConfig.Telnet(host = "demo.test", username = "admin", password = "x")
        try {
            DemoFactory().create(telnet)
            fail("expected an InvalidConfigurationException")
        } catch (_: InvalidConfigurationException) {
        }
    }

    @Test
    fun `the magic password fails like a real router would`() {
        try {
            runBlocking { firmware(DemoFirmware.WRONG_PASSWORD).login() }
            fail("expected a WrongCredentialsException")
        } catch (_: WrongCredentialsException) {
        }
        runBlocking { firmware().login() }
    }

    @Test
    fun `the Wi-Fi switch is idempotent and can target one radio`() = runBlocking {
        val router = firmware()
        router.setWifiEnabled(null, false)
        router.setWifiEnabled(null, false)
        assertTrue(router.getStatus().wifiRadios.none { it.enabled })

        router.setWifiEnabled("wl1", true)
        val radios = router.getStatus().wifiRadios.associate { it.id to it.enabled }
        assertEquals(mapOf("wl0" to false, "wl1" to true), radios)
    }

    @Test
    fun `a Wi-Fi change is validated first and applied afterwards`() = runBlocking {
        val router = firmware()
        router.writeSettings("wifi", mapOf("ssid" to "Cafe", "hide" to "true"))
        val page = router.readSettings("wifi")
        assertEquals("Cafe", page.text("ssid"))
        assertTrue((page.field("hide") as SettingField.Toggle).value)

        // one bad value rejects the whole save: the good change next to it must not be applied
        rejected("password") { router.writeSettings("wifi", mapOf("ssid" to "Other", "password" to "short")) }
        assertEquals("Cafe", router.readSettings("wifi").text("ssid"))
    }

    @Test
    fun `a protected network is never switched to open`() {
        val router = firmware()
        rejected("security") { router.writeSettings("wifi", mapOf("security" to "open")) }
        runBlocking { assertEquals("wpa2", (router.readSettings("wifi").field("security") as SettingField.Choice).value) }
    }

    @Test
    fun `the DHCP range must run forwards`() {
        val router = firmware()
        rejected("end") { router.writeSettings("dhcp", mapOf("end" to "192.168.1.50")) }
        runBlocking {
            router.writeSettings("dhcp", mapOf("end" to "192.168.1.150", "lease" to "12"))
            val page = router.readSettings("dhcp")
            assertEquals("192.168.1.150", page.text("end"))
            assertEquals(12L, (page.field("lease") as SettingField.Number).value)
        }
    }

    @Test
    fun `port forwarding takes the complete list and rejects a clash`() {
        val router = firmware()
        val before = runBlocking { router.readSettings("port-forwarding").rows("rules") }
        assertEquals(2, before.size)

        val added = listOf("Camera", "tcp", "8443", "192.168.1.60", "443")
        runBlocking { router.writeSettings("port-forwarding", mapOf("rules" to TableCodec.encode(before + listOf(added)))) }
        assertEquals(before + listOf(added), runBlocking { router.readSettings("port-forwarding").rows("rules") })

        // the same protocol and external port twice is refused, and the list stays as it was
        val clash = listOf("Other", "tcp", "8443", "192.168.1.61", "80")
        rejected("rules") { router.writeSettings("port-forwarding", mapOf("rules" to TableCodec.encode(before + listOf(added, clash)))) }
        assertEquals(3, runBlocking { router.readSettings("port-forwarding").rows("rules") }.size)
    }

    @Test
    fun `the MAC filter normalises addresses and refuses malformed ones`() {
        val router = firmware()
        val before = runBlocking { router.readSettings("mac-filter").rows("devices") }
        assertEquals(listOf(listOf("02:00:00:00:00:21")), before)

        runBlocking {
            router.writeSettings("mac-filter", mapOf("devices" to TableCodec.encode(before + listOf(listOf(" 02:AA:BB:CC:DD:EE ")))))
        }
        val after = runBlocking { router.readSettings("mac-filter").rows("devices") }
        assertEquals(before + listOf(listOf("02:aa:bb:cc:dd:ee")), after)

        val error = try {
            runBlocking { router.writeSettings("mac-filter", mapOf("devices" to TableCodec.encode(listOf(listOf("00-11-22-33-44-55"))))) }
            null
        } catch (e: InvalidSettingException) {
            e
        }
        assertEquals("devices", error?.key)
        assertTrue("the message shows the expected format", error?.message.orEmpty().contains("00:11:22:33:44:55"))
        assertEquals("a refused list changes nothing", after, runBlocking { router.readSettings("mac-filter").rows("devices") })
    }

    @Test
    fun `an allow-only MAC filter with no devices is refused`() {
        val router = firmware()
        rejected("devices") { router.writeSettings("mac-filter", mapOf("mode" to "allow", "devices" to "")) }
        runBlocking { assertEquals("deny", (router.readSettings("mac-filter").field("mode") as SettingField.Choice).value) }
    }

    @Test
    fun `the system log is read only`() {
        val router = firmware()
        val log = runBlocking { router.readSettings("log").field("log") as SettingField.Table }
        assertTrue(log.readOnly)
        assertTrue(log.rows.isNotEmpty())
        rejected("page") { router.writeSettings("log", mapOf("log" to "")) }
    }

    @Test
    fun `the password page names the login password so the app can follow it`() {
        val page = runBlocking { firmware().readSettings("password") }
        assertEquals("password", page.loginPasswordKey)
        val secret = page.field("password") as SettingField.Secret
        assertTrue(secret.required)
        assertTrue(secret.requiresConfirmation)
    }

    @Test
    fun `every page the router lists can be read`() = runBlocking {
        val router = firmware()
        val pages = router.listSettingsPages()
        assertFalse(pages.isEmpty())
        for (info in pages) {
            val page = router.readSettings(info.id)
            assertNotNull(page)
            assertEquals(info.id, page.info.id)
            assertFalse(page.fields.isEmpty())
        }
    }
}
