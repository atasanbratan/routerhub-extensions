package dev.routerhub.extension.tplink

import dev.routerhub.api.InvalidSettingException
import dev.routerhub.api.SettingField
import dev.routerhub.api.TableCodec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** DHCP reservations, remote access and date/time, against placeholder pages shaped like the router's. */
class TpLinkSystemTest {

    private val strong = SettingsContext("admin", "a-strong-passphrase")
    private val weak = SettingsContext("admin", "admin")

    private class FakeRouter(val pages: Map<String, String>) : PageClient {
        val requests = mutableListOf<String>()
        override suspend fun get(path: String): String {
            requests += path
            return pages[path] ?: ""
        }

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

    // --- DHCP reservations --------------------------------------------------------------------

    private val lanHtml = """
        <script>
        var sessionKey = '111';
        var MAX_ENTRY = 4;
        var lanIpAddr = '192.168.1.1';
        var lanIpMask = '255.255.255.0';
        var info = 'AA:BB:CC:DD:EE:01/192.168.1.50/1|aa:bb:cc:dd:ee:02/192.168.1.51/0';
        </script>
    """.trimIndent()

    private fun lanRouter() = FakeRouter(
        mapOf("/lancfg2.html" to lanHtml, "/StaticIpAdd.html" to "<script>loc += '&sessionKey=222';</script>"),
    )

    private val first = listOf("aa:bb:cc:dd:ee:01", "192.168.1.50", "Enabled")
    private val second = listOf("aa:bb:cc:dd:ee:02", "192.168.1.51", "Disabled")

    @Test
    fun `reads reservations, lower-casing the MAC and showing the status`() {
        val table = DhcpReservationsPage.parse(lanHtml).fields.single() as SettingField.Table

        assertEquals(listOf(first, second), table.rows)
        assertEquals(4, table.maxRows)
    }

    @Test
    fun `an empty reservation list parses`() {
        val table = DhcpReservationsPage.parse(lanHtml.replace(Regex("var info = '[^']*'"), "var info = ''")).fields.single() as SettingField.Table

        assertTrue(table.rows.isEmpty())
    }

    @Test
    fun `adding a reservation sends the page's request`() {
        val router = lanRouter()

        run { DhcpReservationsPage.apply(router, mapOf("reservations" to rows(first, second, listOf("AA:BB:CC:DD:EE:03", "192.168.1.52", ""))), strong) }

        assertEquals(
            listOf("/dhcpdstaticlease.cmd?action=add&mac=aa:bb:cc:dd:ee:03&static_ip=192.168.1.52&sessionKey=222"),
            router.writes,
        )
    }

    @Test
    fun `removing uses the router's own entry id, and changing an address removes before it adds`() {
        val router = lanRouter()

        run {
            DhcpReservationsPage.apply(
                router,
                mapOf("reservations" to rows(listOf("aa:bb:cc:dd:ee:01", "192.168.1.60", ""))),
                strong,
            )
        }

        assertEquals(
            listOf(
                "/dhcpdstaticlease.cmd?action=remove&rmLst=AA:BB:CC:DD:EE:01, aa:bb:cc:dd:ee:02&sessionKey=111",
                "/dhcpdstaticlease.cmd?action=add&mac=aa:bb:cc:dd:ee:01&static_ip=192.168.1.60&sessionKey=222",
            ),
            router.writes,
        )
    }

    @Test
    fun `bad reservations are refused before any request`() {
        fun attempt(vararg wanted: List<String>): Boolean {
            val router = lanRouter()
            val refused = rejected("reservations") { DhcpReservationsPage.apply(router, mapOf("reservations" to rows(*wanted)), strong) }
            return refused && router.writes.isEmpty()
        }

        assertTrue(attempt(first, second, listOf("zz:bb:cc:dd:ee:09", "192.168.1.60", "")))     // bad MAC
        assertTrue(attempt(first, second, listOf("aa:bb:cc:dd:ee:09", "192.168.2.60", "")))     // other network
        assertTrue(attempt(first, second, listOf("aa:bb:cc:dd:ee:09", "192.168.1.1", "")))      // the router itself
        assertTrue(attempt(first, second, listOf("aa:bb:cc:dd:ee:09", "not-an-ip", "")))
        assertTrue(attempt(first, second, listOf("aa:bb:cc:dd:ee:01", "192.168.1.70", "")))     // MAC reserved twice
        assertTrue(attempt(first, second, listOf("aa:bb:cc:dd:ee:09", "192.168.1.50", "")))     // address reserved twice
        assertTrue(attempt(first, second, listOf("aa:bb:cc:dd:ee:03", "192.168.1.52", ""), listOf("aa:bb:cc:dd:ee:04", "192.168.1.53", ""), listOf("aa:bb:cc:dd:ee:05", "192.168.1.54", ""))) // over the limit of 4
    }

