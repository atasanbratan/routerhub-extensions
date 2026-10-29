package dev.routerhub.extension.tplink

import dev.routerhub.api.ConnectionConfig
import dev.routerhub.api.InvalidSettingException
import dev.routerhub.api.RouterException
import dev.routerhub.api.RouterFirmware
import dev.routerhub.api.SettingField
import dev.routerhub.api.SettingsPage
import dev.routerhub.api.TableCodec
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * REAL WRITES against a real router. Every test changes a setting, reads it back from the router,
 * restores the original and reads that back too, in try/finally so a failed assertion still restores.
 *
 * Opt-in only, because it modifies the router:
 *
 *   ROUTERHUB_LIVE_HOST=192.168.1.1 ROUTERHUB_LIVE_USER=admin ROUTERHUB_LIVE_PASS=... ROUTERHUB_LIVE_WRITE=1 \
 *     ./gradlew :extensions:tplink-adsl:testDebugUnitTest --tests '*TpLinkLiveWriteTest.<name>' -i
 *
 * Secrets (Wi-Fi password) are held in memory to restore them and are never printed. The list
 * rows it adds use obviously fake addresses (locally administered MACs, an unused LAN address).
 */
class TpLinkLiveWriteTest {

    private val host: String? = System.getenv("ROUTERHUB_LIVE_HOST")
    private val user = System.getenv("ROUTERHUB_LIVE_USER") ?: "admin"
    private val pass = System.getenv("ROUTERHUB_LIVE_PASS").orEmpty()
    private val writesAllowed = System.getenv("ROUTERHUB_LIVE_WRITE") == "1"

    private fun web(password: String = pass) = ConnectionConfig.Web(host = host!!, username = user, password = password, timeoutMillis = 30_000)

    private fun live(name: String, password: String = pass, block: suspend (RouterFirmware) -> Unit) = runBlocking {
        assumeTrue("set ROUTERHUB_LIVE_HOST and ROUTERHUB_LIVE_WRITE=1 to run real writes", !host.isNullOrBlank() && writesAllowed)
        val firmware = TpLinkAdslFactory(TpLinkModel.TD_W8960N).create(web(password))
        try {
            firmware.login()
            block(firmware)
            println("LIVE PASS $name")
        } finally {
            firmware.close()
        }
    }

    // --- helpers ------------------------------------------------------------------------------

    private fun SettingsPage.field(key: String): SettingField =
        fields.firstOrNull { it.key == key } ?: error("No field '$key' on ${info.id}; fields are ${fields.map { it.key }}")

    private fun SettingsPage.toggle(key: String) = (field(key) as SettingField.Toggle).value
    private fun SettingsPage.text(key: String) = (field(key) as SettingField.Text).value
    private fun SettingsPage.number(key: String) = (field(key) as SettingField.Number).value
    private fun SettingsPage.choice(key: String) = (field(key) as SettingField.Choice).value
    private fun SettingsPage.rows(key: String) = (field(key) as SettingField.Table).rows

    /** Reads a page, retrying briefly: after a radio restart the router's web server can stall. */
    private suspend fun RouterFirmware.read(id: String, attempts: Int = 5): SettingsPage {
        var last: RouterException? = null
        repeat(attempts) {
            try {
                return readSettings(id)
            } catch (e: RouterException) {
                last = e
                delay(2500)
            }
        }
        throw last!!
    }

    private suspend fun RouterFirmware.write(id: String, vararg changes: Pair<String, String>, settle: Long = 1500) {
        writeSettings(id, changes.toMap())
        delay(settle)
    }

    private fun rowsOf(vararg rows: List<String>) = TableCodec.encode(rows.toList())

    private suspend fun <T> rawPage(path: String, block: (String) -> T): T {
        val client = TpLinkHttpClient(web())
        try {
            client.login()
            return block(client.fetchHtml(path))
        } finally {
            client.close()
        }
    }

    private suspend fun wifiPassword(): String = rawPage(WirelessSecurityPage.SELECT_MAIN_NETWORK) {
        Regex("""(?m)^\s*var\s+wpaPskKey\s*=\s*'([^']*)'""").find(it)!!.groupValues[1]
    }

