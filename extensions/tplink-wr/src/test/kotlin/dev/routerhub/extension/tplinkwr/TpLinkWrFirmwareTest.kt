package dev.routerhub.extension.tplinkwr

import dev.routerhub.api.Capability
import dev.routerhub.api.RouterSessionExpiredException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Answers pages from a map and records what was asked for. */
internal class FakeWrApi(private val pages: Map<String, String>) : WrApi {
    val requests = mutableListOf<String>()
    var loggedOut = false
    val failing = mutableSetOf<String>()

    override suspend fun login() {
        requests += "login"
    }

    override suspend fun page(name: String, query: String): String {
        requests += if (query.isEmpty()) name else "$name?$query"
        if (name in failing) throw RouterSessionExpiredException()
        return pages[name] ?: error("No page $name")
    }

    override suspend fun logout() {
        loggedOut = true
    }
}

class TpLinkWrFirmwareTest {
    private val api = FakeWrApi(
        mapOf(
            "StatusRpm.htm" to Fixtures.STATUS,
            "AssignedIpAddrListRpm.htm" to Fixtures.DHCP,
            "WlanStationRpm.htm" to Fixtures.STATIONS,
            "SysRebootRpm.htm" to "",
        ),
    )
    private val firmware = TpLinkWrFirmware(api, setOf(Capability.STATUS))

    @Test
    fun `reboot sends the page's own Reboot button`() = runBlocking {
        firmware.reboot()
        assertEquals(listOf("SysRebootRpm.htm?Reboot=Reboot"), api.requests)
    }

    @Test
    fun `clients are still listed when the Wi-Fi station list fails`() = runBlocking {
        api.failing += "WlanStationRpm.htm"
        assertEquals(3, firmware.getConnectedClients().size)
    }

    @Test
    fun `closing logs out, so the browser can log in again`() = runBlocking {
        firmware.close()
        assertTrue(api.loggedOut)
    }

    @Test
    fun `the extension offers the WR940N`() {
        val factory = TpLinkWrExtension().factories.single()
        assertEquals("tplink.wr.wr940n", factory.id)
        assertEquals(setOf(Capability.STATUS, Capability.CONNECTED_CLIENTS, Capability.REBOOT), factory.capabilities)
    }
}
