package dev.routerhub.extension.tplink

import dev.routerhub.api.ColumnKind
import dev.routerhub.api.InvalidSettingException
import dev.routerhub.api.SettingField
import dev.routerhub.api.TableCodec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** System log, log settings, Dynamic DNS, SNMP, TR-069 and the Wi-Fi schedule, against placeholder pages. */
class TpLinkExtrasTest {

    private val context = SettingsContext("admin", "a-strong-passphrase")

    private class FakeRouter(val pages: Map<String, String>) : PageClient {
        val requests = mutableListOf<String>()
        override suspend fun get(path: String): String {
            requests += path
            return pages[path] ?: ""
        }

        val writes get() = requests.filter { it !in pages.keys }
    }

    private fun rows(vararg row: List<String>) = TableCodec.encode(row.toList())

    private fun rejected(key: String, block: () -> Unit): Boolean = try {
        block()
        false
    } catch (e: InvalidSettingException) {
        e.key == key
    }

    // --- system log ---------------------------------------------------------------------------

    private fun logHtml(entries: Int = 2) = buildString {
        append("<table><tr><td class='h1'>System Log</td></tr></table><table border='1'>")
        append("<tr align='center'><td class='hd'>Date/Time</td><td class='hd'>Facility</td><td class='hd'>Severity</td><td class='hd'>Message</td></tr>")
        for (i in 1..entries) {
            append("<tr style='background-color: #ff3300'><td>Thu Jan 01 00:00:%02d 1970</td><td>daemon</td><td>emerg</td><td>message $i</td></tr>".format(i % 60))
        }
        append("</table>")
    }

    @Test
    fun `the log is shown newest first with the message leading`() {
        val table = SystemLogPage.parse(logHtml()).fields.single() as SettingField.Table

        assertTrue(table.readOnly)
        assertEquals("message 2", table.rows[0][0])
        assertEquals("message 1", table.rows[1][0])
        assertEquals(listOf("message 2", "Thu Jan 01 00:00:02 1970", "emerg", "daemon"), table.rows[0])
    }

    @Test
    fun `an empty log and a long log both parse`() {
        assertTrue((SystemLogPage.parse(logHtml(0)).fields.single() as SettingField.Table).rows.isEmpty())

        val long = SystemLogPage.parse(logHtml(450)).fields.single() as SettingField.Table
        assertEquals(300, long.rows.size)
        assertEquals("message 450", long.rows.first()[0])
        assertTrue(long.summary!!.contains("450"))
    }

    // --- log settings -------------------------------------------------------------------------

    private fun logSettingsHtml(mode: Int = 1, enabled: Boolean = true) = """
        <script>
        var addr = '0.0.0.0';
        var port = '514';
        function frmLoad() {
        with ( document.forms[0] ) {
        status[${if (enabled) 1 else 0}].checked = true;
        levelLog.selectedIndex = 7;
        levelDisplay.selectedIndex = 3;
        logMode.selectedIndex = $mode - 1;
        }
        }
        function btnApply() {
        if ( status[0].checked == true ) { confirm('x'); }
        loc += '&sessionKey=1158388940';
        }
        </script>
    """.trimIndent()

    @Test
    fun `reads the log settings`() {
        val page = LogSettingsPage.parse(logSettingsHtml())

        assertEquals(listOf("status", "level", "display", "mode", "server", "port"), page.fields.map { it.key })
        assertTrue((page.fields[0] as SettingField.Toggle).value)
        assertEquals("7", (page.fields[1] as SettingField.Choice).value)
        assertEquals("3", (page.fields[2] as SettingField.Choice).value)
        assertEquals("1", (page.fields[3] as SettingField.Choice).value)
        assertEquals("", (page.fields[4] as SettingField.Text).value)
        assertFalse((LogSettingsPage.parse(logSettingsHtml(enabled = false)).fields[0] as SettingField.Toggle).value)
    }

    @Test
    fun `changing the display level resends the rest`() {
        val request = LogSettingsPage.buildRequest(logSettingsHtml(), mapOf("display" to "5"), context)

        assertEquals("/logconfig.cgi?logStatus=1&logLevel=7&logDisplay=5&logMode=1&logIpAddress=0.0.0.0&logPort=514&sessionKey=1158388940", request)
    }

