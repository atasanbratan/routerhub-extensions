package dev.routerhub.extension.tplink

import dev.routerhub.api.ChoiceOption
import dev.routerhub.api.InvalidSettingException
import dev.routerhub.api.SettingField
import dev.routerhub.api.SettingsPage
import dev.routerhub.api.SettingsPageInfo

internal object TpLinkSettings {
    // Grouped in the order shown in the app: the host groups pages by their `group`, in this order.
    val specs: List<SettingsPageSpec> = listOf(
        WirelessBasicPage,
        WirelessSecurityPage,
        WirelessGuestPage,
        WirelessMacFilterPage,
        WifiSchedulePage,
        WirelessAdvancedPage,
        DhcpPage,
        DhcpReservationsPage,
        PortForwardingPage,
        StaticRoutesPage,
        UrlFilterPage,
        DynamicDnsPage,
        DmzPage,
        UpnpPage,
        TimePage,
        SystemLogPage,
        LogSettingsPage,
        RemoteAccessPage,
        SnmpPage,
        Tr069Page,
        AdminPasswordPage,
    )

    val byId: Map<String, SettingsPageSpec> = specs.associateBy { it.info.id }
}

// ---------------------------------------------------------------------------------------------
// Wireless: basic (wlcfg.html -> wlcfg.wl)
// ---------------------------------------------------------------------------------------------

internal object WirelessBasicPage : SettingsPageSpec {
    override val info = SettingsPageInfo("wireless-basic", "Wi-Fi", "Wireless", "Name, visibility and client limit")
    override val path = "/wlcfg.html"

    const val ENABLE = "enable"
    const val SSID = "ssid"
    const val HIDE = "hide"
    const val ISOLATE = "isolate"
    const val MAX_CLIENTS = "maxClients"

    private class State(html: String) {
        val enabled = PageData.need(html, "enbl") == "1"
        val hidden = PageData.need(html, "hide") == "1"
        val isolated = PageData.need(html, "apIsolation") == "1"
        val ssid = PageData.need(html, "ssid")
        val maxClients = PageData.need(html, "maxAssoc").toLongOrNull() ?: 32
        val maxAllowed = PageData.jsVar(html, "globalmaxassoc")?.toLongOrNull() ?: 32
        val country = PageData.need(html, "country")
        val countrySelectable = PageData.jsVar(html, "CEOnly") == "0"
        val wpsEnabled = PageData.jsVar(html, "WscVer2") == "enabled" && PageData.jsVar(html, "WscMode") == "enabled"
        val scheduling = PageData.jsVar(html, "supportWlSche") == "1"
        val sessionKey = PageData.sessionKey(html)
    }

    override fun parse(html: String): SettingsPage {
        val s = State(html)
        return SettingsPage(
            info = info,
            fields = listOf(
                SettingField.Toggle(ENABLE, "Wi-Fi", s.enabled, summary = "Turns the wireless radio on or off"),
                SettingField.Text(SSID, "Network name (SSID)", s.ssid, maxLength = 32),
                SettingField.Toggle(HIDE, "Hide network name", s.hidden, summary = "Devices must type the name to join"),
                SettingField.Toggle(ISOLATE, "Client isolation", s.isolated, summary = "Wireless devices can't reach each other"),
                SettingField.Number(MAX_CLIENTS, "Maximum clients", s.maxClients, 1, s.maxAllowed),
            ),
            applyWarning = WIFI_RESTART_WARNING,
        )
    }

