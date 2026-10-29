package dev.routerhub.extension.tenda

import dev.routerhub.api.ChoiceOption
import dev.routerhub.api.InvalidSettingException
import dev.routerhub.api.SettingField
import dev.routerhub.api.SettingsPage
import dev.routerhub.api.SettingsPageInfo

// ---------------------------------------------------------------------------------------------
// Wi-Fi: WifiBasicGet / WifiBasicSet
// ---------------------------------------------------------------------------------------------

internal object WifiPage : TendaPage {
    override val info = SettingsPageInfo("wifi", "Wi-Fi", "Wireless", "Names, passwords and visibility of both bands")

    const val RADIO_24 = "2g"
    const val RADIO_5 = "5g"

    const val SAME = "same"

    private val SECURITY = listOf(
        ChoiceOption("none", "None (open network)"),
        ChoiceOption("wpa2psk", "WPA2-PSK (AES)"),
    )

    /** The field keys of one band, and the suffix the router puts on that band's own keys. */
    private class Keys(val radio: String, val title: String, val suffix: String, tag: String) {
        val enable = "enable$tag"
        val ssid = "ssid$tag"
        val hide = "hide$tag"
        val security = "security$tag"
        val password = "password$tag"
    }

    private val band24 = Keys(RADIO_24, "2.4 GHz", "", "24")
    private val band5 = Keys(RADIO_5, "5 GHz", "_5g", "5")

    internal data class Band(
        val enabled: Boolean,
        val ssid: String,
        val hidden: Boolean,
        val security: String,
        /** What the router holds. It is never shown: the page asks for a new one or keeps this. */
        val password: String,
    )

    internal class State(val same: Boolean, val band24: Band, val band5: Band)

    private fun band(g: JsonObject, suffix: String) = Band(
        enabled = g.isOn("wrlEn$suffix"),
        ssid = g.str("ssid$suffix"),
        hidden = g.isOn("hideSsid$suffix"),
        security = g.str("security$suffix").ifEmpty { "none" },
        password = g.str("wrlPwd$suffix"),
    )

    private suspend fun state(api: TendaApi): State {
        val g = api.read("WifiBasicGet").asObject("WifiBasicGet")
        return State(g.isOn("doubleBand"), band(g, band24.suffix), band(g, band5.suffix))
    }

    override suspend fun read(api: TendaApi): SettingsPage {
        val s = state(api)
        return SettingsPage(
            info = info,
            fields = buildList {
                add(SettingField.Toggle(SAME, "Same name and password on both bands", s.same, summary = "The 5 GHz band then copies every 2.4 GHz setting"))
                addAll(fields(band24, s.band24))
                addAll(fields(band5, s.band5))
            },
            applyWarning = WIFI_RESTART_WARNING,
        )
    }

    private fun fields(keys: Keys, band: Band) = listOf(
        SettingField.Toggle(keys.enable, "${keys.title} Wi-Fi", band.enabled, summary = "Turns this band on or off"),
        SettingField.Text(keys.ssid, "${keys.title} network name (SSID)", band.ssid, maxLength = 32),
        SettingField.Toggle(keys.hide, "Hide ${keys.title} network name", band.hidden, summary = "Devices must type the name to join"),
        SettingField.Choice(keys.security, "${keys.title} security", band.security, SECURITY),
        SettingField.Secret(keys.password, "${keys.title} password", summary = "Leave empty to keep the current password", minLength = 8, maxLength = 64),
    )

    override suspend fun apply(api: TendaApi, changes: Map<String, String>, context: TendaContext) {
        val s = state(api)
        val same = changes.flag(SAME) ?: s.same
        val b24 = merge(band24, s.band24, changes)
        // With one name for both bands the router copies the 2.4 GHz values over the 5 GHz ones, so
        // whatever was typed for the 5 GHz band is moot.
        val b5 = if (same) b24 else merge(band5, s.band5, changes)
        submit(api, same, b24, b5)
    }