    private suspend fun snmpCommunities(): Pair<String, String> = rawPage("/snmpconfig.html") {
        Regex("""rocommunity\.value\s*=\s*(['"])(.*?)\1""").find(it)!!.groupValues[2] to
            Regex("""rwcommunity\.value\s*=\s*(['"])(.*?)\1""").find(it)!!.groupValues[2]
    }

    // --- a before/after picture of the whole router -------------------------------------------

    @Test
    fun snapshot() = live("snapshot") { fw ->
        for (info in fw.listSettingsPages()) {
            val page = fw.read(info.id)
            for (field in page.fields) {
                val value = when (field) {
                    is SettingField.Info -> field.value
                    is SettingField.Toggle -> field.value.toString()
                    is SettingField.Text -> field.value
                    is SettingField.Number -> field.value.toString()
                    is SettingField.Choice -> field.value
                    is SettingField.Secret -> "<secret, never read>"
                    is SettingField.Table -> "${field.rows.size} rows " + field.rows.joinToString(" | ") { it.joinToString("/") }
                }
                // The router's clock and its log move on their own, so they stay out of the comparison.
                if (field.key == "clock" || field.key == "log") continue
                println("SNAP ${info.id}.${field.key} = $value")
            }
        }
    }

    // --- low-risk pages -----------------------------------------------------------------------

    @Test
    fun upnp() = live("upnp") { fw ->
        val before = fw.read("upnp").toggle("enable")
        try {
            fw.write("upnp", "enable" to (!before).toString())
            assertEquals("router should now report UPnP flipped", !before, fw.read("upnp").toggle("enable"))
        } finally {
            fw.write("upnp", "enable" to before.toString())
        }
        assertEquals("UPnP restored", before, fw.read("upnp").toggle("enable"))
    }

    @Test
    fun dmz() = live("dmz") { fw ->
        val before = fw.read("dmz").text("address")
        try {
            fw.write("dmz", "address" to "192.168.1.55")
            assertEquals("192.168.1.55", fw.read("dmz").text("address"))
        } finally {
            fw.write("dmz", "address" to before)
        }
        assertEquals("DMZ restored", before, fw.read("dmz").text("address"))
    }

    @Test
    fun logSettings() = live("log settings") { fw ->
        val before = fw.read("log-settings")
        val display = before.choice("display")
        val level = before.choice("level")
        val newDisplay = if (display == "5") "4" else "5"
        try {
            fw.write("log-settings", "display" to newDisplay)
            assertEquals(newDisplay, fw.read("log-settings").choice("display"))
            assertEquals("untouched fields must survive", level, fw.read("log-settings").choice("level"))
        } finally {
            fw.write("log-settings", "display" to display)
        }
        assertEquals(display, fw.read("log-settings").choice("display"))
    }

    @Test
    fun time() = live("time") { fw ->
        val before = fw.read("time")
        val zone = before.choice("zone")
        val server3 = before.text("server3")
        val newZone = if (zone == "16") "14" else "16" // 16 is GMT+02:00: exercises the raw "+" in the offset
        try {
            fw.write("time", "zone" to newZone, "server3" to "pool.ntp.org")
            val after = fw.read("time")
            assertEquals(newZone, after.choice("zone"))
            assertEquals("pool.ntp.org", after.text("server3"))
            assertEquals("server 1 untouched", before.text("server1"), after.text("server1"))
        } finally {
            fw.write("time", "zone" to zone, "server3" to server3)
        }
        val restored = fw.read("time")
        assertEquals(zone, restored.choice("zone"))
        assertEquals(server3, restored.text("server3"))
    }

