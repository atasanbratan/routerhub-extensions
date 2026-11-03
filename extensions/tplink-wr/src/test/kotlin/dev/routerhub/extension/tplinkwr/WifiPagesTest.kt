package dev.routerhub.extension.tplinkwr

import dev.routerhub.api.InvalidSettingException
import dev.routerhub.api.SettingField
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wi-Fi pages shaped like a TL-WR940N v4/v5 on 3.17.1, with made-up values. */
internal object WifiFixtures {
    /** 63 values; 3 name, 5 region, 7 mode 11bgn, 8 radio, 9 broadcast, 10 channel, 11 width 20 MHz, 12 rate. */
    fun network(): MutableList<String> = mutableListOf(
        "0", "8", "0", "RouterHub-Test", "2", "0", "0", "6", "1", "1", "6", "1", "69", "0", "0", "0", "Extra-2", "Extra-3", "Extra-4", "0", "0", "0", "0", "", "", "1",
        "", "1", "1", "3", "3", "0", "1", "1", "42", "0", "0", "", "", "", "", "", "", "", "", "1", "", "0", "", "", "1", "0", "0", "2", "0", "1", "1", "0", "x", "y", "1", "0", "0",
    )

    /** 2 WPA personal, 3 versions (WEP auto, WPA auto, personal auto), 9 the password, 14 AES. */
    fun security(): MutableList<String> = mutableListOf(
        "8", "1", "3", "333", "1", "0", "", "1812", "", "old-passphrase", "1", "0", "0", "1", "3", "0", "0", "0", "6", "0", "1", "", "0",
    )

    private fun array(name: String, values: List<String>) =
        "var $name = new Array(\n${values.joinToString(", ") { if (it.toIntOrNull() != null) it else "\"${it.replace("\"", "\\\"")}\"" }},\n0,0 );"

    /** Two regions: the first allows channels 1 to 11, the second 1 to 13; channel 14 never. */
    private val regions = run {
        val first = List(11) { "0x290C" } + List(41) { "0x0" }
        val second = List(13) { "0x390C" } + List(39) { "0x0" }
        """var Region_chan_table = new Array(
            /*  1 2 3 */
            "id=\"t_a\">A<", ${first.joinToString(", ")},
            "id=\"t_b\">B<", ${second.joinToString(", ")}
            );"""
    }

    fun networkPage(para: List<String>) = """
        <SCRIPT language="javascript" type="text/javascript">
        ${array("wlanPara", para)}
        </SCRIPT>
        <SCRIPT type="text/javascript">
        var chan_index_map = new Array(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 16, 20, 30, 34, 36, 38, 40, 42, 44, 46, 48, 50, 52, 56, 60, 64, 70, 80, 100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140, 149, 153, 157, 161, 165, 184, 188, 192, 196);
        $regions
        </SCRIPT>
    """.trimIndent()

    fun securityPage(para: List<String>) = """
        <SCRIPT language="javascript" type="text/javascript">
        ${array("wlanPara", para)}
        ${array("wlanList", listOf("", "0", "", "0", "", "0", "", "0"))}
        </SCRIPT>
    """.trimIndent()
}

class WifiPagesTest {
    private val router = FakeWrRouter()

    private fun read(page: WrPage) = runBlocking { page.read(router).fields.associateBy { it.key } }

    private fun save(page: WrPage, vararg changes: Pair<String, String>) = runBlocking { page.apply(router, mapOf(*changes)) }

    private val saves get() = router.requests.filter { "Save=Save" in it }

    @Test
    fun `the Wi-Fi page shows the name, radio, visibility, channel and security, never the password`() {
        val fields = read(WifiPage)
        assertEquals("RouterHub-Test", (fields.getValue(WifiPage.SSID) as SettingField.Text).value)
        assertTrue((fields.getValue(WifiPage.ENABLED) as SettingField.Toggle).value)
        assertFalse((fields.getValue(WifiPage.HIDDEN) as SettingField.Toggle).value)
        val channel = fields.getValue(WifiPage.CHANNEL) as SettingField.Choice
        assertEquals("6", channel.value)
        assertEquals(listOf("15") + (1..11).map { "$it" }, channel.options.map { it.value })
        assertEquals(WifiPage.WPA_WPA2, (fields.getValue(WifiPage.SECURITY) as SettingField.Choice).value)
        assertTrue(fields.getValue(WifiPage.PASSWORD) is SettingField.Secret)
        assertFalse(fields.values.toString().contains("old-passphrase"))
    }

    @Test
    fun `the channel list follows the router's region`() {
        router.network[5] = "1"
        val channel = read(WifiPage).getValue(WifiPage.CHANNEL) as SettingField.Choice
        assertEquals((1..13).map { "$it" }, channel.options.drop(1).map { it.value })
    }

    @Test
    fun `renaming sends the whole network form and leaves security alone`() {
        save(WifiPage, WifiPage.SSID to "New name & more", WifiPage.CHANNEL to "11")
        assertEquals("New name & more", router.network[3])
        assertEquals("11", router.network[10])
        assertEquals(1, saves.size)
        val sent = saves.single()
        assertTrue(sent, sent.startsWith("WlanNetworkRpm.htm?ssid1=New%20name%20%26%20more&ssid2=Extra-2&ssid3=Extra-3&ssid4=Extra-4&region=0&band=0&mode=6&chanWidth=1&channel=11&rate=69&ap=1&broadcast=2&"))
        assertTrue(sent.endsWith("&Save=Save"))
        assertFalse(sent.contains("wdsbrl"))
    }

