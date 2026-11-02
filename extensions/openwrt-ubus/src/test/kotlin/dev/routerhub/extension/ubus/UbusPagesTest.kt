package dev.routerhub.extension.ubus

import dev.routerhub.api.RouterUnreachableException
import dev.routerhub.api.SettingField
import dev.routerhub.api.TableCodec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import dev.routerhub.api.CommandFailedException
import org.junit.Test

class UbusPagesTest {
    private val rpcd = FakeRpcd()
    private val network = WifiNetworkPage("default_radio0", "HomeNet")
    private val radio = WifiRadioPage("radio0")

    @Test
    fun `the pages follow the router's Wi-Fi and every page can be read`() = runBlocking {
        val pages = UbusSettings.pages(router(rpcd))
        assertEquals(
            listOf("wifi.default_radio0", "wifi.default_radio1", "radio.radio0", "radio.radio1"),
            pages.map { it.info.id }.take(4),
        )
        assertFalse("a client uplink is not a network to configure", pages.any { it.info.id == "wifi.wifinet2" })
        pages.forEach { assertTrue(it.info.id, it.read(router(rpcd)).fields.isNotEmpty()) }
    }

    @Test
    fun `a router without a WAN interface has no WAN page`() = runBlocking {
        val noWan = object : UbusApi by rpcd {
            override suspend fun call(target: String, method: String, args: JsonObject, quick: Boolean): JsonObject {
                val answer = rpcd.call(target, method, args, quick)
                return if (args["config"] == "network") mapOf("values" to mapOf("lan" to answer["values"].asObject("v")["lan"])) else answer
            }
        }
        assertFalse(UbusSettings.pages(router(noWan)).any { it.info.id == "wan" })
    }

    @Test
    fun `stored passwords never reach a page`() = runBlocking {
        UbusSettings.pages(router(rpcd)).forEach { page ->
            val shown = page.read(router(rpcd)).toString()
            assertFalse(page.info.id, shown.contains(Fixtures.WIFI_KEY))
            assertFalse(page.info.id, shown.contains(Fixtures.PPPOE_KEY))
        }
    }

    @Test
    fun `a Wi-Fi change commits without a rollback and changes only what was asked`() {
        network.applyChanges(rpcd, WifiNetworkPage.SSID to "NewName", WifiNetworkPage.HIDDEN to "true")
        assertEquals(listOf("uci set", "uci apply"), rpcd.writes)
        assertEquals(mapOf("ssid" to "NewName", "hidden" to "1"), rpcd.lastArgs.getValue("uci set")["values"])
        assertEquals(false, rpcd.lastArgs.getValue("uci apply")["rollback"])
        assertEquals(Fixtures.WIFI_KEY, rpcd.section("wireless", "default_radio0")["key"])
    }

    @Test
    fun `a Wi-Fi password is checked before it is sent`() {
        rejected(WifiNetworkPage.KEY) { network.applyChanges(rpcd, WifiNetworkPage.KEY to "short") }
        rejected(WifiNetworkPage.KEY) { network.applyChanges(rpcd, WifiNetworkPage.KEY to "x".repeat(64)) }
        network.applyChanges(rpcd, WifiNetworkPage.KEY to "a".repeat(64))
        assertEquals("a".repeat(64), rpcd.section("wireless", "default_radio0")["key"])
    }

    @Test
    fun `securing an open network needs a password`() {
        val open = WifiNetworkPage("default_radio1", "OpenNet")
        rejected(WifiNetworkPage.KEY) { open.applyChanges(rpcd, WifiNetworkPage.ENCRYPTION to "psk2") }
        assertTrue(rpcd.writes.isEmpty())
        open.applyChanges(rpcd, WifiNetworkPage.ENCRYPTION to "psk2", WifiNetworkPage.KEY to "a-good-passphrase")
        assertEquals("psk2", rpcd.section("wireless", "default_radio1")["encryption"])
    }

    @Test
    fun `a network name has at most 32 bytes`() {
        rejected(WifiNetworkPage.SSID) { network.applyChanges(rpcd, WifiNetworkPage.SSID to "") }
        rejected(WifiNetworkPage.SSID) { network.applyChanges(rpcd, WifiNetworkPage.SSID to "ü".repeat(17)) }
    }