    @Test
    fun `sending the log to a server needs a valid address and port`() {
        val request = LogSettingsPage.buildRequest(logSettingsHtml(), mapOf("mode" to "2", "server" to "192.168.1.20", "port" to "5514"), context)
        assertEquals("/logconfig.cgi?logStatus=1&logLevel=7&logDisplay=3&logMode=2&logIpAddress=192.168.1.20&logPort=5514&sessionKey=1158388940", request)

        assertTrue(rejected("server") { LogSettingsPage.buildRequest(logSettingsHtml(), mapOf("mode" to "2"), context) })
        assertTrue(rejected("port") { LogSettingsPage.buildRequest(logSettingsHtml(), mapOf("mode" to "3", "server" to "192.168.1.20", "port" to "0"), context) })
        assertTrue(rejected("level") { LogSettingsPage.buildRequest(logSettingsHtml(), mapOf("level" to "9"), context) })
        assertTrue(rejected("mode") { LogSettingsPage.buildRequest(logSettingsHtml(), mapOf("mode" to "7"), context) })
    }

    @Test
    fun `logging can be switched off`() {
        val request = LogSettingsPage.buildRequest(logSettingsHtml(), mapOf("status" to "false"), context)

        assertTrue(request.startsWith("/logconfig.cgi?logStatus=0&"))
    }

    // --- Dynamic DNS --------------------------------------------------------------------------

    private val ddnsView = """
        <script>loc += '&sessionKey=297260644';</script>
        <table><tr><td>
        <table border='1'><tr><td class='hd'>Hostname</td><td class='hd'>Username</td><td class='hd'>Service</td><td class='hd'>Interface</td><td class='hd'>Remove</td></tr>
        <tr><td>home.example.org</td><td>alice</td><td>dyndns</td><td>eth0</td>
            <td><input type='checkbox' name='rml' value='home.example.org'></td></tr>
        </table></td></tr></table>
    """.trimIndent()

    private fun ddnsRouter() = FakeRouter(
        mapOf(
            "/ddnsmngr.cmd" to ddnsView,
            "/ddnsadd.html" to "<script>\nvar interfaceInfo = 'ipoe_eth0/eth0';\nloc += '&sessionKey=953940313';\n</script>",
        ),
    )

    private val existing = listOf("home.example.org", "alice", "dyndns", "eth0", "")

    @Test
    fun `reads accounts without any password and marks the secret column`() {
        val page = runBlocking { DynamicDnsPage.read(ddnsRouter()) }

        val table = page.fields.single() as SettingField.Table
        assertEquals(listOf(existing), table.rows)
        assertEquals(ColumnKind.SECRET, table.columns.last().kind)
        assertEquals(listOf("eth0"), table.columns[3].options.map { it.value })
    }

    @Test
    fun `adding an account sends the page's request with the password encoded`() {
        val router = ddnsRouter()
        val added = listOf("new.example.org", "bob@example.com", "noip", "eth0", "pa&ss")

        runBlocking { DynamicDnsPage.apply(router, mapOf("entries" to rows(existing, added)), context) }

        assertEquals(
            listOf("/ddnsmngr.cmd?action=add&service=2&username=bob@example.com&password=pa%26ss&hostname=new.example.org&iface=eth0&sessionKey=953940313"),
            router.writes,
        )
    }

    @Test
    fun `removing an account joins ids with a comma and a space, and an unchanged list sends nothing`() {
        val removal = ddnsRouter()
        runBlocking { DynamicDnsPage.apply(removal, mapOf("entries" to ""), context) }
        assertEquals(listOf("/ddnsmngr.cmd?action=remove&rmLst=home.example.org, &sessionKey=297260644"), removal.writes)

        val unchanged = ddnsRouter()
        runBlocking { DynamicDnsPage.apply(unchanged, mapOf("entries" to rows(existing)), context) }
        assertTrue(unchanged.writes.isEmpty())
    }

