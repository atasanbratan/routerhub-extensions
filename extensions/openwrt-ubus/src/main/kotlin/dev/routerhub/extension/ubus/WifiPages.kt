package dev.routerhub.extension.ubus

import dev.routerhub.api.ChoiceOption
import dev.routerhub.api.InvalidSettingException
import dev.routerhub.api.RouterException
import dev.routerhub.api.SettingField
import dev.routerhub.api.SettingsPage
import dev.routerhub.api.SettingsPageInfo
import dev.routerhub.api.WifiBand

private const val WIRELESS = "wireless"
private const val GROUP = "Wireless"
private const val WIFI_WARNING =
    "Applying restarts this radio. Wireless devices disconnect for a few seconds, " +
        "including this phone if it is connected over Wi-Fi. This change can't be rolled back automatically."

internal object WifiPages {
    /** A page per radio and a page per network it broadcasts. Client (uplink) networks are left alone. */
    suspend fun pages(router: UbusRouter): List<UbusPage> {
        val sections = router.uci.sections(WIRELESS)
        val radios = sections.filter { it.type == "wifi-device" }
        val networks = sections.filter { it.type == "wifi-iface" && it.str("mode", "ap") == "ap" }
        return networks.map { WifiNetworkPage(it.name, it.str("ssid")) } + radios.map { WifiRadioPage(it.name) }
    }

    fun bandName(band: WifiBand): String = when (band) {
        WifiBand.GHZ_2_4 -> "2.4 GHz"
        WifiBand.GHZ_5 -> "5 GHz"
        WifiBand.GHZ_6 -> "6 GHz"
        WifiBand.UNKNOWN -> "Unknown"
    }
}

internal class WifiNetworkPage(private val section: String, ssid: String) : UbusPage {
    override val info = SettingsPageInfo("wifi.$section", "Wi-Fi: ${ssid.ifEmpty { section }}", GROUP, "Name, password, visibility")

    private suspend fun current(router: UbusRouter) = router.uci.section(WIRELESS, section)
        ?: throw InvalidSettingException("page", "This Wi-Fi network no longer exists")

    override suspend fun read(router: UbusRouter): SettingsPage {
        val wifi = current(router)
        return SettingsPage(
            info = info,
            fields = listOf(
                SettingField.Toggle(ENABLED, "Enabled", !wifi.flag("disabled")),
                SettingField.Text(SSID, "Network name", wifi.str("ssid"), maxLength = 32),
                SettingField.Choice(
                    ENCRYPTION,
                    "Security",
                    wifi.str("encryption", "none"),
                    optionsWith(wifi.str("encryption", "none"), SECURITY),
                    summary = "WPA3 needs a full wpad on the router, and devices that support it",
                ),
                // The stored key stays on the router: it is never read into a field.
                SettingField.Secret(KEY, "New Wi-Fi password", "Leave empty to keep the current one", minLength = 8, maxLength = 64, requiresConfirmation = true),
                SettingField.Toggle(HIDDEN, "Hide network name", wifi.flag("hidden")),
                SettingField.Toggle(ISOLATE, "Isolate clients", wifi.flag("isolate"), "Devices on this network can't reach each other"),
            ),
            applyWarning = WIFI_WARNING,
        )
    }

    override suspend fun apply(router: UbusRouter, changes: Map<String, String>, username: String) {
        val wifi = current(router)
        changes[SSID]?.let { Rules.ssid(SSID, it) }
        changes[KEY]?.takeIf { it.isNotEmpty() }?.let { Rules.wpaKey(KEY, it) }
        val encryption = changes[ENCRYPTION] ?: wifi.str("encryption", "none")
        val key = changes[KEY].orEmpty()
        if (encryption !in OPEN && key.isEmpty() && !wifi.has("key")) {
            throw InvalidSettingException(KEY, "This security mode needs a password")
        }

        val values = buildMap {
            changes[SSID]?.let { put("ssid", it) }
            changes[ENCRYPTION]?.let { put("encryption", it) }
            if (key.isNotEmpty()) put("key", key)
            changes.flag(ENABLED)?.let { put("disabled", if (it) "0" else "1") }
            changes.flag(HIDDEN)?.let { put("hidden", if (it) "1" else "0") }
            changes.flag(ISOLATE)?.let { put("isolate", if (it) "1" else "0") }
        }
        router.uci.change(WIRELESS, Apply.DIRECT) { set(section, values) }
    }

