package dev.routerhub.extension.tplink

import dev.routerhub.api.InvalidSettingException
import dev.routerhub.api.SettingField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pages are reduced to the lines the parsers use, in the same shape as the router's (inline
 * `var x = '...'` values, the literal sessionKey inside the save script). All values are
 * placeholders. Expected URLs follow the request each page's own btnApply() assembles.
 */
class TpLinkSettingsTest {

    private val context = SettingsContext(username = "admin", password = "old-router-pw")

    private val basicHtml = """
        <script>
        var unitNum = '0';
        var ssid = 'HomeNet';
        var enbl = '1';
        var hide = '1';
        var apIsolation = '0';
        var maxAssoc = '32';
        var country = 'US';
        var globalmaxassoc = '32';
        var CEOnly = '1';
        var WscMode = 'disabled';
        var WscVer2 = 'enabled';
        var supportWlSche = '1';
        loc += '&sessionKey=111111';
        </script>
    """.trimIndent()

    private val securityHtml = """
        <script>
        var enblWireless = '1';
        var mode = 'psk2';
        var bit = '0';
        var wpaPskKey = 'placeholder-pass';
        var wpaGTKRekey = '0';
        var wep = 'disabled';
        var wpa = 'aes';
        var varPreauth = '0';
        var varNetReauth = '36000';
        var WscMode = 'disabled';
        var WscAPMode = '1';
        var phy = 'n';
        var nmode = 'auto';
        var nreqd = '0';
        var sessionKey = '222222';
        </script>
    """.trimIndent()

    private val guestHtml = """
        <script>
        var wlEnbl = '1';
        var prmSSID = 'HomeNet';
        var gstSSID = 'HomeNet_Guest';
        var gstEnbl = '0';
        var gstLanAcs = '0';
        var wpaPskKey = '(null)';
        var mode = 'open';
        var wpa = '';
        var wpaGTKRekey = '0';
        var wlAPIsolation = '0';
        loc += '&sessionKey=333333';
        </script>
    """.trimIndent()

    private fun fieldKeys(page: dev.routerhub.api.SettingsPage) = page.fields.map { it.key }

    // --- encoding -----------------------------------------------------------------------------

    @Test
    fun `encodes values like the router's own encodeUrl`() {
        assertEquals("plain-Value_1", FirmwareUrl.encode("plain-Value_1"))
        assertEquals("a%20b%26c%2Bd%25", FirmwareUrl.encode("a b&c+d%"))
        assertEquals("%22%27%23%24%2C", FirmwareUrl.encode("\"'#$,"))
        assertEquals("%7B%7E", FirmwareUrl.encode("{~"))
    }

    // --- Wi-Fi basics -------------------------------------------------------------------------

    @Test
    fun `reads the basic Wi-Fi page`() {
        val page = WirelessBasicPage.parse(basicHtml)

        assertEquals(listOf("enable", "ssid", "hide", "isolate", "maxClients"), fieldKeys(page))
        assertEquals("HomeNet", (page.fields[1] as SettingField.Text).value)
        assertTrue((page.fields[2] as SettingField.Toggle).value)
        assertEquals(32L, (page.fields[4] as SettingField.Number).max)
        assertTrue(page.applyWarning!!.contains("restarts the Wi-Fi"))
    }

    @Test
    fun `renaming and unhiding the network builds the page's own request`() {
        val request = WirelessBasicPage.buildRequest(
            basicHtml, mapOf("ssid" to "New Name", "hide" to "false"), context,
        )

        assertEquals(
            "/wlcfg.wl?wlSsidIdx=0&wlEnbl=1&wlHide=0&wlAPIsolation=0&wlSsid=New%20Name" +
                "&wlMaxAssoc=32&wlSyncNvram=1&sessionKey=111111",
            request,
        )
    }

    @Test
    fun `hiding the network also switches WPS off when it was on`() {
        val wpsOn = basicHtml.replace("var hide = '1'", "var hide = '0'").replace("WscMode = 'disabled'", "WscMode = 'enabled'")

        val request = WirelessBasicPage.buildRequest(wpsOn, mapOf("hide" to "true"), context)

        assertTrue(request.contains("&wlHide=1"))
        assertTrue(request.contains("&wl_wsc_mode=disabled"))
    }

