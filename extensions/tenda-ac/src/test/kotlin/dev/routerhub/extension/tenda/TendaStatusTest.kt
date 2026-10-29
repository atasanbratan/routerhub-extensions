package dev.routerhub.extension.tenda

import dev.routerhub.api.ConnectionMedium
import dev.routerhub.api.FirmwareParseException
import dev.routerhub.api.WifiBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TendaStatusTest {

    private fun status(system: String = Fixtures.SYSTEM_STATUS, router: String = Fixtures.ROUTER_STATUS) =
        TendaStatus.parseStatus(Json.parse(system), Json.parse(router))

    @Test
    fun `the status says what the router reports`() {
        val s = status()
        assertEquals("AC10 V6.0", s.model)
        assertEquals("V16.03.61.08_cn", s.firmwareVersion)
        assertEquals(694L, s.uptimeSeconds)
        assertEquals("203.0.113.9", s.wanIpv4)
        assertEquals(true, s.wanConnected)
    }

    @Test
    fun `both radios are listed with their state and name`() {
        val radios = status().wifiRadios
        assertEquals(listOf("2g", "5g"), radios.map { it.id })
        assertEquals(listOf(WifiBand.GHZ_2_4, WifiBand.GHZ_5), radios.map { it.band })
        assertEquals(listOf(true, false), radios.map { it.enabled })
        assertEquals(listOf("HomeNet", "HomeNet_5G"), radios.map { it.ssid })
    }

    @Test
    fun `the extras describe the connection without addresses of the devices`() {
        val extras = status().extras
        assertEquals("Connected", extras["Internet"])
        assertEquals("Dynamic IP", extras["Connection type"])
        assertEquals("203.0.113.1", extras["Gateway"])
        assertEquals("198.51.100.1", extras["DNS"])
        assertEquals("192.168.0.1", extras["LAN address"])
        assertEquals("3 (20 MHz)", extras["2.4 GHz channel"])
        assertEquals("48 (80 MHz)", extras["5 GHz channel"])
        assertEquals("3", extras["Connected devices"])
        assertFalse("no MAC addresses in the extras", extras.values.any { it.contains("02:00:00") })
    }

    @Test
    fun `an unplugged cable is not connected and has no address`() {
        val system = Fixtures.SYSTEM_STATUS
            .replace("\"adv_connect_status\":3", "\"adv_connect_status\":0")
            .replace("\"adv_ip\":\"203.0.113.9\"", "\"adv_ip\":\"\"")
        val s = status(system = system)
        assertEquals(false, s.wanConnected)
        assertNull(s.wanIpv4)
        assertEquals("Cable unplugged", s.extras["Internet"])
    }

    @Test
    fun `a router without a wan section still reports its radios`() {
        val s = status(system = """{"adv_run_time":5,"adv_firm_ver":"V1","adv_hard_ver":"V6.0"}""")
        assertNull(s.wanConnected)
        assertNull(s.wanIpv4)
        assertEquals(2, s.wifiRadios.size)
    }

    @Test
    fun `models are named from the product and the hardware version`() {
        assertEquals("AC10 V6.0", TendaStatus.modelName("ac10v6", "V6.0"))
        assertEquals("AC6 V2.0", TendaStatus.modelName("ac6v2", "V2.0"))
        assertEquals("AC10", TendaStatus.modelName("ac10v6", ""))
        assertEquals("OTHER", TendaStatus.modelName("other", ""))
        assertNull(TendaStatus.modelName("", "V6.0"))
    }

    @Test
    fun `a status that is not an object is a parse error`() {
        try {
            TendaStatus.parseStatus(Json.parse("[]"), Json.parse("{}"))
            fail("expected a FirmwareParseException")
        } catch (_: FirmwareParseException) {
        }
    }

    @Test
    fun `clients skip the summary and the blacklisted and know how they connect`() {
        val clients = TendaStatus.parseClients(Json.parse(Fixtures.ONLINE_LIST))
        assertEquals(listOf("02:00:00:00:00:10", "02:00:00:00:00:11", "02:00:00:00:00:12"), clients.map { it.macAddress })

        val laptop = clients[0]
        assertEquals("LAPTOP", laptop.hostname)
        assertEquals("192.168.0.125", laptop.ipAddress)
        assertEquals(ConnectionMedium.WIRED, laptop.medium)
        assertEquals("Wired", laptop.interfaceName)

        val phone = clients[1]
        assertEquals(ConnectionMedium.WIRELESS, phone.medium)
        assertEquals("5 GHz", phone.interfaceName)

        val guest = clients[2]
        assertNull("an empty name is no name", guest.hostname)
        assertEquals("2.4 GHz guest", guest.interfaceName)
    }

    @Test
    fun `an empty network has no clients`() {
        assertTrue(TendaStatus.parseClients(Json.parse("""[{"blackNum":0,"macFilterType":"black"}]""")).isEmpty())
        assertTrue(TendaStatus.parseClients(Json.parse("[]")).isEmpty())
    }
}
