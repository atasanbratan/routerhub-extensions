package dev.routerhub.extension.tplinkwr

import dev.routerhub.api.InvalidSettingException
import dev.routerhub.api.SettingField
import dev.routerhub.api.TableCodec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WrSettingsTest {
    private val router = FakeWrRouter()

    private fun rows(page: WrPage, key: String) = runBlocking { (page.read(router).fields.first { it.key == key } as SettingField.Table).rows }

    private fun save(page: WrPage, vararg changes: Pair<String, String>) = runBlocking { page.apply(router, mapOf(*changes)) }

    private fun saveRows(page: WrPage, key: String, rows: List<List<String>>) = save(page, key to TableCodec.encode(rows))

    @Test
    fun `reservations are added in the router's own MAC format and removed by number`() {
        saveRows(ReservationsPage, ReservationsPage.RESERVATIONS, listOf(listOf("02:00:00:00:00:31", "192.168.0.31"), listOf("02-00-00-00-00-32", "192.168.0.32")))
        assertEquals(listOf("02-00-00-00-00-31", "192.168.0.31", "1"), router.reservations[0])
        val both = rows(ReservationsPage, ReservationsPage.RESERVATIONS)
        assertEquals(listOf(listOf("02:00:00:00:00:31", "192.168.0.31"), listOf("02:00:00:00:00:32", "192.168.0.32")), both)

        saveRows(ReservationsPage, ReservationsPage.RESERVATIONS, both.drop(1))
        assertEquals(listOf("02-00-00-00-00-32"), router.reservations.map { it[0] })
    }

    @Test
    fun `removing several deletes the highest number first`() {
        val many = (1..10).map { listOf("02:00:00:00:00:%02d".format(it), "192.168.0.${100 + it}") }
        saveRows(ReservationsPage, ReservationsPage.RESERVATIONS, many)
        assertEquals(many, rows(ReservationsPage, ReservationsPage.RESERVATIONS)) // across two pages of eight
        saveRows(ReservationsPage, ReservationsPage.RESERVATIONS, listOf(many[0], many[5]))
        assertEquals(listOf("02-00-00-00-00-01", "02-00-00-00-00-06"), router.reservations.map { it[0] })
        val deletes = router.requests.filter { "Del=" in it }.map { it.substringAfter("Del=").substringBefore('&').toInt() }
        assertEquals(deletes.sortedDescending(), deletes)
    }

    @Test
    fun `a refused reservation reports the router's error and saves nothing`() {
        val error = assertThrows(InvalidSettingException::class.java) {
            saveRows(ReservationsPage, ReservationsPage.RESERVATIONS, listOf(listOf("02:00:00:00:00:31", "203.0.113.5")))
        }
        assertTrue(error.message!!.contains("26106"))
        assertTrue(router.reservations.isEmpty())
    }

    @Test
    fun `bad values are refused before anything is sent`() {
        assertThrows(InvalidSettingException::class.java) {
            saveRows(ReservationsPage, ReservationsPage.RESERVATIONS, listOf(listOf("not a mac", "192.168.0.31")))
        }
        assertThrows(InvalidSettingException::class.java) {
            saveRows(PortForwardsPage, PortForwardsPage.FORWARDS, listOf(listOf("70000", "192.168.0.31", "", "1")))
        }
        assertThrows(InvalidSettingException::class.java) {
            saveRows(PortForwardsPage, PortForwardsPage.FORWARDS, listOf(listOf("8000-8010", "192.168.0.31", "80", "1")))
        }
        assertTrue(router.requests.none { "Save" in it })
    }

    @Test
    fun `port forwards keep ranges and an empty device port means the same port`() {
        val wanted = listOf(listOf("8080", "192.168.0.31", "80", "2"), listOf("6000-6010", "192.168.0.32", "", "1"))
        saveRows(PortForwardsPage, PortForwardsPage.FORWARDS, wanted)
        assertEquals(listOf("6000", "6010", "6000", "6010", "192.168.0.32", "1", "1"), router.forwards[1])
        assertEquals(wanted, rows(PortForwardsPage, PortForwardsPage.FORWARDS))
        saveRows(PortForwardsPage, PortForwardsPage.FORWARDS, wanted.take(1))
        assertEquals(1, router.forwards.size)
    }

    @Test
    fun `the DMZ needs an address to switch on, and switching off keeps the address`() {
        assertThrows(InvalidSettingException::class.java) { save(DmzPage, DmzPage.ENABLED to "true") }
        save(DmzPage, DmzPage.ENABLED to "true", DmzPage.HOST to "192.168.0.50")
        assertEquals(listOf("1", "192.168.0.50"), router.dmz)
        save(DmzPage, DmzPage.ENABLED to "false")
        assertEquals(listOf("0", "192.168.0.50"), router.dmz)
    }

    @Test
    fun `UPnP is switched with the page's own buttons`() {
        save(UpnpPage, UpnpPage.ENABLED to "false")
        assertEquals(false, router.upnp)
        assertTrue(router.requests.last().endsWith("upnpenb=Disable&Upnpdisable=Disable"))
        save(UpnpPage, UpnpPage.ENABLED to "true")
        assertEquals(true, router.upnp)
    }
}