    private fun merge(keys: Keys, current: Band, changes: Map<String, String>): Band {
        val enabled = changes.flag(keys.enable) ?: current.enabled
        val ssid = changes[keys.ssid] ?: current.ssid
        val hidden = changes.flag(keys.hide) ?: current.hidden
        val security = changes[keys.security] ?: current.security
        val typedPassword = changes[keys.password].orEmpty()

        Rules.oneOf(keys.security, security, SECURITY.map { it.value }, "The security")
        // The same safeguard as on the other routers: never quietly turn a protected network into an open one.
        if (security == "none" && current.security != "none") {
            throw InvalidSettingException(keys.security, "Refusing to remove the password of the ${keys.title} network: anyone nearby could join. Use the router's own page if you really want that")
        }
        // A band that is off keeps whatever name it has, but a name somebody typed is checked either way:
        // an empty or oversized one must never reach the router.
        if (enabled || ssid != current.ssid) Rules.ssid(keys.ssid, ssid)

        val password = if (security == "none") "" else typedPassword.ifEmpty { current.password }
        if (enabled && security != "none") {
            if (password.isEmpty()) throw InvalidSettingException(keys.password, "Enter a password (8 to 63 characters) for the ${keys.title} network")
            if (typedPassword.isNotEmpty()) Rules.wpaPassphrase(keys.password, typedPassword)
        }
        return Band(enabled, ssid, hidden, security, password)
    }

    private suspend fun submit(api: TendaApi, same: Boolean, b24: Band, b5: Band) {
        api.write(
            "WifiBasicSet",
            Form.of(
                "doubleBand" to same.bit(),
                "wrlEn" to b24.enabled.bit(),
                "wrlEn_5g" to b5.enabled.bit(),
                "security" to b24.security,
                "security_5g" to b5.security,
                "ssid" to b24.ssid,
                "ssid_5g" to b5.ssid,
                "hideSsid" to b24.hidden.bit(),
                "hideSsid_5g" to b5.hidden.bit(),
                "wrlPwd" to b24.password,
                "wrlPwd_5g" to b5.password,
            ),
        )
    }

    /** The Wi-Fi switch of the router screen: [radioId] is [RADIO_24], [RADIO_5] or `null` for both. */
    suspend fun setEnabled(api: TendaApi, radioId: String?, enabled: Boolean) {
        if (radioId != null && radioId != RADIO_24 && radioId != RADIO_5) {
            throw InvalidSettingException("radio", "Unknown Wi-Fi radio: $radioId")
        }
        val s = state(api)
        if (radioId == RADIO_5 && s.same) {
            throw InvalidSettingException(SAME, "Both bands share one setting. Turn off \"Same name and password on both bands\" to switch only the 5 GHz band")
        }
        val b24 = if (radioId == null || radioId == RADIO_24) s.band24.copy(enabled = enabled) else s.band24
        val b5 = if (s.same) b24 else if (radioId == null || radioId == RADIO_5) s.band5.copy(enabled = enabled) else s.band5
        submit(api, s.same, b24, b5)
    }
}

// ---------------------------------------------------------------------------------------------
// Guest network: WifiGuestGet / WifiGuestSet
// ---------------------------------------------------------------------------------------------

internal object GuestWifiPage : TendaPage {
    override val info = SettingsPageInfo("guest-wifi", "Guest network", "Wireless", "A separate network for visitors")

    const val ENABLE = "enable"
    const val SSID_24 = "ssid24"
    const val SSID_5 = "ssid5"
    const val OPEN = "open"
    const val PASSWORD = "password"
    const val HOURS = "hours"
    const val LIMIT = "limit"

    private val DURATIONS = listOf(
        ChoiceOption("0", "Always on"),
        ChoiceOption("4", "Switch off after 4 hours"),
        ChoiceOption("8", "Switch off after 8 hours"),
    )

    /** The router counts the shared bandwidth in KB/s; the web UI shows Mbps, which is 128 of those. */
    private const val KBPS_PER_MBPS = 128L

    private class State(g: JsonObject) {
        val enabled = g.isOn("guestEn")
        val ssid24 = g.str("guestSsid")
        val ssid5 = g.str("guestSsid_5g")
        val password = g.str("guestWrlPwd")
        val hours = g.str("effectiveTime").ifEmpty { "0" }
        val limitMbps = (g.num("shareSpeed") ?: 0L) / KBPS_PER_MBPS

