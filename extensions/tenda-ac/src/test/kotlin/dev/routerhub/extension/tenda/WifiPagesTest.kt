package dev.routerhub.extension.tenda

import dev.routerhub.api.SettingField
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiPagesTest {

    private fun wifi() = FakeTendaApi("WifiBasicGet" to Fixtures.WIFI_BASIC)

    private fun guest() = FakeTendaApi("WifiGuestGet" to Fixtures.WIFI_GUEST)

    private fun radio() = FakeTendaApi("WifiRadioGet" to Fixtures.WIFI_RADIO)

    private fun extras() = FakeTendaApi(
        "WifiPowerGet" to Fixtures.WIFI_POWER,
        "WifiWpsGet" to Fixtures.WIFI_WPS,
        "WifiBeamformingGet" to Fixtures.WIFI_BEAMFORMING,
    )

    // --- Wi-Fi -------------------------------------------------------------------------------

    @Test
    fun `the wifi page shows both bands and never a password`() {
        val page = WifiPage.readPage(wifi())
        assertEquals("HomeNet", (page.field("ssid24") as SettingField.Text).value)
        assertEquals("HomeNet_5G", (page.field("ssid5") as SettingField.Text).value)
        assertEquals(true, (page.field("enable24") as SettingField.Toggle).value)
        assertEquals("wpa2psk", (page.field("security5") as SettingField.Choice).value)
        assertFalse((page.field("same") as SettingField.Toggle).value)

        val everything = page.fields.joinToString { it.toString() }
        assertFalse("the Wi-Fi password never leaves the extension", everything.contains("correct-horse"))
        assertFalse(everything.contains("battery-staple"))
        assertTrue(page.field("password24") is SettingField.Secret)
        assertTrue(page.field("password5") is SettingField.Secret)
    }

    @Test
    fun `renaming one band resends the other values the router holds`() {
        val api = wifi()
        WifiPage.applyChanges(api, "ssid24" to "New Name")
        assertEquals(
            "WifiBasicSet" to "doubleBand=0&wrlEn=1&wrlEn_5g=1&security=wpa2psk&security_5g=wpa2psk" +
                "&ssid=New%20Name&ssid_5g=HomeNet_5G&hideSsid=0&hideSsid_5g=0" +
                "&wrlPwd=correct-horse&wrlPwd_5g=battery-staple",
            api.onlyWrite,
        )
    }

    @Test
    fun `a new password replaces the old one on that band only`() {
        val api = wifi()
        WifiPage.applyChanges(api, "password5" to "n3w-passw0rd")
        val body = api.onlyWrite.second
        assertTrue(body, body.contains("&wrlPwd=correct-horse&wrlPwd_5g=n3w-passw0rd"))
    }

    @Test
    fun `one name for both bands copies the 2_4 GHz values over the 5 GHz ones`() {
        val api = wifi()
        WifiPage.applyChanges(api, "same" to "true", "ssid5" to "ignored")
        assertEquals(
            "doubleBand=1&wrlEn=1&wrlEn_5g=1&security=wpa2psk&security_5g=wpa2psk" +
                "&ssid=HomeNet&ssid_5g=HomeNet&hideSsid=0&hideSsid_5g=0" +
                "&wrlPwd=correct-horse&wrlPwd_5g=correct-horse",
            api.onlyWrite.second,
        )
    }

    @Test
    fun `hiding a network name is passed on`() {
        val api = wifi()
        WifiPage.applyChanges(api, "hide5" to "true")
        assertTrue(api.onlyWrite.second.contains("&hideSsid=0&hideSsid_5g=1&"))
    }

    @Test
    fun `a bad value is refused before anything is sent`() {
        val api = wifi()
        rejected("password24") { WifiPage.applyChanges(api, "ssid5" to "Fine", "password24" to "short") }
        rejected("ssid24") { WifiPage.applyChanges(api, "ssid24" to "") }
        rejected("ssid24") { WifiPage.applyChanges(api, "ssid24" to "x".repeat(33)) }
        rejected("ssid5") { WifiPage.applyChanges(api, "ssid5" to "中".repeat(11)) } // 11 x 3 = 33 bytes
        rejected("password5") { WifiPage.applyChanges(api, "password5" to "café-passw0rd") }
        rejected("security24") { WifiPage.applyChanges(api, "security24" to "wep") }
        assertTrue("no request left the extension", api.writes.isEmpty())
    }

    @Test
    fun `a name of 32 characters and one of 10 wide characters both fit`() {
        val api = wifi()
        WifiPage.applyChanges(api, "ssid24" to "x".repeat(32), "ssid5" to "中".repeat(10))
        assertEquals(1, api.writes.size)
    }

    @Test
    fun `a protected network is never switched to open`() {
        val api = wifi()
        val message = rejected("security24") { WifiPage.applyChanges(api, "security24" to "none") }
        assertTrue(message, message.contains("anyone nearby"))
        assertTrue(api.writes.isEmpty())
    }

    @Test
    fun `a password is needed to protect an open network`() {
        val open = Fixtures.WIFI_BASIC
            .replace("\"security\":\"wpa2psk\"", "\"security\":\"none\"")
            .replace("\"wrlPwd\":\"correct-horse\"", "\"wrlPwd\":\"\"")
        val api = FakeTendaApi("WifiBasicGet" to open)
        rejected("password24") { WifiPage.applyChanges(api, "security24" to "wpa2psk") }

        WifiPage.applyChanges(api, "security24" to "wpa2psk", "password24" to "n3w-passw0rd")
        assertTrue(api.onlyWrite.second.contains("&security=wpa2psk&"))
        assertTrue(api.onlyWrite.second.contains("&wrlPwd=n3w-passw0rd&"))
    }

    @Test
    fun `an open network is left open and sends no password`() {
        val open = Fixtures.WIFI_BASIC
            .replace("\"security\":\"wpa2psk\"", "\"security\":\"none\"")
            .replace("\"wrlPwd\":\"correct-horse\"", "\"wrlPwd\":\"\"")
        val api = FakeTendaApi("WifiBasicGet" to open)
        WifiPage.applyChanges(api, "ssid24" to "Cafe")
        assertTrue(api.onlyWrite.second.contains("&security=none&security_5g=wpa2psk&ssid=Cafe&"))
        assertTrue(api.onlyWrite.second.contains("&wrlPwd=&wrlPwd_5g=battery-staple"))
    }

    @Test
    fun `a switched off band keeps its name, and a name typed for it is still checked`() {
        val api = wifi()
        WifiPage.applyChanges(api, "enable5" to "false")
        assertTrue(api.onlyWrite.second.contains("&wrlEn_5g=0&"))
        assertTrue(api.onlyWrite.second.contains("&ssid_5g=HomeNet_5G&"))

        val other = wifi()
        rejected("ssid5") { WifiPage.applyChanges(other, "enable5" to "false", "ssid5" to "") }
        assertTrue(other.writes.isEmpty())
    }

    // --- the Wi-Fi switch of the router screen -----------------------------------------------

    @Test
    fun `one radio can be switched without touching anything else`() {
        val api = wifi()
        runBlocking { WifiPage.setEnabled(api, "5g", false) }
        assertEquals(
            "doubleBand=0&wrlEn=1&wrlEn_5g=0&security=wpa2psk&security_5g=wpa2psk" +
                "&ssid=HomeNet&ssid_5g=HomeNet_5G&hideSsid=0&hideSsid_5g=0" +
                "&wrlPwd=correct-horse&wrlPwd_5g=battery-staple",
            api.onlyWrite.second,
        )
    }

    @Test
    fun `all radios can be switched together`() {
        val api = wifi()
        runBlocking { WifiPage.setEnabled(api, null, false) }
        assertTrue(api.onlyWrite.second.startsWith("doubleBand=0&wrlEn=0&wrlEn_5g=0&"))
    }

    @Test
    fun `with one setting for both bands only both can be switched`() {
        val api = FakeTendaApi("WifiBasicGet" to Fixtures.WIFI_BASIC.replace("\"doubleBand\":\"0\"", "\"doubleBand\":\"1\""))
        rejected("same") { runBlocking { WifiPage.setEnabled(api, "5g", false) } }
        runBlocking { WifiPage.setEnabled(api, null, false) }
        assertTrue(api.onlyWrite.second.startsWith("doubleBand=1&wrlEn=0&wrlEn_5g=0&"))
    }

    @Test
    fun `an unknown radio is refused`() {
        val api = wifi()
        rejected("radio") { runBlocking { WifiPage.setEnabled(api, "6g", true) } }
        assertTrue(api.writes.isEmpty())
    }

    // --- Guest network -----------------------------------------------------------------------

    @Test
    fun `the guest page shows the network, not its password`() {
        val page = GuestWifiPage.readPage(guest())
        assertEquals(false, (page.field("enable") as SettingField.Toggle).value)
        assertEquals("Visitors", (page.field("ssid24") as SettingField.Text).value)
        assertEquals(true, (page.field("open") as SettingField.Toggle).value)
        assertEquals("8", (page.field("hours") as SettingField.Choice).value)
        assertEquals(0L, (page.field("limit") as SettingField.Number).value)
        assertTrue(page.field("password") is SettingField.Secret)
    }

    @Test
    fun `the shared bandwidth is shown in Mbps and sent in KB per second`() {
        val api = FakeTendaApi("WifiGuestGet" to Fixtures.WIFI_GUEST.replace("\"shareSpeed\":\"0\"", "\"shareSpeed\":\"1280\""))
        assertEquals(10L, (GuestWifiPage.readPage(api).field("limit") as SettingField.Number).value)
        GuestWifiPage.applyChanges(api, "limit" to "20")
        assertTrue(api.onlyWrite.second.endsWith("&shareSpeed=2560"))
    }

    @Test
    fun `switching the guest network on with a password`() {
        val api = guest()
        GuestWifiPage.applyChanges(api, "enable" to "true", "open" to "false", "password" to "visitor-pass")
        assertEquals(
            "WifiGuestSet" to "guestEn=1&guestEn_5g=1&guestSecurity=wpapsk&guestSecurity_5g=wpapsk" +
                "&guestSsid=Visitors&guestSsid_5g=Visitors_5G&guestWrlPwd=visitor-pass&guestWrlPwd_5g=visitor-pass" +
                "&effectiveTime=8&shareSpeed=0",
            api.onlyWrite,
        )
    }

    @Test
    fun `an open guest network sends no password and says none`() {
        val api = guest()
        GuestWifiPage.applyChanges(api, "enable" to "true")
        assertTrue(api.onlyWrite.second.startsWith("guestEn=1&guestEn_5g=1&guestSecurity=none&guestSecurity_5g=none&"))
        assertTrue(api.onlyWrite.second.contains("&guestWrlPwd=&guestWrlPwd_5g=&"))
    }

    @Test
    fun `the guest password is kept when it is not asked for`() {
        val withPassword = Fixtures.WIFI_GUEST.replace("\"guestWrlPwd\":\"\"", "\"guestWrlPwd\":\"kept-secret\"")
        val api = FakeTendaApi("WifiGuestGet" to withPassword)
        GuestWifiPage.applyChanges(api, "enable" to "true", "hours" to "4")
        assertTrue(api.onlyWrite.second.contains("guestSecurity=wpapsk"))
        assertTrue(api.onlyWrite.second.contains("&guestWrlPwd=kept-secret&"))
        assertTrue(api.onlyWrite.second.contains("&effectiveTime=4&"))
    }

    @Test
    fun `a guest network needs a password or an explicit no password`() {
        val withPassword = Fixtures.WIFI_GUEST.replace("\"guestWrlPwd\":\"\"", "\"guestWrlPwd\":\"kept-secret\"")
        val api = FakeTendaApi("WifiGuestGet" to withPassword)
        // "no password" turned off, nothing typed: the old password stays, which is fine
        GuestWifiPage.applyChanges(api, "enable" to "true", "open" to "false")
        assertTrue(api.onlyWrite.second.contains("&guestWrlPwd=kept-secret&"))

        // currently open, asked to be protected, nothing typed: nothing to protect it with
        val openApi = guest()
        rejected("password") { GuestWifiPage.applyChanges(openApi, "enable" to "true", "open" to "false") }
        rejected("password") { GuestWifiPage.applyChanges(openApi, "enable" to "true", "open" to "false", "password" to "short") }
        assertTrue(openApi.writes.isEmpty())
    }

    @Test
    fun `bad guest values are refused`() {
        val api = guest()
        rejected("hours") { GuestWifiPage.applyChanges(api, "hours" to "12") }
        rejected("limit") { GuestWifiPage.applyChanges(api, "limit" to "1001") }
        rejected("limit") { GuestWifiPage.applyChanges(api, "limit" to "fast") }
        rejected("ssid24") { GuestWifiPage.applyChanges(api, "enable" to "true", "ssid24" to "") }
        assertTrue(api.writes.isEmpty())
    }

    @Test
    fun `the guest network is refused while wifi is off`() {
        val api = FakeTendaApi("WifiGuestGet" to Fixtures.WIFI_GUEST.replace("\"wl_en\":\"1\"", "\"wl_en\":\"0\""))
        rejected("enable") { GuestWifiPage.applyChanges(api, "enable" to "true") }
    }

    // --- Channel and mode --------------------------------------------------------------------

    @Test
    fun `the channel page lists the channels the router allows`() {
        val page = WifiChannelPage.readPage(radio())
        val channels24 = (page.field("channel24") as SettingField.Choice).options
        assertEquals("Auto", channels24.first().label)
        assertEquals("13", channels24.last().value)
        val channels5 = (page.field("channel5") as SettingField.Choice).options.map { it.value }
        assertEquals(listOf("0", "36", "40", "44", "48", "149", "153", "157", "161", "165"), channels5)
        assertEquals("CN", (page.field("country") as SettingField.Info).value)
    }

    @Test
    fun `changing the channel sends all six values`() {
        val api = radio()
        WifiChannelPage.applyChanges(api, "channel5" to "36", "width5" to "40", "channel24" to "6")
        assertEquals(
            "WifiRadioSet" to "adv_mode=bgn&adv_channel=6&adv_band=auto&adv_mode_5g=ac&adv_channel_5g=36&adv_band_5g=40",
            api.onlyWrite,
        )
    }

    @Test
    fun `a space in a mode is sent as the page sends it`() {
        val api = radio()
        WifiChannelPage.applyChanges(api, "mode24" to "n only", "width24" to "40")
        assertTrue(api.onlyWrite.second.startsWith("adv_mode=n+only&adv_channel=0&adv_band=40&"))
    }

    @Test
    fun `channels and widths that do not go together are refused`() {
        val api = radio()
        // 165 only exists at 20 MHz
        rejected("channel5") { WifiChannelPage.applyChanges(api, "channel5" to "165", "width5" to "40") }
        rejected("channel5") { WifiChannelPage.applyChanges(api, "channel5" to "52") }
        rejected("width24") { WifiChannelPage.applyChanges(api, "mode24" to "bg", "width24" to "40") }
        rejected("channel24") { WifiChannelPage.applyChanges(api, "channel24" to "14") }
        rejected("mode24") { WifiChannelPage.applyChanges(api, "mode24" to "ax") }
        assertTrue(api.writes.isEmpty())
        WifiChannelPage.applyChanges(api, "channel5" to "165", "width5" to "20")
        assertTrue(api.onlyWrite.second.endsWith("&adv_channel_5g=165&adv_band_5g=20"))
    }

    // --- Power, WPS, beamforming -------------------------------------------------------------

    @Test
    fun `the extras show what the router holds`() {
        val page = WifiExtrasPage.readPage(extras())
        assertEquals("high", (page.field("power24") as SettingField.Choice).value)
        assertEquals("middle", (page.field("power5") as SettingField.Choice).value)
        assertEquals(true, (page.field("wps") as SettingField.Toggle).value)
        assertEquals(true, (page.field("beamforming") as SettingField.Toggle).value)
        assertFalse("the WPS PIN is not shown", page.fields.joinToString { it.toString() }.contains("00000000"))
    }

    @Test
    fun `changing one power level resends the other`() {
        val api = extras()
        WifiExtrasPage.applyChanges(api, "power24" to "low")
        assertEquals("WifiPowerSet" to "power=low&power_5g=middle", api.onlyWrite)
    }

    @Test
    fun `each extra is saved on its own page of the router`() {
        val api = extras()
        WifiExtrasPage.applyChanges(api, "wps" to "false", "beamforming" to "false", "power5" to "high")
        assertEquals(
            listOf(
                "WifiPowerSet" to "power=high&power_5g=high",
                "WifiWpsSet" to "wpsEn=0",
                "WifiBeamformingSet" to "beamformingEn=0",
            ),
            api.writes,
        )
    }

    @Test
    fun `an unknown power level is refused`() {
        val api = extras()
        rejected("power24") { WifiExtrasPage.applyChanges(api, "power24" to "turbo") }
        assertTrue(api.writes.isEmpty())
    }

    @Test
    fun `no change sends nothing`() {
        val api = extras()
        WifiExtrasPage.applyChanges(api)
        assertTrue(api.writes.isEmpty())
    }
}