    companion object {
        const val ENABLED = "enabled"
        const val SSID = "ssid"
        const val ENCRYPTION = "encryption"
        const val KEY = "key"
        const val HIDDEN = "hidden"
        const val ISOLATE = "isolate"

        private val OPEN = setOf("none", "owe")
        private val SECURITY = listOf(
            ChoiceOption("sae", "WPA3"),
            ChoiceOption("sae-mixed", "WPA2/WPA3"),
            ChoiceOption("psk2", "WPA2"),
            ChoiceOption("psk-mixed", "WPA/WPA2"),
            ChoiceOption("none", "None (open network)"),
        )
    }
}

internal class WifiRadioPage(private val radio: String) : UbusPage {
    override val info = SettingsPageInfo("radio.$radio", "Radio $radio", GROUP, "Channel, width, power, country")

    /** What the driver offers. Without a network device on the radio there is nobody to ask. */
    private suspend fun ask(router: UbusRouter, method: String, device: String?): JsonObject? {
        if (device == null) return null
        return try {
            router.api.call("iwinfo", method, mapOf("device" to device))
        } catch (_: RouterException) {
            null
        }
    }

    override suspend fun read(router: UbusRouter): SettingsPage {
        val config = router.uci.section(WIRELESS, radio) ?: throw InvalidSettingException("page", "This radio no longer exists")
        val state = UbusStatusReader.radios(router).firstOrNull { it.name == radio }
        val device = state?.device

        val channel = config.str("channel", AUTO)
        val channels = ask(router, "freqlist", device)?.objects("results").orEmpty()
            .filterNot { it["restricted"] == true }
            .map { ChoiceOption(it.str("channel"), "${it.str("channel")} (${it.str("mhz")} MHz)") }

        val width = config.str("htmode")
        val widths = (ask(router, "info", device)?.get("htmodes") as? List<*>).orEmpty()
            .map { ChoiceOption(it.toString(), it.toString()) }

        val power = config.str("txpower", AUTO)
        val powers = ask(router, "txpowerlist", device)?.objects("results").orEmpty()
            .map { ChoiceOption(it.str("dbm"), "${it.str("dbm")} dBm (${it.str("mw")} mW)") }

        return SettingsPage(
            info = info,
            fields = buildList {
                add(SettingField.Info(BAND, "Band", WifiPages.bandName(state?.band ?: WifiBand.UNKNOWN)))
                add(SettingField.Toggle(ENABLED, "Enabled", !config.flag("disabled")))
                add(SettingField.Choice(CHANNEL, "Channel", channel, optionsWith(channel, listOf(ChoiceOption(AUTO, "Automatic")) + channels)))
                if (width.isNotEmpty() && widths.isNotEmpty()) {
                    add(SettingField.Choice(WIDTH, "Channel width", width, optionsWith(width, widths)))
                }
                add(SettingField.Choice(POWER, "Transmit power", power, optionsWith(power, listOf(ChoiceOption(AUTO, "Driver default")) + powers)))
                add(SettingField.Text(COUNTRY, "Country code", config.str("country"), "Decides the channels and power that are legal", maxLength = 2))
            },
            applyWarning = WIFI_WARNING,
        )
    }

    override suspend fun apply(router: UbusRouter, changes: Map<String, String>, username: String) {
        val country = changes[COUNTRY]?.let { Rules.country(COUNTRY, it) }
        val deletePower = changes[POWER] == AUTO
        val values = buildMap {
            changes[CHANNEL]?.let { put("channel", it) }
            changes[WIDTH]?.let { put("htmode", it) }
            changes[POWER]?.takeIf { !deletePower }?.let { put("txpower", it) }
            country?.let { put("country", it) }
            changes.flag(ENABLED)?.let { put("disabled", if (it) "0" else "1") }
        }
        val hadPower = router.uci.section(WIRELESS, radio)?.has("txpower") == true
        router.uci.change(WIRELESS, Apply.DIRECT) {
            set(radio, values)
            if (deletePower && hadPower) deleteOption(radio, "txpower")
        }
    }

    companion object {
        const val AUTO = "auto"
        const val BAND = "band"
        const val ENABLED = "enabled"
        const val CHANNEL = "channel"
        const val WIDTH = "width"
        const val POWER = "power"
        const val COUNTRY = "country"
    }
}
