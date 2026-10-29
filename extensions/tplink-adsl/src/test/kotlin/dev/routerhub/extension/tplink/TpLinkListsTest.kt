package dev.routerhub.extension.tplink

import dev.routerhub.api.InvalidSettingException
import dev.routerhub.api.SettingField
import dev.routerhub.api.TableCodec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs each list page's real `read` / `apply` against a fake router that serves placeholder pages
 * and records every request. Expected URLs follow what the pages' own scripts assemble; the
 * separators in removal lists differ per page on purpose (`,` / `, ` / space).
 */
class TpLinkListsTest {

    private val context = SettingsContext("admin", "router-pw")

    private class FakeRouter(val pages: Map<String, String>) : PageClient {
        val requests = mutableListOf<String>()
        override suspend fun get(path: String): String {
            requests += path
            return pages[path] ?: ""
        }

        /** Requests that were not page loads: the ones that would change something. */
        val writes get() = requests.filter { it !in pages.keys }
    }

    private fun rows(vararg row: List<String>) = TableCodec.encode(row.toList())

    private fun <T> run(block: suspend () -> T): T = runBlocking { block() }

    private fun rejected(key: String, block: suspend () -> Unit): Boolean = try {
        runBlocking { block() }
        false
    } catch (e: InvalidSettingException) {
        e.key == key
    }

    // --- shared mechanics ---------------------------------------------------------------------

    @Test
    fun `finds data rows by their checkbox even inside nested layout tables`() {
        val html = """
            <table><tr><td>
              <table>
                <tr><td class='hd'>Name</td><td class='hd'>Remove</td></tr>
                <tr><td>first</td><td><input type='checkbox' name='rml' value='id-1'></td></tr>
                <tr><td>sec&nbsp;ond</td><td><input type='checkbox' name='rml' value='id-2'></td></tr>
                <tr><td>other list</td><td><input type='checkbox' name='rmlmac' value='x'></td></tr>
              </table>
            </td></tr></table>
        """.trimIndent()

        val found = ListPages.checkboxRows(html)

        assertEquals(listOf("id-1", "id-2"), found.map { it.removeId })
        assertEquals(listOf("first"), found[0].cells)
        assertEquals(listOf("sec ond"), found[1].cells)
        assertEquals(listOf("x"), ListPages.checkboxRows(html, "rmlmac").map { it.removeId })
    }

    @Test
    fun `diff matches unchanged rows and counts duplicates`() {
        val current = listOf(ListRow(listOf("a"), "1"), ListRow(listOf("b"), "2"), ListRow(listOf("b"), "3"))

        val change = ListPages.diff(current, listOf(listOf("b"), listOf("c"), listOf("a")))

        assertEquals(listOf("3"), change.removeIds)
        assertEquals(listOf(listOf("c")), change.toAdd)
    }

    // --- port forwarding ----------------------------------------------------------------------

    private val forwardingView = """
        <script>
        function removeClick(rml) { loc += '&sessionKey=111'; }
        </script>
        <table><tr><td>
        <table border='1'>
        <tr><td class='hd'>Server Name</td><td class='hd'>External Port Start</td><td class='hd'>Remove</td></tr>
        <tr><td>Web</td><td>8080</td><td>8080</td><td>TCP</td><td>80</td><td>80</td><td>192.168.1.50</td><td>eth0</td>
            <td align='center'><input type='checkbox' name='rml' value='Web|8080|8080|1'></td></tr>
        <tr><td>Cams</td><td>9000</td><td>9010</td><td>TCP/UDP</td><td>9000</td><td>9010</td><td>192.168.1.60</td><td>eth0</td>
            <td align='center'><input type='checkbox' name='rml' value='Cams|9000|9010|0'></td></tr>
        </table></td></tr></table>
    """.trimIndent()

    private val forwardingAdd = """
        <script>
        var interfaceInfo = 'ipoe_eth0/eth0';
        loc += '&sessionKey=222';
        </script>
    """.trimIndent()

    private fun forwardingRouter() = FakeRouter(
        mapOf("/scvrtsrv.cmd?action=view" to forwardingView, "/scvrtsrv.html" to forwardingAdd),
    )

    private val web = listOf("Web", "8080", "8080", "1", "80", "80", "192.168.1.50", "eth0")
    private val cams = listOf("Cams", "9000", "9010", "0", "9000", "9010", "192.168.1.60", "eth0")