    override fun buildRequest(html: String, changes: Map<String, String>, context: SettingsContext): String {
        val s = State(html)
        val enable = changes.flag(ENABLE) ?: s.enabled

        // The page's script resends the OLD values when the radio is switched off, so edits made
        // in the same step would be silently dropped. Better to refuse than to pretend.
        if (!enable && changes.keys.any { it != ENABLE }) {
            throw InvalidSettingException(ENABLE, "Wi-Fi is off. Turn it on to change the other settings")
        }

        val query = StringBuilder("/wlcfg.wl?wlSsidIdx=0")
        if (!enable) {
            query.append("&wlEnbl=0&wlHide=${s.hidden.bit()}&wlAPIsolation=${s.isolated.bit()}")
                .append("&wlSsid=${FirmwareUrl.encode(s.ssid)}&wlCountry=${s.country}")
        } else {
            val ssid = changes[SSID] ?: s.ssid
            Rules.ssid(SSID, ssid)
            val maxClients = changes[MAX_CLIENTS]
                ?.let { Rules.number(MAX_CLIENTS, it, 1L..s.maxAllowed, "Maximum clients") }
                ?: s.maxClients
            val hide = changes.flag(HIDE) ?: s.hidden
            val isolate = changes.flag(ISOLATE) ?: s.isolated

            query.append("&wlEnbl=1&wlHide=${hide.bit()}&wlAPIsolation=${isolate.bit()}")
                .append("&wlSsid=${FirmwareUrl.encode(ssid)}")
            if (s.countrySelectable) query.append("&wlCountry=${s.country}")
            query.append("&wlMaxAssoc=$maxClients")
            // Hiding the SSID switches WPS off on this firmware; the page asks, we state it in the summary.
            if (hide && s.wpsEnabled) query.append("&wl_wsc_mode=disabled")
        }
        // Tells the schedule feature that the user, not the timer, changed the radio.
        if (s.scheduling && enable != s.enabled) query.append("&wlIsUsrCtrl=1")
        return query.append("&wlSyncNvram=1&sessionKey=${s.sessionKey}").toString()
    }
}

// ---------------------------------------------------------------------------------------------
// Wireless: security (wlsecurity.html -> wlsecurity.wl)
// ---------------------------------------------------------------------------------------------

internal object WirelessSecurityPage : SettingsPageSpec {
    override val info = SettingsPageInfo("wireless-security", "Wi-Fi security", "Wireless", "Password and encryption")
    override val path = "/wlsecurity.html"

    /**
     * The firmware keeps ONE server-side "selected SSID" for this page, and opening the guest
     * network page switches it to the guest (the router's own web UI has the same flaw: its SSID
     * dropdown is disabled in script, yet the data shown follows the stored selection). Its save
     * request always targets the main network, so showing or resending the guest's values would
     * overwrite the main network. This is the page's own redisplay URL with the main network
     * selected explicitly; it carries no settings and no save flag.
     */
    const val SELECT_MAIN_NETWORK = "/wlsecrefresh.wl?wlRefresh=0&wlSsidIdx=0"
    override val loadPath = SELECT_MAIN_NETWORK

    const val MODE = "mode"
    const val ENCRYPTION = "encryption"
    const val PASSWORD = "password"
    const val REKEY = "rekey"

    private const val OPEN = "open"
    private val WPA_PSK_MODES = listOf("psk2", "psk psk2", "psk")
    private const val MAX_REKEY = 2_147_483_647L

    private class State(html: String) {
        /** Which network the values belong to: 0 is the main one, 1 the guest network. */
        val ssidIndex = PageData.jsVar(html, "ssidIdx") ?: "0"
        val wirelessOn = PageData.need(html, "enblWireless") == "1"
        val mode = PageData.need(html, "mode")
        val wep = PageData.need(html, "wep")
        val wpa = PageData.jsVar(html, "wpa").orEmpty()
        val keyBit = PageData.jsVar(html, "bit") ?: "0"
        val rekey = PageData.jsVar(html, "wpaGTKRekey")?.toLongOrNull() ?: 0
        val netReauth = PageData.jsVar(html, "varNetReauth") ?: "36000"
        val preauth = PageData.jsVar(html, "varPreauth") ?: "0"
        val wps = PageData.jsVar(html, "WscMode") ?: "disabled"
        val wpsApMode = PageData.jsVar(html, "WscAPMode") ?: "1"
        val phy = PageData.jsVar(html, "phy") ?: "n"
        val nmode = PageData.jsVar(html, "nmode") ?: "auto"
        val nreqd = PageData.jsVar(html, "nreqd") ?: "0"
        val sessionKey = PageData.sessionKey(html)

