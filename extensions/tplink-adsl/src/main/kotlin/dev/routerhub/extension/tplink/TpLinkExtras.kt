package dev.routerhub.extension.tplink

import dev.routerhub.api.ChoiceOption
import dev.routerhub.api.ColumnKind
import dev.routerhub.api.FirmwareParseException
import dev.routerhub.api.InvalidSettingException
import dev.routerhub.api.SettingField
import dev.routerhub.api.SettingsPage
import dev.routerhub.api.SettingsPageInfo
import dev.routerhub.api.TableCodec
import dev.routerhub.api.TableColumn
import org.jsoup.Jsoup

/** `name = 'value'` or `name = "value"` anywhere in a page's script, quotes of either kind. */
private fun scriptValue(html: String, name: String): String? =
    Regex("""${Regex.escape(name)}\s*=\s*(['"])(.*?)\1""").find(html)?.groupValues?.get(2)

// ---------------------------------------------------------------------------------------------
// System log (logview.cmd), read-only
// ---------------------------------------------------------------------------------------------

internal object SystemLogPage : SettingsPageSpec {
    override val info = SettingsPageInfo("system-log", "System log", "System", "Recent messages from the router")
    override val path = "/logview.cmd"

    private const val SHOWN = 300

    override fun parse(html: String): SettingsPage {
        val entries = Jsoup.parse(html).select("tr").filter { it.select("tr").size == 1 }.mapNotNull { tr ->
            val cells = tr.children().filter { it.tagName() == "td" }
            if (cells.size != 4 || cells.any { it.hasClass("hd") }) return@mapNotNull null
            val text = cells.map { it.text().replace(' ', ' ').trim() }
            // The router lists Date/Time, Facility, Severity, Message. The message is what one reads, so it leads.
            listOf(text[3], text[0], text[2], text[1])
        }
        return SettingsPage(
            info = info,
            fields = listOf(
                SettingField.Table(
                    key = "log", title = "Messages",
                    columns = listOf(
                        TableColumn("message", "Message"),
                        TableColumn("time", "Time", shortTitle = ""),
                        TableColumn("severity", "Severity", shortTitle = ""),
                        TableColumn("facility", "Facility", shortTitle = ""),
                    ),
                    // Newest first, and only the latest: the router keeps a ring buffer, and a phone screen is no place for all of it.
                    rows = entries.takeLast(SHOWN).reversed(),
                    summary = "Newest first" + if (entries.size > SHOWN) ", the latest $SHOWN of ${entries.size}" else "",
                    itemName = "message", emptyText = "The log is empty", readOnly = true,
                ),
            ),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// System log settings (logconfig.html -> logconfig.cgi)
// ---------------------------------------------------------------------------------------------

internal object LogSettingsPage : SettingsPageSpec {
    override val info = SettingsPageInfo("log-settings", "Log settings", "System", "What the router records, and where it sends it")
    override val path = "/logconfig.html"

    const val STATUS = "status"
    const val LEVEL = "level"
    const val DISPLAY = "display"
    const val MODE = "mode"
    const val SERVER = "server"
    const val PORT = "port"

    private val LEVELS = listOf("Emergency", "Alert", "Critical", "Error", "Warning", "Notice", "Informational", "Debugging")
        .mapIndexed { i, name -> ChoiceOption(i.toString(), name) }
    private val MODES = listOf(ChoiceOption("1", "On the router only"), ChoiceOption("2", "Send to a log server"), ChoiceOption("3", "Both"))

    private class State(html: String) {
        val sessionKey = PageData.sessionKey(html)
        // frmLoad(): `status[1].checked = true` means enabled; the selects are set by index.
        val enabled = Regex("""status\[(\d)]\.checked\s*=\s*true""").find(html)?.groupValues?.get(1) == "1"
        val level = Regex("""levelLog\.selectedIndex\s*=\s*(\d+)""").find(html)?.groupValues?.get(1)
            ?: throw FirmwareParseException("No log level on the page")
        val display = Regex("""levelDisplay\.selectedIndex\s*=\s*(\d+)""").find(html)?.groupValues?.get(1)
            ?: throw FirmwareParseException("No display level on the page")

        /** Written as `<mode> - 1`: the select index is the mode value minus one. */
        val mode = Regex("""logMode\.selectedIndex\s*=\s*(\d+)\s*-\s*1""").find(html)?.groupValues?.get(1)
            ?: throw FirmwareParseException("No log mode on the page")
        val server = PageData.jsVar(html, "addr").orEmpty()
        val port = PageData.jsVar(html, "port")?.takeIf { it.toIntOrNull() in 1..65535 } ?: "514"
    }

    override fun parse(html: String): SettingsPage {
        val s = State(html)
        return SettingsPage(
            info = info,
            fields = listOf(
                SettingField.Toggle(STATUS, "Logging", s.enabled),
                SettingField.Choice(LEVEL, "Record messages from", s.level, LEVELS, summary = "This level and anything more severe"),
                SettingField.Choice(DISPLAY, "Show messages from", s.display, LEVELS, summary = "This level and anything more severe"),
                SettingField.Choice(MODE, "Keep the log", s.mode, MODES),
                SettingField.Text(SERVER, "Log server address", s.server.takeUnless { it == "0.0.0.0" }.orEmpty(), summary = "Used when sending to a log server", keyboard = SettingField.Keyboard.IP_ADDRESS),
                SettingField.Number(PORT, "Log server port", s.port.toLong(), 1, 65535, summary = "UDP, usually 514"),
            ),
            applyWarning = "Turning logging off discards the messages the router has captured so far.",
        )
    }

    override fun buildRequest(html: String, changes: Map<String, String>, context: SettingsContext): String {
        val s = State(html)
        val enabled = changes.flag(STATUS) ?: s.enabled

        fun level(key: String, current: String): String {
            val value = (changes[key] ?: current).trim()
            if (LEVELS.none { it.value == value }) throw InvalidSettingException(key, "Choose a level from the list")
            return value
        }
        val level = level(LEVEL, s.level)
        val display = level(DISPLAY, s.display)
        val mode = (changes[MODE] ?: s.mode).trim()
        if (MODES.none { it.value == mode }) throw InvalidSettingException(MODE, "Choose where to keep the log")

        // Local-only mode resends whatever server the router already holds; a remote mode needs a real one.
        val (server, port) = if (mode == "1") {
            s.server to s.port
        } else {
            val address = (changes[SERVER] ?: s.server.takeUnless { it == "0.0.0.0" }.orEmpty()).trim()
            Rules.ipv4(SERVER, address)
            address to Rules.number(PORT, changes[PORT] ?: s.port, 1L..65535L, "The port").toString()
        }
        return "/logconfig.cgi?logStatus=${enabled.bit()}&logLevel=$level&logDisplay=$display&logMode=$mode" +
            "&logIpAddress=$server&logPort=$port&sessionKey=${s.sessionKey}"
    }
}

// ---------------------------------------------------------------------------------------------
// Dynamic DNS (ddnsmngr.cmd / ddnsadd.html)
// ---------------------------------------------------------------------------------------------

internal object DynamicDnsPage : SettingsPageSpec {
    override val info = SettingsPageInfo("dynamic-dns", "Dynamic DNS", "Network", "A fixed hostname for a changing address")
    override val path = "/ddnsmngr.cmd"
    private const val ADD_PATH = "/ddnsadd.html"

    const val ENTRIES = "entries"
    private const val COLUMNS = 5

    /** Option values are the page's own provider names; the number is what its request carries. */
    private val SERVICES = listOf(
        ChoiceOption("dyndns", "DynDNS.org"),
        ChoiceOption("tzo", "TZO"),
        ChoiceOption("noip", "no-ip.org"),
        ChoiceOption("thaiddns", "ThaiDDNS"),
    )
    private val SERVICE_CODE = mapOf("dyndns" to "1", "tzo" to "0", "noip" to "2", "thaiddns" to "3")
    private val HOSTNAME = Regex("""[A-Za-z0-9]([A-Za-z0-9.-]{0,62})""")

    override suspend fun read(client: PageClient): SettingsPage =
        build(client.get(path), ListRules.interfaces(client.get(ADD_PATH), "interfaceInfo"))

    override fun parse(html: String): SettingsPage = build(html, emptyList())

    fun build(viewHtml: String, interfaces: List<ChoiceOption>): SettingsPage = SettingsPage(
        info = info,
        fields = listOf(
            SettingField.Table(
                key = ENTRIES, title = "Accounts",
                columns = columns(interfaces),
                rows = currentRows(viewHtml, interfaces).map { it.cells },
                summary = "The router tells the provider its new address whenever it changes",
                itemName = "account", emptyText = "No Dynamic DNS accounts",
            ),
        ),
        applyWarning = "The password is sent to the router as typed and can't be read back later.",
    )

    private fun columns(interfaces: List<ChoiceOption>) = listOf(
        TableColumn("hostname", "Hostname", hint = "e.g. myhome.example.org"),
        TableColumn("username", "Username or email", shortTitle = "User"),
        TableColumn("service", "Provider", ColumnKind.CHOICE, SERVICES, defaultValue = "dyndns", shortTitle = ""),
        TableColumn("interface", "Interface", ColumnKind.CHOICE, interfaces, defaultValue = interfaces.firstOrNull()?.value.orEmpty(), shortTitle = ""),
        TableColumn("password", "Password or key", ColumnKind.SECRET),
    )

    private fun currentRows(viewHtml: String, interfaces: List<ChoiceOption>): List<ListRow> =
        ListPages.checkboxRows(viewHtml).map { row ->
            if (row.cells.size != COLUMNS - 1) throw FirmwareParseException("Unexpected layout of the Dynamic DNS table (${row.cells.size} columns)")
            val (hostname, user, service, iface) = row.cells
            val provider = service.lowercase().let { text ->
                when {
                    "dyndns" in text -> "dyndns"
                    "tzo" in text -> "tzo"
                    "no-ip" in text || "noip" in text -> "noip"
                    "thai" in text -> "thaiddns"
                    else -> service
                }
            }
            val interfaceValue = interfaces.firstOrNull { it.value == iface || it.label == iface }?.value ?: iface
            // The router never shows a stored password, so the secret cell of an existing row is empty.
            ListRow(listOf(hostname, user, provider, interfaceValue, ""), row.removeId)
        }

    private class Account(val hostname: String, val username: String, val service: String, val iface: String, val password: String)

    private fun validated(row: List<String>, interfaces: List<ChoiceOption>): Account {
        val hostname = row[0].trim()
        val username = row[1].trim()
        val service = row[2]
        val iface = row[3]
        val password = row[4]
        if (service !in SERVICE_CODE) throw InvalidSettingException(ENTRIES, "Choose a provider")
        if (username.isEmpty()) throw InvalidSettingException(ENTRIES, "Enter the username")
        if ((service == "tzo" || service == "noip") && !username.contains('@')) throw InvalidSettingException(ENTRIES, "This provider needs an email address")
        if (password.isEmpty()) throw InvalidSettingException(ENTRIES, "Enter the password or key")
        if (!HOSTNAME.matches(hostname)) throw InvalidSettingException(ENTRIES, "\"$hostname\" is not a valid hostname")
        if (interfaces.none { it.value == iface }) throw InvalidSettingException(ENTRIES, "Choose an interface")
        if (!Rules.isAscii(username) || !Rules.isAscii(password)) throw InvalidSettingException(ENTRIES, "The username and password may only use plain ASCII characters")
        return Account(hostname, username, service, iface, password)
    }

    override suspend fun apply(client: PageClient, changes: Map<String, String>, context: SettingsContext) {
        val wanted = TableCodec.decode(changes[ENTRIES] ?: return)
        ListPages.requireShape(ENTRIES, wanted, COLUMNS)

        val interfaces = ListRules.interfaces(client.get(ADD_PATH), "interfaceInfo")
        val viewHtml = client.get(path)
        val change = ListPages.diff(currentRows(viewHtml, interfaces), wanted)
        val accounts = change.toAdd.map { validated(it, interfaces) }

        if (change.removeIds.isNotEmpty()) {
            // removeClick(): every checked value followed by a comma and a space.
            val list = change.removeIds.joinToString("") { "$it, " }
            client.get("/ddnsmngr.cmd?action=remove&rmLst=$list&sessionKey=${PageData.sessionKey(viewHtml)}")
        }
        for (account in accounts) {
            val key = ListRules.freshKey(client, ADD_PATH)
            // The page sends these raw; encoding is safe and keeps a stray "&" in a password from splitting the query.
            client.get(
                "/ddnsmngr.cmd?action=add&service=${SERVICE_CODE.getValue(account.service)}" +
                    "&username=${FirmwareUrl.encode(account.username)}&password=${FirmwareUrl.encode(account.password)}" +
                    "&hostname=${FirmwareUrl.encode(account.hostname)}&iface=${account.iface}&sessionKey=$key",
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// SNMP (snmpconfig.html -> snmpconfig.cgi)
// ---------------------------------------------------------------------------------------------

internal object SnmpPage : SettingsPageSpec {
    override val info = SettingsPageInfo("snmp", "SNMP", "System", "Lets a management tool read the router's statistics")
    override val path = "/snmpconfig.html"

    const val ENABLE = "enable"
    const val READ_COMMUNITY = "readCommunity"
    const val WRITE_COMMUNITY = "writeCommunity"
    const val NAME = "name"
    const val CONTACT = "contact"
    const val LOCATION = "location"
    const val TRAP = "trap"

    private const val MAX_LENGTH = 15

    /** Community strings act as passwords, and these are the ones everybody tries first. */
    private val DEFAULT_COMMUNITIES = setOf("public", "private")

    private class State(html: String) {
        val sessionKey = PageData.need(html, "sessionKey")
        // frmLoad(): `status[1].checked = true` is "enabled" (it sends snmpStatus=1), index 0 is "disabled".
        val enabled = Regex("""status\[(\d)]\.checked\s*=\s*true""").find(html)?.groupValues?.get(1) == "1"
        val readCommunity = value(html, "rocommunity")
        val writeCommunity = value(html, "rwcommunity")
        val name = value(html, "sysName")
        val contact = value(html, "sysContact")
        val location = value(html, "sysLocation")
        val trap = value(html, "trapIp")

        private fun value(html: String, control: String) =
            scriptValue(html, "$control.value") ?: throw FirmwareParseException("The SNMP page has no value for $control")

        val usesDefaultCommunity get() = readCommunity in DEFAULT_COMMUNITIES || writeCommunity in DEFAULT_COMMUNITIES
    }

    override fun parse(html: String): SettingsPage {
        val s = State(html)
        return SettingsPage(
            info = info,
            fields = buildList {
                if (s.enabled && s.usesDefaultCommunity) {
                    add(
                        SettingField.Info(
                            "warning", "Default community strings",
                            "SNMP is on and uses \"public\" or \"private\"",
                            summary = "Anyone who can reach the router can read, and maybe change, its settings. Set your own strings or turn SNMP off.",
                        ),
                    )
                }
                add(SettingField.Toggle(ENABLE, "SNMP agent", s.enabled))
                add(SettingField.Secret(READ_COMMUNITY, "Read community", summary = "Leave empty to keep the current one", minLength = 1, maxLength = MAX_LENGTH))
                add(SettingField.Secret(WRITE_COMMUNITY, "Write community", summary = "Leave empty to keep the current one", minLength = 1, maxLength = MAX_LENGTH))
                add(SettingField.Text(NAME, "System name", s.name, maxLength = MAX_LENGTH))
                add(SettingField.Text(CONTACT, "Contact", s.contact, maxLength = MAX_LENGTH))
                add(SettingField.Text(LOCATION, "Location", s.location, maxLength = MAX_LENGTH))
                add(SettingField.Text(TRAP, "Trap manager address", s.trap, summary = "0.0.0.0 for none", keyboard = SettingField.Keyboard.IP_ADDRESS))
            },
            applyWarning = "SNMP exposes the router's details to anyone who knows the community string.",
        )
    }

    override fun buildRequest(html: String, changes: Map<String, String>, context: SettingsContext): String {
        val s = State(html)
        val enabled = changes.flag(ENABLE) ?: s.enabled

        fun community(key: String, current: String): String {
            val value = changes[key]?.takeIf { it.isNotEmpty() } ?: current
            if (value.isEmpty()) throw InvalidSettingException(key, "A community string is required")
            if (value.length > MAX_LENGTH) throw InvalidSettingException(key, "At most $MAX_LENGTH characters")
            return value
        }
        val read = community(READ_COMMUNITY, s.readCommunity)
        val write = community(WRITE_COMMUNITY, s.writeCommunity)

        // Closing the agent is always fine. Running it with the well-known strings is not.
        if (enabled && (read in DEFAULT_COMMUNITIES || write in DEFAULT_COMMUNITIES)) {
            val key = if (read in DEFAULT_COMMUNITIES) READ_COMMUNITY else WRITE_COMMUNITY
            throw InvalidSettingException(key, "Choose your own community strings before SNMP runs: \"public\" and \"private\" are what everyone tries first")
        }

        fun text(key: String, current: String): String {
            val value = changes[key] ?: current
            if (value.length > MAX_LENGTH) throw InvalidSettingException(key, "At most $MAX_LENGTH characters")
            return value
        }
        val trap = (changes[TRAP] ?: s.trap).trim()
        if (trap != "0.0.0.0") Rules.ipv4(TRAP, trap)

        return "/snmpconfig.cgi?snmpStatus=${enabled.bit()}&snmpRoCommunity=${FirmwareUrl.encode(read)}" +
            "&snmpRwCommunity=${FirmwareUrl.encode(write)}&snmpSysName=${FirmwareUrl.encode(text(NAME, s.name))}" +
            "&snmpSysContact=${FirmwareUrl.encode(text(CONTACT, s.contact))}&snmpSysLocation=${FirmwareUrl.encode(text(LOCATION, s.location))}" +
            "&snmpTrapIp=$trap&sessionKey=${s.sessionKey}"
    }
}

// ---------------------------------------------------------------------------------------------
// TR-069 (tr69cfg.html), view-only
// ---------------------------------------------------------------------------------------------

/**
 * Remote management by the provider. Never editable from here: a wrong value can end the
 * provider's support, and the page carries credentials this app has no reason to touch. Only the
 * non-secret parts are read.
 */
internal object Tr069Page : SettingsPageSpec {
    override val info = SettingsPageInfo("tr069", "TR-069", "System", "Management by your provider (view only)")
    override val path = "/tr69cfg.html"

    override fun parse(html: String): SettingsPage {
        val inform = Regex("""enblInform\s*=\s*'(\d)'""").find(html)?.groupValues?.get(1)
        val interval = Regex("""informInterval\.value\s*=\s*['"]?(\d+)""").find(html)?.groupValues?.get(1)
        val server = scriptValue(html, "acsURL.value").orEmpty()
        return SettingsPage(
            info = info,
            fields = listOf(
                SettingField.Info("inform", "Periodic contact with the provider", if (inform == "1") "On" else "Off"),
                SettingField.Info("interval", "Contact interval", interval?.let { "$it s" } ?: "-"),
                SettingField.Info("server", "Provider's server", server.ifEmpty { "None configured" }),
                SettingField.Info(
                    "note", "Editing",
                    "Not available here: the provider's settings, user names and passwords stay on the router's own page.",
                ),
            ),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Wi-Fi schedule (wlschedule.html -> wlschedule.wl)
// ---------------------------------------------------------------------------------------------

/**
 * The weekly pattern is a 7 by 48 grid of half-hour cells that the page edits with mouse
 * selections, and nothing on the page says whether a selected cell means Wi-Fi on or off. Only the
 * switch is offered; the grid is summarised and stays on the router's page. Because an empty
 * pattern or an unset clock could switch the radio off for good, enabling is refused in both cases.
 */
internal object WifiSchedulePage : SettingsPageSpec {
    override val info = SettingsPageInfo("wireless-schedule", "Wi-Fi schedule", "Wireless", "Switch the radio on a timetable")
    override val path = "/wlschedule.html"

    const val ENABLE = "enable"

    /** The page's own order of the 14 half-day masks in its request. */
    private val DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    private class State(html: String) {
        val sessionKey = PageData.need(html, "sessionKey")
        val enabled = PageData.need(html, "enblSch") == "1"
        val timeSet = PageData.need(html, "isTimeSet")
        val masks: Map<String, String> = DAYS.flatMap { day -> listOf("Am", "Pm").map { half -> day.lowercase() + half } }
            .associateWith { PageData.need(html, it) }

        /** Selected half-hour cells in the week: each mask holds 24 bits, one per cell of that half day. */
        val selectedCells get() = masks.values.sumOf { (it.toLongOrNull() ?: 0L).and(0xFFFFFFL).countOneBits() }
    }

    override fun parse(html: String): SettingsPage {
        val s = State(html)
        return SettingsPage(
            info = info,
            fields = listOf(
                SettingField.Toggle(
                    ENABLE, "Use the schedule", s.enabled,
                    summary = "Can only be turned on once the router knows the time and the weekly pattern is not empty",
                ),
                SettingField.Info("cells", "Weekly pattern", "${s.selectedCells} of 336 half-hour cells selected"),
                SettingField.Info("clock", "Router clock", if (s.timeSet == "1") "Set" else "Not set yet (see Date and time)"),
                SettingField.Info("note", "Editing the pattern", "Done on the router's own page: a grid of cells that this app doesn't reproduce."),
            ),
        )
    }

    override fun buildRequest(html: String, changes: Map<String, String>, context: SettingsContext): String {
        val s = State(html)
        val enable = changes.flag(ENABLE) ?: s.enabled

        if (enable && !s.enabled) {
            if (s.timeSet != "1") {
                throw InvalidSettingException(ENABLE, "The router's clock isn't set yet. A schedule can't work until it is: check Date and time first")
            }
            if (s.selectedCells == 0) {
                throw InvalidSettingException(ENABLE, "The weekly pattern is empty, and an empty schedule could keep Wi-Fi off for good. Fill it in on the router's page first")
            }
        }

        val query = StringBuilder("/wlschedule.wl?wlEnblSch=${enable.bit()}&wlIsTimeSet=${s.timeSet}")
        for (day in DAYS) {
            for (half in listOf("Am", "Pm")) {
                query.append("&wl$day$half=${s.masks.getValue(day.lowercase() + half)}")
            }
        }
        // btnApply() sets isUsrCtrl to 0 before building the request.
        return query.append("&wlIsUsrCtrl=0&sessionKey=${s.sessionKey}").toString()
    }
}