    @Test
    fun `reads port forwarding rules and normalises protocol and interface`() {
        val page = run { PortForwardingPage.read(forwardingRouter()) }

        val table = page.fields.single() as SettingField.Table
        assertEquals(listOf(web, cams), table.rows)
        assertEquals(listOf("eth0"), table.columns.last().options.map { it.value })
        assertEquals(80, table.maxRows)
    }

    @Test
    fun `adding a rule sends the page's own add request and nothing else`() {
        val router = forwardingRouter()
        val game = listOf("Game server", "27015", "27015", "2", "27015", "27015", "192.168.1.70", "eth0")

        run { PortForwardingPage.apply(router, mapOf("rules" to rows(web, cams, game)), context) }

        assertEquals(
            listOf(
                "/scvrtsrv.cmd?action=add&srvName=Game%20server&dstWanIf=eth0&srvAddr=192.168.1.70" +
                    "&proto=2,&eStart=27015,&eEnd=27015,&iStart=27015,&iEnd=27015,&sessionKey=222",
            ),
            router.writes,
        )
    }

    @Test
    fun `removing a rule sends its checkbox value followed by a comma`() {
        val router = forwardingRouter()

        run { PortForwardingPage.apply(router, mapOf("rules" to rows(web)), context) }

        assertEquals(listOf("/scvrtsrv.cmd?action=remove&rmLst=Cams|9000|9010|0,&sessionKey=111"), router.writes)
    }

    @Test
    fun `an unchanged list sends nothing`() {
        val router = forwardingRouter()

        run { PortForwardingPage.apply(router, mapOf("rules" to rows(web, cams)), context) }

        assertTrue(router.writes.isEmpty())
    }

    @Test
    fun `bad port forwarding rows are refused before any request is sent`() {
        fun attempt(row: List<String>): Boolean {
            val router = forwardingRouter()
            val refused = rejected("rules") { PortForwardingPage.apply(router, mapOf("rules" to rows(web, row)), context) }
            return refused && router.writes.isEmpty()
        }

        assertTrue(attempt(listOf("", "1", "1", "1", "1", "1", "192.168.1.9", "eth0")))          // no name
        assertTrue(attempt(listOf("x", "0", "1", "1", "1", "1", "192.168.1.9", "eth0")))          // port 0
        assertTrue(attempt(listOf("x", "70000", "70000", "1", "1", "1", "192.168.1.9", "eth0")))   // port too big
        assertTrue(attempt(listOf("x", "20", "10", "1", "1", "1", "192.168.1.9", "eth0")))         // backwards range
        assertTrue(attempt(listOf("x", "1", "1", "1", "1", "1", "999.1.1.1", "eth0")))             // bad address
        assertTrue(attempt(listOf("x", "1", "1", "5", "1", "1", "192.168.1.9", "eth0")))           // bad protocol
        assertTrue(attempt(listOf("x", "1", "1", "1", "1", "1", "192.168.1.9", "ppp9")))           // unknown interface
        assertTrue(attempt(listOf("a,b", "1", "1", "1", "1", "1", "192.168.1.9", "eth0")))         // comma in name
    }

    @Test
    fun `ports the router itself uses are refused instead of silently rewritten`() {
        for ((port, hint) in listOf("80" to "8080", "23" to "2323")) {
            val router = forwardingRouter()
            val error = runCatching {
                run { PortForwardingPage.apply(router, mapOf("rules" to rows(web, listOf("x", port, port, "1", port, port, "192.168.1.9", "eth0"))), context) }
            }.exceptionOrNull() as? InvalidSettingException
            assertTrue(error!!.message!!.contains(hint))
            assertTrue(router.writes.isEmpty())
        }
    }

    // --- wireless MAC filter ------------------------------------------------------------------