    @Test
    fun snmp() = live("snmp") { fw ->
        val before = fw.read("snmp")
        val wasEnabled = before.toggle("enable")
        val (ro, rw) = snmpCommunities()
        try {
            // 1. the app's own safeguard: refuse to run SNMP with the well-known strings
            if (ro == "public" || rw == "private") {
                try {
                    fw.write("snmp", "enable" to "true")
                    error("enabling with default communities should have been refused")
                } catch (e: InvalidSettingException) {
                    println("LIVE safeguard held: ${e.message}")
                }
            }
            // 2. real enable with our own strings
            fw.write("snmp", "enable" to "true", "readCommunity" to "rhtest_ro", "writeCommunity" to "rhtest_rw")
            assertTrue("SNMP should be on", fw.read("snmp").toggle("enable"))
            assertEquals("rhtest_ro", snmpCommunities().first)
        } finally {
            // 3. put everything back: agent off first, then the original strings
            fw.write("snmp", "enable" to wasEnabled.toString(), "readCommunity" to ro, "writeCommunity" to rw)
        }
        val restored = fw.read("snmp")
        assertEquals(wasEnabled, restored.toggle("enable"))
        assertEquals(ro to rw, snmpCommunities())
        assertEquals(before.text("name"), restored.text("name"))
    }

    // --- list pages: the real row layouts finally get exercised ------------------------------

    @Test
    fun dhcpReservations() = live("dhcp reservations") { fw ->
        val before = fw.read("dhcp-reservations").rows("reservations")
        val added = listOf("02:00:00:00:00:99", "192.168.1.77", "")
        try {
            fw.write("dhcp-reservations", "reservations" to TableCodec.encode(before + listOf(added)))
            val rows = fw.read("dhcp-reservations").rows("reservations")
            println("LIVE reservation rows after add: $rows")
            assertTrue("new reservation visible", rows.any { it[0] == "02:00:00:00:00:99" && it[1] == "192.168.1.77" })
        } finally {
            fw.write("dhcp-reservations", "reservations" to TableCodec.encode(before))
        }
        assertEquals("reservations restored", before, fw.read("dhcp-reservations").rows("reservations"))
    }

    @Test
    fun portForwarding() = live("port forwarding") { fw ->
        val page = fw.read("port-forwarding")
        val before = page.rows("rules")
        val wan = (page.field("rules") as SettingField.Table).columns.last().options.first().value
        val rule = listOf("RHTest", "8081", "8081", "1", "8081", "8081", "192.168.1.50", wan)
        try {
            fw.write("port-forwarding", "rules" to TableCodec.encode(before + listOf(rule)))
            val rows = fw.read("port-forwarding").rows("rules")
            println("LIVE port forwarding rows after add: $rows")
            assertTrue("rule visible with the cells we sent", rows.any { it == rule })
        } finally {
            fw.write("port-forwarding", "rules" to TableCodec.encode(before))
        }
        assertEquals("rules restored", before, fw.read("port-forwarding").rows("rules"))
    }

    @Test
    fun staticRoutes() = live("static routes") { fw ->
        val before = fw.read("static-routes").rows("routes")
        val route = listOf("10.99.0.0/24", "192.168.1.2", "br0", "", "")
        try {
            fw.write("static-routes", "routes" to TableCodec.encode(before + listOf(route)))
            val rows = fw.read("static-routes").rows("routes")
            println("LIVE static route rows after add: $rows")
            assertTrue("route visible", rows.any { it[0] == "10.99.0.0/24" && it[1] == "192.168.1.2" })
        } finally {
            fw.write("static-routes", "routes" to TableCodec.encode(before))
        }
        assertEquals("routes restored", before, fw.read("static-routes").rows("routes"))
    }

    @Test
    fun websiteFilter() = live("website filter") { fw ->
        val before = fw.read("url-filter")
        val mode = before.choice("mode")
        val devices = before.rows("devices")
        val urls = before.rows("urls")
        try {
            fw.write(
                "url-filter",
                "mode" to "Exclude",
                "devices" to TableCodec.encode(devices + listOf(listOf("02:00:00:00:00:98"))),
                "urls" to TableCodec.encode(urls + listOf(listOf("example.org"))),
            )
            val after = fw.read("url-filter")
            println("LIVE url filter after add: mode=${after.choice("mode")} devices=${after.rows("devices")} urls=${after.rows("urls")}")
            assertEquals("Exclude", after.choice("mode"))
            assertTrue(after.rows("devices").any { it[0] == "02:00:00:00:00:98" })
            assertTrue(after.rows("urls").any { it[0] == "example.org" })
        } finally {
            fw.write("url-filter", "mode" to mode, "devices" to TableCodec.encode(devices), "urls" to TableCodec.encode(urls))
        }
        val restored = fw.read("url-filter")
        assertEquals(mode, restored.choice("mode"))
        assertEquals(devices, restored.rows("devices"))
        assertEquals(urls, restored.rows("urls"))
    }

