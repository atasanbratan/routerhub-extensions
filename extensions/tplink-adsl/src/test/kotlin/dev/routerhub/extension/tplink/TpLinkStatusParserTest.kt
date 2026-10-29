package dev.routerhub.extension.tplink

import dev.routerhub.api.ConnectionMedium
import dev.routerhub.api.DslLineState
import dev.routerhub.api.WifiBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Fixtures are trimmed copies of pages served by a real TD-W8960N V7 (firmware 1.1.0 Build 171106),
 * with markup kept verbatim (including the firmware's quirks like `&nbsp&nbsp;`) and personal
 * values (MACs, hostnames, SSID, WAN address) replaced.
 */
class TpLinkStatusParserTest {

    private val infoHtml = """
        <html><head><script language="javascript">
        function genWanInfo() {
        var info = 'ETH WAN|eth0|eth0|IPoE|Disconnected|203.0.113.6|255.255.255.0|203.0.113.254|0Day(s) 00:00:00|02:00:00:00:00:b5|'.split('|');
        var waninfo6 = 'ETH WAN|eth0|eth0|IPoE|Unconfigured|||(null)|0Day(s) 00:00:00|disable|02:00:00:00:00:b5|'.split('|');
        }
        </script></head><body>
        <table><tr><td class="h1">Device Info</td></tr>
        <tr><td class="h3">Version Info</td></tr>
        <tr><td><table border="1" cellpadding="3" cellspacing="0" style="width:100%">
        <tr>
        <td class="item">Firmware Version:</td>
        <td class="dataStyle">1.1.0 Build 171106 Rel.72267</td>
        </tr>
        <tr>
        <td class="item">Hardware Version:</td>
        <td class="dataStyle">TD-W8960N V7 0x00000000</td>
        </tr>
        <tr>
        <td class="item">System Running Time:</td>
        <td class="dataStyle">0Day(s) 00:45:05</td>
        </tr>
        </table></td></tr>
        <tr><td><table border="1" cellpadding="3" cellspacing="0" style="width:100%">
        <tr>
        <td rowspan=2 class="item3" id="IPv4">IPv4</td>
        <td class="item4">LAN IP Address:</td>
        <td class="dataStyle">192.168.1.1</td>
        </tr>
        </table></td></tr></table>
        </body></html>
    """.trimIndent()

    private val wirelessHtml = """
        <script language="javascript">
        var ssid = 'HomeNet';
        var enbl = '1';
        var ssidArray = new Array();
        </script>
    """.trimIndent()

    private val dslDisabledHtml = """
        <table border="1" cellpadding="2" cellspacing="0" style='width:100%'>
        <tr><td colspan="2" class='hd'>Mode:</td><td>&nbsp&nbsp;</td></tr><tr><td colspan="2" class='hd'>Traffic Type:</td><td>&nbsp&nbsp;</td></tr><tr><td colspan="2" class='hd'>Status:</td><td>Disabled</td></tr><tr><td colspan="2" class='hd'>Link Power State:</td><td>L3</td></tr><tr><td colspan="3">&nbsp;</td></tr><tr><td class='hd'>&nbsp;</td><td class='hd'>Downstream</td><td class='hd'>Upstream</td></tr><tr><td class='hd'><nobreak>Line Coding(Trellis):</nobreak></td><td>&nbsp&nbsp;</td><td>&nbsp&nbsp;</td></tr><tr><td class='hd'>SNR Margin (0.1 dB):</td><td>&nbsp&nbsp;</td><td>&nbsp&nbsp;</td></tr><tr><td class='hd'>Attenuation (0.1 dB):</td><td>&nbsp&nbsp;</td><td>&nbsp&nbsp;</td></tr><tr><td class='hd'><nobreak>Attainable Rate (Kbps):</nobreak></td><td>&nbsp&nbsp;</td><td>&nbsp&nbsp;</td></tr><tr><td class='hd'>Rate (Kbps):</td><td>&nbsp&nbsp;</td><td>&nbsp&nbsp;</td></tr>
        </table>
    """.trimIndent()

    // Same layout with a trained line. Values are illustrative: the test router runs in Ethernet-WAN mode.
    private val dslUpHtml = dslDisabledHtml
        .replace("<td>Disabled</td>", "<td>Up</td>")
        .replace("Mode:</td><td>&nbsp&nbsp;", "Mode:</td><td>ADSL2+")
        .replace("SNR Margin (0.1 dB):</td><td>&nbsp&nbsp;</td><td>&nbsp&nbsp;", "SNR Margin (0.1 dB):</td><td>98</td><td>110")
        .replace("Attenuation (0.1 dB):</td><td>&nbsp&nbsp;</td><td>&nbsp&nbsp;", "Attenuation (0.1 dB):</td><td>305</td><td>150")
        .replace("Attainable Rate (Kbps):</nobreak></td><td>&nbsp&nbsp;</td><td>&nbsp&nbsp;", "Attainable Rate (Kbps):</nobreak></td><td>12400</td><td>1180")
        .replace("Rate (Kbps):</td><td>&nbsp&nbsp;</td><td>&nbsp&nbsp;", "Rate (Kbps):</td><td>8128</td><td>1022")

    private val arpHtml = """
        <table border='1' cellpadding='4' cellspacing='0'>
           <tr>
              <td class='hd'>IP address</td>
              <td class='hd'>Flags</td>
              <td class='hd'>HW Address</td>
              <td class='hd'>Device</td>
           </tr>
        <tr>
        <td>192.168.1.100</td>
        <td>Complete</td>
        <td>02:11:22:33:44:01</td>
        <td>br0</td>
        </tr>
        <tr>
        <td>192.168.1.101</td>
        <td>Complete</td>
        <td>02:11:22:33:44:02</td>
        <td>br0</td>
        </tr>
        <tr>
        <td>192.168.1.102</td>
        <td>Incomplete</td>
        <td>00:00:00:00:00:00</td>
        <td>br0</td>
        </tr>
        </table>
    """.trimIndent()

    private val dhcpHtml = """
        <table border="1" cellpadding="4" cellspacing="0">
        <tr>
        <td class="hd">Hostname</td>
        <td class="hd">MAC Address</td>
        <td class="hd">IP Address</td>
        <td class="hd">Expires In</td>
        </tr>
         <tr><td>DESKTOP-EXAMPLE</td><td>02:11:22:33:44:01</td><td>192.168.1.100</td><td>23 hours, 58 minutes, 6 seconds</td></tr>
        </table>
    """.trimIndent()

    private val stationsHtml = """
        <table border='1' cellpadding='4' cellspacing='0'> <tr> <td class='hd'><p align=center> MAC </td> <td class='hd'><p align=center> Associated </td> <td class='hd'><p align=center> Authorized </td> <td class='hd'><p align=center> SSID </td> </tr>
        <tr><td>02:11:22:33:44:02</td><td>Yes</td><td>Yes</td><td>HomeNet</td></tr>
        </table>
    """.trimIndent()

    @Test
    fun `parses device info, JavaScript-only WAN status and Wi-Fi`() {
        val status = TpLinkStatusParser.parseStatus(infoHtml, wirelessHtml)

        assertEquals("1.1.0 Build 171106 Rel.72267", status.firmwareVersion)
        assertEquals("TD-W8960N V7 0x00000000", status.model)
        assertEquals(45 * 60L + 5, status.uptimeSeconds)
        assertEquals(false, status.wanConnected)
        assertEquals("203.0.113.6", status.wanIpv4)
        assertEquals("ETH WAN", status.extras["WAN type"])
        assertEquals("IPoE", status.extras["Connection type"])
        assertEquals("203.0.113.254", status.extras["Gateway"])

        val radio = status.wifiRadios.single()
        assertEquals(WifiBand.GHZ_2_4, radio.band)
        assertEquals(true, radio.enabled)
        assertEquals("HomeNet", radio.ssid)
    }

    @Test
    fun `parses the firmware's uptime format`() {
        assertEquals(2 * 86_400L + 3 * 3_600 + 4 * 60 + 5, TpLinkStatusParser.parseUptimeSeconds("2Day(s) 03:04:05"))
        assertEquals(3 * 3_600L + 12 * 60 + 55, TpLinkStatusParser.parseUptimeSeconds("0 days, 3:12:55"))
    }

    @Test
    fun `reports disabled DSL as a down line instead of failing`() {
        val dsl = TpLinkStatusParser.parseDsl(dslDisabledHtml)

        assertEquals(DslLineState.DOWN, dsl.lineState)
        assertEquals(0, dsl.downstreamKbps)
        assertEquals(0, dsl.upstreamKbps)
        assertNull(dsl.snrMarginDownDb)
    }

    @Test
    fun `parses a trained line and scales tenth-of-dB values`() {
        val dsl = TpLinkStatusParser.parseDsl(dslUpHtml)

        assertEquals(DslLineState.SHOWTIME, dsl.lineState)
        assertEquals("ADSL2+", dsl.modulation)
        assertEquals(8128, dsl.downstreamKbps)
        assertEquals(1022, dsl.upstreamKbps)
        assertEquals(12400, dsl.attainableDownstreamKbps)
        assertEquals(1180, dsl.attainableUpstreamKbps)
        assertEquals(9.8, dsl.snrMarginDownDb!!, 0.001)
        assertEquals(11.0, dsl.snrMarginUpDb!!, 0.001)
        assertEquals(30.5, dsl.attenuationDownDb!!, 0.001)
        assertEquals(15.0, dsl.attenuationUpDb!!, 0.001)
    }

    @Test
    fun `joins ARP, DHCP leases and wireless stations`() {
        val clients = TpLinkStatusParser.parseClients(arpHtml, dhcpHtml, stationsHtml)

        assertEquals(2, clients.size)
        assertEquals("02:11:22:33:44:01", clients[0].macAddress)
        assertEquals("DESKTOP-EXAMPLE", clients[0].hostname)
        assertEquals(ConnectionMedium.WIRED, clients[0].medium)
        assertEquals("192.168.1.101", clients[1].ipAddress)
        assertNull(clients[1].hostname)
        assertEquals(ConnectionMedium.WIRELESS, clients[1].medium)
    }

    @Test
    fun `extracts the reboot sessionKey`() {
        assertEquals("756527635", TpLinkStatusParser.parseSessionKey("var sessionKey='756527635';\nfunction btnReset() {"))
        assertFalse(TpLinkStatusParser.parseSessionKey("<html></html>") != null)
    }
}