    private fun macView(mode: String = "allow", wirelessOn: Boolean = true) = """
        <script>
        function modeClick() {
        var loc = 'wlmacflt.cmd?action=save';
        if ( '${if (wirelessOn) "1" else "0"}' == '0' ) {
        alert('Cannot change MAC filter mode since wireless is currently disabled.');
        return;
        }
        loc += '&sessionKey=333';
        }
        function frmLoad() {
        if ( '$mode' == 'disabled' ) wlFltMacMode[0].checked = true;
        else if ( '$mode' == 'allow' ) wlFltMacMode[1].checked = true;
        else wlFltMacMode[2].checked = true;
        }
        </script>
        <table><tr><td>
        <table border='1'>
        <tr><td class='hd'>MAC Address</td><td class='hd'>Remove</td></tr>
        <tr><td>AA:BB:CC:DD:EE:01</td><td><input type='checkbox' name='rml' value='AA:BB:CC:DD:EE:01'></td></tr>
        </table></td></tr></table>
    """.trimIndent()

    private fun macRouter(mode: String = "allow", wirelessOn: Boolean = true) = FakeRouter(
        mapOf(
            "/wlmacflt.cmd?action=view" to macView(mode, wirelessOn),
            "/wlmacflt.html" to "<script>loc += '&sessionKey=444';</script>",
        ),
    )

    @Test
    fun `reads the MAC filter mode and devices`() {
        val page = WirelessMacFilterPage.parse(macView("deny"))

        assertEquals("deny", (page.fields[0] as SettingField.Choice).value)
        assertEquals(listOf(listOf("aa:bb:cc:dd:ee:01")), (page.fields[1] as SettingField.Table).rows)
        assertTrue(WirelessMacFilterPage.parse(macView(wirelessOn = false)).fields.all { it is SettingField.Info })
    }

    @Test
    fun `adding a device and switching mode follow the page's requests in a safe order`() {
        val router = macRouter()

        run {
            WirelessMacFilterPage.apply(
                router,
                mapOf("mode" to "deny", "devices" to rows(listOf("aa:bb:cc:dd:ee:01"), listOf("AA:BB:CC:DD:EE:02"))),
                context,
            )
        }

        assertEquals(
            listOf(
                "/wlmacflt.cmd?action=add&wlFltMacAddr=aa:bb:cc:dd:ee:02&wlSyncNvram=1&sessionKey=444",
                "/wlmacflt.cmd?action=save&wlFltMacMode=deny&sessionKey=333",
            ),
            router.writes,
        )
    }

    @Test
    fun `removing a device sends its value followed by a comma and a space`() {
        val router = macRouter(mode = "deny")

        run { WirelessMacFilterPage.apply(router, mapOf("devices" to rows(listOf("aa:bb:cc:dd:ee:09"))), context) }

        assertEquals(
            listOf(
                "/wlmacflt.cmd?action=add&wlFltMacAddr=aa:bb:cc:dd:ee:09&wlSyncNvram=1&sessionKey=444",
                "/wlmacflt.cmd?action=remove&rmLst=AA:BB:CC:DD:EE:01, &sessionKey=333",
            ),
            router.writes,
        )
    }

    @Test
    fun `allow mode with nobody listed would lock everyone out, so it is refused`() {
        val router = macRouter(mode = "disabled")

        assertTrue(rejected("mode") { WirelessMacFilterPage.apply(router, mapOf("mode" to "allow", "devices" to ""), context) })
        assertTrue(router.writes.isEmpty())
    }

    @Test
    fun `malformed MAC addresses are refused`() {
        for (bad in listOf("aa:bb:cc:dd:ee", "gg:bb:cc:dd:ee:ff", "00:00:00:00:00:00", "aabbccddeeff")) {
            val router = macRouter()
            assertTrue(bad, rejected("devices") { WirelessMacFilterPage.apply(router, mapOf("devices" to rows(listOf(bad))), context) })
            assertTrue(router.writes.isEmpty())
        }
    }

    // --- website filter -----------------------------------------------------------------------

    private val urlView = """
        <script>
        function frmLoad() {
        if ( 'Disable' == 'Disable' ) listtype[0].checked = true;
        }
        function modeClick() { loc += '&sessionKey=555'; }
        </script>
        <table><tr><td>
        <table><tr><td class='hd'>MAC Address</td><td class='hd'>Remove</td></tr>
        <tr><td>11:22:33:44:55:66</td><td><input type='checkbox' name='rmlmac' value='11:22:33:44:55:66'></td></tr></table>
        <table><tr><td class='hd'>Address</td><td class='hd'>Remove</td></tr>
        <tr><td>old.example.org</td><td><input type='checkbox' name='rml' value='old.example.org'></td></tr></table>
        </td></tr></table>
    """.trimIndent()