    // --- remote access ------------------------------------------------------------------------

    private fun remoteHtml(bits: String = "111", blocked: String = "00") = """
        <script>
        var sessionKey = '627028247';
        var entryList = [
        	['eth0','$bits', '$blocked']
        ];
        if (entryList === undefined) {
        var sessionKey = '123456789';
        var entryList = [
        ['eth0.1','110','10'],
        ['ppp0.1','010','01']
        ];
        }
        </script>
    """.trimIndent()

    private fun remoteRouter(bits: String = "111", blocked: String = "00") = FakeRouter(mapOf("/remote.cmd?action=view" to remoteHtml(bits, blocked)))

    @Test
    fun `shows what the WAN side can reach and warns when web or telnet are open`() {
        val page = RemoteAccessPage.parse(remoteHtml("111"))

        assertEquals(listOf("exposure", "web:eth0", "telnet:eth0", "icmp:eth0"), page.fields.map { it.key })
        val exposure = page.fields[0] as SettingField.Info
        assertTrue(exposure.value.contains("web admin page") && exposure.value.contains("Telnet"))
        assertTrue(page.fields.drop(1).all { (it as SettingField.Toggle).value })
    }

    @Test
    fun `a closed router shows no exposure warning`() {
        val page = RemoteAccessPage.parse(remoteHtml("001"))

        assertEquals(listOf("web:eth0", "telnet:eth0", "icmp:eth0"), page.fields.map { it.key })
        assertTrue((page.fields[2] as SettingField.Toggle).value)
    }

    @Test
    fun `a port forward that blocks web or telnet is explained`() {
        val page = RemoteAccessPage.parse(remoteHtml("111", blocked = "10"))

        assertTrue((page.fields[1] as SettingField.Toggle).summary!!.contains("port 80"))
        assertEquals(null, (page.fields[2] as SettingField.Toggle).summary)
    }

    @Test
    fun `closing web and telnet sends the page's request, even with a default password`() {
        val router = remoteRouter("111")

        run { RemoteAccessPage.apply(router, mapOf("web:eth0" to "false", "telnet:eth0" to "false"), weak) }

        assertEquals(listOf("/remote.cmd?action=set&selIndex=0&ifname=eth0&access=001&sessionKey=627028247"), router.writes)
    }

    @Test
    fun `opening web or telnet is refused while the router password is a default one`() {
        val router = remoteRouter("001")

        assertTrue(rejected("web:eth0") { RemoteAccessPage.apply(router, mapOf("web:eth0" to "true"), weak) })
        assertTrue(rejected("telnet:eth0") { RemoteAccessPage.apply(router, mapOf("telnet:eth0" to "true"), weak) })
        assertTrue(router.writes.isEmpty())

        run { RemoteAccessPage.apply(router, mapOf("web:eth0" to "true"), strong) }
        assertEquals(listOf("/remote.cmd?action=set&selIndex=0&ifname=eth0&access=101&sessionKey=627028247"), router.writes)
    }

    @Test
    fun `ping alone can be toggled with a default password and nothing is sent when nothing changes`() {
        val router = remoteRouter("000")

        run { RemoteAccessPage.apply(router, mapOf("icmp:eth0" to "true", "web:eth0" to "false"), weak) }
        assertEquals(listOf("/remote.cmd?action=set&selIndex=0&ifname=eth0&access=001&sessionKey=627028247"), router.writes)

        val unchanged = remoteRouter("010")
        run { RemoteAccessPage.apply(unchanged, mapOf("telnet:eth0" to "true"), weak) }
        assertTrue(unchanged.writes.isEmpty())
    }

    // --- date and time ------------------------------------------------------------------------

    private fun timeHtml(
        clock: String = "Thu Jan 01 01:24:55 1970",
        dst: String = "0",
        jumpBack: String = "0",
        zone: String = "Greenwich Mean Time; Dublin, Edinburgh, London, Lisbon",
        ntp: String = "1",
        servers: List<String> = listOf("time.nist.gov", "ntp1.tummy.com", "", "", ""),
    ) = """
        <script>
        var sessionKey = '999';
        var supportWlSche = '1';	//wifi schedule
        var use_dst = '$dst';
        var ntpDate = '$clock';
        var jumpBack = $jumpBack;
        var is_sync = 0;
        var wlIsTimeSet = '0';//for wireless schedule
        timeZones[0] = '(GMT-12:00) Enewetak, Kwajalein';
        timeZones[1] = '(GMT-11:00) Midway Island, Samoa';
        timeZones[2] = '(GMT-10:00) Hawaii';
        timeZones[3] = '(GMT-09:00) Alaska';
        timeZones[4] = '(GMT-08:00) Pacific Time (US & Canada); Tijuana';
        timeZones[14] = '(GMT-00:00) Greenwich Mean Time; Dublin, Edinburgh, London, Lisbon';
        timeZones[15] = '(GMT+01:00) Amsterdam, Berlin, Rome, Stockholm, Paris, Prague, Bratislava';
        timeZones[16] = '(GMT+02:00) Cairo, Athens, Istanbul, Minsk, Jerusalem, Kiev, Chisinau';
        function frmLoad() {
        var ntp1 = "${servers[0]}";
        var ntp2 = "${servers[1]}";
        var ntp3 = "${servers[2]}";
        var ntp4 = "${servers[3]}";
        var ntp5 = "${servers[4]}";
        var ntp_enabled = "$ntp";
        var tz_name = "$zone";
        }
        </script>
    """.trimIndent()

