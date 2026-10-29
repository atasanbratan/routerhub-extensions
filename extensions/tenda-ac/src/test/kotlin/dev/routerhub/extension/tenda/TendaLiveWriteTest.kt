package dev.routerhub.extension.tenda

import dev.routerhub.api.ConnectionConfig
import dev.routerhub.api.RouterException
import dev.routerhub.api.RouterFirmware
import dev.routerhub.api.SettingField
import dev.routerhub.api.SettingsPage
import dev.routerhub.api.TableCodec
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/**
 * REAL WRITES against a real router. Every test changes a setting, reads it back from the router,
 * restores the original and reads that back too, in try/finally so a failed assertion still restores.
 *
 * Opt-in only, because it modifies the router:
 *
 *   ROUTERHUB_LIVE_HOST=192.168.0.1 ROUTERHUB_LIVE_USER=admin ROUTERHUB_LIVE_PASS=... ROUTERHUB_LIVE_WRITE=1 \
 *     ./gradlew :extensions:tenda-ac:cleanTestDebugUnitTest :extensions:tenda-ac:testDebugUnitTest \
 *     --tests '*TendaLiveWriteTest.<name>' -i
 *
 * Left out on purpose: the router's own address, remote management, an allow-only MAC filter and
 * dynamic DNS, because a mistake there can cut the router off. Passwords are held in memory to
 * restore them and never printed. The rows it adds use obviously fake addresses (locally
 * administered MACs, an unused LAN address).
 */
class TendaLiveWriteTest {

    private val host: String? = System.getenv("ROUTERHUB_LIVE_HOST")
    private val user = System.getenv("ROUTERHUB_LIVE_USER") ?: "admin"
    private val pass = System.getenv("ROUTERHUB_LIVE_PASS").orEmpty()
    private val writesAllowed = System.getenv("ROUTERHUB_LIVE_WRITE") == "1"

    private fun web(password: String = pass) =
        ConnectionConfig.Web(host = host!!, username = user, password = password, timeoutMillis = 30_000)

    private fun live(name: String, block: suspend (RouterFirmware) -> Unit) = runBlocking {
        assumeTrue("set ROUTERHUB_LIVE_HOST and ROUTERHUB_LIVE_WRITE=1 to run real writes", !host.isNullOrBlank() && writesAllowed)
        val firmware = TendaAcFactory(TendaModel.AC10_V6).create(web())
        try {
            firmware.login()
            block(firmware)
            println("LIVE PASS $name")
        } finally {
            firmware.close()
        }
    }

    // --- helpers -----------------------------------------------------------------------------

    private fun SettingsPage.field(key: String): SettingField =
        fields.firstOrNull { it.key == key } ?: error("No field '$key' on ${info.id}; fields are ${fields.map { it.key }}")

    private fun SettingsPage.toggle(key: String) = (field(key) as SettingField.Toggle).value
    private fun SettingsPage.text(key: String) = (field(key) as SettingField.Text).value
    private fun SettingsPage.number(key: String) = (field(key) as SettingField.Number).value
    private fun SettingsPage.choice(key: String) = (field(key) as SettingField.Choice).value
    private fun SettingsPage.rows(key: String) = (field(key) as SettingField.Table).rows

    /** Everything on a page that can be written back as it was: the way to undo a test. */
    private fun SettingsPage.asChanges(): Map<String, String> = fields.mapNotNull { f ->
        when (f) {
            is SettingField.Toggle -> f.key to f.value.toString()
            is SettingField.Text -> f.key to f.value
            is SettingField.Choice -> f.key to f.value
            is SettingField.Number -> f.key to f.value.toString()
            is SettingField.Table -> if (f.readOnly) null else f.key to TableCodec.encode(f.rows)
            is SettingField.Info, is SettingField.Secret -> null
        }
    }.toMap()

    /** Runs [block] on a page, then puts the page back as it was found, even if [block] fails. */
    private suspend fun RouterFirmware.restoring(pageId: String, ignore: Set<String> = emptySet(), block: suspend (before: SettingsPage) -> Unit) {
        val before = readSettings(pageId)
        try {
            block(before)
        } finally {
            writeSettings(pageId, before.asChanges())
            delay(SETTLE)
        }
        assertEquals("$pageId was not restored", before.snapshot(ignore), readSettings(pageId).snapshot(ignore))
    }

    private suspend fun RouterFirmware.change(pageId: String, vararg changes: Pair<String, String>): SettingsPage {
        writeSettings(pageId, mapOf(*changes))
        delay(SETTLE)
        return readSettings(pageId)
    }

    /** What a page holds, without the texts that change by themselves (summaries, the clock, secrets). */
    private fun SettingsPage.snapshot(ignore: Set<String>): Map<String, Any> = fields.mapNotNull { f ->
        if (f.key in ignore) return@mapNotNull null
        when (f) {
            is SettingField.Toggle -> f.key to f.value
            is SettingField.Text -> f.key to f.value
            is SettingField.Choice -> f.key to f.value
            is SettingField.Number -> f.key to f.value
            is SettingField.Table -> f.key to f.rows
            is SettingField.Info, is SettingField.Secret -> null
        }
    }.toMap()