        val isMainNetwork get() = ssidIndex == "0"

        val editable get() = isMainNetwork && wirelessOn && wep == "disabled" && (mode == OPEN || mode in WPA_PSK_MODES)

        private val nEnabled get() = phy == "n" && nmode != "off"

        /** Mirrors the encryption list the page builds for the radio's current 802.11 mode. */
        val algorithms: List<String>
            get() = when {
                nEnabled && nmode == "auto" && nreqd == "1" -> listOf("aes")
                nEnabled -> listOf("aes", "tkip+aes")
                else -> listOf("aes", "tkip", "tkip+aes")
            }

        /** What the page preselects when the user switches the security mode. */
        fun defaultEncryption(mode: String): String = when (mode) {
            "psk" -> if (nEnabled) "tkip+aes" else "tkip"
            "psk2" -> "aes"
            else -> "tkip+aes"
        }
    }

    override fun parse(html: String): SettingsPage {
        val s = State(html)
        if (!s.isMainNetwork) {
            return SettingsPage(
                info,
                listOf(
                    SettingField.Info(
                        "note", "Not the main network",
                        "The router is showing another network's security settings. Reopen this page; " +
                            "if it persists, restart the router.",
                    ),
                ),
            )
        }
        if (!s.wirelessOn) return readOnly(s, "Wi-Fi is off. Turn it on under Wi-Fi first.")
        if (!s.editable) {
            return readOnly(s, "This router uses ${describe(s)}, which can only be changed on its own web page.")
        }

        val modes = buildList {
            if (s.mode == OPEN) add(ChoiceOption(OPEN, "Open (no password)"))
            add(ChoiceOption("psk2", "WPA2-PSK (recommended)"))
            add(ChoiceOption("psk psk2", "Mixed WPA2/WPA-PSK"))
            add(ChoiceOption("psk", "WPA-PSK"))
        }
        val encryption = s.algorithms.map { ChoiceOption(it, encryptionLabel(it)) }
        return SettingsPage(
            info = info,
            fields = listOf(
                SettingField.Choice(MODE, "Security mode", s.mode, modes),
                SettingField.Choice(
                    ENCRYPTION, "Encryption",
                    s.wpa.takeIf { it in s.algorithms } ?: s.defaultEncryption(s.mode), encryption,
                ),
                SettingField.Secret(
                    PASSWORD, "Wi-Fi password",
                    summary = if (s.mode == OPEN) "Required to switch on WPA" else "Leave empty to keep the current password",
                    minLength = 8, maxLength = 64, requiresConfirmation = true,
                ),
                SettingField.Number(REKEY, "Group key renewal", s.rekey, 0, MAX_REKEY, summary = "0 turns renewal off", unit = "s"),
            ),
            applyWarning = WIFI_RESTART_WARNING + " Every device must reconnect with the new password.",
        )
    }