    @Test
    fun `a security mode the app has no name for is kept as an option`() {
        (rpcd.section("wireless", "default_radio0") as MutableMap)["encryption"] = "wpa3-mixed"
        val field = network.readPage(rpcd).field(WifiNetworkPage.ENCRYPTION) as SettingField.Choice
        assertEquals("wpa3-mixed", field.value)
        assertTrue(field.options.any { it.value == "wpa3-mixed" })
    }

    @Test
    fun `a radio offers what its driver offers`() {
        val page = radio.readPage(rpcd)
        assertEquals(
            listOf("auto", "1", "6"),
            (page.field(WifiRadioPage.CHANNEL) as SettingField.Choice).options.map { it.value },
        )
        assertEquals(listOf("HT20", "HT40"), (page.field(WifiRadioPage.WIDTH) as SettingField.Choice).options.map { it.value })
        assertEquals(listOf("auto", "10", "20"), (page.field(WifiRadioPage.POWER) as SettingField.Choice).options.map { it.value })
        assertEquals("2.4 GHz", (page.field(WifiRadioPage.BAND) as SettingField.Info).value)
    }

    @Test
    fun `a radio without a network device still has a page`() {
        val page = WifiRadioPage("radio1").readPage(rpcd)
        assertEquals("auto", (page.field(WifiRadioPage.CHANNEL) as SettingField.Choice).value)
        assertTrue(page.fields.none { it.key == WifiRadioPage.WIDTH })
    }

    @Test
    fun `setting the power back to automatic removes the option`() {
        rpcd.section("wireless", "radio0").let { (it as MutableMap)["txpower"] = "10" }
        radio.applyChanges(rpcd, WifiRadioPage.POWER to WifiRadioPage.AUTO, WifiRadioPage.COUNTRY to "tm")
        assertFalse(rpcd.section("wireless", "radio0").containsKey("txpower"))
        assertEquals("TM", rpcd.section("wireless", "radio0")["country"])
        rejected(WifiRadioPage.COUNTRY) { radio.applyChanges(rpcd, WifiRadioPage.COUNTRY to "germany") }
    }

    @Test
    fun `the LAN page leaves the router's address alone and applies with a rollback`() {
        assertTrue(LanPage.readPage(rpcd).field("address") is SettingField.Info)
        LanPage.applyChanges(rpcd, LanPage.START to "50", LanPage.LEASE to "1d", LanPage.DHCP_ON to "false")
        assertEquals("50", rpcd.section("dhcp", "lan")["start"])
        assertEquals("1", rpcd.section("dhcp", "lan")["ignore"])
        assertEquals(listOf("uci set", "uci apply", "uci confirm"), rpcd.writes)
        assertEquals(true, rpcd.lastArgs.getValue("uci apply")["rollback"])

        LanPage.applyChanges(rpcd, LanPage.DHCP_ON to "true")
        assertFalse(rpcd.section("dhcp", "lan").containsKey("ignore"))
        rejected(LanPage.LEASE) { LanPage.applyChanges(rpcd, LanPage.LEASE to "soon") }
    }

    @Test
    fun `a static WAN needs an address and a mask`() {
        rejected(WanPage.ADDRESS) { WanPage.applyChanges(rpcd, WanPage.PROTOCOL to "static") }
        rejected(WanPage.MASK) { WanPage.applyChanges(rpcd, WanPage.PROTOCOL to "static", WanPage.ADDRESS to "203.0.113.5") }
        rejected(WanPage.MASK) {
            WanPage.applyChanges(rpcd, WanPage.PROTOCOL to "static", WanPage.ADDRESS to "203.0.113.5", WanPage.MASK to "255.0.255.0")
        }
        WanPage.applyChanges(rpcd, WanPage.PROTOCOL to "static", WanPage.ADDRESS to "203.0.113.5", WanPage.MASK to "255.255.255.0")
        assertEquals("static", rpcd.section("network", "wan")["proto"])
    }

    @Test
    fun `custom DNS servers replace the provider's until they are cleared`() {
        WanPage.applyChanges(rpcd, WanPage.DNS to "198.51.100.1, 198.51.100.2")
        assertEquals(listOf("198.51.100.1", "198.51.100.2"), rpcd.section("network", "wan")["dns"])
        assertEquals("0", rpcd.section("network", "wan")["peerdns"])
        rejected(WanPage.DNS) { WanPage.applyChanges(rpcd, WanPage.DNS to "dns.example") }
        WanPage.applyChanges(rpcd, WanPage.DNS to "")
        assertFalse(rpcd.section("network", "wan").containsKey("dns"))
        assertFalse(rpcd.section("network", "wan").containsKey("peerdns"))
    }