    private val noChanges = emptyMap<String, String>()

    @Test
    fun `reads servers, time zone and the router clock, and says when the clock was never set`() {
        val page = TimePage.parse(timeHtml())

        assertEquals(listOf("clock", "ntp", "server1", "server2", "server3", "server4", "server5", "zone"), page.fields.map { it.key })
        val clock = page.fields[0] as SettingField.Info
        assertEquals("1970-01-01 01:24:55", clock.value)
        assertTrue(clock.summary!!.contains("internet"))
        assertEquals("time.nist.gov", (page.fields[2] as SettingField.Text).value)
        assertEquals("14", (page.fields.last() as SettingField.Choice).value)
    }

    @Test
    fun `changing the time zone resends the servers and builds the page's request`() {
        val request = TimePage.buildRequest(timeHtml(), mapOf("zone" to "16"), strong)

        assertEquals(
            "/sntpcfg.cgi?ntp_enabled=1&ntpServer1=time.nist.gov&ntpServer2=ntp1.tummy.com&ntpServer3=&ntpServer4=&ntpServer5=" +
                "&sysDate=1970.01.01-01:24:55&timezone_offset=+02:00&timezone=Cairo, Athens, Istanbul, Minsk, Jerusalem, Kiev, Chisinau" +
                "&use_dst=0&wlIsTimeSet=0&sessionKey=999",
            request,
        )
    }

    @Test
    fun `the page's quirks are reproduced - ampersand in a zone name and the empty offset of zone 15`() {
        val pacific = TimePage.buildRequest(timeHtml(), mapOf("zone" to "4"), strong)
        assertTrue(pacific.contains("&timezone_offset=-08:00&timezone=Pacific Time (US _and_bit_ Canada); Tijuana&"))

        val berlin = TimePage.buildRequest(timeHtml(), mapOf("zone" to "15"), strong)
        assertTrue(berlin.contains("&timezone_offset=&timezone=Amsterdam, Berlin"))
    }

    @Test
    fun `turning automatic time off sends neither servers nor zone`() {
        val request = TimePage.buildRequest(timeHtml(), mapOf("ntp" to "false"), strong)

        assertEquals("/sntpcfg.cgi?ntp_enabled=0&sysDate=1970.01.01-01:24:55&use_dst=0&wlIsTimeSet=0&sessionKey=999", request)
    }

    @Test
    fun `a real date marks the time as set for the Wi-Fi schedule`() {
        val request = TimePage.buildRequest(timeHtml(clock = "Tue Mar 05 10:11:12 2024"), noChanges + ("zone" to "14"), strong)

        assertTrue(request.contains("&sysDate=2024.03.05-10:11:12&"))
        assertTrue(request.endsWith("&use_dst=0&wlIsTimeSet=1&sessionKey=999"))
    }

    @Test
    fun `server names are checked and the first one is required`() {
        fun failsOn(key: String, vararg changes: Pair<String, String>) = try {
            TimePage.buildRequest(timeHtml(), changes.toMap(), strong)
            false
        } catch (e: InvalidSettingException) {
            e.key == key
        }

        assertTrue(failsOn("server1", "server1" to ""))
        assertTrue(failsOn("server2", "server2" to "bad host!"))
        assertTrue(failsOn("server3", "server3" to "a&b.example"))
        assertTrue(failsOn("zone", "zone" to "99"))
        assertFalse(failsOn("server2", "server2" to "pool.ntp.org"))
    }

    @Test
    fun `daylight saving and setup flows stay view-only`() {
        for (html in listOf(timeHtml(dst = "1"), timeHtml(jumpBack = "1"))) {
            assertTrue(TimePage.parse(html).fields.all { it is SettingField.Info })
            assertTrue(runCatching { TimePage.buildRequest(html, mapOf("zone" to "14"), strong) }.exceptionOrNull() is InvalidSettingException)
        }
    }

    @Test
    fun `every page has a unique id`() {
        val ids = TpLinkSettings.specs.map { it.info.id }
        assertEquals(ids.size, ids.toSet().size)
    }
}