    override fun buildRequest(html: String, changes: Map<String, String>, context: SettingsContext): String {
        val s = State(html)
        // Hard stop: this request always targets the main network, so it must never be built from
        // another network's values.
        if (!s.isMainNetwork) {
            throw InvalidSettingException(MODE, "The router is showing another network's settings. Reload and try again")
        }
        if (!s.editable) throw InvalidSettingException(MODE, "These security settings can't be edited from the app")

        val mode = changes[MODE] ?: s.mode
        if (mode == OPEN) {
            // Also covers "open to open": nothing in this page applies to an unprotected network.
            throw InvalidSettingException(MODE, "Choose a WPA mode. Switching to an open network isn't offered here")
        }
        if (mode !in WPA_PSK_MODES) throw InvalidSettingException(MODE, "Unsupported security mode")

        val modeChanged = mode != s.mode
        val encryption = changes[ENCRYPTION]
            ?: if (modeChanged || s.wpa !in s.algorithms) s.defaultEncryption(mode) else s.wpa
        if (encryption !in s.algorithms) {
            throw InvalidSettingException(ENCRYPTION, "This radio mode doesn't allow that encryption")
        }

        val passphrase = changes[PASSWORD]?.takeIf { it.isNotEmpty() }
        val key = if (passphrase != null) {
            Rules.wpaPassphrase(PASSWORD, passphrase)
            passphrase
        } else {
            // Not changing the password still means resending it: the router overwrites the stored
            // key with whatever the request carries. It is read here, in memory, and never surfaced.
            if (s.mode == OPEN) throw InvalidSettingException(PASSWORD, "Enter a Wi-Fi password")
            storedPassphrase(html) ?: throw InvalidSettingException(PASSWORD, "Enter a Wi-Fi password")
        }
        val rekey = changes[REKEY]?.let { Rules.number(REKEY, it, 0L..MAX_REKEY, "Group key renewal") } ?: s.rekey

        // WPS can't run on plain WPA-PSK.
        val wps = if (mode == "psk") "disabled" else s.wps
        val configState = if (s.wpsApMode == "0" && (modeChanged || encryption != s.wpa)) "&wsc_config_state=1" else ""

        return "/wlsecurity.wl?wl_wsc_mode=$wps&wl_wsc_reg=1$configState" +
            "&wlAuthMode=$mode&wlAuth=0&wlWpaPsk=${FirmwareUrl.encode(key)}" +
            "&wlWpaGtkRekey=$rekey&wlNetReauth=${s.netReauth}" +
            "&wlWep=disabled&wlWpa=$encryption&wlKeyBit=${s.keyBit}&wlPreauth=${s.preauth}" +
            "&wlSsidIdx=0&wlSyncNvram=1&sessionKey=${s.sessionKey}"
    }

    /** Only the write path reads this; [parse] never touches the stored key. */
    private fun storedPassphrase(html: String): String? =
        PageData.jsVar(html, "wpaPskKey")?.takeUnless { it.isEmpty() || it == "(null)" }

    private fun readOnly(s: State, note: String) = SettingsPage(
        info = info,
        fields = listOf(
            SettingField.Info(MODE, "Security mode", describe(s)),
            SettingField.Info("note", "Editing", note),
        ),
    )

    private fun describe(s: State): String = when {
        !s.wirelessOn -> "Wi-Fi is off"
        s.wep == "enabled" || s.mode == "shared" -> "WEP"
        s.mode == "radius" || s.mode.startsWith("wpa") -> "WPA enterprise (RADIUS)"
        s.mode.startsWith("wapi") -> "WAPI"
        s.mode == OPEN -> "Open"
        s.mode == "psk2" -> "WPA2-PSK"
        s.mode == "psk psk2" -> "Mixed WPA2/WPA-PSK"
        s.mode == "psk" -> "WPA-PSK"
        else -> s.mode
    }

    private fun encryptionLabel(value: String) = when (value) {
        "aes" -> "AES"
        "tkip" -> "TKIP"
        else -> "TKIP+AES"
    }
}

// ---------------------------------------------------------------------------------------------
// Wireless: guest network (wlgstnwk.html -> wlgstnwk.wl)
// ---------------------------------------------------------------------------------------------

internal object WirelessGuestPage : SettingsPageSpec {
    override val info = SettingsPageInfo("wireless-guest", "Guest network", "Wireless", "A separate network for visitors")
    override val path = "/wlgstnwk.html"

    const val ENABLE = "enable"
    const val SSID = "ssid"
    const val SECURITY = "security"
    const val ENCRYPTION = "encryption"
    const val PASSWORD = "password"
    const val REKEY = "rekey"
    const val LAN_ACCESS = "lanAccess"
    const val ISOLATE = "isolate"

    private const val MAX_REKEY = 4_294_967_295L