    private fun urlRouter() = FakeRouter(
        mapOf("/urlfilter.cmd?action=view" to urlView, "/url_add.html" to "<script>loc += '&sessionKey=9919681';</script>"),
    )

    @Test
    fun `reads the filter mode and both lists`() {
        val page = UrlFilterPage.parse(urlView)

        assertEquals("Disable", (page.fields[0] as SettingField.Choice).value)
        assertEquals(listOf(listOf("11:22:33:44:55:66")), (page.fields[1] as SettingField.Table).rows)
        assertEquals(listOf(listOf("old.example.org")), (page.fields[2] as SettingField.Table).rows)
    }

    @Test
    fun `adding a site, switching mode and removing one use each request's own separator`() {
        val router = urlRouter()

        run {
            UrlFilterPage.apply(
                router,
                mapOf("mode" to "Exclude", "urls" to rows(listOf("example.com"))),
                context,
            )
        }

        // The router refuses to ADD a website while the filter is off (found on real hardware), so the
        // filter goes on first; the chosen mode is already in force, so there is no second save.
        assertEquals(
            listOf(
                "/urlfilter.cmd?action=save&listtype=Exclude&sessionKey=555",
                "/urlfilter.cmd?action=set_url&TodUrlAdd=example.com&port_num=80&sessionKey=9919681",
                "/urlfilter.cmd?action=remove_url&rmLst=old.example.org &sessionKey=555",
            ),
            router.writes,
        )
    }

    @Test
    fun `adding a site while the filter is off goes through a neutral mode and ends in the chosen one`() {
        val router = urlRouter()

        run { UrlFilterPage.apply(router, mapOf("mode" to "Include", "urls" to rows(listOf("old.example.org"), listOf("new.example.org"))), context) }

        assertEquals(
            listOf(
                "/urlfilter.cmd?action=save&listtype=Exclude&sessionKey=555",
                "/urlfilter.cmd?action=set_url&TodUrlAdd=new.example.org&port_num=80&sessionKey=9919681",
                "/urlfilter.cmd?action=save&listtype=Include&sessionKey=555",
            ),
            router.writes,
        )
    }

    @Test
    fun `adding a site while the filter stays off is refused with an explanation`() {
        val router = urlRouter()

        assertTrue(rejected("urls") { run { UrlFilterPage.apply(router, mapOf("urls" to rows(listOf("old.example.org"), listOf("new.example.org"))), context) } })
        assertTrue(router.writes.isEmpty())
    }

    // --- router-reported errors ---------------------------------------------------------------

    @Test
    fun `a refusal page from the router becomes an error carrying the router's reason`() {
        val refusal = "<html><body><table><tr><td class='h1'>Url Filter Entry Add Error</td></tr><tr><td class='blue'></td></tr>" +
            "<tr><td>\nConfigure url filter: url = example.org and port number = 80 failed</td></tr></table></body></html>"
        val client = CheckedPageClient(FakeRouter(mapOf("/urlfilter.cmd?action=set_url&password=hunter2" to refusal)))

        val error = runCatching { run { client.get("/urlfilter.cmd?action=set_url&password=hunter2") } }.exceptionOrNull()

        assertTrue(error is dev.routerhub.api.CommandFailedException)
        error as dev.routerhub.api.CommandFailedException
        assertTrue(error.output.contains("url = example.org"))
        assertEquals("/urlfilter.cmd", error.command)
        assertFalse("the query, which may hold a password, must not leak", error.toString().contains("hunter2"))
    }

    @Test
    fun `ordinary pages pass through untouched`() {
        val page = "<table><tr><td class='h1'>Wireless -- Basic</td></tr></table>"
        val client = CheckedPageClient(FakeRouter(mapOf("/wlcfg.html" to page)))

        assertEquals(page, run { client.get("/wlcfg.html") })
    }

    @Test
    fun `adding and removing a controlled device`() {
        val router = urlRouter()

        run { UrlFilterPage.apply(router, mapOf("devices" to rows(listOf("aa:aa:aa:aa:aa:aa"))), context) }

        assertEquals(
            listOf(
                "/urlfilter.cmd?action=add_mac&macaddr=aa:aa:aa:aa:aa:aa&sessionKey=555",
                "/urlfilter.cmd?action=remove_mac&rmLst=11:22:33:44:55:66 &sessionKey=555",
            ),
            router.writes,
        )
    }