        // The guest network lives on the radios of an access point: the router's own page refuses it otherwise.
        val usable = g.str("wl_mode").let { it.isEmpty() || it == "ap" } && g.str("wl_en") != "0"
    }

    private suspend fun state(api: TendaApi) = State(api.read("WifiGuestGet").asObject("WifiGuestGet"))

    override suspend fun read(api: TendaApi): SettingsPage {
        val s = state(api)
        return SettingsPage(
            info = info,
            fields = listOf(
                SettingField.Toggle(ENABLE, "Guest network", s.enabled, summary = "Visitors get internet access, not your local devices"),
                SettingField.Text(SSID_24, "2.4 GHz guest network name", s.ssid24, maxLength = 32),
                SettingField.Text(SSID_5, "5 GHz guest network name", s.ssid5, maxLength = 32),
                SettingField.Toggle(OPEN, "No password", s.password.isEmpty(), summary = "An open guest network lets anyone nearby use your internet"),
                SettingField.Secret(PASSWORD, "Guest password", summary = "Leave empty to keep the current password", minLength = 8, maxLength = 64),
                SettingField.Choice(HOURS, "Stays on", s.hours, DURATIONS),
                SettingField.Number(LIMIT, "Shared bandwidth for guests", s.limitMbps, 0, 1000, summary = "0 means unlimited", unit = "Mbps"),
            ),
            applyWarning = WIFI_RESTART_WARNING,
        )
    }

