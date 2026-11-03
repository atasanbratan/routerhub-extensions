package dev.routerhub.extension.ubus

import dev.routerhub.api.ConnectedClient
import dev.routerhub.api.ConnectionConfig
import dev.routerhub.api.RouterException
import dev.routerhub.api.RouterFirmware
import dev.routerhub.api.SettingField
import dev.routerhub.api.TableCodec
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Tests against a real OpenWrt router, skipped unless the environment provides one:
 *
 *   ROUTERHUB_LIVE_HOST=192.168.1.1 ROUTERHUB_LIVE_USER=root ROUTERHUB_LIVE_PASS=... \
 *     ./gradlew :extensions:openwrt-ubus:cleanTestDebugUnitTest :extensions:openwrt-ubus:testDebugUnitTest \
 *     --tests '*UbusLiveTest*' -i
 *
 * The read test changes nothing. The others also need ROUTERHUB_LIVE_WRITE=1: they change the router
 * and put everything back, so run them only on a router you may experiment with.
 *
 * Names, addresses and MACs of the real network are never printed, nor is any password.
 */
class UbusLiveTest {

    private val CALLS = listOf(
        "system board", "system info", "system reboot", "network.interface dump", "network.wireless status",
        "luci-rpc getWirelessDevices", "luci-rpc getDHCPLeases", "luci-rpc getHostHints", "iwinfo assoclist", "iwinfo info",
        "iwinfo freqlist", "iwinfo txpowerlist", "iwinfo countrylist", "luci getTimezones", "luci setPassword",
        "uci get", "uci set", "uci apply", "uci confirm",
    )

    private val host: String? = System.getenv("ROUTERHUB_LIVE_HOST")
    private val port = System.getenv("ROUTERHUB_LIVE_PORT")?.toInt() ?: 80
    private val user = System.getenv("ROUTERHUB_LIVE_USER") ?: "root"
    private val pass = System.getenv("ROUTERHUB_LIVE_PASS").orEmpty()
    private val writes = System.getenv("ROUTERHUB_LIVE_WRITE") == "1"

    private fun live(write: Boolean = false, block: suspend (RouterFirmware) -> Unit) = runBlocking {
        assumeTrue("ROUTERHUB_LIVE_HOST not set", !host.isNullOrBlank())
        if (write) assumeTrue("ROUTERHUB_LIVE_WRITE not set", writes)
        val firmware = UbusFactory().create(ConnectionConfig.Web(host = host!!, port = port, username = user, password = pass, timeoutMillis = 15_000))
        try {
            firmware.login()
            block(firmware)
        } finally {
            firmware.close()
        }
    }

    @Test
    fun `reads the status, the clients and every settings page`() = live { firmware ->
        for (call in CALLS) {
            val (target, method) = call.split(' ')
            val allowed = (firmware as UbusFirmware).mayCall(target, method)
            println("CALL    ${if (allowed) "allowed" else "DENIED "} $call")
        }
        val status = firmware.getStatus()
        println("STATUS  firmware=${status.firmwareVersion} uptime=${status.uptimeSeconds}s wanConnected=${status.wanConnected} " +
            "radios=${status.wifiRadios.map { "${it.id}:${it.band}:${it.enabled}" }} extras=${status.extras.keys}")
        assertTrue("the model is known", !status.model.isNullOrBlank())

        val clients = firmware.getConnectedClients()
        println("CLIENTS ${clients.size} connected: ${clients.map { "${it.medium}/${it.interfaceName}" }}")

        val pages = firmware.listSettingsPages()
        assertTrue("the router offers settings pages", pages.isNotEmpty())
        val failed = pages.filter { info ->
            try {
                val page = firmware.readSettings(info.id)
                assertTrue("${info.id} has fields", page.fields.isNotEmpty())
                println("PAGE    ${info.id.padEnd(24)} ${page.fields.map { it.javaClass.simpleName.first() }.joinToString("")}")
                false
            } catch (e: RouterException) {
                println("PAGE    ${info.id.padEnd(24)} FAILED ${e.message}")
                true
            }
        }
        assertTrue("every page reads", failed.isEmpty())
    }

    private suspend fun rows(firmware: RouterFirmware, page: String, key: String): List<List<String>> =
        (firmware.readSettings(page).fields.first { it.key == key } as SettingField.Table).rows

    @Test
    fun `a call after the router closed an idle connection still works`() = live { firmware ->
        firmware.getStatus()
        delay(25_000)
        firmware.getStatus()
    }