    @Test
    fun `switching the radio off resends the old values and flags a user action`() {
        val request = WirelessBasicPage.buildRequest(basicHtml, mapOf("enable" to "false"), context)

        assertEquals(
            "/wlcfg.wl?wlSsidIdx=0&wlEnbl=0&wlHide=1&wlAPIsolation=0&wlSsid=HomeNet&wlCountry=US" +
                "&wlIsUsrCtrl=1&wlSyncNvram=1&sessionKey=111111",
            request,
        )
    }

    @Test
    fun `refuses edits that the page would silently drop while the radio is off`() {
        val error = runCatching {
            WirelessBasicPage.buildRequest(basicHtml, mapOf("enable" to "false", "ssid" to "Other"), context)
        }.exceptionOrNull()

        assertTrue(error is InvalidSettingException)
    }

    @Test
    fun `rejects invalid names and client limits before anything is sent`() {
        fun failsOn(key: String, value: String): Boolean = try {
            WirelessBasicPage.buildRequest(basicHtml, mapOf(key to value), context)
            false
        } catch (e: InvalidSettingException) {
            e.key == key
        }

        assertTrue(failsOn("ssid", ""))
        assertTrue(failsOn("ssid", "x".repeat(33)))
        assertTrue(failsOn("ssid", "café"))
        assertTrue(failsOn("maxClients", "0"))
        assertTrue(failsOn("maxClients", "33"))
        assertTrue(failsOn("maxClients", "many"))
    }

    // --- Wi-Fi security -----------------------------------------------------------------------

    @Test
    fun `reading the security page never exposes the stored password`() {
        val page = WirelessSecurityPage.parse(securityHtml)

        assertEquals(listOf("mode", "encryption", "password", "rekey"), fieldKeys(page))
        assertFalse(page.toString().contains("placeholder-pass"))
        assertTrue(page.fields[2] is SettingField.Secret)
    }

    @Test
    fun `the security page is always loaded with the main network selected`() {
        assertEquals("/wlsecrefresh.wl?wlRefresh=0&wlSsidIdx=0", WirelessSecurityPage.loadPath)
        assertEquals(WirelessBasicPage.path, WirelessBasicPage.loadPath)
    }

    @Test
    fun `the security page never shows or resends another network's values`() {
        // What the router serves after the guest page was opened: the guest's data, ssidIdx 1.
        val guestView = securityHtml.replace("<script>", "<script>\nvar ssidIdx = '1';")

        val page = WirelessSecurityPage.parse(guestView)
        assertTrue(page.fields.all { it is SettingField.Info })

        val error = runCatching {
            WirelessSecurityPage.buildRequest(guestView, mapOf("password" to "NewPass123"), context)
        }.exceptionOrNull()
        assertTrue(error is InvalidSettingException)

        // The same page for the main network is fine.
        val mainView = securityHtml.replace("<script>", "<script>\nvar ssidIdx = '0';")
        assertFalse(WirelessSecurityPage.parse(mainView).fields.all { it is SettingField.Info })
    }

    @Test
    fun `changing the Wi-Fi password builds the page's own request`() {
        val request = WirelessSecurityPage.buildRequest(securityHtml, mapOf("password" to "NewPass123"), context)

        assertEquals(
            "/wlsecurity.wl?wl_wsc_mode=disabled&wl_wsc_reg=1&wlAuthMode=psk2&wlAuth=0&wlWpaPsk=NewPass123" +
                "&wlWpaGtkRekey=0&wlNetReauth=36000&wlWep=disabled&wlWpa=aes&wlKeyBit=0&wlPreauth=0" +
                "&wlSsidIdx=0&wlSyncNvram=1&sessionKey=222222",
            request,
        )
    }

    @Test
    fun `an untouched password is resent so the router keeps it`() {
        val request = WirelessSecurityPage.buildRequest(securityHtml, mapOf("rekey" to "3600"), context)

        assertTrue(request.contains("&wlWpaPsk=placeholder-pass&"))
        assertTrue(request.contains("&wlWpaGtkRekey=3600&"))
    }

    @Test
    fun `switching to mixed mode picks the encryption the page would preselect`() {
        val request = WirelessSecurityPage.buildRequest(securityHtml, mapOf("mode" to "psk psk2"), context)

        assertTrue(request.contains("&wlAuthMode=psk psk2&"))
        assertTrue(request.contains("&wlWpa=tkip+aes&"))
    }

