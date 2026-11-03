package dev.routerhub.extension.tplinkwr

import dev.routerhub.api.Capability
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TpLinkWrFirmwareTest {
    private val router = FakeWrRouter()
    private val firmware = TpLinkWrFirmware(router, setOf(Capability.STATUS))

    @Test
    fun `reboot sends the page's own Reboot button`() = runBlocking {
        firmware.reboot()
        assertEquals(listOf("SysRebootRpm.htm?Reboot=Reboot"), router.requests)
    }

    @Test
    fun `clients are still listed when the Wi-Fi station list fails`() = runBlocking {
        router.failing += "WlanStationRpm.htm"
        assertEquals(3, firmware.getConnectedClients().size)
    }

    @Test
    fun `closing logs out, so the browser can log in again`() = runBlocking {
        firmware.close()
        assertTrue(router.loggedOut)
    }

    @Test
    fun `every settings page can be read`() = runBlocking {
        val pages = firmware.listSettingsPages()
        assertEquals(listOf("wifi", "dhcp-server", "dhcp-reservations", "port-forwarding", "dmz", "upnp"), pages.map { it.id })
        pages.forEach { assertEquals(it, firmware.readSettings(it.id).info) }
    }

    @Test
    fun `the extension offers the WR940N`() {
        val factory = TpLinkWrExtension().factories.single()
        assertEquals("tplink.wr.wr940n", factory.id)
        assertEquals(
            setOf(Capability.STATUS, Capability.CONNECTED_CLIENTS, Capability.CLIENT_ACTIONS, Capability.REBOOT, Capability.WIFI_TOGGLE, Capability.SETTINGS),
            factory.capabilities,
        )
    }
}