    @Test
    fun `a port forward is added with a confirmed rollback and removed again`() = live(write = true) { firmware ->
        val before = rows(firmware, "port-forwards", PortForwardsPage.FORWARDS)
        val forward = listOf("routerhub-test", "tcp", "65001", "192.0.2.10", "65001")
        try {
            firmware.writeSettings("port-forwards", mapOf(PortForwardsPage.FORWARDS to TableCodec.encode(before + listOf(forward))))
            assertEquals(before + listOf(forward), rows(firmware, "port-forwards", PortForwardsPage.FORWARDS))
        } finally {
            firmware.writeSettings("port-forwards", mapOf(PortForwardsPage.FORWARDS to TableCodec.encode(before)))
        }
        assertEquals(before, rows(firmware, "port-forwards", PortForwardsPage.FORWARDS))
        println("WRITE   port forward added, confirmed and removed")
    }

    @Test
    fun `a reservation is added and removed again`() = live(write = true) { firmware ->
        val before = rows(firmware, "reservations", ReservationsPage.HOSTS)
        val host = listOf("routerhub-test", "02:00:00:00:fe:01", "192.0.2.11")
        try {
            firmware.writeSettings("reservations", mapOf(ReservationsPage.HOSTS to TableCodec.encode(before + listOf(host))))
            assertEquals(before + listOf(host), rows(firmware, "reservations", ReservationsPage.HOSTS))
        } finally {
            firmware.writeSettings("reservations", mapOf(ReservationsPage.HOSTS to TableCodec.encode(before)))
        }
        assertEquals(before, rows(firmware, "reservations", ReservationsPage.HOSTS))
        println("WRITE   reservation added and removed")
    }

    @Test
    fun `the router name is changed and put back`() = live(write = true) { firmware ->
        suspend fun name() = (firmware.readSettings("system").fields.first { it.key == SystemPage.HOSTNAME } as SettingField.Text).value
        val before = name()
        try {
            firmware.writeSettings("system", mapOf(SystemPage.HOSTNAME to "routerhub-test"))
            assertEquals("routerhub-test", name())
        } finally {
            firmware.writeSettings("system", mapOf(SystemPage.HOSTNAME to before))
        }
        assertEquals(before, name())
        println("WRITE   router name changed and put back")
    }

    @Test
    fun `a Wi-Fi option is changed without a rollback and put back`() = live(write = true) { firmware ->
        val page = firmware.listSettingsPages().map { it.id }.firstOrNull { it.startsWith("wifi.") }
        assumeTrue("no Wi-Fi network on this router", page != null)
        suspend fun isolated() = (firmware.readSettings(page!!).fields.first { it.key == WifiNetworkPage.ISOLATE } as SettingField.Toggle).value
        val before = isolated()
        try {
            firmware.writeSettings(page!!, mapOf(WifiNetworkPage.ISOLATE to (!before).toString()))
            assertEquals(!before, isolated())
            // A new session sees only what was committed.
            live { other ->
                val field = other.readSettings(page!!).fields.first { it.key == WifiNetworkPage.ISOLATE } as SettingField.Toggle
                assertEquals(!before, field.value)
            }
        } finally {
            firmware.writeSettings(page!!, mapOf(WifiNetworkPage.ISOLATE to before.toString()))
            // Reloading Wi-Fi also stalls the router's web server for a while, which would fail the next test.
            delay(30_000)
        }
        assertEquals(before, isolated())
        println("WRITE   Wi-Fi option changed and put back")
    }

    @Test
    fun `a schedule across midnight is added and removed again`() = live(write = true) { firmware ->
        val before = rows(firmware, "schedules", SchedulesPage.SCHEDULES)
        val schedule = listOf("routerhub-test", "02:00:00:00:fe:02", "22:00", "07:00", "Mon Tue Wed Thu Fri")
        try {
            firmware.writeSettings("schedules", mapOf(SchedulesPage.SCHEDULES to TableCodec.encode(before + listOf(schedule))))
            assertEquals(before + listOf(schedule), rows(firmware, "schedules", SchedulesPage.SCHEDULES))
        } finally {
            firmware.writeSettings("schedules", mapOf(SchedulesPage.SCHEDULES to TableCodec.encode(before)))
        }
        assertEquals(before, rows(firmware, "schedules", SchedulesPage.SCHEDULES))
        println("WRITE   schedule added and removed")
    }

    @Test
    fun `a device is paused and resumed`() = live(write = true) { firmware ->
        val device = ConnectedClient("02:00:00:00:fe:03", hostname = "routerhub-test")
        try {
            firmware.runClientAction(device, ClientActions.PAUSE)
            assertEquals(ClientActions.RESUME, firmware.clientActions(device).first().id)
        } finally {
            firmware.runClientAction(device, ClientActions.RESUME)
        }
        assertEquals(ClientActions.PAUSE, firmware.clientActions(device).first().id)
        println("WRITE   device paused and resumed")
    }
}