    @Test
    fun macFilter() = live("mac filter") { fw ->
        val before = fw.read("wireless-mac-filter")
        val mode = before.choice("mode")
        val devices = before.rows("devices")
        try {
            fw.write(
                "wireless-mac-filter",
                "mode" to "deny",
                "devices" to TableCodec.encode(devices + listOf(listOf("02:00:00:00:00:97"))),
            )
            val after = fw.read("wireless-mac-filter")
            println("LIVE mac filter after add: mode=${after.choice("mode")} devices=${after.rows("devices")}")
            assertEquals("deny", after.choice("mode"))
            assertTrue(after.rows("devices").any { it[0] == "02:00:00:00:00:97" })
        } finally {
            fw.write("wireless-mac-filter", "mode" to mode, "devices" to TableCodec.encode(devices))
        }
        val restored = fw.read("wireless-mac-filter")
        assertEquals(mode, restored.choice("mode"))
        assertEquals(devices, restored.rows("devices"))
    }

    @Test
    fun dynamicDns() = live("dynamic dns") { fw ->
        val page = fw.read("dynamic-dns")
        val before = page.rows("entries")
        val iface = (page.field("entries") as SettingField.Table).columns[3].options.first().value
        val account = listOf("rhtest.example.org", "rhtest", "dyndns", iface, "rhtest-password")
        try {
            fw.write("dynamic-dns", "entries" to TableCodec.encode(before + listOf(account)))
            val rows = fw.read("dynamic-dns").rows("entries")
            println("LIVE ddns rows after add: $rows")
            assertTrue("account visible, password never shown", rows.any { it[0] == "rhtest.example.org" && it[4] == "" })
        } finally {
            fw.write("dynamic-dns", "entries" to TableCodec.encode(before))
        }
        assertEquals("accounts restored", before, fw.read("dynamic-dns").rows("entries"))
    }

    // --- DHCP server and the Wi-Fi schedule ---------------------------------------------------

    @Test
    fun dhcpServer() = live("dhcp server") { fw ->
        val before = fw.read("dhcp")
        val end = before.text("end")
        val lease = before.number("lease")
        try {
            fw.write("dhcp", "end" to "192.168.1.150", "lease" to "12")
            val after = fw.read("dhcp")
            assertEquals("192.168.1.150", after.text("end"))
            assertEquals(12L, after.number("lease"))
            assertEquals("first address untouched", before.text("start"), after.text("start"))
        } finally {
            fw.write("dhcp", "end" to end, "lease" to lease.toString())
        }
        val restored = fw.read("dhcp")
        assertEquals(end, restored.text("end"))
        assertEquals(lease, restored.number("lease"))
    }

    @Test
    fun wifiSchedule() = live("wifi schedule") { fw ->
        // The app's guard: an unset clock / empty pattern must never be allowed to switch it on.
        try {
            fw.write("wireless-schedule", "enable" to "true")
            error("enabling an empty schedule should have been refused")
        } catch (e: InvalidSettingException) {
            println("LIVE safeguard held: ${e.message}")
        }
        // The request itself: switching it off (already off) is accepted by the router and changes nothing.
        fw.write("wireless-schedule", "enable" to "false")
        assertFalse(fw.read("wireless-schedule").toggle("enable"))
    }

    // --- wireless: these restart the radio, so they settle longer ----------------------------