    @Test
    fun `site names with characters the page would not encode are refused`() {
        for (bad in listOf("a&b.com", "x.com/?q=1", "sp ace.com", "a".repeat(129), "")) {
            val router = urlRouter()
            assertTrue("[$bad]", rejected("urls") { UrlFilterPage.apply(router, mapOf("urls" to rows(listOf("old.example.org"), listOf(bad))), context) })
            assertTrue(router.writes.isEmpty())
        }
    }

    @Test
    fun `allow-only mode with no sites would block everything and is refused`() {
        val router = urlRouter()

        assertTrue(rejected("mode") { UrlFilterPage.apply(router, mapOf("mode" to "Include", "urls" to ""), context) })
        assertTrue(router.writes.isEmpty())
    }

    @Test
    fun `at most four devices can be controlled`() {
        val router = urlRouter()
        val five = (1..5).map { listOf("aa:aa:aa:aa:aa:0$it") }.toTypedArray()

        assertTrue(rejected("devices") { UrlFilterPage.apply(router, mapOf("devices" to rows(*five)), context) })
    }

    // --- static routes ------------------------------------------------------------------------

    private val routesView = """
        <script>
        var sessionKey = '666';
        var MAX_ENTRY = 32;
        var entryList = [
        [4,1,'192.168.5.0/24','192.168.1.2','br0',0,1],
        [6,2,'fd00::/64','fe80::1','',-1,0]
        ];
        if (entryList === undefined) {
        var sessionKey = '123456789';
        var entryList = [
        [4,1,'1.2.3.0/24','1.2.3.1','br0',0,1]
        ];
        }
        </script>
    """.trimIndent()

    private val routesAdd = """
        <script>
        var intf = 'ipoe_eth0/eth0';
        loc += '&sessionKey=777';
        </script>
    """.trimIndent()

    private fun routesRouter() = FakeRouter(mapOf("/rtroutecfg.cmd?action=viewcfg" to routesView, "/routeadd.html" to routesAdd))

    private val local = listOf("192.168.5.0/24", "192.168.1.2", "br0", "0", "Enabled")
    private val v6 = listOf("fd00::/64", "fe80::1", "", "", "Disabled")

    @Test
    fun `reads routes from the first entry list only`() {
        val page = run { StaticRoutesPage.read(routesRouter()) }

        val table = page.fields.single() as SettingField.Table
        assertEquals(listOf(local, v6), table.rows)
        assertEquals(listOf("", "eth0", "br0"), table.columns[2].options.map { it.value })
    }

    @Test
    fun `adding a route builds the page's request`() {
        val router = routesRouter()

        run {
            StaticRoutesPage.apply(router, mapOf("routes" to rows(local, v6, listOf("10.9.0.0/16", "192.168.1.9", "eth0", "5", ""))), context)
        }

        assertEquals(
            listOf("/rtroutecfg.cmd?action=add&ipver=4&dstAddr=10.9.0.0/16&dstWanIf=eth0&dstGtwy=192.168.1.9&metric=5&sessionKey=777"),
            router.writes,
        )
    }

    @Test
    fun `optional parts of a route are left out and removal uses version and id`() {
        val router = routesRouter()

        run { StaticRoutesPage.apply(router, mapOf("routes" to rows(v6, listOf("10.8.0.0/24", "192.168.1.8", "", "", ""))), context) }

        assertEquals(
            listOf(
                "/rtroutecfg.cmd?action=remove&rmLst=4|1&sessionKey=666",
                "/rtroutecfg.cmd?action=add&ipver=4&dstAddr=10.8.0.0/24&dstGtwy=192.168.1.8&sessionKey=777",
            ),
            router.writes,
        )
    }

    @Test
    fun `bad routes are refused`() {
        for (bad in listOf(
            listOf("10.9.0.0", "192.168.1.9", "", "", ""),                // no prefix
            listOf("0.0.0.0/0", "192.168.1.9", "", "", ""),              // default route
            listOf("127.0.0.0/8", "192.168.1.9", "", "", ""),            // loopback
            listOf("224.0.0.0/4", "192.168.1.9", "", "", ""),            // multicast
            listOf("10.9.0.0/33", "192.168.1.9", "", "", ""),            // prefix too long
            listOf("10.9.0.0/16", "", "", "", ""),                       // gateway required
            listOf("10.9.0.0/16", "192.168.1.9/24", "", "", ""),         // gateway with prefix
            listOf("10.9.0.0/16", "192.168.1.9", "ppp7", "", ""),        // unknown interface
            listOf("10.9.0.0/16", "192.168.1.9", "", "300", ""),         // metric out of range
        )) {
            val router = routesRouter()
            assertTrue(bad.toString(), rejected("routes") { StaticRoutesPage.apply(router, mapOf("routes" to rows(local, v6, bad)), context) })
            assertTrue(router.writes.isEmpty())
        }
    }