    // --- Wi-Fi -------------------------------------------------------------------------------

    @Test
    fun `wifi name and visibility change and are restored`() = live("wifi") { f ->
        f.restoring("wifi") { before ->
            val after = f.change("wifi", "ssid24" to "RouterHub-Live", "hide5" to "true")
            assertEquals("RouterHub-Live", after.text("ssid24"))
            assertTrue(after.toggle("hide5"))
            assertEquals("the 5 GHz name is untouched", before.text("ssid5"), after.text("ssid5"))
        }
    }

    @Test
    fun `one radio is switched off and on again`() = live("radio switch") { f ->
        val before = f.getStatus().wifiRadios.associate { it.id to it.enabled }
        try {
            f.setWifiEnabled("5g", false)
            delay(SETTLE)
            val during = f.getStatus().wifiRadios.associate { it.id to it.enabled }
            assertEquals(false, during["5g"])
            assertEquals("the 2.4 GHz radio is untouched", before["2g"], during["2g"])
        } finally {
            f.setWifiEnabled("5g", before["5g"] ?: true)
            delay(SETTLE)
        }
        assertEquals(before, f.getStatus().wifiRadios.associate { it.id to it.enabled })
    }

    @Test
    fun `the guest network is configured and switched off again`() = live("guest wifi") { f ->
        val password = "g" + UUID.randomUUID().toString().take(11)
        f.restoring("guest-wifi") {
            val after = f.change(
                "guest-wifi",
                "enable" to "true", "open" to "false", "password" to password, "hours" to "4", "limit" to "5",
                "ssid24" to "RouterHub-Guest", "ssid5" to "RouterHub-Guest-5G",
            )
            assertTrue(after.toggle("enable"))
            assertFalse("a password is set", after.toggle("open"))
            assertEquals("4", after.choice("hours"))
            assertEquals(5L, after.number("limit"))
            assertEquals("RouterHub-Guest", after.text("ssid24"))
        }
    }

    @Test
    fun `channel and mode change and are restored`() = live("wifi channel") { f ->
        f.restoring("wifi-channel") {
            val after = f.change("wifi-channel", "channel24" to "6", "width5" to "40", "channel5" to "36")
            assertEquals("6", after.choice("channel24"))
            assertEquals("40", after.choice("width5"))
            assertEquals("36", after.choice("channel5"))
        }
    }

    @Test
    fun `power, wps and beamforming change and are restored`() = live("wifi extras") { f ->
        f.restoring("wifi-extras") { before ->
            val after = f.change("wifi-extras", "power24" to "low", "wps" to (!before.toggle("wps")).toString(), "beamforming" to (!before.toggle("beamforming")).toString())
            assertEquals("low", after.choice("power24"))
            assertEquals(!before.toggle("wps"), after.toggle("wps"))
            assertEquals(!before.toggle("beamforming"), after.toggle("beamforming"))
        }
    }

    // --- Network -----------------------------------------------------------------------------

    @Test
    fun `the lease time changes and is restored`() = live("lan") { f ->
        f.restoring("lan") { before ->
            val other = if (before.choice("lease") == "3600") "21600" else "3600"
            assertEquals(other, f.change("lan", "lease" to other).choice("lease"))
        }
    }

    @Test
    fun `a dhcp reservation is added and removed`() = live("dhcp reservations") { f ->
        f.restoring("dhcp-reservations") { before ->
            val mine = listOf("RouterHub-Live", "02:00:00:00:99:01", "192.168.0.77")
            val after = f.change("dhcp-reservations", "reservations" to TableCodec.encode(before.rows("reservations") + listOf(mine)))
            assertTrue("the reservation reads back as written: ${after.rows("reservations")}", mine in after.rows("reservations"))
        }
    }

    @Test
    fun `a forwarded port is added and removed`() = live("port forwarding") { f ->
        f.restoring("port-forwarding") { before ->
            val mine = listOf("192.168.0.77", "8081", "18081", "tcp")
            val after = f.change("port-forwarding", "rules" to TableCodec.encode(before.rows("rules") + listOf(mine)))
            assertTrue("the rule reads back as written: ${after.rows("rules")}", mine in after.rows("rules"))
        }
    }

    @Test
    fun `the dmz host is set and cleared`() = live("dmz") { f ->
        val before = f.readSettings("dmz")
        try {
            val after = f.change("dmz", "enable" to "true", "ip" to "192.168.0.77")
            assertTrue(after.toggle("enable"))
            assertEquals("192.168.0.77", after.text("ip"))
        } finally {
            // switching it off keeps the address the router holds, so the old address goes back first
            f.writeSettings("dmz", mapOf("enable" to "true", "ip" to before.text("ip")))
            delay(SETTLE)
            f.writeSettings("dmz", mapOf("enable" to before.toggle("enable").toString()))
            delay(SETTLE)
        }
        assertEquals(before.snapshot(emptySet()), f.readSettings("dmz").snapshot(emptySet()))
    }