    @Test
    fun `an empty PPPoE password keeps the stored one`() {
        WanPage.applyChanges(rpcd, WanPage.USER to "new-user", WanPage.PASSWORD to "")
        assertEquals("new-user", rpcd.section("network", "wan")["username"])
        assertEquals(Fixtures.PPPOE_KEY, rpcd.section("network", "wan")["password"])
    }

    @Test
    fun `a reservation that stays keeps the options the app doesn't show`() {
        val printer = listOf("printer", "02:00:00:00:00:21", "192.168.1.50")
        assertEquals(listOf(printer), ReservationsPage.readPage(rpcd).rows(ReservationsPage.HOSTS))
        val table = TableCodec.encode(listOf(printer, listOf("", "02-00-00-00-00-22", "192.168.1.51")))
        ReservationsPage.applyChanges(rpcd, ReservationsPage.HOSTS to table)

        val hosts = rpcd.sections("dhcp", "host")
        assertEquals("infinite", hosts[0]["leasetime"])
        assertEquals("02:00:00:00:00:22", hosts[1]["mac"])
        assertFalse(hosts[1].containsKey("name"))
        assertEquals(listOf("uci add", "uci apply", "uci confirm"), rpcd.writes)
    }

    @Test
    fun `a removed row deletes its section and nothing else`() {
        ReservationsPage.applyChanges(rpcd, ReservationsPage.HOSTS to "")
        assertTrue(rpcd.sections("dhcp", "host").isEmpty())
        assertEquals(1, rpcd.sections("dhcp", "dhcp").count { it[".name"] == "lan" })
    }

    @Test
    fun `reservations are checked before anything changes`() {
        val bad = TableCodec.encode(listOf(listOf("x", "not-a-mac", "192.168.1.51")))
        rejected(ReservationsPage.HOSTS) { ReservationsPage.applyChanges(rpcd, ReservationsPage.HOSTS to bad) }
        val group = TableCodec.encode(listOf(listOf("x", "03:00:00:00:00:01", "192.168.1.51")))
        rejected(ReservationsPage.HOSTS) { ReservationsPage.applyChanges(rpcd, ReservationsPage.HOSTS to group) }
        assertTrue(rpcd.writes.isEmpty())
    }

    @Test
    fun `port forwards show DNAT rules only and new ones are complete`() {
        val web = listOf("web", "tcp", "8080", "192.168.1.20", "80")
        assertEquals(listOf(web), PortForwardsPage.readPage(rpcd).rows(PortForwardsPage.FORWARDS))

        val game = listOf("game", "tcp udp", "27000-27010", "192.168.1.30", "")
        PortForwardsPage.applyChanges(rpcd, PortForwardsPage.FORWARDS to TableCodec.encode(listOf(web, game)))

        val added = rpcd.sections("firewall", "redirect").last()
        assertEquals("DNAT", added["target"])
        assertEquals("wan", added["src"])
        assertEquals("lan", added["dest"])
        assertEquals("27000-27010", added["dest_port"])
        assertEquals(3, rpcd.sections("firewall", "redirect").size)
    }

    @Test
    fun `removing every forward keeps other redirects`() {
        PortForwardsPage.applyChanges(rpcd, PortForwardsPage.FORWARDS to "")
        assertEquals(listOf("hairpin"), rpcd.sections("firewall", "redirect").map { it["name"] })
    }

    @Test
    fun `port forwards are checked before anything changes`() {
        fun table(vararg row: String) = TableCodec.encode(listOf(row.toList()))
        val page = PortForwardsPage.FORWARDS
        rejected(page) { PortForwardsPage.applyChanges(rpcd, page to table("", "tcp", "80", "192.168.1.2", "")) }
        rejected(page) { PortForwardsPage.applyChanges(rpcd, page to table("x", "icmp", "80", "192.168.1.2", "")) }
        rejected(page) { PortForwardsPage.applyChanges(rpcd, page to table("x", "tcp", "70000", "192.168.1.2", "")) }
        rejected(page) { PortForwardsPage.applyChanges(rpcd, page to table("x", "tcp", "90-80", "192.168.1.2", "")) }
        rejected(page) { PortForwardsPage.applyChanges(rpcd, page to table("x", "tcp", "80", "router", "")) }
        assertTrue(rpcd.writes.isEmpty())
    }