    @Test
    fun wifiBasics() = live("wifi basics") { fw ->
        val before = fw.read("wireless-basic")
        val ssid = before.text("ssid")
        val hidden = before.toggle("hide")
        val isolated = before.toggle("isolate")
        val maxClients = before.number("maxClients")
        try {
            fw.write(
                "wireless-basic",
                "ssid" to "RH-Test", "hide" to (!hidden).toString(), "isolate" to (!isolated).toString(), "maxClients" to "16",
                settle = 7000,
            )
            val after = fw.read("wireless-basic")
            assertEquals("RH-Test", after.text("ssid"))
            assertEquals(!hidden, after.toggle("hide"))
            assertEquals(!isolated, after.toggle("isolate"))
            assertEquals(16L, after.number("maxClients"))
        } finally {
            fw.write(
                "wireless-basic",
                "ssid" to ssid, "hide" to hidden.toString(), "isolate" to isolated.toString(), "maxClients" to maxClients.toString(),
                settle = 7000,
            )
        }
        val restored = fw.read("wireless-basic")
        assertEquals(ssid, restored.text("ssid"))
        assertEquals(hidden, restored.toggle("hide"))
        assertEquals(isolated, restored.toggle("isolate"))
        assertEquals(maxClients, restored.number("maxClients"))
        assertTrue("radio still on", restored.toggle("enable"))
    }

    @Test
    fun guestNetwork() = live("guest network") { fw ->
        val before = fw.read("wireless-guest")
        val enabled = before.toggle("enable")
        val ssid = before.text("ssid")
        val security = before.choice("security")
        val encryption = before.choice("encryption")
        val lan = before.toggle("lanAccess")
        val isolate = before.toggle("isolate")
        try {
            fw.write(
                "wireless-guest",
                "enable" to "true", "ssid" to "RH-Guest", "security" to "psk", "password" to "rhGuestPw1", "encryption" to "aes",
                "lanAccess" to "false", "isolate" to "true",
                settle = 7000,
            )
            val after = fw.read("wireless-guest")
            assertTrue(after.toggle("enable"))
            assertEquals("RH-Guest", after.text("ssid"))
            assertEquals("psk", after.choice("security"))
            assertEquals("aes", after.choice("encryption"))
            assertTrue(after.toggle("isolate"))
        } finally {
            // back to the original name and security first, then the original on/off state
            fw.write(
                "wireless-guest",
                "enable" to "true", "ssid" to ssid, "security" to security, "encryption" to encryption,
                "lanAccess" to lan.toString(), "isolate" to isolate.toString(),
                settle = 7000,
            )
            if (!enabled) fw.write("wireless-guest", "enable" to "false", settle = 7000)
        }
        val restored = fw.read("wireless-guest")
        assertEquals(enabled, restored.toggle("enable"))
        assertEquals(ssid, restored.text("ssid"))
        assertEquals(security, restored.choice("security"))
        // the page used for the main network's security must still show the MAIN network afterwards
        assertEquals("psk2", fw.read("wireless-security").choice("mode"))
    }

    @Test
    fun wifiSecurity() = live("wifi security") { fw ->
        val before = fw.read("wireless-security")
        val mode = before.choice("mode")
        val encryption = before.choice("encryption")
        val original = wifiPassword() // in memory only; never printed
        val testPassword = "RhTestPass123"
        try {
            fw.write("wireless-security", "password" to testPassword, "encryption" to "tkip+aes", settle = 7000)
            assertEquals("the password really changed on the router", testPassword, wifiPassword())
            val after = fw.read("wireless-security")
            assertEquals("mode untouched", mode, after.choice("mode"))
            assertEquals("tkip+aes", after.choice("encryption"))
        } finally {
            fw.write("wireless-security", "password" to original, "encryption" to encryption, settle = 7000)
        }
        assertEquals("the ORIGINAL password is back", original, wifiPassword())
        val restored = fw.read("wireless-security")
        assertEquals(mode, restored.choice("mode"))
        assertEquals(encryption, restored.choice("encryption"))
    }

    // --- remote access (left CLOSED on purpose) and the router password ----------------------