    @Test
    fun `bad accounts are refused before any request`() {
        fun attempt(row: List<String>): Boolean {
            val router = ddnsRouter()
            val refused = rejected("entries") { runBlocking { DynamicDnsPage.apply(router, mapOf("entries" to rows(existing, row)), context) } }
            return refused && router.writes.isEmpty()
        }

        assertTrue(attempt(listOf("a.example.org", "bob", "noip", "eth0", "pw")))              // email required
        assertTrue(attempt(listOf("a.example.org", "bob", "tzo", "eth0", "pw")))
        assertTrue(attempt(listOf("a.example.org", "", "dyndns", "eth0", "pw")))               // no username
        assertTrue(attempt(listOf("a.example.org", "bob", "dyndns", "eth0", "")))              // no password
        assertTrue(attempt(listOf("bad host!", "bob", "dyndns", "eth0", "pw")))
        assertTrue(attempt(listOf("a.example.org", "bob", "dyndns", "ppp9", "pw")))
        assertTrue(attempt(listOf("a.example.org", "bob", "nonsense", "eth0", "pw")))
        assertTrue(attempt(listOf("a.example.org", "bob", "dyndns", "eth0", "café")))     // not ASCII
    }

    // --- SNMP ---------------------------------------------------------------------------------

    private fun snmpHtml(enabled: Boolean = false, read: String = "public", write: String = "private") = """
        <script>
        var sessionKey = '555';
        function frmLoad() {
        with ( document.forms[0] ) {
        status[${if (enabled) 1 else 0}].checked = true;
        rocommunity.value='$read';
        rwcommunity.value='$write';
        sysName.value='router';
        sysContact.value='';
        sysLocation.value='';
        trapIp.value='0.0.0.0';
        }
        }
        function btnApply() {
        if (rocommunity.value == '') { alert('x'); return; }
        if ( status[1].checked == true ) loc += 'snmpStatus=1';
        }
        </script>
    """.trimIndent()

    @Test
    fun `reads SNMP without revealing the community strings`() {
        val page = SnmpPage.parse(snmpHtml(read = "very-secret-ro", write = "very-secret-rw"))

        assertEquals(listOf("enable", "readCommunity", "writeCommunity", "name", "contact", "location", "trap"), page.fields.map { it.key })
        assertFalse(page.toString().contains("very-secret"))
        assertFalse((page.fields[0] as SettingField.Toggle).value)
    }

    @Test
    fun `an agent running on default communities is flagged`() {
        val flagged = SnmpPage.parse(snmpHtml(enabled = true))
        assertEquals("warning", flagged.fields.first().key)

        assertEquals("enable", SnmpPage.parse(snmpHtml(enabled = true, read = "mine", write = "also-mine")).fields.first().key)
        assertEquals("enable", SnmpPage.parse(snmpHtml(enabled = false)).fields.first().key)
    }

    @Test
    fun `turning SNMP on with default communities is refused until they are changed`() {
        assertTrue(rejected("readCommunity") { SnmpPage.buildRequest(snmpHtml(), mapOf("enable" to "true"), context) })
        assertTrue(rejected("writeCommunity") { SnmpPage.buildRequest(snmpHtml(), mapOf("enable" to "true", "readCommunity" to "my-ro"), context) })

        val request = SnmpPage.buildRequest(
            snmpHtml(),
            mapOf("enable" to "true", "readCommunity" to "ro-secret", "writeCommunity" to "rw-secret"),
            context,
        )
        assertEquals(
            "/snmpconfig.cgi?snmpStatus=1&snmpRoCommunity=ro-secret&snmpRwCommunity=rw-secret&snmpSysName=router" +
                "&snmpSysContact=&snmpSysLocation=&snmpTrapIp=0.0.0.0&sessionKey=555",
            request,
        )
    }

    @Test
    fun `turning SNMP off is always allowed and resends what the router holds`() {
        val request = SnmpPage.buildRequest(snmpHtml(enabled = true), mapOf("enable" to "false"), context)

        assertEquals(
            "/snmpconfig.cgi?snmpStatus=0&snmpRoCommunity=public&snmpRwCommunity=private&snmpSysName=router" +
                "&snmpSysContact=&snmpSysLocation=&snmpTrapIp=0.0.0.0&sessionKey=555",
            request,
        )
    }

    @Test
    fun `SNMP values are checked like the page does`() {
        fun failsOn(key: String, vararg changes: Pair<String, String>) =
            rejected(key) { SnmpPage.buildRequest(snmpHtml(enabled = false), changes.toMap(), context) }

        assertTrue(failsOn("name", "name" to "x".repeat(16)))
        assertTrue(failsOn("trap", "trap" to "not-an-ip"))
        assertTrue(failsOn("readCommunity", "readCommunity" to "x".repeat(16)))
        assertFalse(failsOn("trap", "trap" to "192.168.1.9"))
    }

