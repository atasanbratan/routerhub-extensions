package dev.routerhub.extension.tplinkwr

import dev.routerhub.api.ConnectedClient
import dev.routerhub.api.InvalidConfigurationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WrClientActionsTest {
    private val router = FakeWrRouter()
    private val phone = ConnectedClient("02:00:00:00:00:12", "192.168.0.102", "Phone")

    private fun ids(client: ConnectedClient = phone) = runBlocking { WrClientActions.offered(router, client).map { it.id } }

    @Test
    fun `blocking lists the device and switches the filter on, allowing undoes both`() = runBlocking {
        assertEquals(listOf(WrClientActions.BLOCK), ids())
        WrClientActions.run(router, phone, WrClientActions.BLOCK)
        assertEquals(listOf("02-00-00-00-00-12", "1", "1", "", "Phone"), router.filterEntries.single())
        assertTrue(router.filterOn)
        assertEquals(listOf(WrClientActions.ALLOW), ids())

        WrClientActions.run(router, phone, WrClientActions.ALLOW)
        assertTrue(router.filterEntries.isEmpty())
        assertFalse(router.filterOn)
    }

    @Test
    fun `allowing one of several keeps the filter on for the others`() = runBlocking {
        val tablet = ConnectedClient("02:00:00:00:00:30", hostname = "Tablet")
        WrClientActions.run(router, phone, WrClientActions.BLOCK)
        WrClientActions.run(router, tablet, WrClientActions.BLOCK)
        WrClientActions.run(router, phone, WrClientActions.ALLOW)
        assertEquals(listOf("02-00-00-00-00-30"), router.filterEntries.map { it[0] })
        assertTrue(router.filterOn)
    }

    @Test
    fun `blocking asks first and warns about the phone in use`() = runBlocking {
        val confirmation = WrClientActions.offered(router, phone).single().confirmation!!
        assertTrue(confirmation.contains("another device"))
    }

    @Test
    fun `an allow-only filter offers nothing and refuses to change`() {
        router.filterRefusesListed = false
        assertTrue(ids().isEmpty())
        assertThrows(InvalidConfigurationException::class.java) { runBlocking { WrClientActions.run(router, phone, WrClientActions.BLOCK) } }
        assertTrue(router.filterEntries.isEmpty())
    }

    @Test
    fun `blocked devices are marked, and the ones away are still listed`() = runBlocking {
        val away = ConnectedClient("02:00:00:00:00:40", hostname = "Old laptop")
        WrClientActions.run(router, away, WrClientActions.BLOCK)
        WrClientActions.run(router, phone, WrClientActions.BLOCK)
        val clients = WrClientActions.withBlocked(router, listOf(phone))
        assertEquals(true, clients.single { it.macAddress == phone.macAddress }.internetBlocked)
        val gone = clients.single { it.macAddress == away.macAddress }
        assertEquals(false, gone.online)
        assertEquals("Old laptop", gone.hostname)
    }
}