    @Test
    fun `WPS is switched off for plain WPA-PSK`() {
        val wpsOn = securityHtml.replace("WscMode = 'disabled'", "WscMode = 'enabled'")

        val request = WirelessSecurityPage.buildRequest(wpsOn, mapOf("mode" to "psk"), context)

        assertTrue(request.startsWith("/wlsecurity.wl?wl_wsc_mode=disabled&"))
    }

    @Test
    fun `validates the passphrase rules of the page`() {
        fun rejected(value: String) = try {
            WirelessSecurityPage.buildRequest(securityHtml, mapOf("password" to value), context)
            false
        } catch (e: InvalidSettingException) {
            e.key == "password"
        }

        assertTrue(rejected("short"))
        assertTrue(rejected("x".repeat(65)))
        assertTrue(rejected("g".repeat(64))) // 64 characters must be hexadecimal
        assertFalse(rejected("a".repeat(64)))
        assertFalse(rejected("eight888"))
    }

    @Test
    fun `an open network can be upgraded but not created`() {
        val open = securityHtml.replace("mode = 'psk2'", "mode = 'open'").replace("wpa = 'aes'", "wpa = ''")

        val page = WirelessSecurityPage.parse(open)
        val modeChoice = page.fields[0] as SettingField.Choice
        assertEquals("open", modeChoice.value)
        assertTrue(modeChoice.options.any { it.value == "open" })

        // Upgrading needs a password...
        val missingPassword = runCatching {
            WirelessSecurityPage.buildRequest(open, mapOf("mode" to "psk2"), context)
        }.exceptionOrNull()
        assertTrue(missingPassword is InvalidSettingException)

        // ...and then works.
        val request = WirelessSecurityPage.buildRequest(open, mapOf("mode" to "psk2", "password" to "FreshPass99"), context)
        assertTrue(request.contains("&wlAuthMode=psk2&"))

        // Switching a protected network to open is not offered.
        val toOpen = runCatching { WirelessSecurityPage.buildRequest(securityHtml, mapOf("mode" to "open"), context) }
        assertTrue(toOpen.exceptionOrNull() is InvalidSettingException)
    }

    @Test
    fun `enterprise and WEP security stay view-only`() {
        val enterprise = securityHtml.replace("mode = 'psk2'", "mode = 'wpa2'")
        val wep = securityHtml.replace("wep = 'disabled'", "wep = 'enabled'")

        for (html in listOf(enterprise, wep)) {
            val page = WirelessSecurityPage.parse(html)
            assertTrue(page.fields.all { it is SettingField.Info })
            assertTrue(runCatching { WirelessSecurityPage.buildRequest(html, mapOf("password" to "NewPass123"), context) }.isFailure)
        }
    }

    // --- Guest network ------------------------------------------------------------------------

    @Test
    fun `enabling a protected guest network needs a password and builds the page's request`() {
        val without = runCatching {
            WirelessGuestPage.buildRequest(guestHtml, mapOf("enable" to "true", "security" to "psk"), context)
        }.exceptionOrNull()
        assertTrue(without is InvalidSettingException)

        val request = WirelessGuestPage.buildRequest(
            guestHtml,
            mapOf("enable" to "true", "security" to "psk", "password" to "GuestPass1", "encryption" to "aes", "lanAccess" to "false"),
            context,
        )

        assertEquals(
            "/wlgstnwk.wl?wlSsidIdx=1&wlEnbl=1&wlGntEnbl=1&wlSsid=HomeNet_Guest&wlAuthMode=psk&wlWpa=aes" +
                "&wlWpaPsk=GuestPass1&wlWpaGtkRekey=0&wlGstLanAcs=0&wlAPIsolation=0&wlSyncNvram=1&sessionKey=333333",
            request,
        )
    }

    @Test
    fun `an open guest network sends no password`() {
        val request = WirelessGuestPage.buildRequest(guestHtml, mapOf("enable" to "true"), context)

        assertTrue(request.contains("&wlAuthMode=open&"))
        assertFalse(request.contains("wlWpaPsk"))
    }

