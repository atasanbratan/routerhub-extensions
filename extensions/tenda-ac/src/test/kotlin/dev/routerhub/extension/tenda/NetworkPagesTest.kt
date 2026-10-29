package dev.routerhub.extension.tenda

import dev.routerhub.api.SettingField
import dev.routerhub.api.TableCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkPagesTest {

    private fun rows(vararg row: List<String>) = TableCodec.encode(row.toList())

    // --- LAN and DHCP ------------------------------------------------------------------------

    private fun lan(json: String = Fixtures.LAN) = FakeTendaApi("AdvGetLanIp" to json)

    @Test
    fun `the lan page shows the address, the server and the dns choice`() {
        val page = LanPage.readPage(lan())
        assertEquals("192.168.0.1", (page.field("lanIp") as SettingField.Text).value)
        assertEquals("255.255.255.0", (page.field("mask") as SettingField.Text).value)
        assertEquals(true, (page.field("dhcp") as SettingField.Toggle).value)
        assertEquals("192.168.0.100", (page.field("start") as SettingField.Text).value)
        assertEquals("86400", (page.field("lease") as SettingField.Choice).value)
        assertEquals(false, (page.field("customDns") as SettingField.Toggle).value)
    }

    @Test
    fun `a lease time the router has that is not on the list stays selectable`() {
        val page = LanPage.readPage(lan(Fixtures.LAN.replace("\"leaseTime\":\"86400\"", "\"leaseTime\":\"7200\"")))
        val lease = page.field("lease") as SettingField.Choice
        assertEquals("7200", lease.value)
        assertTrue(lease.options.any { it.value == "7200" })
        assertEquals(6, lease.options.size)
    }

    @Test
    fun `changing the lease sends the whole page as it is`() {
        val api = lan()
        LanPage.applyChanges(api, "lease" to "21600")
        assertEquals(
            "AdvSetLanip" to "lanIp=192.168.0.1&lanMask=255.255.255.0&dhcpEn=1&startIp=192.168.0.100&endIp=192.168.0.200" +
                "&leaseTime=21600&lanDnsAuto=1&lanDns1=&lanDns2=",
            api.onlyWrite,
        )
    }

    @Test
    fun `the address range and custom dns are sent`() {
        val api = lan()
        LanPage.applyChanges(api, "start" to "192.168.0.50", "end" to "192.168.0.150", "customDns" to "true", "dns1" to "1.1.1.1", "dns2" to "9.9.9.9")
        val body = api.onlyWrite.second
        assertTrue(body, body.contains("&startIp=192.168.0.50&endIp=192.168.0.150&"))
        assertTrue(body, body.endsWith("&lanDnsAuto=0&lanDns1=1.1.1.1&lanDns2=9.9.9.9"))
    }

    @Test
    fun `with the server off the old range is kept`() {
        val api = lan()
        LanPage.applyChanges(api, "dhcp" to "false", "start" to "10.9.9.9")
        val body = api.onlyWrite.second
        assertTrue(body, body.contains("&dhcpEn=0&startIp=192.168.0.100&endIp=192.168.0.200&"))
    }

    @Test
    fun `lan values that do not make sense are refused`() {
        val api = lan()
        rejected("lanIp") { LanPage.applyChanges(api, "lanIp" to "192.168.0.999") }
        rejected("lanIp") { LanPage.applyChanges(api, "lanIp" to "224.0.0.1") }
        rejected("mask") { LanPage.applyChanges(api, "mask" to "255.0.255.0") }
        rejected("mask") { LanPage.applyChanges(api, "mask" to "0.0.0.0") }
        rejected("start") { LanPage.applyChanges(api, "start" to "10.0.0.5") }
        rejected("end") { LanPage.applyChanges(api, "end" to "192.168.1.5") }
        rejected("end") { LanPage.applyChanges(api, "start" to "192.168.0.150", "end" to "192.168.0.120") }
        rejected("start") { LanPage.applyChanges(api, "start" to "192.168.0.1", "end" to "192.168.0.50") }
        rejected("lease") { LanPage.applyChanges(api, "lease" to "12345") }
        rejected("dns1") { LanPage.applyChanges(api, "customDns" to "true") }
        rejected("dns2") { LanPage.applyChanges(api, "customDns" to "true", "dns1" to "1.1.1.1", "dns2" to "nope") }
        assertTrue(api.writes.isEmpty())
    }

    @Test
    fun `moving the router without moving the range is refused`() {
        val api = lan()
        rejected("start") { LanPage.applyChanges(api, "lanIp" to "192.168.1.1") }
        LanPage.applyChanges(api, "lanIp" to "192.168.1.1", "start" to "192.168.1.100", "end" to "192.168.1.200")
        assertTrue(api.onlyWrite.second.startsWith("lanIp=192.168.1.1&lanMask=255.255.255.0&dhcpEn=1&startIp=192.168.1.100&endIp=192.168.1.200&"))
    }

    @Test
    fun `a lan that overlaps the network on the internet side is refused`() {
        val api = lan(Fixtures.LAN.replace("\"wanIp\":\"\"", "\"wanIp\":\"192.168.1.40\""))
        val message = rejected("lanIp") { LanPage.applyChanges(api, "lanIp" to "192.168.1.1", "start" to "192.168.1.100", "end" to "192.168.1.200") }
        assertTrue(message, message.contains("192.168.1.40"))
        assertTrue(api.writes.isEmpty())
    }

    // --- DHCP reservations -------------------------------------------------------------------

    private fun reservations() = FakeTendaApi("GetIpMacBind" to Fixtures.IP_MAC_BIND)

    private val printer = listOf("Printer", "02:00:00:00:00:21", "192.168.0.50")

    @Test
    fun `the reservations are the router's bindings`() {
        val table = DhcpReservationsPage.readPage(reservations()).field("reservations") as SettingField.Table
        assertEquals(listOf(printer), table.rows)
        assertEquals(32, table.maxRows)
    }

    @Test
    fun `adding a reservation sends them all in the pages own format`() {
        val api = reservations()
        DhcpReservationsPage.applyChanges(
            api,
            "reservations" to rows(printer, listOf("My Phone", "02-AA-BB-CC-DD-EE", "192.168.0.51")),
        )
        assertEquals(
            "SetIpMacBind" to "bindnum=2&list=Printer\r02:00:00:00:00:21\r192.168.0.50\nMy%20Phone\r02:aa:bb:cc:dd:ee\r192.168.0.51",
            api.onlyWrite,
        )
    }

    @Test
    fun `an empty name and an empty list are fine`() {
        val api = reservations()
        DhcpReservationsPage.applyChanges(api, "reservations" to rows(listOf("", "02:00:00:00:00:22", "192.168.0.52")))
        assertEquals("bindnum=1&list=\r02:00:00:00:00:22\r192.168.0.52", api.onlyWrite.second)

        val none = reservations()
        DhcpReservationsPage.applyChanges(none, "reservations" to "")
        assertEquals("bindnum=0&list=", none.onlyWrite.second)
    }

    @Test
    fun `no change sends nothing`() {
        val api = reservations()
        DhcpReservationsPage.applyChanges(api)
        assertTrue(api.writes.isEmpty())
    }

    @Test
    fun `bad reservations are refused`() {
        val api = reservations()
        fun refuse(vararg row: List<String>) = rejected("reservations") { DhcpReservationsPage.applyChanges(api, "reservations" to rows(*row)) }

        refuse(listOf("A", "not-a-mac", "192.168.0.60"))
        refuse(listOf("A", "02:00:00:00:00:22", "192.168.0.999"))
        refuse(listOf("A", "02:00:00:00:00:22", "10.0.0.5"))
        refuse(listOf("A", "02:00:00:00:00:22", "192.168.0.1"))
        refuse(listOf("A", "02:00:00:00:00:22", "172.31.131.54"))
        refuse(listOf("A", "01:00:5e:00:00:01", "192.168.0.60"))
        refuse(listOf("x".repeat(21), "02:00:00:00:00:22", "192.168.0.60"))
        refuse(listOf("A", "02:00:00:00:00:22"))
        refuse(printer, listOf("B", "02:00:00:00:00:21", "192.168.0.61"))
        refuse(printer, listOf("B", "02:00:00:00:00:22", "192.168.0.50"))
        assertTrue(api.writes.isEmpty())
    }

    @Test
    fun `at most 32 reservations`() {
        val api = reservations()
        val many = (1..33).map { listOf("d$it", "02:00:00:00:01:%02x".format(it), "192.168.0.${100 + it}") }
        rejected("reservations") { DhcpReservationsPage.applyChanges(api, "reservations" to TableCodec.encode(many)) }
        DhcpReservationsPage.applyChanges(api, "reservations" to TableCodec.encode(many.take(32)))
        assertEquals(1, api.writes.size)
    }

    // --- Port forwarding ---------------------------------------------------------------------

    private fun forwarding() = FakeTendaApi("GetVirtualServerCfg" to Fixtures.VIRTUAL_SERVER)

    private val web = listOf("192.168.0.20", "80", "8080", "tcp")

    @Test
    fun `the rules show the protocol by name`() {
        val table = PortForwardingPage.readPage(forwarding()).field("rules") as SettingField.Table
        assertEquals(listOf(web), table.rows)
        assertEquals(16, table.maxRows)
        assertEquals(listOf("both", "tcp", "udp"), table.columns.last().options.map { it.value })
    }

    @Test
    fun `a rule with protocol code zero is tcp and udp`() {
        val api = FakeTendaApi("GetVirtualServerCfg" to Fixtures.VIRTUAL_SERVER.replace("\"protocol\":\"1\"", "\"protocol\":\"0\""))
        assertEquals("both", ((PortForwardingPage.readPage(api).field("rules") as SettingField.Table).rows.single()[3]))
    }

    @Test
    fun `the complete list is sent in the pages own format`() {
        val api = forwarding()
        PortForwardingPage.applyChanges(api, "rules" to rows(web, listOf("192.168.0.21", "443", "8443", "both"), listOf("192.168.0.22", "27015", "27015", "udp")))
        assertEquals("SetVirtualServerCfg" to "list=192.168.0.20,80,8080,1~192.168.0.21,443,8443,0~192.168.0.22,27015,27015,2", api.onlyWrite)
    }

    @Test
    fun `removing every rule sends an empty list`() {
        val api = forwarding()
        PortForwardingPage.applyChanges(api, "rules" to "")
        assertEquals("SetVirtualServerCfg" to "list=", api.onlyWrite)
    }

    @Test
    fun `bad rules are refused`() {
        val api = forwarding()
        fun refuse(vararg row: List<String>) = rejected("rules") { PortForwardingPage.applyChanges(api, "rules" to rows(*row)) }

        refuse(listOf("10.0.0.5", "80", "81", "tcp"))
        refuse(listOf("192.168.0.1", "80", "81", "tcp"))
        refuse(listOf("192.168.0.20", "0", "81", "tcp"))
        refuse(listOf("192.168.0.20", "80", "65536", "tcp"))
        refuse(listOf("192.168.0.20", "web", "81", "tcp"))
        refuse(listOf("192.168.0.20", "80", "81", "sctp"))
        refuse(listOf("192.168.0.20", "80", "81"))
        // a WAN port can be mapped only once, whatever the protocol
        refuse(web, listOf("192.168.0.21", "53", "8080", "udp"))
        assertTrue(api.writes.isEmpty())
    }

    @Test
    fun `at most 16 rules`() {
        val api = forwarding()
        val many = (1..17).map { listOf("192.168.0.20", "80", "${9000 + it}", "tcp") }
        rejected("rules") { PortForwardingPage.applyChanges(api, "rules" to TableCodec.encode(many)) }
        PortForwardingPage.applyChanges(api, "rules" to TableCodec.encode(many.take(16)))
        assertEquals(1, api.writes.size)
    }

    // --- DMZ ---------------------------------------------------------------------------------

    private fun dmz() = FakeTendaApi("GetDMZCfg" to Fixtures.DMZ)

    @Test
    fun `the dmz host is switched on with an address in the network`() {
        val api = dmz()
        DmzPage.applyChanges(api, "enable" to "true", "ip" to "192.168.0.77")
        assertEquals("SetDMZCfg" to "dmzEn=1&dmzIp=192.168.0.77", api.onlyWrite)
    }

    @Test
    fun `switching the dmz host off keeps the address`() {
        val api = FakeTendaApi("GetDMZCfg" to Fixtures.DMZ.replace("\"dmzEn\":\"0\"", "\"dmzEn\":\"1\""))
        DmzPage.applyChanges(api, "enable" to "false", "ip" to "garbage")
        assertEquals("dmzEn=0&dmzIp=192.168.0.100", api.onlyWrite.second)
    }

    @Test
    fun `a bad dmz host is refused`() {
        val api = dmz()
        rejected("ip") { DmzPage.applyChanges(api, "enable" to "true", "ip" to "10.0.0.2") }
        rejected("ip") { DmzPage.applyChanges(api, "enable" to "true", "ip" to "192.168.0.1") }
        rejected("ip") { DmzPage.applyChanges(api, "enable" to "true", "ip" to "") }
        assertTrue(api.writes.isEmpty())
    }

    // --- UPnP and firewall -------------------------------------------------------------------

    @Test
    fun `upnp is read from the one element array and saved as a switch`() {
        val api = FakeTendaApi("GetUpnpCfg" to Fixtures.UPNP)
        assertEquals(true, (UpnpPage.readPage(api).field("enable") as SettingField.Toggle).value)
        UpnpPage.applyChanges(api, "enable" to "false")
        assertEquals("SetUpnpCfg" to "upnpEn=0", api.onlyWrite)
        val quiet = FakeTendaApi("GetUpnpCfg" to Fixtures.UPNP)
        UpnpPage.applyChanges(quiet)
        assertTrue(quiet.writes.isEmpty())
    }

    @Test
    fun `the firewall keeps four switches in one string of digits`() {
        val api = FakeTendaApi("GetFirewallCfg" to Fixtures.FIREWALL)
        val page = FirewallPage.readPage(api)
        assertEquals(listOf(true, true, false, true), listOf("icmp", "tcp", "udp", "wanPing").map { (page.field(it) as SettingField.Toggle).value })

        FirewallPage.applyChanges(api, "udp" to "true", "icmp" to "false")
        assertEquals("SetFirewallCfg" to "firewallEn=0111", api.onlyWrite)
    }

    @Test
    fun `an unchanged firewall sends nothing`() {
        val api = FakeTendaApi("GetFirewallCfg" to Fixtures.FIREWALL)
        FirewallPage.applyChanges(api)
        assertTrue(api.writes.isEmpty())
    }

    // --- Dynamic DNS -------------------------------------------------------------------------

    private fun ddns(json: String = Fixtures.DDNS) = FakeTendaApi("GetDDNSCfg" to json)

    @Test
    fun `the ddns password is never shown`() {
        val page = DdnsPage.readPage(ddns(Fixtures.DDNS.replace("\"ddnsPwd\":\"\"", "\"ddnsPwd\":\"hunter2-secret\"")))
        assertFalse(page.fields.joinToString { it.toString() }.contains("hunter2-secret"))
        assertTrue(page.field("password") is SettingField.Secret)
    }

    @Test
    fun `enabling ddns needs the account and the domain`() {
        val api = ddns()
        rejected("user") { DdnsPage.applyChanges(api, "enable" to "true") }
        rejected("password") { DdnsPage.applyChanges(api, "enable" to "true", "user" to "me") }
        rejected("domain") { DdnsPage.applyChanges(api, "enable" to "true", "user" to "me", "password" to "pw") }
        rejected("domain") { DdnsPage.applyChanges(api, "enable" to "true", "user" to "me", "password" to "pw", "domain" to "my host.example") }
        rejected("provider") { DdnsPage.applyChanges(api, "enable" to "true", "provider" to "nowhere.example", "user" to "me", "password" to "pw", "domain" to "h.example") }
        assertTrue(api.writes.isEmpty())

        DdnsPage.applyChanges(api, "enable" to "true", "user" to "me", "password" to "p&w", "domain" to "home.example.org")
        assertEquals(
            "SetDDNSCfg" to "ddnsEn=1&serverName=no-ip.com&ddnsUser=me&ddnsPwd=p%26w&ddnsDomain=home.example.org",
            api.onlyWrite,
        )
    }

    @Test
    fun `the stored ddns password is resent when none is typed`() {
        val configured = """{"ddnsEn":"1","serverName":"oray.com","ddnsUser":"me","ddnsPwd":"stored-pw","ddnsDomain":"home.example.org","ddnsStatus":"1"}"""
        val api = ddns(configured)
        DdnsPage.applyChanges(api, "domain" to "other.example.org")
        assertEquals("ddnsEn=1&serverName=oray.com&ddnsUser=me&ddnsPwd=stored-pw&ddnsDomain=other.example.org", api.onlyWrite.second)
    }

    @Test
    fun `switching ddns off resends what the router held`() {
        val configured = """{"ddnsEn":"1","serverName":"oray.com","ddnsUser":"me","ddnsPwd":"stored-pw","ddnsDomain":"home.example.org","ddnsStatus":"1"}"""
        val api = ddns(configured)
        DdnsPage.applyChanges(api, "enable" to "false", "domain" to "ignored.example")
        assertEquals("ddnsEn=0&serverName=oray.com&ddnsUser=me&ddnsPwd=stored-pw&ddnsDomain=home.example.org", api.onlyWrite.second)
    }

    // --- Remote management -------------------------------------------------------------------

    private fun remote(json: String = Fixtures.REMOTE_WEB) = FakeTendaApi("GetRemoteWebCfg" to json)

    @Test
    fun `remote management is refused while there is no login password`() {
        val api = remote(Fixtures.REMOTE_WEB.replace("\"syspwdflag\":\"1\"", "\"syspwdflag\":\"0\""))
        val message = rejected("enable") { RemoteWebPage.applyChanges(api, "enable" to "true") }
        assertTrue(message, message.contains("login password"))
        assertTrue(api.writes.isEmpty())
    }

    @Test
    fun `remote management is enabled with a port and an allowed address`() {
        val api = remote()
        RemoteWebPage.applyChanges(api, "enable" to "true", "ip" to "203.0.113.7", "port" to "9090")
        assertEquals("SetRemoteWebCfg" to "remoteWebEn=1&remoteIp=203.0.113.7&remotePort=9090", api.onlyWrite)

        val everyone = remote()
        RemoteWebPage.applyChanges(everyone, "enable" to "true")
        assertEquals("remoteWebEn=1&remoteIp=0.0.0.0&remotePort=8888", everyone.onlyWrite.second)
    }

    @Test
    fun `bad remote management values are refused and switching off resends the old ones`() {
        val api = remote()
        rejected("port") { RemoteWebPage.applyChanges(api, "enable" to "true", "port" to "70000") }
        rejected("ip") { RemoteWebPage.applyChanges(api, "enable" to "true", "ip" to "not-an-ip") }
        assertTrue(api.writes.isEmpty())

        val on = remote(Fixtures.REMOTE_WEB.replace("\"remoteWebEn\":\"0\"", "\"remoteWebEn\":\"1\"").replace("\"remotePort\":\"8888\"", "\"remotePort\":\"9999\""))
        RemoteWebPage.applyChanges(on, "enable" to "false", "port" to "1")
        assertEquals("remoteWebEn=0&remoteIp=0.0.0.0&remotePort=9999", on.onlyWrite.second)
    }
}