    private class State(html: String) {
        val radioOn = PageData.need(html, "wlEnbl") == "1"
        val enabled = PageData.need(html, "gstEnbl") == "1"
        val ssid = PageData.need(html, "gstSSID")
        val primarySsid = PageData.jsVar(html, "prmSSID").orEmpty()
        val secured = PageData.need(html, "mode") == "psk"
        val encryption = if (PageData.jsVar(html, "wpa") == "aes") "aes" else "tkip+aes"
        val rekey = PageData.jsVar(html, "wpaGTKRekey")?.toLongOrNull() ?: 0
        val lanAccess = PageData.jsVar(html, "gstLanAcs") == "1"
        val isolated = PageData.jsVar(html, "wlAPIsolation") == "1"
        val sessionKey = PageData.sessionKey(html)
    }

    override fun parse(html: String): SettingsPage {
        val s = State(html)
        if (!s.radioOn) {
            return SettingsPage(
                info,
                listOf(SettingField.Info("note", "Guest network", "Turn Wi-Fi on first: the guest network needs the main radio.")),
            )
        }
        return SettingsPage(
            info = info,
            fields = listOf(
                SettingField.Toggle(ENABLE, "Guest network", s.enabled),
                SettingField.Text(SSID, "Network name (SSID)", s.ssid, maxLength = 32),
                SettingField.Choice(
                    SECURITY, "Security", if (s.secured) "psk" else "open",
                    listOf(ChoiceOption("open", "Open (no password)"), ChoiceOption("psk", "WPA-PSK")),
                ),
                SettingField.Choice(
                    ENCRYPTION, "Encryption", s.encryption,
                    listOf(ChoiceOption("aes", "AES"), ChoiceOption("tkip+aes", "AES+TKIP")),
                ),
                SettingField.Secret(
                    PASSWORD, "Guest password",
                    summary = "Leave empty to keep the current password",
                    minLength = 8, maxLength = 64, requiresConfirmation = true,
                ),
                SettingField.Number(REKEY, "Group key renewal", s.rekey, 0, MAX_REKEY, unit = "s"),
                SettingField.Toggle(LAN_ACCESS, "Access to the home network", s.lanAccess, summary = "Guests can reach your own devices"),
                SettingField.Toggle(ISOLATE, "Isolate guests", s.isolated, summary = "Guests can't reach each other"),
            ),
            applyWarning = WIFI_RESTART_WARNING,
        )
    }

    override fun buildRequest(html: String, changes: Map<String, String>, context: SettingsContext): String {
        val s = State(html)
        if (!s.radioOn) throw InvalidSettingException(ENABLE, "Turn the main Wi-Fi on first")
        val enable = changes.flag(ENABLE) ?: s.enabled

        if (!enable) {
            if (changes.keys.any { it != ENABLE }) {
                throw InvalidSettingException(ENABLE, "The guest network is off. Turn it on to change the other settings")
            }
            return "/wlgstnwk.wl?wlSsidIdx=1&wlEnbl=0&wlGntEnbl=0&wlSyncNvram=1&sessionKey=${s.sessionKey}"
        }

        val ssid = changes[SSID] ?: s.ssid
        Rules.ssid(SSID, ssid)
        if (ssid == s.primarySsid) {
            throw InvalidSettingException(SSID, "The guest network needs a different name than the main network")
        }

        val secured = (changes[SECURITY] ?: if (s.secured) "psk" else "open") == "psk"
        val encryption = changes[ENCRYPTION] ?: s.encryption
        if (encryption != "aes" && encryption != "tkip+aes") {
            throw InvalidSettingException(ENCRYPTION, "Unsupported encryption")
        }
        val lanAccess = changes.flag(LAN_ACCESS) ?: s.lanAccess
        val isolate = changes.flag(ISOLATE) ?: s.isolated

        val query = StringBuilder("/wlgstnwk.wl?wlSsidIdx=1&wlEnbl=1&wlGntEnbl=1&wlSsid=${FirmwareUrl.encode(ssid)}")
        query.append(if (secured) "&wlAuthMode=psk" else "&wlAuthMode=open")
        query.append("&wlWpa=$encryption")
        if (secured) {
            val passphrase = changes[PASSWORD]?.takeIf { it.isNotEmpty() }
                ?: storedPassphrase(html)
                ?: throw InvalidSettingException(PASSWORD, "Enter a guest password")
            Rules.wpaPassphrase(PASSWORD, passphrase)
            val rekey = changes[REKEY]?.let { Rules.number(REKEY, it, 0L..MAX_REKEY, "Group key renewal") } ?: s.rekey
            query.append("&wlWpaPsk=${FirmwareUrl.encode(passphrase)}&wlWpaGtkRekey=$rekey")
        }
        query.append("&wlGstLanAcs=${lanAccess.bit()}&wlAPIsolation=${isolate.bit()}")
        return query.append("&wlSyncNvram=1&sessionKey=${s.sessionKey}").toString()
    }

