package dev.routerhub.extension.tenda

import dev.routerhub.api.SettingField
import dev.routerhub.api.TableCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessAndSystemPagesTest {

    private fun rows(vararg row: List<String>) = TableCodec.encode(row.toList())

    // --- MAC filter --------------------------------------------------------------------------

    private fun filter(json: String = Fixtures.MAC_FILTER) = FakeTendaApi("getMacFilterCfg" to json)

    private val tablet = listOf("Tablet", "02:00:00:00:00:30")
    private val phone = listOf("Phone", "02:00:00:00:00:11")
    private val laptop = listOf("LAPTOP", "02:00:00:00:00:10") // the device that is asking

    @Test
    fun `the page shows the list of the active mode and the device that is asking`() {
        val page = MacFilterPage.readPage(filter())
        assertEquals("black", (page.field("mode") as SettingField.Choice).value)
        val table = page.field("devices") as SettingField.Table
        assertEquals("Blocked devices", table.title)
        assertEquals(listOf(tablet), table.rows)
        assertEquals(30, table.maxRows)
        assertEquals("LAPTOP (02:00:00:00:00:10)", (page.field("thisDevice") as SettingField.Info).value)

        val allow = MacFilterPage.readPage(filter(Fixtures.MAC_FILTER.replace("\"macFilterType\":\"black\"", "\"macFilterType\":\"white\"")))
        assertEquals("Allowed devices", (allow.field("devices") as SettingField.Table).title)
        assertEquals(listOf(phone, laptop), (allow.field("devices") as SettingField.Table).rows)
    }

    @Test
    fun `a device is added to the blacklist in the pages own format`() {
        val api = filter()
        MacFilterPage.applyChanges(api, "devices" to rows(tablet, listOf("New Phone", "02:AA:BB:CC:DD:EE")))
        assertEquals(
            "setMacFilterCfg" to "macFilterType=black&deviceList=Tablet\r02:00:00:00:00:30\nNew%20Phone\r02:aa:bb:cc:dd:ee",
            api.onlyWrite,
        )
    }

    @Test
    fun `the mode alone keeps the list of that mode`() {
        val api = filter()
        MacFilterPage.applyChanges(api, "mode" to "white")
        assertEquals("setMacFilterCfg" to "macFilterType=white&deviceList=Phone\r02:00:00:00:00:11\nLAPTOP\r02:00:00:00:00:10", api.onlyWrite)
    }

    @Test
    fun `switching to allow only puts the device that is asking on the list, like the router's page`() {
        val noOneYet = Fixtures.MAC_FILTER.replace(
            "\"whiteList\":[{\"devName\":\"Phone\",\"devMac\":\"02:00:00:00:00:11\"},{\"devName\":\"LAPTOP\",\"devMac\":\"02:00:00:00:00:10\"}]",
            "\"whiteList\":[{\"devName\":\"Phone\",\"devMac\":\"02:00:00:00:00:11\"}]",
        )
        val api = filter(noOneYet)
        MacFilterPage.applyChanges(api, "mode" to "white")
        assertEquals("macFilterType=white&deviceList=LAPTOP\r02:00:00:00:00:10\nPhone\r02:00:00:00:00:11", api.onlyWrite.second)

        val empty = filter(noOneYet.replace("{\"devName\":\"Phone\",\"devMac\":\"02:00:00:00:00:11\"}", ""))
        MacFilterPage.applyChanges(empty, "mode" to "white")
        assertEquals("macFilterType=white&deviceList=LAPTOP\r02:00:00:00:00:10", empty.onlyWrite.second)
    }

    @Test
    fun `the mode and the list cannot be saved together`() {
        val api = filter()
        val message = rejected("devices") { MacFilterPage.applyChanges(api, "mode" to "white", "devices" to rows(phone)) }
        assertTrue(message, message.contains("old mode"))
        assertTrue(api.writes.isEmpty())
    }

    @Test
    fun `the device that is asking can neither be blocked nor dropped from the allow list`() {
        val block = filter()
        val blocking = rejected("devices") { MacFilterPage.applyChanges(block, "devices" to rows(tablet, laptop)) }
        assertTrue(blocking, blocking.contains("cut you off"))

        val allow = filter(Fixtures.MAC_FILTER.replace("\"macFilterType\":\"black\"", "\"macFilterType\":\"white\""))
        val dropping = rejected("devices") { MacFilterPage.applyChanges(allow, "devices" to rows(phone)) }
        assertTrue(dropping, dropping.contains("cut you off"))
        assertTrue(block.writes.isEmpty() && allow.writes.isEmpty())

        MacFilterPage.applyChanges(allow, "devices" to rows(laptop))
        assertEquals("macFilterType=white&deviceList=LAPTOP\r02:00:00:00:00:10", allow.onlyWrite.second)
    }

    @Test
    fun `an allow list that could be empty is refused when the router cannot tell who is asking`() {
        val unknown = Fixtures.MAC_FILTER.replace("\"localhostMac\":\"02:00:00:00:00:10\"", "\"localhostMac\":\"\"")
            .replace("\"macFilterType\":\"black\"", "\"macFilterType\":\"white\"")
        val api = filter(unknown)
        rejected("devices") { MacFilterPage.applyChanges(api, "devices" to "") }
        assertTrue(api.writes.isEmpty())
        MacFilterPage.applyChanges(api, "devices" to rows(phone))
        assertEquals("macFilterType=white&deviceList=Phone\r02:00:00:00:00:11", api.onlyWrite.second)
    }

    @Test
    fun `an empty blacklist is fine`() {
        val api = filter()
        MacFilterPage.applyChanges(api, "devices" to "")
        assertEquals("setMacFilterCfg" to "macFilterType=black&deviceList=", api.onlyWrite)
    }

    @Test
    fun `bad filter entries are refused`() {
        val api = filter()
        fun refuse(vararg row: List<String>) = rejected("devices") { MacFilterPage.applyChanges(api, "devices" to rows(*row)) }
        refuse(listOf("A", "nope"))
        refuse(listOf("A", "01:00:5e:00:00:01"))
        refuse(listOf("x".repeat(21), "02:00:00:00:00:40"))
        refuse(listOf("A", "02:00:00:00:00:40"), listOf("B", "02:00:00:00:00:40"))
        refuse(listOf("A"))
        rejected("mode") { MacFilterPage.applyChanges(api, "mode" to "gray") }
        val many = (1..31).map { listOf("d$it", "02:00:00:00:02:%02x".format(it)) }
        rejected("devices") { MacFilterPage.applyChanges(api, "devices" to TableCodec.encode(many)) }
        assertTrue(api.writes.isEmpty())
    }

    // --- Date and time -----------------------------------------------------------------------

    private fun time() = FakeTendaApi("GetSysTimeCfg" to Fixtures.SYS_TIME)

    @Test
    fun `the time page shows the clock, the mode and the zone`() {
        val page = TimePage.readPage(time())
        assertEquals("2026-10-29 12:34:56", (page.field("now") as SettingField.Info).value)
        assertEquals("sync", (page.field("mode") as SettingField.Choice).value)
        assertEquals("15:00", (page.field("zone") as SettingField.Choice).value)
        assertEquals(45, (page.field("zone") as SettingField.Choice).options.size)
    }

    @Test
    fun `a zone the router has that is not on the list stays selectable`() {
        val page = TimePage.readPage(FakeTendaApi("GetSysTimeCfg" to Fixtures.SYS_TIME.replace("\"15:00\"", "\"99:00\"")))
        assertTrue((page.field("zone") as SettingField.Choice).options.any { it.value == "99:00" })
    }

    @Test
    fun `changing the zone keeps synchronising and resends the router's own time`() {
        val api = time()
        TimePage.applyChanges(api, "zone" to "17:00", "time" to "1999-01-01 00:00:00")
        assertEquals(
            "SetSysTimeCfg" to "timeType=sync&timePeriod=86400&ntpServer=time.example.org&timeZone=17%3A00&time=2026-10-29%2012%3A34%3A56",
            api.onlyWrite,
        )
    }

    @Test
    fun `setting the time by hand sends it`() {
        val api = time()
        TimePage.applyChanges(api, "mode" to "manual", "time" to "2027-01-02 03:04:05")
        assertEquals("timeType=manual&timePeriod=86400&ntpServer=time.example.org&timeZone=15%3A00&time=2027-01-02%2003%3A04%3A05", api.onlyWrite.second)
    }

    @Test
    fun `a bad time or zone is refused`() {
        val api = time()
        for (bad in listOf("garbage", "2027-13-01 00:00:00", "2027-02-30 10:00:00", "2027-01-01 25:00:00", "2027-1-1 1:1:1", "1700-01-01 00:00:00")) {
            rejected("time") { TimePage.applyChanges(api, "mode" to "manual", "time" to bad) }
        }
        rejected("zone") { TimePage.applyChanges(api, "zone" to "99:99") }
        rejected("mode") { TimePage.applyChanges(api, "mode" to "magic") }
        assertTrue(api.writes.isEmpty())
    }

    // --- LED and scheduled reboot ------------------------------------------------------------

    @Test
    fun `the leds can be switched off at night`() {
        val api = FakeTendaApi("GetLEDCfg" to Fixtures.LED)
        LedPage.applyChanges(api, "mode" to "time", "window" to "23:30-06:15")
        assertEquals("SetLEDCfg" to "ledType=time&time=23%3A30-06%3A15&ledCloseType=allClose", api.onlyWrite)

        val off = FakeTendaApi("GetLEDCfg" to Fixtures.LED)
        LedPage.applyChanges(off, "mode" to "close", "window" to "not even checked")
        assertEquals("ledType=close&time=00%3A00-07%3A00&ledCloseType=allClose", off.onlyWrite.second)
    }

    @Test
    fun `a bad led window is refused`() {
        val api = FakeTendaApi("GetLEDCfg" to Fixtures.LED)
        for (bad in listOf("7-8", "24:00-01:00", "01:00-25:00", "01:60-02:00", "08:00-08:00", "")) {
            rejected("window") { LedPage.applyChanges(api, "mode" to "time", "window" to bad) }
        }
        rejected("mode") { LedPage.applyChanges(api, "mode" to "disco") }
        assertTrue(api.writes.isEmpty())
    }

    @Test
    fun `the scheduled reboot is saved as the page saves it`() {
        val api = FakeTendaApi("GetSysAutoRebbotCfg" to Fixtures.AUTO_REBOOT)
        val page = AutoRebootPage.readPage(api)
        assertEquals("02:00", (page.field("time") as SettingField.Text).value)
        assertEquals(true, (page.field("delay") as SettingField.Toggle).value)

        AutoRebootPage.applyChanges(api, "time" to "04:30", "delay" to "false")
        assertEquals("SetSysAutoRebbotCfg" to "autoRebootEn=1&delayRebootEn=false&rebootTime=04%3A30", api.onlyWrite)
    }

    @Test
    fun `a bad reboot time is refused, but switching it off needs no time`() {
        val api = FakeTendaApi("GetSysAutoRebbotCfg" to Fixtures.AUTO_REBOOT)
        rejected("time") { AutoRebootPage.applyChanges(api, "time" to "25:00") }
        rejected("time") { AutoRebootPage.applyChanges(api, "time" to "3:30") }
        assertTrue(api.writes.isEmpty())
        AutoRebootPage.applyChanges(api, "enable" to "false")
        assertEquals("autoRebootEn=0&delayRebootEn=true&rebootTime=02%3A00", api.onlyWrite.second)
    }

    // --- System log --------------------------------------------------------------------------

    @Test
    fun `the log is read only and newest first`() {
        val api = FakeTendaApi("GetSySLogCfg" to Fixtures.SYSTEM_LOG)
        val table = SystemLogPage.readPage(api).field("log") as SettingField.Table
        assertTrue(table.readOnly)
        assertEquals(listOf("2000-01-01 00:00:02", "system", "DHCP Server Start"), table.rows.first())
        assertEquals(2, table.rows.size)
        rejected("page") { SystemLogPage.applyChanges(api, "log" to "") }
    }

    // --- Login password ----------------------------------------------------------------------

    @Test
    fun `the login password page asks twice and names the secret`() {
        val page = LoginPasswordPage.readPage(FakeTendaApi())
        assertEquals("password", page.loginPasswordKey)
        val secret = page.field("password") as SettingField.Secret
        assertTrue(secret.required)
        assertTrue(secret.requiresConfirmation)
    }

    @Test
    fun `the new password is posted as a plain form with both hashes`() {
        val api = FakeTendaApi("SysToolpassword" to Fixtures.PASSWORD_INFO)
        LoginPasswordPage.applyChanges(api, "password" to "n3w-secret")
        val old = TendaCrypto.md5("current-login-password")
        val new = TendaCrypto.md5("n3w-secret")
        assertEquals(listOf("SysToolChangePwd" to "GO=system_password.html&SYSOPS=$old&SYSPS=$new&SYSPS2=$new"), api.plainPosts)
        assertTrue("it is not a scrambled write", api.writes.isEmpty())
    }

    @Test
    fun `without a password yet no old one is sent`() {
        val api = FakeTendaApi("SysToolpassword" to """{"ispwd":"0","remoteEn":"0"}""")
        LoginPasswordPage.applyChanges(api, "password" to "n3w-secret")
        assertTrue(api.plainPosts.single().second.startsWith("GO=system_password.html&SYSOPS=&SYSPS="))
    }

    @Test
    fun `a bad login password is refused before anything is sent`() {
        val api = FakeTendaApi("SysToolpassword" to Fixtures.PASSWORD_INFO)
        for (bad in listOf("abcd", "x".repeat(33), "café-secret", " leading-space", "trailing-space ", "current-login-password")) {
            rejected("password") { LoginPasswordPage.applyChanges(api, "password" to bad) }
        }
        assertTrue(api.plainPosts.isEmpty())
    }

    @Test
    fun `no change sends nothing`() {
        val api = FakeTendaApi("SysToolpassword" to Fixtures.PASSWORD_INFO)
        LoginPasswordPage.applyChanges(api)
        assertTrue(api.plainPosts.isEmpty() && api.writes.isEmpty())
    }

    // --- all pages ---------------------------------------------------------------------------

    @Test
    fun `every page can be read and has fields of its own`() {
        val api = Fixtures.everything()
        val ids = TendaSettings.pages.map { it.info.id }
        assertEquals("page ids are unique", ids.size, ids.toSet().size)
        for (page in TendaSettings.pages) {
            val read = page.readPage(api)
            assertEquals(page.info.id, read.info.id)
            assertFalse("${page.info.id} has fields", read.fields.isEmpty())
            assertEquals("keys are unique on ${page.info.id}", read.fields.size, read.fields.map { it.key }.toSet().size)
        }
    }

    @Test
    fun `pages are grouped under a few titles`() {
        assertEquals(setOf("Wireless", "Network", "System"), TendaSettings.pages.map { it.info.group }.toSet())
    }
}