    // --- DHCP ---------------------------------------------------------------------------------

    private fun lanHtml(dhcp: String = "1", ipExt: String = "0", lan2: String = "0") = """
        <script>
        var sessionKey = '888';
        var dhcpStart = '192.168.1.100';
        var dhcpEnd = '192.168.1.200';
        var dhcpLease = '24';
        var enblLanFirewall = '0';
        var ipExt = '$ipExt';
        var dhcpEnbl = '$dhcp';
        var enblIgmpSnp = '1';
        var enblIgmpMode = '1';
        var lanIpAddr = '192.168.1.1';
        var lanIpMask = '255.255.255.0';
        function frmLoad() {
        if ('$lan2' == '1') {
        enblLan2.checked = true;
        hideLan2Info(0);
        } else {
        enblLan2.checked = false;
        }
        }
        </script>
    """.trimIndent()

    @Test
    fun `reads the DHCP server settings and shows the router address read-only`() {
        val page = DhcpPage.parse(lanHtml())

        assertEquals(listOf("lan", "mode", "start", "end", "lease"), page.fields.map { it.key })
        assertEquals("192.168.1.1 / 255.255.255.0", (page.fields[0] as SettingField.Info).value)
        assertEquals(24L, (page.fields[4] as SettingField.Number).value)
    }

    @Test
    fun `changing the range and lease resends everything else untouched`() {
        val request = DhcpPage.buildRequest(lanHtml(), mapOf("end" to "192.168.1.150", "lease" to "12"), context)

        assertEquals(
            "/lancfg2.cgi?ethIpAddress=192.168.1.1&ethSubnetMask=255.255.255.0&enblLanFirewall=0&enblIgmpSnp=1&enblIgmpMode=1" +
                "&dhcpEthStart=192.168.1.100&dhcpEthEnd=192.168.1.150&dhcpLeasedTime=12&enblDhcpSrv=1&enblLan2=0&sessionKey=888",
            request,
        )
    }

    @Test
    fun `turning the server off sends only the off flag for DHCP`() {
        val request = DhcpPage.buildRequest(lanHtml(), mapOf("mode" to "0"), context)

        assertTrue(request.endsWith("&enblDhcpSrv=0&enblLan2=0&sessionKey=888"))
        assertFalse(request.contains("dhcpEthStart"))
    }

    @Test
    fun `DHCP ranges are checked like the page does`() {
        fun failsOn(key: String, vararg changes: Pair<String, String>) = try {
            DhcpPage.buildRequest(lanHtml(), changes.toMap(), context)
            false
        } catch (e: InvalidSettingException) {
            e.key == key
        }

        assertTrue(failsOn("end", "end" to "192.168.1.50"))                       // before the start
        assertTrue(failsOn("end", "end" to "192.168.2.50"))                       // other subnet
        assertTrue(failsOn("start", "start" to "192.168.1.1"))                    // router's own address
        assertTrue(failsOn("start", "start" to "not-an-ip"))
        assertTrue(failsOn("lease", "lease" to "49"))
        assertTrue(failsOn("lease", "lease" to "0"))
    }

    @Test
    fun `relay, IP extension and a second LAN stay view-only`() {
        for (html in listOf(lanHtml(dhcp = "2"), lanHtml(ipExt = "1"), lanHtml(lan2 = "1"))) {
            assertTrue(DhcpPage.parse(html).fields.all { it is SettingField.Info })
            assertTrue(runCatching { DhcpPage.buildRequest(html, mapOf("lease" to "12"), context) }.exceptionOrNull() is InvalidSettingException)
        }
    }

    @Test
    fun `every page still has a unique id`() {
        val ids = TpLinkSettings.specs.map { it.info.id }
        assertEquals(ids.size, ids.toSet().size)
    }
}