    private fun storedPassphrase(html: String): String? =
        PageData.jsVar(html, "wpaPskKey")?.takeUnless { it.isEmpty() || it == "(null)" }
}

// ---------------------------------------------------------------------------------------------
// Wireless: advanced (wlcfgadv.html), read-only
// ---------------------------------------------------------------------------------------------

/**
 * Shown but not editable. This page resubmits about 40 values whose final state is computed by a
 * dozen coupled scripts; reproducing that blind could leave the radio misconfigured, so editing
 * waits until it can be checked against live saves.
 */
internal object WirelessAdvancedPage : SettingsPageSpec {
    override val info = SettingsPageInfo("wireless-advanced", "Advanced radio", "Wireless", "Channel, power and mode (view only)")
    override val path = "/wlcfgadv.html"

    override fun parse(html: String): SettingsPage {
        fun value(name: String) = PageData.jsVar(html, name)
        val channel = value("channel")
        val current = value("wlCurrentChannel")
        val bandwidth = when (value("currbw")) {
            "2" -> "20 MHz"
            "3" -> "40 MHz"
            "4" -> "80 MHz"
            else -> null
        }
        val mode = when {
            value("nmode") == "auto" && value("nreqd") == "1" -> "802.11n only"
            value("nmode") == "auto" -> "802.11b/g/n mixed"
            else -> "Legacy 802.11b/g (n off)"
        }
        val fields = buildList<SettingField> {
            add(
                SettingField.Info(
                    "channel", "Channel",
                    if (channel == "0") "Auto" + (current?.let { " (now $it)" } ?: "") else channel ?: "-",
                ),
            )
            value("TxPwrPcnt")?.let { add(SettingField.Info("power", "Transmit power", "$it%")) }
            bandwidth?.let { add(SettingField.Info("bandwidth", "Bandwidth", it)) }
            add(SettingField.Info("mode", "Radio mode", mode))
            value("bcn")?.let { add(SettingField.Info("beacon", "Beacon interval", "$it ms")) }
            value("frg")?.let { add(SettingField.Info("fragmentation", "Fragmentation threshold", it)) }
            value("rts")?.let { add(SettingField.Info("rts", "RTS threshold", it)) }
            add(
                SettingField.Info(
                    "note", "Editing",
                    "Not available yet: the router's page sends about 40 linked values that can't be safely reproduced without testing on the device.",
                ),
            )
        }
        return SettingsPage(info, fields)
    }

    override fun buildRequest(html: String, changes: Map<String, String>, context: SettingsContext): String =
        throw InvalidSettingException("page", "The advanced radio settings are view-only for now")
}

// ---------------------------------------------------------------------------------------------
// NAT: DMZ host (scdmz.html -> scdmz.cmd)
// ---------------------------------------------------------------------------------------------

internal object DmzPage : SettingsPageSpec {
    override val info = SettingsPageInfo("dmz", "DMZ host", "Network", "Forward unsolicited traffic to one device")
    override val path = "/scdmz.html"

    const val ADDRESS = "address"

