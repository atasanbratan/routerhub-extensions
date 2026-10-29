package dev.routerhub.extension.openwrt

import dev.routerhub.api.WifiBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenWrtParsersTest {

    @Test
    fun `splits a multi-command script output into sections`() {
        val output = """
            @@routerhub:uptime
            350735.47 234388.90
            @@routerhub:release
            DISTRIB_ID='OpenWrt'
            DISTRIB_RELEASE='23.05.3'
            DISTRIB_DESCRIPTION='OpenWrt 23.05.3 r23809-234f1a2efa'
            @@routerhub:model
            @@routerhub:route
            1.1.1.1 via 192.0.2.1 dev wan src 192.0.2.23 uid 0
        """.trimIndent()

        val sections = OpenWrtParsers.splitSections(output)

        assertEquals(350_735L, OpenWrtParsers.parseUptimeSeconds(sections.getValue("uptime")))
        assertEquals(
            "OpenWrt 23.05.3 r23809-234f1a2efa",
            OpenWrtParsers.parseKeyValues(sections.getValue("release"))["DISTRIB_DESCRIPTION"],
        )
        assertEquals("", sections.getValue("model"))
        assertEquals(
            OpenWrtParsers.DefaultRoute(device = "wan", gateway = "192.0.2.1", sourceAddress = "192.0.2.23"),
            OpenWrtParsers.parseDefaultRoute(sections.getValue("route")),
        )
    }

    @Test
    fun `no default route means WAN is down`() {
        assertNull(OpenWrtParsers.parseDefaultRoute(""))
    }

    @Test
    fun `parses wireless radios from uci show`() {
        val uci = """
            wireless.radio0=wifi-device
            wireless.radio0.band='2g'
            wireless.radio1=wifi-device
            wireless.radio1.band='5g'
            wireless.radio1.disabled='1'
            wireless.default_radio0=wifi-iface
            wireless.default_radio0.device='radio0'
            wireless.default_radio0.ssid='Home'
            wireless.@wifi-iface[1]=wifi-iface
            wireless.@wifi-iface[1].device='radio1'
            wireless.@wifi-iface[1].ssid='Home-5G'
        """.trimIndent()

        val radios = OpenWrtParsers.parseWifiRadios(uci)

        assertEquals(2, radios.size)
        assertEquals("radio0", radios[0].id)
        assertEquals(WifiBand.GHZ_2_4, radios[0].band)
        assertEquals(true, radios[0].enabled)
        assertEquals("Home", radios[0].ssid)
        assertEquals(WifiBand.GHZ_5, radios[1].band)
        assertEquals(false, radios[1].enabled)
        assertEquals("Home-5G", radios[1].ssid)
    }

    @Test
    fun `joins ARP table with DHCP leases`() {
        val arp = """
            IP address       HW type     Flags       HW address            Mask     Device
            192.168.1.100    0x1         0x2         AA:BB:CC:DD:EE:01     *        br-lan
            192.168.1.101    0x1         0x2         aa:bb:cc:dd:ee:02     *        br-lan
            192.168.1.102    0x1         0x0         00:00:00:00:00:00     *        br-lan
        """.trimIndent()
        val leases = """
            1700000000 aa:bb:cc:dd:ee:01 192.168.1.100 laptop 01:aa:bb:cc:dd:ee:01
            1700000000 aa:bb:cc:dd:ee:02 192.168.1.101 * *
        """.trimIndent()

        val clients = OpenWrtParsers.parseClients(arp, leases)

        assertEquals(2, clients.size)
        assertEquals("aa:bb:cc:dd:ee:01", clients[0].macAddress)
        assertEquals("192.168.1.100", clients[0].ipAddress)
        assertEquals("laptop", clients[0].hostname)
        assertEquals("br-lan", clients[0].interfaceName)
        assertNull(clients[1].hostname)
    }
}
