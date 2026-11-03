package dev.routerhub.extension.tplinkwr

import dev.routerhub.api.ConnectionMedium
import dev.routerhub.api.WifiBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pages shaped like a TL-WR940N v4/v5 on 3.17.1, with made-up values. */
internal object Fixtures {
    val STATUS = """
        <SCRIPT language="javascript" type="text/javascript">
        var statusPara = new Array(
        1, 1, 22, 20000, 1483099, "3.17.1 Build 170207 Rel.37277n ", "WR940N v4\/v5 00000000", 1, 0, 3,
        0,0 );
        </SCRIPT>
        <SCRIPT language="javascript" type="text/javascript">
        var lanPara = new Array(
        "02-00-00-00-00-01", "192.168.0.1", "255.255.255.0",
        0,0 );
        </SCRIPT>
        <SCRIPT language="javascript" type="text/javascript">
        var wlanPara = new Array(
        1, "RouterHub-Test", 11, 6, "02-00-00-00-00-01", "192.168.0.1", 1, 8, 69, 0, 6, "", "", "", 1, 0, 2, 0, "",
        0,0 );
        </SCRIPT>
        <SCRIPT language="javascript" type="text/javascript">
        var wanPara = new Array(
        4, "02-00-00-00-00-02", "203.0.113.20", 1, "255.255.255.0", 0, 0, "203.0.113.1", 1, 1, 0, "198.51.100.53 , 198.51.100.54", "", 0, 0, "0.0.0.0", "0.0.0.0", 0, 0, 0, 0, 0,
        0,0 );
        </SCRIPT>
        <SCRIPT language="javascript" type="text/javascript">
        var wanTypeStringArray = new Array(" ", "<span id = \"t_dyn_ip\">Dynamic IP<\/span>");
        </SCRIPT>
    """.trimIndent()

    val DHCP = """
        <SCRIPT language="javascript" type="text/javascript">
        var DHCPDynList = new Array(
        "Laptop", "02-00-00-00-00-10", "192.168.0.100", "01:59:12",
        "", "02-00-00-00-00-11", "192.168.0.101", "Permanent",
        "Phone", "02-00-00-00-00-12", "192.168.0.102", "00:12:00",
        0,0 );
        </SCRIPT>
        <SCRIPT language="javascript" type="text/javascript">
        var DHCPDynPara = new Array(
        3, 4,
        0,0 );
        </SCRIPT>
    """.trimIndent()

    val STATIONS = """
        <SCRIPT language="javascript" type="text/javascript">
        var wlanHostPara = new Array(
        1, 1, 8, 5000, 5, 0, 0, 1, 0,
        0,0 );
        </SCRIPT>
        <SCRIPT language="javascript" type="text/javascript">
        var hostList = new Array(
        "02-00-00-00-00-12", 1, 53406, 15430, 2,
        0,0 );
        </SCRIPT>
    """.trimIndent()

    const val LOGIN_OK = """<body><script language="javaScript">window.parent.location.href = "http://192.168.0.1/ABCDEFGHIJKLMNOP/userRpm/Index.htm";</script></body></html>"""
    const val LOGIN_PAGE = """<HTML><HEAD><TITLE>TL-WR940N</TITLE><SCRIPT>var httpAutErrorArray = new Array(0, 1, 0,0);</SCRIPT></HEAD></HTML>"""
    const val SENT_HOME = """<body><script language="javaScript">window.parent.location.href = "http://192.168.0.1";
</script></body></html>"""
    const val SESSION_GONE = """<body><script language="javaScript">window.parent.document.cookie="Authorization=;path=/"; window.parent.location.href = "http://192.168.0.1"; </script></body></html>"""
}

class WrPagesTest {
    @Test
    fun `status reads the firmware, uptime, Wi-Fi and internet connection`() {
        val status = WrPages.status(Fixtures.STATUS)
        assertEquals("TL-WR940N v4/v5", status.model)
        assertEquals("3.17.1 Build 170207 Rel.37277n", status.firmwareVersion)
        assertEquals(1483099L, status.uptimeSeconds)
        assertEquals("203.0.113.20", status.wanIpv4)
        assertEquals(true, status.wanConnected)
        val radio = status.wifiRadios.single()
        assertEquals(WifiBand.GHZ_2_4, radio.band)
        assertTrue(radio.enabled)
        assertEquals("RouterHub-Test", radio.ssid)
        assertEquals("192.168.0.1", status.extras["LAN address"])
        assertEquals("203.0.113.1", status.extras["Gateway"])
        assertEquals("198.51.100.53, 198.51.100.54", status.extras["DNS servers"])
    }

    @Test
    fun `no internet address means no connection, and Wi-Fi off reads as off`() {
        val offline = Fixtures.STATUS.replace("\"203.0.113.20\"", "\"0.0.0.0\"").replace("1, \"RouterHub-Test\"", "0, \"RouterHub-Test\"")
        val status = WrPages.status(offline)
        assertEquals(false, status.wanConnected)
        assertNull(status.wanIpv4)
        assertNull(status.extras["Gateway"])
        assertFalse(status.wifiRadios.single().enabled)
    }

    @Test
    fun `clients come from the DHCP list, marked wired or Wi-Fi by the station list`() {
        val clients = WrPages.clients(Fixtures.DHCP, Fixtures.STATIONS)
        assertEquals(listOf("02:00:00:00:00:10", "02:00:00:00:00:11", "02:00:00:00:00:12"), clients.map { it.macAddress })
        assertEquals(listOf("Laptop", null, "Phone"), clients.map { it.hostname })
        assertEquals("192.168.0.101", clients[1].ipAddress)
        assertEquals(listOf(ConnectionMedium.WIRED, ConnectionMedium.WIRED, ConnectionMedium.WIRELESS), clients.map { it.medium })
    }

    @Test
    fun `without the station list the medium is unknown`() {
        assertTrue(WrPages.clients(Fixtures.DHCP, null).all { it.medium == ConnectionMedium.UNKNOWN })
    }

    @Test
    fun `a login answer names the session, the login page and a stale session are recognised`() {
        assertEquals("ABCDEFGHIJKLMNOP", WrPages.sessionToken(Fixtures.LOGIN_OK))
        assertNull(WrPages.sessionToken(Fixtures.LOGIN_PAGE))
        assertTrue(WrPages.isSessionGone(Fixtures.SESSION_GONE))
        assertFalse(WrPages.isSessionGone(Fixtures.STATUS))
    }

    @Test
    fun `quoted values keep their escaped characters`() {
        val arrays = WrPages.arrays("""var a = new Array("x\/y", "say \"hi\"", 5, 0,0 );""")
        assertEquals(listOf("x/y", "say \"hi\"", "5", "0", "0"), arrays["a"])
    }

    @Test
    fun `a login sent home is told apart from a refused one`() {
        assertTrue(WrPages.isSentHome(Fixtures.SENT_HOME))
        assertFalse(WrPages.isSentHome(Fixtures.LOGIN_PAGE))
        assertFalse(WrPages.isSentHome(Fixtures.LOGIN_OK))
    }
}