    override suspend fun apply(api: TendaApi, changes: Map<String, String>, context: TendaContext) {
        val s = state(api)
        val enabled = changes.flag(ENABLE) ?: s.enabled
        val ssid24 = changes[SSID_24] ?: s.ssid24
        val ssid5 = changes[SSID_5] ?: s.ssid5
        val open = changes.flag(OPEN) ?: s.password.isEmpty()
        val typedPassword = changes[PASSWORD].orEmpty()
        val hours = changes[HOURS] ?: s.hours
        val limit = changes[LIMIT]?.let { Rules.number(LIMIT, it, 0L..1000L, "The bandwidth limit") } ?: s.limitMbps

        Rules.oneOf(HOURS, hours, DURATIONS.map { it.value }, "The duration")
        if (enabled) {
            if (!s.usable) throw InvalidSettingException(ENABLE, "The guest network needs Wi-Fi to be on")
            Rules.ssid(SSID_24, ssid24)
            Rules.ssid(SSID_5, ssid5)
        }
        val password = if (open) "" else typedPassword.ifEmpty { s.password }
        if (enabled && !open) {
            if (password.isEmpty()) throw InvalidSettingException(PASSWORD, "Enter a guest password, or choose \"No password\"")
            if (typedPassword.isNotEmpty()) Rules.wpaPassphrase(PASSWORD, typedPassword)
        }

        val security = if (password.isEmpty()) "none" else "wpapsk"
        api.write(
            "WifiGuestSet",
            Form.of(
                "guestEn" to enabled.bit(),
                "guestEn_5g" to enabled.bit(),
                "guestSecurity" to security,
                "guestSecurity_5g" to security,
                "guestSsid" to ssid24,
                "guestSsid_5g" to ssid5,
                "guestWrlPwd" to password,
                "guestWrlPwd_5g" to password,
                "effectiveTime" to hours,
                "shareSpeed" to (limit * KBPS_PER_MBPS).toString(),
            ),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Channel and mode: WifiRadioGet / WifiRadioSet
// ---------------------------------------------------------------------------------------------

internal object WifiChannelPage : TendaPage {
    override val info = SettingsPageInfo("wifi-channel", "Channel and mode", "Wireless", "Radio mode, channel width and channel")

    const val MODE_24 = "mode24"
    const val WIDTH_24 = "width24"
    const val CHANNEL_24 = "channel24"
    const val MODE_5 = "mode5"
    const val WIDTH_5 = "width5"
    const val CHANNEL_5 = "channel5"

    private val MODES_24 = listOf(
        ChoiceOption("bgn", "802.11b/g/n"),
        ChoiceOption("bg", "802.11b/g"),
        ChoiceOption("n only", "802.11n only"),
    )
    private val MODES_5 = listOf(
        ChoiceOption("ac", "802.11a/n/ac"),
        ChoiceOption("ac only", "802.11ac only"),
    )
    private val WIDTHS_24 = listOf(ChoiceOption("20", "20 MHz"), ChoiceOption("40", "40 MHz"), ChoiceOption("auto", "20/40 MHz (auto)"))
    private val WIDTHS_5 = listOf(
        ChoiceOption("20", "20 MHz"),
        ChoiceOption("40", "40 MHz"),
        ChoiceOption("80", "80 MHz"),
        ChoiceOption("auto", "20/40/80 MHz (auto)"),
    )

    private class State(g: JsonObject) {
        val country = g.str("adv_country")
        val mode24 = g.str("adv_mode").ifEmpty { "bgn" }
        val width24 = g.str("adv_band").ifEmpty { "auto" }
        val channel24 = g.str("adv_channel").ifEmpty { "0" }
        val currentChannel24 = g.str("adv_current_channel")
        val currentWidth24 = g.str("adv_current_band")
        val channels24 = (g["channel"] as? List<*>).orEmpty().map { it.toString() }.ifEmpty { listOf("0") }

        val mode5 = g.str("adv_mode_5g").ifEmpty { "ac" }
        val width5 = g.str("adv_band_5g").ifEmpty { "auto" }
        val channel5 = g.str("adv_channel_5g").ifEmpty { "0" }
        val currentChannel5 = g.str("adv_current_channel_5g")
        val currentWidth5 = g.str("adv_current_band_5g")

        /** Which 5 GHz channels the router allows for each channel width. */
        val channels5: Map<String, List<String>> =
            (g["channel_5g"] as? Map<*, *>).orEmpty().entries.associate { (width, list) ->
                width.toString() to (list as? List<*>).orEmpty().map { it.toString() }
            }
    }

    private suspend fun state(api: TendaApi) = State(api.read("WifiRadioGet").asObject("WifiRadioGet"))

    private fun channelOptions(channels: Collection<String>) =
        channels.map { ChoiceOption(it, if (it == "0") "Auto" else it) }

    override suspend fun read(api: TendaApi): SettingsPage {
        val s = state(api)
        val all5 = s.channels5.values.flatten().distinct().sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
        return SettingsPage(
            info = info,
            fields = listOf(
                SettingField.Info("country", "Country or region", s.country),
                SettingField.Choice(MODE_24, "2.4 GHz mode", s.mode24, MODES_24),
                SettingField.Choice(WIDTH_24, "2.4 GHz channel width", s.width24, WIDTHS_24),
                SettingField.Choice(CHANNEL_24, "2.4 GHz channel", s.channel24, channelOptions(s.channels24),
                    summary = "Now: channel ${s.currentChannel24} at ${s.currentWidth24} MHz"),
                SettingField.Choice(MODE_5, "5 GHz mode", s.mode5, MODES_5),
                SettingField.Choice(WIDTH_5, "5 GHz channel width", s.width5, WIDTHS_5),
                SettingField.Choice(CHANNEL_5, "5 GHz channel", s.channel5, channelOptions(all5),
                    summary = "Now: channel ${s.currentChannel5} at ${s.currentWidth5} MHz"),
            ),
            applyWarning = WIFI_RESTART_WARNING,
        )
    }

    override suspend fun apply(api: TendaApi, changes: Map<String, String>, context: TendaContext) {
        val s = state(api)
        val mode24 = changes[MODE_24] ?: s.mode24
        val width24 = changes[WIDTH_24] ?: s.width24
        val channel24 = changes[CHANNEL_24] ?: s.channel24
        val mode5 = changes[MODE_5] ?: s.mode5
        val width5 = changes[WIDTH_5] ?: s.width5
        val channel5 = changes[CHANNEL_5] ?: s.channel5

        Rules.oneOf(MODE_24, mode24, MODES_24.map { it.value }, "The 2.4 GHz mode")
        Rules.oneOf(WIDTH_24, width24, WIDTHS_24.map { it.value }, "The 2.4 GHz channel width")
        Rules.oneOf(CHANNEL_24, channel24, s.channels24, "The 2.4 GHz channel")
        Rules.oneOf(MODE_5, mode5, MODES_5.map { it.value }, "The 5 GHz mode")
        Rules.oneOf(WIDTH_5, width5, WIDTHS_5.map { it.value }, "The 5 GHz channel width")

        // The page's own rules: without 11n there is no wide channel, and channel 14 is not usable with a wide one.
        if (mode24 == "bg" && width24 != "20") throw InvalidSettingException(WIDTH_24, "802.11b/g only works with a 20 MHz channel")
        if (channel24 == "14" && width24 != "20") throw InvalidSettingException(CHANNEL_24, "Channel 14 only works with a 20 MHz channel")
        val allowed5 = s.channels5[if (width5 == "auto") (if ("80" in s.channels5) "80" else "40") else width5].orEmpty()
        if (channel5 !in allowed5) {
            throw InvalidSettingException(CHANNEL_5, "Channel $channel5 is not available at ${width5.replace("auto", "this")} width (use ${allowed5.joinToString(", ")})")
        }

        api.write(
            "WifiRadioSet",
            // The page posts its form as it is serialised, where a space is a plus sign ("n only").
            listOf(
                "adv_mode" to mode24, "adv_channel" to channel24, "adv_band" to width24,
                "adv_mode_5g" to mode5, "adv_channel_5g" to channel5, "adv_band_5g" to width5,
            ).joinToString("&") { (key, value) -> "$key=${Form.component(value).replace("%20", "+")}" },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Power, WPS and beamforming: WifiPowerGet/Set, WifiWpsGet/Set, WifiBeamformingGet/Set
// ---------------------------------------------------------------------------------------------

internal object WifiExtrasPage : TendaPage {
    override val info = SettingsPageInfo("wifi-extras", "Wi-Fi advanced", "Wireless", "Transmit power, WPS and beamforming")

    const val POWER_24 = "power24"
    const val POWER_5 = "power5"
    const val WPS = "wps"
    const val BEAMFORMING = "beamforming"

    private val POWER = listOf(ChoiceOption("low", "Low"), ChoiceOption("middle", "Medium"), ChoiceOption("high", "High"))

    override suspend fun read(api: TendaApi): SettingsPage {
        val power = api.read("WifiPowerGet").asObject("WifiPowerGet")
        val wps = api.read("WifiWpsGet").asObject("WifiWpsGet")
        val beamforming = api.read("WifiBeamformingGet").asObject("WifiBeamformingGet")
        return SettingsPage(
            info = info,
            fields = listOf(
                SettingField.Choice(POWER_24, "2.4 GHz transmit power", power.str("power").ifEmpty { "high" }, POWER),
                SettingField.Choice(POWER_5, "5 GHz transmit power", power.str("power_5g").ifEmpty { "high" }, POWER),
                SettingField.Toggle(WPS, "WPS", wps.isOn("wpsEn"), summary = "Join a device with a button or PIN; switch it off if you don't use it"),
                SettingField.Toggle(BEAMFORMING, "Beamforming", beamforming.isOn("beamformingEn"), summary = "Aims the signal at each device"),
            ),
            applyWarning = WIFI_RESTART_WARNING,
        )
    }

    override suspend fun apply(api: TendaApi, changes: Map<String, String>, context: TendaContext) {
        changes[POWER_24]?.let { Rules.oneOf(POWER_24, it, POWER.map { o -> o.value }, "The transmit power") }
        changes[POWER_5]?.let { Rules.oneOf(POWER_5, it, POWER.map { o -> o.value }, "The transmit power") }

        if (POWER_24 in changes || POWER_5 in changes) {
            val power = api.read("WifiPowerGet").asObject("WifiPowerGet")
            api.write(
                "WifiPowerSet",
                Form.of(
                    "power" to (changes[POWER_24] ?: power.str("power").ifEmpty { "high" }),
                    "power_5g" to (changes[POWER_5] ?: power.str("power_5g").ifEmpty { "high" }),
                ),
            )
        }
        changes.flag(WPS)?.let { api.write("WifiWpsSet", Form.of("wpsEn" to it.bit())) }
        changes.flag(BEAMFORMING)?.let { api.write("WifiBeamformingSet", Form.of("beamformingEn" to it.bit())) }
    }
}
