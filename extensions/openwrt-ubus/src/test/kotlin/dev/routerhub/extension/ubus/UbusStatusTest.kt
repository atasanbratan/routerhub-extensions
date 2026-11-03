package dev.routerhub.extension.ubus

import dev.routerhub.api.CommandFailedException
import dev.routerhub.api.ConnectionMedium
import dev.routerhub.api.WifiBand
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class UbusStatusTest {
    private val rpcd = FakeRpcd()

    @Test
    fun `the status comes from the board, the routes and the radios`() = runBlocking {
        val status = UbusStatusReader.status(router(rpcd))
        assertEquals("Example Router X1", status.model)
        assertEquals("OpenWrt 24.10.0 r28427", status.firmwareVersion)
        assertEquals(3600L, status.uptimeSeconds)
        assertEquals("203.0.113.9", status.wanIpv4)
        assertEquals(true, status.wanConnected)
        assertEquals(listOf("radio0", "radio1"), status.wifiRadios.map { it.id })
        assertEquals(WifiBand.GHZ_5, status.wifiRadios[1].band)
        assertFalse(status.wifiRadios[1].enabled)
        assertEquals("HomeNet", status.wifiRadios[0].ssid)
        assertEquals("wan", status.extras["WAN interface"])
        assertEquals("64 of 128 MiB", status.extras["Free memory"])
    }

    @Test
    fun `radios fall back to network wireless where LuCI's call is missing`() = runBlocking {
        rpcd.failing["luci-rpc getWirelessDevices"] = CommandFailedException("luci-rpc getWirelessDevices", null, "no")
        rpcd.answer("network.wireless status", """{"radio0":{"disabled":false,"config":{"band":"2g"},"interfaces":[]}}""")
        assertEquals(listOf("radio0"), UbusStatusReader.radios(router(rpcd)).map { it.name })
    }

    @Test
    fun `a router without Wi-Fi has no radios, and its status still loads`() = runBlocking {
        rpcd.failing["luci-rpc getWirelessDevices"] = CommandFailedException("luci-rpc getWirelessDevices", null, "no")
        rpcd.failing["network.wireless status"] = CommandFailedException("network.wireless status", null, "This router doesn't offer this call")
        val status = UbusStatusReader.status(router(rpcd))
        assertEquals(emptyList<Any>(), status.wifiRadios)
        assertEquals("Example Router X1", status.model)
    }

    @Test
    fun `the WAN is whichever interface has the default route, like a Wi-Fi uplink`() = runBlocking {
        rpcd.answer(
            "network.interface dump",
            """{"interface":[
                {"interface":"wan","up":false,"proto":"dhcp","ipv4-address":[],"route":[]},
                {"interface":"wwan","up":true,"proto":"dhcp","ipv4-address":[{"address":"198.51.100.7","mask":24}],"route":[{"target":"0.0.0.0","mask":0,"nexthop":"198.51.100.1"}]}
            ]}""",
        )
        val status = UbusStatusReader.status(router(rpcd))
        assertEquals("198.51.100.7", status.wanIpv4)
        assertEquals("wwan", status.extras["WAN interface"])
    }

    @Test
    fun `no default route means no internet`() = runBlocking {
        rpcd.answer("network.interface dump", """{"interface":[{"interface":"lan","up":true,"route":[]}]}""")
        val status = UbusStatusReader.status(router(rpcd))
        assertEquals(false, status.wanConnected)
        assertNull(status.wanIpv4)
    }

    @Test
    fun `the band also comes from the older hwmode option`() {
        assertEquals(WifiBand.GHZ_2_4, UbusStatusReader.band(mapOf("hwmode" to "11g")))
        assertEquals(WifiBand.GHZ_5, UbusStatusReader.band(mapOf("hwmode" to "11a")))
        assertEquals(WifiBand.UNKNOWN, UbusStatusReader.band(emptyMap()))
    }

    @Test
    fun `clients are stations and reachable LAN neighbours`() = runBlocking {
        val clients = UbusStatusReader.clients(router(rpcd)).associateBy { it.macAddress }
        assertEquals(setOf("02:00:00:00:00:11", "02:00:00:00:00:10", "02:00:00:00:00:13"), clients.keys)

        val phone = clients.getValue("02:00:00:00:00:11")
        assertEquals(ConnectionMedium.WIRELESS, phone.medium)
        assertEquals("phone", phone.hostname)
        assertEquals("192.168.1.101", phone.ipAddress)
        assertEquals("phy0-ap0", phone.interfaceName)

        val laptop = clients.getValue("02:00:00:00:00:10")
        assertEquals(ConnectionMedium.WIRED, laptop.medium)
        assertEquals("br-lan", laptop.interfaceName)

        assertEquals("nas", clients.getValue("02:00:00:00:00:13").hostname)
    }

    @Test
    fun `without the ARP table leases stand in for wired clients`() = runBlocking {
        rpcd.failing["file read"] = UbusCallFailedException("file read", UbusStatus.PERMISSION_DENIED)
        val clients = UbusStatusReader.clients(router(rpcd)).associateBy { it.macAddress }
        assertEquals(3, clients.size)
        assertEquals(ConnectionMedium.UNKNOWN, clients.getValue("02:00:00:00:00:12").medium)
    }

    @Test
    fun `stale ARP entries are skipped`() {
        val text = "header\n192.0.2.1 0x1 0x0 02:00:00:00:00:01 * br-lan\n192.0.2.2 0x1 0x2 02:AA:00:00:00:02 * br-lan\n\n"
        assertEquals(listOf(listOf("192.0.2.2", "02:aa:00:00:00:02", "br-lan")), UbusStatusReader.parseArp(text))
    }
}