    // --- TR-069 -------------------------------------------------------------------------------

    @Test
    fun `TR-069 shows the harmless parts and none of the credentials`() {
        val html = """
            <script>
            function frmLoad() {
            acsURL.value='http://acs.example.net/cwmp';
            acsUser.value='isp-user-name';
            acsPwd.value='isp-secret-password';
            connReqUser.value='conn-user';
            connReqPwd.value='conn-secret';
            enblInform = '1';
            informInterval.value=3600;
            }
            </script>
        """.trimIndent()

        val page = Tr069Page.parse(html)

        assertTrue(page.fields.all { it is SettingField.Info })
        assertEquals("On", (page.fields[0] as SettingField.Info).value)
        assertEquals("3600 s", (page.fields[1] as SettingField.Info).value)
        assertEquals("http://acs.example.net/cwmp", (page.fields[2] as SettingField.Info).value)
        val everything = page.toString()
        assertFalse(everything.contains("isp-user-name") || everything.contains("isp-secret") || everything.contains("conn-"))
    }

    @Test
    fun `TR-069 without a provider says so`() {
        val page = Tr069Page.parse("<script>acsURL.value='';\nenblInform = '0';\ninformInterval.value=86400;</script>")

        assertEquals("Off", (page.fields[0] as SettingField.Info).value)
        assertEquals("None configured", (page.fields[2] as SettingField.Info).value)
    }

    // --- Wi-Fi schedule -----------------------------------------------------------------------

    private fun scheduleHtml(enabled: Boolean = false, timeSet: Boolean = true, masks: Map<String, Int> = emptyMap()) = buildString {
        append("<script>\nvar enblSch = '${if (enabled) 1 else 0}';\nvar isUsrCtrl = '0';\nvar isTimeSet = '${if (timeSet) 1 else 0}';\n")
        for (day in listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")) {
            for (half in listOf("Am", "Pm")) append("var $day$half = '${masks[day + half] ?: 0}';\n")
        }
        append("var sessionKey = '777';\n</script>")
    }

    @Test
    fun `the schedule page counts the selected cells and never shows a grid`() {
        val page = WifiSchedulePage.parse(scheduleHtml(masks = mapOf("monAm" to 255, "satPm" to 1)))

        assertEquals("9 of 336 half-hour cells selected", (page.fields[1] as SettingField.Info).value)
        assertEquals("Set", (page.fields[2] as SettingField.Info).value)
    }

    @Test
    fun `enabling a usable schedule builds the page's request in its order`() {
        val request = WifiSchedulePage.buildRequest(
            scheduleHtml(masks = mapOf("monAm" to 255, "satPm" to 1)),
            mapOf("enable" to "true"),
            context,
        )

        assertEquals(
            "/wlschedule.wl?wlEnblSch=1&wlIsTimeSet=1&wlMonAm=255&wlMonPm=0&wlTueAm=0&wlTuePm=0&wlWedAm=0&wlWedPm=0" +
                "&wlThuAm=0&wlThuPm=0&wlFriAm=0&wlFriPm=0&wlSatAm=0&wlSatPm=1&wlSunAm=0&wlSunPm=0&wlIsUsrCtrl=0&sessionKey=777",
            request,
        )
    }

    @Test
    fun `a schedule that could keep Wi-Fi off for good is refused`() {
        assertTrue(rejected("enable") { WifiSchedulePage.buildRequest(scheduleHtml(timeSet = false, masks = mapOf("monAm" to 1)), mapOf("enable" to "true"), context) })
        assertTrue(rejected("enable") { WifiSchedulePage.buildRequest(scheduleHtml(masks = emptyMap()), mapOf("enable" to "true"), context) })
    }

    @Test
    fun `an enabled schedule can always be switched off`() {
        val request = WifiSchedulePage.buildRequest(scheduleHtml(enabled = true, timeSet = false), mapOf("enable" to "false"), context)

        assertTrue(request.startsWith("/wlschedule.wl?wlEnblSch=0&wlIsTimeSet=0&"))
    }

    @Test
    fun `every page has a unique id`() {
        val ids = TpLinkSettings.specs.map { it.info.id }
        assertEquals(ids.size, ids.toSet().size)
        assertNull(null)
    }
}
