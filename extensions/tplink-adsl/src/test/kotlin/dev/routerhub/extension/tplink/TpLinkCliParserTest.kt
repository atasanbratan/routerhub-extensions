package dev.routerhub.extension.tplink

import dev.routerhub.api.DslLineState
import dev.routerhub.api.WifiBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Outputs captured from a TD-W8960N V7 telnet CLI unless marked otherwise. */
class TpLinkCliParserTest {

    @Test
    fun `parses an idle DSL line`() {
        val output = """
            xdslctl: ADSL driver and PHY status
            Status: Idle
            Last Retrain Reason:	0
            Last initialization procedure status:	0

            Link Power State:	L0
        """.trimIndent()

        val dsl = TpLinkCliParser.parseXdsl(output)

        assertEquals(DslLineState.DOWN, dsl.lineState)
        assertEquals(0, dsl.downstreamKbps)
        assertNull(dsl.attainableDownstreamKbps)
    }

    @Test
    fun `parses a trained line, upstream-first rate lines included`() {
        // Standard Broadcom xdslctl layout for a synced line (illustrative values; the test router
        // runs in Ethernet-WAN mode and never trains).
        val output = """
            xdslctl: ADSL driver and PHY status
            Status: Showtime
            Last Retrain Reason:	0
            Max:	Upstream rate = 1180 Kbps, Downstream rate = 12400 Kbps
            Bearer:	0, Upstream rate = 1022 Kbps, Downstream rate = 8128 Kbps

            Link Power State:	L0
            Mode:			ADSL2+
            			Down		Up
            SNR (dB):	 9.8		 11.0
            Attn(dB):	 30.5		 15.0
            Pwr(dBm):	 19.5		 12.3
        """.trimIndent()

        val dsl = TpLinkCliParser.parseXdsl(output)

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
    fun `parses wireless state`() {
        val status = """
            SSID: "HomeNet"
            Mode: Managed	RSSI: 0 dBm	SNR: 0 dB	noise: -90 dBm	Channel: 2
            HT Capable:
            	Chanspec: 2.4GHz channel 2 20MHz (0x1002)
            	Primary channel: 2
        """.trimIndent()

        assertEquals(true, TpLinkCliParser.parseWlIsUp("1"))
        assertEquals(false, TpLinkCliParser.parseWlIsUp("0"))
        assertNull(TpLinkCliParser.parseWlIsUp("wl: error"))
        assertEquals("HomeNet", TpLinkCliParser.parseWlSsid("""Current SSID: "HomeNet""""))
        assertEquals(WifiBand.GHZ_2_4, TpLinkCliParser.parseWlBand(status))
    }

    @Test
    fun `no default route means WAN is down`() {
        val output = """
            Routing Table
            Destination     Gateway           NetMask         Flags  Used  Hops Interface
            -----------------------------------------------------------------------------
            127.0.0.1       127.0.0.1         255.255.255.255 UH     2222  0    lo0
            192.168.1.0     192.168.1.1       255.255.255.0   UC     0     0    br0
        """.trimIndent()

        assertNull(TpLinkCliParser.parseDefaultRoute(output))
    }

    @Test
    fun `finds the default route and WAN address`() {
        val route = """
            Destination     Gateway           NetMask         Flags  Used  Hops Interface
            0.0.0.0         203.0.113.254     0.0.0.0         UG     12    0    eth0
        """.trimIndent()
        val ifconfig = """
            eth0    Link encap:Ethernet  HWaddr 02:00:00:00:00:b5
                    inet addr:203.0.113.6  Bcast:203.0.113.255  Mask:255.255.255.0
        """.trimIndent()

        assertEquals(TpLinkCliParser.DefaultRoute("203.0.113.254", "eth0"), TpLinkCliParser.parseDefaultRoute(route))
        assertEquals("203.0.113.6", TpLinkCliParser.parseInetAddress(ifconfig))
    }
}