    @Test
    fun remoteAccess() = live("remote access") { fw ->
        val before = fw.read("remote-access")
        val webKey = before.fields.first { it.key.startsWith("web:") }.key
        val telnetKey = before.fields.first { it.key.startsWith("telnet:") }.key
        println("LIVE remote access before: web=${before.toggle(webKey)} telnet=${before.toggle(telnetKey)}")

        // 1. closing is always allowed, even with the default password
        fw.write("remote-access", webKey to "false", telnetKey to "false")
        val closed = fw.read("remote-access")
        assertFalse(closed.toggle(webKey))
        assertFalse(closed.toggle(telnetKey))
        assertFalse("no exposure warning once closed", closed.fields.any { it.key == "exposure" })

        // 2. the app's guard: opening them again with a default password is refused
        try {
            fw.write("remote-access", webKey to "true")
            error("opening web with the default password should have been refused")
        } catch (e: InvalidSettingException) {
            println("LIVE safeguard held: ${e.message}")
        }
        assertFalse(fw.read("remote-access").toggle(webKey))
    }

    @Test
    fun routerPassword() = live("router password") { fw ->
        val temporary = "RhTest12345"
        try {
            fw.write("admin-password", "password" to temporary, settle = 2500)
            // the same firmware object must keep working: it re-logged in with the new password
            assertTrue(fw.read("upnp").fields.isNotEmpty())
            // and the OLD password must no longer open the router
            val old = TpLinkAdslFactory(TpLinkModel.TD_W8960N).create(web(pass))
            try {
                old.login()
                error("the old password should have been rejected")
            } catch (e: dev.routerhub.api.WrongCredentialsException) {
                println("LIVE old password rejected as expected")
            } finally {
                old.close()
            }
        } finally {
            // restore: the firmware object knows the temporary password now
            fw.write("admin-password", "password" to pass, settle = 2500)
        }
        // a fresh login with the ORIGINAL password works again
        val again = TpLinkAdslFactory(TpLinkModel.TD_W8960N).create(web(pass))
        try {
            again.login()
            assertNotEquals(0, again.listSettingsPages().size)
        } finally {
            again.close()
        }
    }

    // --- the telnet transport and reboot -----------------------------------------------------

    @Test
    fun telnetWifiToggle() = runBlocking {
        assumeTrue("real writes are opt-in", !host.isNullOrBlank() && writesAllowed)
        val firmware = TpLinkTelnetFactory(TpLinkModel.TD_W8960N)
            .create(ConnectionConfig.Telnet(host = host!!, username = user, password = pass, timeoutMillis = 30_000))
        try {
            firmware.login()
            val before = firmware.getStatus().wifiRadios.single()
            println("LIVE telnet: radio enabled before = ${before.enabled}")
            try {
                firmware.setWifiEnabled("wl0", !before.enabled)
                delay(2000)
                assertEquals(!before.enabled, firmware.getStatus().wifiRadios.single().enabled)
            } finally {
                firmware.setWifiEnabled("wl0", before.enabled)
                delay(2000)
            }
            assertEquals(before.enabled, firmware.getStatus().wifiRadios.single().enabled)
        } finally {
            firmware.close()
        }
        println("LIVE PASS telnet wifi toggle")
    }

    @Test
    fun webReboot() = live("web reboot") { fw ->
        val uptimeBefore = fw.getStatus().uptimeSeconds!!
        println("LIVE uptime before reboot: ${uptimeBefore}s")
        fw.reboot()
        fw.close()

        // wait for it to go down and come back, up to about three minutes
        var back: RouterFirmware? = null
        for (attempt in 1..36) {
            delay(5000)
            val candidate = TpLinkAdslFactory(TpLinkModel.TD_W8960N).create(web())
            try {
                candidate.login()
                val uptime = candidate.getStatus().uptimeSeconds!!
                if (uptime < uptimeBefore) {
                    back = candidate
                    break
                }
                candidate.close()
            } catch (e: RouterException) {
                candidate.close()
            }
        }
        val after = back ?: error("the router did not come back within three minutes")
        try {
            println("LIVE uptime after reboot: ${after.getStatus().uptimeSeconds}s")
        } finally {
            after.close()
        }
    }
}