    @Test
    fun `hiding the name and switching the radio off leave their boxes out, as a browser does`() {
        save(WifiPage, WifiPage.HIDDEN to "true")
        assertEquals("0", router.network[9])
        assertFalse(saves.last().contains("broadcast="))
        runBlocking { WifiPage.setEnabled(router, false) }
        assertEquals("0", router.network[8])
        assertFalse(saves.last().contains("ap="))
        runBlocking { WifiPage.setEnabled(router, false) }
        assertEquals(2, saves.size) // already off: nothing sent
    }

    @Test
    fun `a new password goes to the security page only`() {
        save(WifiPage, WifiPage.PASSWORD to "a new passphrase")
        assertEquals("a new passphrase", router.security[9])
        assertEquals("3", router.security[2])
        assertEquals(1, saves.size)
        assertTrue(saves.single().startsWith("WlanSecurityRpm.htm?secType=3&pskSecOpt=3&pskCipher=3&pskSecret=a%20new%20passphrase&"))
    }

    @Test
    fun `switching to WPA2 keeps the current password`() {
        save(WifiPage, WifiPage.SECURITY to WifiPage.WPA2)
        assertEquals("332", router.security[3])
        assertEquals("old-passphrase", router.security[9])
        assertEquals(WifiPage.WPA2, (read(WifiPage).getValue(WifiPage.SECURITY) as SettingField.Choice).value)
    }

    @Test
    fun `bad values and an open network are refused before anything is sent`() {
        assertThrows(InvalidSettingException::class.java) { save(WifiPage, WifiPage.SECURITY to WifiPage.NONE) }
        assertThrows(InvalidSettingException::class.java) { save(WifiPage, WifiPage.PASSWORD to "short") }
        assertThrows(InvalidSettingException::class.java) { save(WifiPage, WifiPage.PASSWORD to "with a ünicode letter") }
        assertThrows(InvalidSettingException::class.java) { save(WifiPage, WifiPage.SSID to "") }
        assertThrows(InvalidSettingException::class.java) { save(WifiPage, WifiPage.SSID to "x".repeat(33)) }
        assertThrows(InvalidSettingException::class.java) { save(WifiPage, WifiPage.CHANNEL to "13") }
        assertTrue(saves.isEmpty())
    }

    @Test
    fun `unknown security set on the router is shown as it is and kept`() {
        router.security[2] = "1" // WEP
        val security = read(WifiPage).getValue(WifiPage.SECURITY) as SettingField.Choice
        assertEquals(WifiPage.OTHER, security.value)
        save(WifiPage, WifiPage.SSID to "Renamed", WifiPage.SECURITY to WifiPage.OTHER)
        assertEquals("1", router.security[2])
        assertTrue(saves.none { it.startsWith("WlanSecurityRpm") })
        assertThrows(InvalidSettingException::class.java) { save(WifiPage, WifiPage.PASSWORD to "a new passphrase") }
    }

    @Test
    fun `the DHCP server page reads and saves every field, empty addresses meaning the router`() {
        val fields = read(DhcpServerPage)
        assertEquals("192.168.0.100", (fields.getValue(DhcpServerPage.START) as SettingField.Text).value)
        assertEquals(120L, (fields.getValue(DhcpServerPage.LEASE) as SettingField.Number).value)
        assertEquals("", (fields.getValue(DhcpServerPage.DNS1) as SettingField.Text).value)

        save(DhcpServerPage, DhcpServerPage.END to "192.168.0.150", DhcpServerPage.LEASE to "60", DhcpServerPage.DNS1 to "1.1.1.1", DhcpServerPage.DOMAIN to "home")
        assertEquals(listOf("1", "192.168.0.100", "192.168.0.150", "60", "0.0.0.0", "home", "1.1.1.1", "0.0.0.0"), router.dhcp.take(8))
        assertEquals(
            "LanDhcpServerRpm.htm?dhcpserver=1&ip1=192.168.0.100&ip2=192.168.0.150&Lease=60&gateway=0.0.0.0&domain=home&dnsserver=1.1.1.1&dnsserver2=0.0.0.0&Save=Save",
            saves.single(),
        )
        save(DhcpServerPage, DhcpServerPage.DNS1 to "")
        assertEquals("0.0.0.0", router.dhcp[6])
    }

    @Test
    fun `DHCP values the router would refuse are caught first, and a refusal is reported`() {
        assertThrows(InvalidSettingException::class.java) { save(DhcpServerPage, DhcpServerPage.START to "192.168.0.200") }
        assertThrows(InvalidSettingException::class.java) { save(DhcpServerPage, DhcpServerPage.END to "192.168.1.150") }
        assertThrows(InvalidSettingException::class.java) { save(DhcpServerPage, DhcpServerPage.LEASE to "3000") }
        assertThrows(InvalidSettingException::class.java) { save(DhcpServerPage, DhcpServerPage.DOMAIN to "bad domain") }
        assertTrue(saves.isEmpty())
        val refused = assertThrows(InvalidSettingException::class.java) {
            save(DhcpServerPage, DhcpServerPage.START to "10.0.0.10", DhcpServerPage.END to "10.0.0.20")
        }
        assertTrue(refused.message!!.contains("26106"))
    }

    @Test
    fun `a DHCP server the router runs by itself is shown, not edited`() {
        router.dhcp[9] = "1"
        assertTrue(read(DhcpServerPage).values.all { it is SettingField.Info })
        assertThrows(InvalidSettingException::class.java) { save(DhcpServerPage, DhcpServerPage.LEASE to "60") }
    }
}