    @Test
    fun `upnp and the firewall switches change and are restored`() = live("upnp and firewall") { f ->
        f.restoring("upnp") { before ->
            assertEquals(!before.toggle("enable"), f.change("upnp", "enable" to (!before.toggle("enable")).toString()).toggle("enable"))
        }
        f.restoring("firewall") { before ->
            val after = f.change("firewall", "wanPing" to (!before.toggle("wanPing")).toString(), "udp" to (!before.toggle("udp")).toString())
            assertEquals(!before.toggle("wanPing"), after.toggle("wanPing"))
            assertEquals(!before.toggle("udp"), after.toggle("udp"))
            assertEquals("icmp is untouched", before.toggle("icmp"), after.toggle("icmp"))
        }
    }

    @Test
    fun `a device is blacklisted and removed again`() = live("mac filter") { f ->
        f.restoring("mac-filter") { before ->
            assertEquals("the test starts from a blacklist", "black", before.choice("mode"))
            val mine = listOf("RouterHub-Live", "02:00:00:00:99:02")
            val after = f.change("mac-filter", "devices" to TableCodec.encode(before.rows("devices") + listOf(mine)))
            assertTrue("the entry reads back as written: ${after.rows("devices")}", mine in after.rows("devices"))
        }
    }

    // --- System ------------------------------------------------------------------------------

    @Test
    fun `the time zone changes and is restored`() = live("time") { f ->
        f.restoring("time", ignore = setOf("time")) { before ->
            val other = if (before.choice("zone") == "17:00") "13:00" else "17:00"
            val after = f.change("time", "zone" to other)
            assertEquals(other, after.choice("zone"))
            assertEquals("still synchronising", before.choice("mode"), after.choice("mode"))
        }
    }

    @Test
    fun `the leds and the scheduled reboot change and are restored`() = live("led and auto reboot") { f ->
        val led = f.readSettings("led")
        try {
            val after = f.change("led", "mode" to "time", "window" to "01:00-05:00")
            assertEquals("time", after.choice("mode"))
            assertEquals("01:00-05:00", after.text("window"))
        } finally {
            // the window is only sent while the mode is "time", so put the old window back in that mode first
            f.writeSettings("led", mapOf("mode" to "time", "window" to led.text("window")))
            delay(SETTLE)
            f.writeSettings("led", mapOf("mode" to led.choice("mode")))
            delay(SETTLE)
        }
        assertEquals(led.snapshot(emptySet()), f.readSettings("led").snapshot(emptySet()))

        f.restoring("auto-reboot") {
            val after = f.change("auto-reboot", "time" to "03:45")
            assertEquals("03:45", after.text("time"))
        }
    }

    @Test
    fun `the log can be read and not written`() = live("log") { f ->
        val log = f.readSettings("log").field("log") as SettingField.Table
        assertTrue(log.readOnly)
        assertTrue("the router logged something since it started", log.rows.isNotEmpty())
        try {
            f.writeSettings("log", mapOf("log" to ""))
            error("a read-only page accepted a write")
        } catch (_: RouterException) {
        }
    }

    // --- Things that end or replace the session ----------------------------------------------

    /** Changes the login password to a generated one and back. Keep the original password in mind if this fails halfway. */
    @Test
    fun `the login password is changed and changed back`() = live("login password") { f ->
        val temporary = "t" + UUID.randomUUID().toString().replace("-", "").take(14)
        f.writeSettings("login-password", mapOf("password" to temporary))
        try {
            // the firmware logged in again with the new password by itself: reading works
            assertTrue(f.getStatus().firmwareVersion != null)
        } finally {
            f.writeSettings("login-password", mapOf("password" to pass))
        }
        assertTrue("the original password works again", f.getStatus().firmwareVersion != null)
    }

    /** Reboots the router and waits for it to come back. */
    @Test
    fun `the router reboots and comes back`() = live("reboot") { f ->
        f.reboot()
        f.close()
        delay(15_000)
        var back: RouterFirmware? = null
        val deadline = System.currentTimeMillis() + 150_000
        while (back == null && System.currentTimeMillis() < deadline) {
            val candidate = TendaAcFactory(TendaModel.AC10_V6).create(web())
            try {
                candidate.login()
                back = candidate
            } catch (_: RouterException) {
                candidate.close()
                delay(5_000)
            }
        }
        try {
            val uptime = checkNotNull(back) { "the router did not come back within 150 s" }.getStatus().uptimeSeconds
            assertTrue("a fresh start: uptime was $uptime s", uptime != null && uptime < 300)
        } finally {
            back?.close()
        }
    }

    private companion object {
        /** Wi-Fi and network changes are applied a moment after the router answers. */
        const val SETTLE = 2_500L
    }
}
