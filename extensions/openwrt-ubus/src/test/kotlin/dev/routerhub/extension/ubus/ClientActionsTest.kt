package dev.routerhub.extension.ubus

import dev.routerhub.api.ConnectedClient
import dev.routerhub.api.InvalidConfigurationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientActionsTest {
    private val rpcd = FakeRpcd()
    private val router = router(rpcd)
    private val client = ConnectedClient("02:00:00:00:aa:01", "192.0.2.50", "my-laptop")

    private suspend fun ids(of: ConnectedClient = client) = ClientActions.offered(router, of).map { it.id }

    private suspend fun run(id: String) = ClientActions.run(router, client, id, "root")

    @Test
    fun `blocking adds a LAN to WAN rule for that MAC, and allowing takes it away`() = runBlocking {
        val before = rpcd.sections("firewall", "rule").size
        assertTrue(ClientActions.BLOCK in ids())
        run(ClientActions.BLOCK)

        val rule = rpcd.sections("firewall", "rule").last()
        assertEquals("RouterHub block: my-laptop", rule["name"])
        assertEquals(listOf("lan", "wan", client.macAddress, "all", "REJECT"), listOf("src", "dest", "src_mac", "proto", "target").map { rule[it] })
        assertEquals(setOf(client.macAddress), BlockedDevicesPage.macs(router))
        assertEquals(listOf(ClientActions.ALLOW, ClientActions.RESERVE), ids())

        run(ClientActions.ALLOW)
        assertEquals(before, rpcd.sections("firewall", "rule").size)
        assertTrue(ClientActions.BLOCK in ids())
    }

    @Test
    fun `blocking asks first`() = runBlocking {
        assertTrue(ClientActions.offered(router, client).first { it.id == ClientActions.BLOCK }.confirmation != null)
    }

    @Test
    fun `rules the user made are not touched`() = runBlocking {
        val before = rpcd.sections("firewall", "rule").map { it["name"] }
        run(ClientActions.BLOCK)
        run(ClientActions.ALLOW)
        assertEquals(before, rpcd.sections("firewall", "rule").map { it["name"] })
        assertTrue(BlockedDevicesPage.rows(router).isEmpty())
    }

    @Test
    fun `reserving keeps the address and a usable name, and can be undone`() = runBlocking {
        val before = ReservationsPage.rows(router)
        run(ClientActions.RESERVE)
        assertEquals(before + listOf(listOf("my-laptop", client.macAddress, "192.0.2.50")), ReservationsPage.rows(router))
        assertEquals(listOf(ClientActions.BLOCK, ClientActions.RELEASE), ids())
        run(ClientActions.RELEASE)
        assertEquals(before, ReservationsPage.rows(router))
    }

    @Test
    fun `a host name dnsmasq would refuse is left out`() = runBlocking {
        ClientActions.run(router, client.copy(hostname = "Pat's phone"), ClientActions.RESERVE, "root")
        assertEquals("", ReservationsPage.rows(router).last()[0])
    }

    @Test
    fun `an address reserved for another device is refused`() {
        runBlocking { run(ClientActions.RESERVE) }
        val other = ConnectedClient("02:00:00:00:aa:02", "192.0.2.50")
        assertThrows(InvalidConfigurationException::class.java) {
            runBlocking { ClientActions.run(router, other, ClientActions.RESERVE, "root") }
        }
    }

    @Test
    fun `without an address only blocking is offered`() = runBlocking {
        assertEquals(listOf(ClientActions.BLOCK), ids(client.copy(ipAddress = null)))
    }
}
