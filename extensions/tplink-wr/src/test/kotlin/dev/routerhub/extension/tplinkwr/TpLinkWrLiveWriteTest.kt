package dev.routerhub.extension.tplinkwr

import dev.routerhub.api.ConnectedClient
import dev.routerhub.api.ConnectionConfig
import dev.routerhub.api.RouterFirmware
import dev.routerhub.api.SettingField
import dev.routerhub.api.TableCodec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Changes a real router and puts everything back. Needs ROUTERHUB_LIVE_HOST and ROUTERHUB_LIVE_WRITE=1;
 * the reboot also needs ROUTERHUB_LIVE_REBOOT=1, since everyone on the router loses it for a minute.
 * Test entries use a locally administered MAC (02:00:00:00:fe:xx) that no real device has, and an
 * address at the top of the router's range. Prints no names or addresses of the user's.
 */
class TpLinkWrLiveWriteTest {
    private val host: String? = System.getenv("ROUTERHUB_LIVE_HOST")
    private val user = System.getenv("ROUTERHUB_LIVE_USER") ?: "admin"
    private val pass = System.getenv("ROUTERHUB_LIVE_PASS").orEmpty()
    private val writes = System.getenv("ROUTERHUB_LIVE_WRITE") == "1"

    private fun live(block: suspend (RouterFirmware, String) -> Unit) = runBlocking {
        assumeTrue("ROUTERHUB_LIVE_HOST not set", !host.isNullOrBlank())
        assumeTrue("ROUTERHUB_LIVE_WRITE not set", writes)
        val firmware = TpLinkWrFactory(WrModel.WR940N).create(ConnectionConfig.Web(host!!, user, pass))
        try {
            firmware.login()
            val lan = firmware.getStatus().extras.getValue("LAN address")
            block(firmware, lan.substringBeforeLast('.') + ".250")
        } finally {
            firmware.close()
        }
    }

    private suspend fun rows(firmware: RouterFirmware, page: String, key: String) =
        (firmware.readSettings(page).fields.first { it.key == key } as SettingField.Table).rows

    private suspend fun value(firmware: RouterFirmware, page: String, key: String) =
        when (val field = firmware.readSettings(page).fields.first { it.key == key }) {
            is SettingField.Toggle -> field.value.toString()
            is SettingField.Text -> field.value
            else -> error("unexpected field")
        }

    @Test
    fun `a reservation is added and removed`() = live { firmware, spare ->
        val before = rows(firmware, "dhcp-reservations", ReservationsPage.RESERVATIONS)
        val test = listOf("02:00:00:00:fe:01", spare)
        try {
            firmware.writeSettings("dhcp-reservations", mapOf(ReservationsPage.RESERVATIONS to TableCodec.encode(before + listOf(test))))
            assertEquals(before + listOf(test), rows(firmware, "dhcp-reservations", ReservationsPage.RESERVATIONS))
        } finally {
            firmware.writeSettings("dhcp-reservations", mapOf(ReservationsPage.RESERVATIONS to TableCodec.encode(before)))
        }
        assertEquals(before, rows(firmware, "dhcp-reservations", ReservationsPage.RESERVATIONS))
        println("WRITE   reservation added and removed")
    }

    @Test
    fun `a port forward is added and removed`() = live { firmware, spare ->
        val before = rows(firmware, "port-forwarding", PortForwardsPage.FORWARDS)
        val test = listOf("18080", spare, "80", "2")
        try {
            firmware.writeSettings("port-forwarding", mapOf(PortForwardsPage.FORWARDS to TableCodec.encode(before + listOf(test))))
            assertEquals(before + listOf(test), rows(firmware, "port-forwarding", PortForwardsPage.FORWARDS))
        } finally {
            firmware.writeSettings("port-forwarding", mapOf(PortForwardsPage.FORWARDS to TableCodec.encode(before)))
        }
        assertEquals(before, rows(firmware, "port-forwarding", PortForwardsPage.FORWARDS))
        println("WRITE   port forward added and removed")
    }

    @Test
    fun `UPnP is switched and put back`() = live { firmware, _ ->
        val before = value(firmware, "upnp", UpnpPage.ENABLED)
        val flipped = (!before.toBoolean()).toString()
        try {
            firmware.writeSettings("upnp", mapOf(UpnpPage.ENABLED to flipped))
            assertEquals(flipped, value(firmware, "upnp", UpnpPage.ENABLED))
        } finally {
            firmware.writeSettings("upnp", mapOf(UpnpPage.ENABLED to before))
        }
        assertEquals(before, value(firmware, "upnp", UpnpPage.ENABLED))
        println("WRITE   UPnP switched and put back")
    }

    @Test
    fun `the DMZ is pointed at an unused address and put back`() = live { firmware, spare ->
        val enabled = value(firmware, "dmz", DmzPage.ENABLED)
        val address = value(firmware, "dmz", DmzPage.HOST)
        try {
            firmware.writeSettings("dmz", mapOf(DmzPage.ENABLED to "true", DmzPage.HOST to spare))
            assertEquals(listOf("true", spare), listOf(value(firmware, "dmz", DmzPage.ENABLED), value(firmware, "dmz", DmzPage.HOST)))
        } finally {
            firmware.writeSettings("dmz", mapOf(DmzPage.ENABLED to enabled, DmzPage.HOST to address))
        }
        assertEquals(listOf(enabled, address), listOf(value(firmware, "dmz", DmzPage.ENABLED), value(firmware, "dmz", DmzPage.HOST)))
        println("WRITE   DMZ set and put back")
    }

    @Test
    fun `a device that doesn't exist is blocked and allowed again`() = live { firmware, _ ->
        val nobody = ConnectedClient("02:00:00:00:fe:02", hostname = "routerhub-test")
        val offered = firmware.clientActions(nobody).map { it.id }
        assumeTrue("the MAC filter lets only its list in", offered.isNotEmpty())
        assertEquals(listOf(WrClientActions.BLOCK), offered)
        try {
            firmware.runClientAction(nobody, WrClientActions.BLOCK)
            assertEquals(listOf(WrClientActions.ALLOW), firmware.clientActions(nobody).map { it.id })
            assertEquals(true, firmware.getConnectedClients().single { it.macAddress == nobody.macAddress }.internetBlocked)
        } finally {
            firmware.runClientAction(nobody, WrClientActions.ALLOW)
        }
        assertEquals(listOf(WrClientActions.BLOCK), firmware.clientActions(nobody).map { it.id })
        println("WRITE   device blocked and allowed again")
    }

    @Test
    fun `the router reboots`() {
        assumeTrue("ROUTERHUB_LIVE_REBOOT not set", System.getenv("ROUTERHUB_LIVE_REBOOT") == "1")
        live { firmware, _ ->
            firmware.reboot()
            println("WRITE   reboot sent")
        }
    }
}