    @Test
    fun `turning the guest network off sends only the switch`() {
        val on = guestHtml.replace("gstEnbl = '0'", "gstEnbl = '1'")

        val request = WirelessGuestPage.buildRequest(on, mapOf("enable" to "false"), context)

        assertEquals("/wlgstnwk.wl?wlSsidIdx=1&wlEnbl=0&wlGntEnbl=0&wlSyncNvram=1&sessionKey=333333", request)
    }

    @Test
    fun `the guest name must differ from the main network`() {
        val error = runCatching {
            WirelessGuestPage.buildRequest(guestHtml, mapOf("enable" to "true", "ssid" to "HomeNet"), context)
        }.exceptionOrNull() as? InvalidSettingException

        assertEquals("ssid", error?.key)
    }

    // --- DMZ, UPnP ----------------------------------------------------------------------------

    @Test
    fun `DMZ host accepts a valid address and an empty one`() {
        val html = "<script>\nvar nat = '1';\nvar addr = '';\nloc += '&sessionKey=444444';\n</script>"

        assertEquals("/scdmz.cmd?address=192.168.1.50&sessionKey=444444", DmzPage.buildRequest(html, mapOf("address" to "192.168.1.50"), context))
        assertEquals("/scdmz.cmd?address=&sessionKey=444444", DmzPage.buildRequest(html, mapOf("address" to ""), context))
    }

    @Test
    fun `DMZ host rejects the addresses the page rejects`() {
        val html = "<script>\nvar nat = '1';\nvar addr = '';\nloc += '&sessionKey=444444';\n</script>"

        for (bad in listOf("0.0.0.0", "255.255.255.255", "127.0.0.1", "169.254.1.1", "224.0.0.1", "192.168.1.0", "192.168.1.255", "192.168.1", "abc", "192.168.1.300")) {
            assertTrue("$bad should be rejected", runCatching { DmzPage.buildRequest(html, mapOf("address" to bad), context) }.isFailure)
        }
    }

    @Test
    fun `UPnP toggles`() {
        val html = "<script>\nvar enblUpnp = '1';\nloc += '&sessionKey=555555';\n</script>"

        assertTrue((UpnpPage.parse(html).fields.single() as SettingField.Toggle).value)
        assertEquals("/upnpcfg.cgi?enblUpnp=0&sessionKey=555555", UpnpPage.buildRequest(html, mapOf("enable" to "false"), context))
    }

    // --- Admin password -----------------------------------------------------------------------

    @Test
    fun `changing the admin password sends the old and new one`() {
        val html = "<script>\nloc += '&sessionKey=666666';\n</script>"

        val request = AdminPasswordPage.buildRequest(html, mapOf("password" to "new pass".replace(' ', 'X')), context)

        assertEquals(
            "/password.cgi?inUserName=admin&inPassword=newXpass&inOrgPassword=old-router-pw&sessionKey=666666",
            request,
        )
        assertEquals("password", AdminPasswordPage.parse(html).loginPasswordKey)
    }

    @Test
    fun `admin password rules`() {
        val html = "<script>\nloc += '&sessionKey=666666';\n</script>"
        fun rejected(value: String) = runCatching { AdminPasswordPage.buildRequest(html, mapOf("password" to value), context) }.isFailure

        assertTrue(rejected(""))
        assertTrue(rejected("has space"))
        assertTrue(rejected("x".repeat(17)))
        assertTrue(rejected("old-router-pw")) // unchanged
        assertTrue(rejected("café"))
        assertFalse(rejected("x".repeat(16)))
    }

    // --- Advanced radio -----------------------------------------------------------------------

    @Test
    fun `advanced radio page is shown but not writable`() {
        val html = "<script>\nvar channel = '0';\nvar wlCurrentChannel = '6';\nvar TxPwrPcnt = '100';\nvar currbw = '2';\nvar nmode = 'auto';\nvar nreqd = '0';\n</script>"

        val page = WirelessAdvancedPage.parse(html)
        assertTrue(page.fields.all { it is SettingField.Info })
        assertEquals("Auto (now 6)", (page.fields.first() as SettingField.Info).value)

        try {
            WirelessAdvancedPage.buildRequest(html, mapOf("channel" to "1"), context)
            fail("expected a refusal")
        } catch (_: InvalidSettingException) {
        }
    }

    @Test
    fun `every page has a unique id`() {
        val ids = TpLinkSettings.specs.map { it.info.id }
        assertEquals(ids.size, ids.toSet().size)
    }
}
