package dev.routerhub.extension.tenda

import dev.routerhub.api.ChoiceOption
import dev.routerhub.api.ColumnKind
import dev.routerhub.api.InvalidSettingException
import dev.routerhub.api.SettingField
import dev.routerhub.api.SettingsPage
import dev.routerhub.api.SettingsPageInfo
import dev.routerhub.api.TableColumn
import java.time.LocalDateTime
import java.time.format.DateTimeParseException

// ---------------------------------------------------------------------------------------------
// Date and time: GetSysTimeCfg / SetSysTimeCfg
// ---------------------------------------------------------------------------------------------

internal object TimePage : TendaPage {
    override val info = SettingsPageInfo("time", "Date and time", "System", "Clock and time zone")

    const val MODE = "mode"
    const val ZONE = "zone"
    const val TIME = "time"

    private val MODES = listOf(
        ChoiceOption("sync", "Synchronise with the internet"),
        ChoiceOption("manual", "Set manually"),
    )

    /** The router's own list: the value is the zone's offset from GMT-12:00, as the router counts it. */
    internal val ZONES = listOf(
        ChoiceOption("0:00", "(GMT-12:00) Eniwetok Island"),
        ChoiceOption("1:00", "(GMT-11:00) Samoa"),
        ChoiceOption("2:00", "(GMT-10:00) Hawaii"),
        ChoiceOption("3:00", "(GMT-09:00) Alaska"),
        ChoiceOption("4:00", "(GMT-08:00) San Francisco"),
        ChoiceOption("5:00", "(GMT-07:00) Denver"),
        ChoiceOption("6:00", "(GMT-06:00) Mexico City, Guatemala, Costa Rica, Salvador, Nicaragua"),
        ChoiceOption("7:00", "(GMT-05:00) New York, Ottawa"),
        ChoiceOption("8:00", "(GMT-04:00) Chile, Brazil"),
        ChoiceOption("8:30", "(GMT-03:30) Newfoundland and Labrador"),
        ChoiceOption("9:00", "(GMT-03:00) Buenos Aires"),
        ChoiceOption("10:00", "(GMT-02:00) Mid-Atlantic"),
        ChoiceOption("11:00", "(GMT-01:00) Cape Verde Islands"),
        ChoiceOption("12:00", "(GMT) Greenwich Mean Time"),
        ChoiceOption("13:00", "(GMT+01:00) Denmark, Germany, Norway, Hungary, France, Belgium"),
        ChoiceOption("14:10", "(GMT+02:00) Kaliningrad"),
        ChoiceOption("14:00", "(GMT+02:00) Israel, Egypt, Bucharest"),
        ChoiceOption("15:10", "(GMT+03:00) Moscow, St. Petersburg, Kazan, Volgograd, Nizhny Novgorod, Krasnodar"),
        ChoiceOption("15:00", "(GMT+03:00) Moscow"),
        ChoiceOption("15:30", "(GMT+03:30) Tehran"),
        ChoiceOption("16:10", "(GMT+04:00) Samara, Izhevsk"),
        ChoiceOption("16:00", "(GMT+04:00) Sultanate of Oman, Mauritania, Reunion Island"),
        ChoiceOption("16:30", "(GMT+04:30) Kabul"),
        ChoiceOption("17:10", "(GMT+05:00) Ekaterinburg"),
        ChoiceOption("17:00", "(GMT+05:00) Pakistan, Novaya Zemlya, Maldives"),
        ChoiceOption("17:30", "(GMT+05:30) Chennai, Kolkata, Mumbai, New Delhi"),
        ChoiceOption("17:45", "(GMT+05:45) Kathmandu"),
        ChoiceOption("18:10", "(GMT+06:00) Novosibirsk, Omsk, Tomsk, Barnaul, Gorno-Altaisk, Kemerovo"),
        ChoiceOption("18:00", "(GMT+06:00) Colombo"),
        ChoiceOption("18:30", "(GMT+06:30) Yangon"),
        ChoiceOption("19:10", "(GMT+07:00) Krasnoyarsk"),
        ChoiceOption("19:00", "(GMT+07:00) Bangkok, Jakarta"),
        ChoiceOption("20:10", "(GMT+08:00) Irkutsk"),
        ChoiceOption("20:00", "(GMT+08:00) Beijing, Chongqing, Hong Kong, Urumqi, Taipei"),
        ChoiceOption("21:10", "(GMT+09:00) Yakutsk"),
        ChoiceOption("21:00", "(GMT+09:00) Tokyo, Pyongyang"),
        ChoiceOption("21:30", "(GMT+09:30) Darwin"),
        ChoiceOption("22:10", "(GMT+10:00) Vladivostok"),
        ChoiceOption("22:00", "(GMT+10:00) Sydney, Guam"),
        ChoiceOption("23:10", "(GMT+11:00) Magadan"),
        ChoiceOption("23:00", "(GMT+11:00) Solomon Islands"),
        ChoiceOption("24:10", "(GMT+12:00) Peninsula Kamchatka"),
        ChoiceOption("24:00", "(GMT+12:00) Wellington"),
        ChoiceOption("25:00", "(GMT+13:00) Nuku'alofa"),
        ChoiceOption("26:00", "(GMT+14:00) Christmas Island"),
    )