    override fun parse(html: String): SettingsPage {
        if (PageData.jsVar(html, "nat") != "1") {
            return SettingsPage(info, listOf(SettingField.Info("note", "DMZ", "NAT is off, so the DMZ host isn't available.")))
        }
        return SettingsPage(
            info = info,
            fields = listOf(
                SettingField.Text(
                    ADDRESS, "DMZ host address", PageData.jsVar(html, "addr").orEmpty(),
                    summary = "Leave empty to turn the DMZ off",
                    keyboard = SettingField.Keyboard.IP_ADDRESS,
                ),
            ),
            applyWarning = "A DMZ host receives all unsolicited traffic from the internet. Only use it for a device you trust and keep up to date.",
        )
    }

    override fun buildRequest(html: String, changes: Map<String, String>, context: SettingsContext): String {
        if (PageData.jsVar(html, "nat") != "1") throw InvalidSettingException(ADDRESS, "NAT is off")
        val address = (changes[ADDRESS] ?: PageData.jsVar(html, "addr").orEmpty()).trim()
        if (address.isNotEmpty()) Rules.ipv4(ADDRESS, address)
        return "/scdmz.cmd?address=${FirmwareUrl.encode(address)}&sessionKey=${PageData.sessionKey(html)}"
    }
}

// ---------------------------------------------------------------------------------------------
// UPnP (upnpcfg.html -> upnpcfg.cgi)
// ---------------------------------------------------------------------------------------------

internal object UpnpPage : SettingsPageSpec {
    override val info = SettingsPageInfo("upnp", "UPnP", "Network", "Let devices open ports automatically")
    override val path = "/upnpcfg.html"

    const val ENABLE = "enable"

    override fun parse(html: String): SettingsPage = SettingsPage(
        info = info,
        fields = listOf(
            SettingField.Toggle(
                ENABLE, "Enable UPnP", PageData.need(html, "enblUpnp") == "1",
                summary = "Apps and games can request port forwards without asking you",
            ),
        ),
    )

    override fun buildRequest(html: String, changes: Map<String, String>, context: SettingsContext): String {
        val enable = changes.flag(ENABLE) ?: (PageData.need(html, "enblUpnp") == "1")
        return "/upnpcfg.cgi?enblUpnp=${enable.bit()}&sessionKey=${PageData.sessionKey(html)}"
    }
}

// ---------------------------------------------------------------------------------------------
// Admin password (password.html -> password.cgi)
// ---------------------------------------------------------------------------------------------

internal object AdminPasswordPage : SettingsPageSpec {
    override val info = SettingsPageInfo("admin-password", "Router password", "System", "The password for this app and the web page")
    override val path = "/password.html"

    const val PASSWORD = "password"

    override fun parse(html: String): SettingsPage = SettingsPage(
        info = info,
        fields = listOf(
            SettingField.Secret(
                PASSWORD, "New password",
                summary = "Up to 16 characters, no spaces",
                minLength = 1, maxLength = 16, requiresConfirmation = true,
            ),
        ),
        applyWarning = "The router's other sessions are signed out. The app stores the new password for this router.",
        loginPasswordKey = PASSWORD,
    )

    override fun buildRequest(html: String, changes: Map<String, String>, context: SettingsContext): String {
        val new = changes[PASSWORD].orEmpty()
        if (new.isEmpty()) throw InvalidSettingException(PASSWORD, "Enter a new password")
        if (new.length > 16) throw InvalidSettingException(PASSWORD, "The password can be at most 16 characters")
        if (new.contains(' ')) throw InvalidSettingException(PASSWORD, "The password can't contain a space")
        if (!Rules.isAscii(new)) throw InvalidSettingException(PASSWORD, "The password may only use plain ASCII characters")
        if (new == context.password) throw InvalidSettingException(PASSWORD, "That is already the current password")

        return "/password.cgi?inUserName=${FirmwareUrl.encode(context.username)}" +
            "&inPassword=${FirmwareUrl.encode(new)}&inOrgPassword=${FirmwareUrl.encode(context.password)}" +
            "&sessionKey=${PageData.sessionKey(html)}"
    }
}
