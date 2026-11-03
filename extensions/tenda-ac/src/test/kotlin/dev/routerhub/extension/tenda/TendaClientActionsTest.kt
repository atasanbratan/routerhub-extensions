package dev.routerhub.extension.tenda

import dev.routerhub.api.ConnectedClient
import dev.routerhub.api.InvalidSettingException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TendaClientActionsTest {
    private val context = TendaContext("admin", "x")
    private fun api(json: String = Fixtures.MAC_FILTER) = FakeTendaApi("getMacFilterCfg" to json)
    private val allowOnly = Fixtures.MAC_FILTER.replace("\"macFilterType\":\"black\"", "\"macFilterType\":\"white\"")

    private val phone = ConnectedClient("02:00:00:00:00:11", "192.168.0.101", "Phone")
    private val tablet = ConnectedClient("02:00:00:00:00:30", "192.168.0.102", "Tablet")

    @Test
    fun `blocking adds the device to the router's block list, in the page's own format`() = runBlocking {
        val api = api()
        assertEquals(listOf(TendaClientActions.BLOCK), TendaClientActions.offered(api, phone).map { it.id })
        TendaClientActions.run(api, phone, TendaClientActions.BLOCK, context)
        assertEquals(
            "setMacFilterCfg" to "macFilterType=black&deviceList=Tablet\r02:00:00:00:00:30\nPhone\r02:00:00:00:00:11",
            api.writes.single(),
        )
    }

    @Test
    fun `allowing takes the device off the list`() = runBlocking {
        val api = api()
        assertEquals(listOf(TendaClientActions.ALLOW), TendaClientActions.offered(api, tablet).map { it.id })
        TendaClientActions.run(api, tablet, TendaClientActions.ALLOW, context)
        assertEquals("setMacFilterCfg" to "macFilterType=black&deviceList=", api.writes.single())
    }

    @Test
    fun `blocking asks first`() = runBlocking {
        assertTrue(TendaClientActions.offered(api(), phone).single().confirmation != null)
    }

    @Test
    fun `the device that is asking can't be blocked`() {
        val laptop = ConnectedClient("02:00:00:00:00:10", hostname = "LAPTOP")
        assertThrows(InvalidSettingException::class.java) {
            runBlocking { TendaClientActions.run(api(), laptop, TendaClientActions.BLOCK, context) }
        }
    }

    @Test
    fun `an allow-only filter offers nothing and changes nothing`() = runBlocking {
        val api = api(allowOnly)
        assertTrue(TendaClientActions.offered(api, phone).isEmpty())
        assertEquals(listOf(phone), TendaClientActions.withBlocked(api, listOf(phone)))
        assertTrue(api.writes.isEmpty())
    }

    @Test
    fun `blocked devices are marked, and the ones away are still listed`() = runBlocking {
        val clients = TendaClientActions.withBlocked(api(), listOf(phone))
        val away = clients.single { it.macAddress == tablet.macAddress }
        assertEquals(false, away.online)
        assertEquals(true, away.internetBlocked)
        assertEquals("Tablet", away.hostname)
        assertEquals(false, clients.single { it.macAddress == phone.macAddress }.internetBlocked)
    }

    @Test
    fun `long host names are cut to what the router keeps`() = runBlocking {
        val api = api()
        TendaClientActions.run(api, phone.copy(hostname = "A very long device name indeed"), TendaClientActions.BLOCK, context)
        assertTrue(api.writes.single().second.endsWith("A%20very%20long%20device%20n\r02:00:00:00:00:11"))
    }
}