    private val CLOCK = Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""")

    private fun zoneOptions(current: String) =
        if (ZONES.any { it.value == current } || current.isEmpty()) ZONES else ZONES + ChoiceOption(current, "Zone $current")

    override suspend fun read(api: TendaApi): SettingsPage {
        val g = api.read("GetSysTimeCfg").asObject("GetSysTimeCfg")
        val zone = g.str("timeZone")
        return SettingsPage(
            info = info,
            fields = listOf(
                SettingField.Info(
                    "now", "Router time", g.str("time"),
                    summary = if (g.str("isSyncInternetTime") == "true") "Synchronised with internet time" else "Not synchronised with internet time",
                ),
                SettingField.Choice(MODE, "Set the time", g.str("timeType").ifEmpty { "sync" }, MODES),
                SettingField.Choice(ZONE, "Time zone", zone.ifEmpty { ZONES.first().value }, zoneOptions(zone)),
                SettingField.Text(TIME, "Time to set", g.str("time"), summary = "Used when set manually. Format: YYYY-MM-DD HH:MM:SS"),
            ),
        )
    }

    override suspend fun apply(api: TendaApi, changes: Map<String, String>, context: TendaContext) {
        val g = api.read("GetSysTimeCfg").asObject("GetSysTimeCfg")
        val mode = changes[MODE] ?: g.str("timeType").ifEmpty { "sync" }
        val zone = changes[ZONE] ?: g.str("timeZone")
        Rules.oneOf(MODE, mode, MODES.map { it.value }, "The way to set the time")
        Rules.oneOf(ZONE, zone, zoneOptions(g.str("timeZone")).map { it.value }, "The time zone")

        // Synchronising ignores the clock fields, but the page sends what it has: the router's time.
        val time = if (mode == "manual") (changes[TIME]?.trim() ?: g.str("time")) else g.str("time")
        if (mode == "manual") validateClock(time)

        api.write(
            "SetSysTimeCfg",
            Form.of(
                "timeType" to mode,
                "timePeriod" to g.str("timePeriod"),
                "ntpServer" to g.str("ntpServer"),
                "timeZone" to zone,
                "time" to time,
            ),
        )
    }

    private fun validateClock(time: String) {
        val valid = CLOCK.matches(time) && try {
            LocalDateTime.parse(time.replace(' ', 'T')).year in 1800..9999
        } catch (_: DateTimeParseException) {
            false
        }
        if (!valid) throw InvalidSettingException(TIME, "\"$time\" is not a valid date and time (use YYYY-MM-DD HH:MM:SS)")
    }
}

// ---------------------------------------------------------------------------------------------
// LEDs: GetLEDCfg / SetLEDCfg
// ---------------------------------------------------------------------------------------------

internal object LedPage : TendaPage {
    override val info = SettingsPageInfo("led", "LED", "System", "Switch the indicator lights off, for example at night")

    const val MODE = "mode"
    const val WINDOW = "window"

    private val MODES = listOf(
        ChoiceOption("open", "Always on"),
        ChoiceOption("close", "Always off"),
        ChoiceOption("time", "Off during a time window"),
    )

    private val WINDOW_FORMAT = Regex("""([01]\d|2[0-3]):[0-5]\d-([01]\d|2[0-3]):[0-5]\d""")

    override suspend fun read(api: TendaApi): SettingsPage {
        val g = api.read("GetLEDCfg").asObject("GetLEDCfg")
        return SettingsPage(
            info = info,
            fields = listOf(
                SettingField.Choice(MODE, "LEDs", g.str("ledType").ifEmpty { "open" }, MODES),
                SettingField.Text(WINDOW, "Off from - to", g.str("time"), summary = "Used for the time window. Format: HH:MM-HH:MM, for example 00:00-07:00"),
            ),
        )
    }

    override suspend fun apply(api: TendaApi, changes: Map<String, String>, context: TendaContext) {
        val g = api.read("GetLEDCfg").asObject("GetLEDCfg")
        val mode = changes[MODE] ?: g.str("ledType").ifEmpty { "open" }
        Rules.oneOf(MODE, mode, MODES.map { it.value }, "The LED mode")

        val window = if (mode == "time") changes[WINDOW]?.trim() ?: g.str("time") else g.str("time")
        if (mode == "time") {
            if (!WINDOW_FORMAT.matches(window)) throw InvalidSettingException(WINDOW, "\"$window\" is not a time window (use HH:MM-HH:MM)")
            if (window.substringBefore('-') == window.substringAfter('-')) throw InvalidSettingException(WINDOW, "The start and the end of the window must differ")
        }
        api.write("SetLEDCfg", Form.of("ledType" to mode, "time" to window, "ledCloseType" to g.str("ledCloseType")))
    }
}

// ---------------------------------------------------------------------------------------------
// Scheduled reboot: GetSysAutoRebbotCfg / SetSysAutoRebbotCfg
// ---------------------------------------------------------------------------------------------

internal object AutoRebootPage : TendaPage {
    override val info = SettingsPageInfo("auto-reboot", "Scheduled reboot", "System", "Restart the router by itself at a fixed time")

    const val ENABLE = "enable"
    const val TIME = "time"
    const val DELAY = "delay"

    private val CLOCK = Regex("""([01]\d|2[0-3]):[0-5]\d""")

    override suspend fun read(api: TendaApi): SettingsPage {
        val g = api.read("GetSysAutoRebbotCfg").asObject("GetSysAutoRebbotCfg")
        return SettingsPage(
            info = info,
            fields = listOf(
                SettingField.Toggle(ENABLE, "Scheduled reboot", g.isOn("autoRebootEn"), summary = "Only works while the router's clock is synchronised with the internet"),
                SettingField.Text(TIME, "Reboot at", g.str("rebootTime"), summary = "Format: HH:MM"),
                SettingField.Toggle(DELAY, "Wait while the network is in use", g.str("delayRebootEn") == "true",
                    summary = "Postpones the reboot while a device is transferring data faster than ${g.str("speed").ifEmpty { "3" }} KB/s"),
            ),
        )
    }

    override suspend fun apply(api: TendaApi, changes: Map<String, String>, context: TendaContext) {
        val g = api.read("GetSysAutoRebbotCfg").asObject("GetSysAutoRebbotCfg")
        val enable = changes.flag(ENABLE) ?: g.isOn("autoRebootEn")
        val time = changes[TIME]?.trim() ?: g.str("rebootTime")
        val delay = changes.flag(DELAY) ?: (g.str("delayRebootEn") == "true")
        if ((enable || TIME in changes) && !CLOCK.matches(time)) {
            throw InvalidSettingException(TIME, "\"$time\" is not a time of day (use HH:MM, for example 03:30)")
        }
        api.write("SetSysAutoRebbotCfg", Form.of("autoRebootEn" to enable.bit(), "delayRebootEn" to delay.toString(), "rebootTime" to time))
    }
}

// ---------------------------------------------------------------------------------------------
// System log: GetSySLogCfg (read only)
// ---------------------------------------------------------------------------------------------

internal object SystemLogPage : TendaPage {
    override val info = SettingsPageInfo("log", "System log", "System", "Recent messages from the router")

    const val LOG = "log"

    override suspend fun read(api: TendaApi): SettingsPage {
        val rows = api.read("GetSySLogCfg").asList("GetSySLogCfg").mapNotNull { entry ->
            (entry as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }?.let { e ->
                listOf(e.str("time"), e.str("type"), e.str("log"))
            }
        }.asReversed() // the router lists the oldest entry first
        return SettingsPage(
            info = info,
            fields = listOf(
                SettingField.Table(
                    LOG, "Log", listOf(
                        TableColumn("time", "Time", ColumnKind.TEXT, shortTitle = ""),
                        TableColumn("type", "Type", ColumnKind.TEXT, shortTitle = ""),
                        TableColumn("message", "Message", ColumnKind.TEXT, shortTitle = ""),
                    ),
                    rows, itemName = "message", emptyText = "The log is empty", readOnly = true,
                    summary = "Newest first. The time is wrong until the router has synchronised its clock",
                ),
            ),
        )
    }

    override suspend fun apply(api: TendaApi, changes: Map<String, String>, context: TendaContext) {
        throw InvalidSettingException("page", "The system log is read only")
    }
}

// ---------------------------------------------------------------------------------------------
// Login password: SysToolpassword / SysToolChangePwd (a plain form)
// ---------------------------------------------------------------------------------------------

internal object LoginPasswordPage : TendaPage {
    override val info = SettingsPageInfo("login-password", "Login password", "System", "The password of this router's web page and of this app")

    const val PASSWORD = "password"

    override suspend fun read(api: TendaApi): SettingsPage = SettingsPage(
        info = info,
        fields = listOf(
            SettingField.Secret(
                PASSWORD, "New login password", summary = "5 to 32 characters, letters, digits and symbols",
                minLength = 5, maxLength = 32, required = true, requiresConfirmation = true,
            ),
        ),
        applyWarning = "Saving ends every login session on the router, including the router's own web page.",
        loginPasswordKey = PASSWORD,
    )

    override suspend fun apply(api: TendaApi, changes: Map<String, String>, context: TendaContext) {
        val password = changes[PASSWORD] ?: return
        if (password.length !in 5..32 || password.any { it.code > 127 }) {
            throw InvalidSettingException(PASSWORD, "Use 5 to 32 ASCII characters")
        }
        if (password.first() == ' ' || password.last() == ' ') {
            throw InvalidSettingException(PASSWORD, "The first and last character can't be a space")
        }
        if (password == context.password) throw InvalidSettingException(PASSWORD, "That is the password already")

        // The page is a real HTML form, not scrambled: the old password is only asked for if there is one.
        val hasPassword = api.read("SysToolpassword").asObject("SysToolpassword").str("ispwd") == "1"
        val old = if (hasPassword) TendaCrypto.md5(context.password) else ""
        val new = TendaCrypto.md5(password)
        api.postPlain("SysToolChangePwd", "GO=system_password.html&SYSOPS=$old&SYSPS=$new&SYSPS2=$new")
    }
}