    @Test
    fun `the firewall page lists zones and rules`() {
        val page = FirewallPage.readPage(rpcd)
        assertEquals(listOf("wan", "REJECT", "ACCEPT", "REJECT", "On"), page.rows("zones")[1])
        assertEquals("Router", page.rows("rules")[0][2])
        assertTrue((page.field("rules") as SettingField.Table).readOnly)
    }

    @Test
    fun `a time zone is stored with its POSIX rule`() {
        SystemPage.applyChanges(rpcd, SystemPage.ZONE to "Asia/Ashgabat", SystemPage.HOSTNAME to "home-router", SystemPage.NTP to "false")
        val system = rpcd.section("system", "cfg01")
        assertEquals("Asia/Ashgabat", system["zonename"])
        assertEquals("<+05>-5", system["timezone"])
        assertEquals("home-router", system["hostname"])
        assertEquals("0", rpcd.section("system", "ntp")["enabled"])
        rejected(SystemPage.ZONE) { SystemPage.applyChanges(rpcd, SystemPage.ZONE to "Mars/Base") }
        rejected(SystemPage.HOSTNAME) { SystemPage.applyChanges(rpcd, SystemPage.HOSTNAME to "-bad") }
    }

    @Test
    fun `the log comes from the first command the router allows`() = runBlocking {
        val tried = mutableListOf<String>()
        val api = object : UbusApi {
            override suspend fun call(target: String, method: String, args: JsonObject, quick: Boolean): JsonObject {
                tried += args.str("command")
                if (tried.size == 1) throw CommandFailedException("file exec", null, "denied")
                if (tried.size == 2) return mapOf("code" to 6L, "stderr" to "permission denied")
                return mapOf("code" to 0L, "stdout" to "one line")
            }
        }
        val page = SystemLogPage.read(router(api))
        assertEquals(listOf("/usr/libexec/syslog-wrapper", "/sbin/logread", "/sbin/logread"), tried)
        assertEquals(1, (page.fields.single() as SettingField.Table).rows.size)
    }

    @Test
    fun `the log is newest first with its time apart`() {
        val rows = SystemLogPage.readPage(rpcd).rows("log")
        assertEquals(listOf("Thu Oct 29 10:00:05 2026", "kern.info kernel: link up"), rows[0])
        assertEquals(listOf(listOf("", "something odd")), SystemLogPage.parse("something odd"))
    }

    @Test
    fun `a page that only shows things refuses to save`() {
        rejected("page") { InterfacesPage.applyChanges(rpcd, "x" to "y") }
    }

    @Test
    fun `the interfaces page shows each interface's state`() {
        val rows = InterfacesPage.readPage(rpcd).rows("interfaces")
        assertEquals(listOf("wan", "Up", "pppoe", "203.0.113.9/32", "pppoe-wan"), rows[1])
    }

    @Test
    fun `a failed change is taken back`() {
        rpcd.failing["uci add"] = UbusCallFailedException("uci add", 2)
        val row = TableCodec.encode(listOf(listOf("x", "02:00:00:00:00:30", "192.168.1.60")))
        assertThrows(UbusCallFailedException::class.java) { ReservationsPage.applyChanges(rpcd, ReservationsPage.HOSTS to row) }
        assertEquals("uci revert", rpcd.writes.last())
        assertFalse("nothing is applied", rpcd.writes.contains("uci apply"))
    }

    @Test
    fun `nothing to apply is not a failure and needs no confirmation`() {
        rpcd.failing["uci apply"] = UbusCallFailedException("uci apply", UbusStatus.NO_DATA)
        LanPage.applyChanges(rpcd, LanPage.START to "50")
        assertFalse(rpcd.writes.contains("uci confirm"))
    }

    @Test
    fun `a router that stops answering after a change is reported as rolling back`() {
        rpcd.failing["uci confirm"] = RouterUnreachableException("gone")
        val e = assertThrows(RollbackPendingException::class.java) { LanPage.applyChanges(rpcd, LanPage.START to "50") }
        assertTrue(e.message!!.contains("undo"))
        assertEquals(5, rpcd.writes.count { it == "uci confirm" })
    }

    @Test
    fun `the login password goes to the logged in user`() {
        LoginPasswordPage.applyChanges(rpcd, LoginPasswordPage.PASSWORD to "a-new-password")
        assertEquals(mapOf("username" to "root", "password" to "a-new-password"), rpcd.lastArgs.getValue("luci setPassword"))
        assertEquals(LoginPasswordPage.PASSWORD, LoginPasswordPage.readPage(rpcd).loginPasswordKey)
    }
}
