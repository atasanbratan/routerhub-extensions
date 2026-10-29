package dev.routerhub.extension.tplink

import dev.routerhub.api.ChoiceOption
import dev.routerhub.api.FirmwareParseException
import dev.routerhub.api.InvalidSettingException
import dev.routerhub.api.SettingField
import dev.routerhub.api.SettingsPage
import dev.routerhub.api.SettingsPageInfo

// ---------------------------------------------------------------------------------------------
// Remote access (remote.cmd)
// ---------------------------------------------------------------------------------------------

/**
 * Which services the WAN side of the router may reach: its web page, Telnet and ping, per WAN
 * interface. The firmware ships with these as the user left them, so the page's job here is as
 * much to SHOW what is exposed as to change it.
 */
internal object RemoteAccessPage : SettingsPageSpec {
    override val info = SettingsPageInfo("remote-access", "Remote access", "System", "What the WAN side of the router can reach")
    override val path = "/remote.cmd?action=view"

    /** `['eth0','111','00']`: interface, access bits (web, telnet, ping), "blocked by a port forward" bits. */
    private val ENTRY = Regex("""\[\s*'([^']*)'\s*,\s*'([01]{3})'\s*,\s*'([01]{2})'\s*]""")

    /** Not a complete list, just the ones nobody should protect a door to the internet with. */
    private val WEAK_PASSWORDS = setOf("admin", "password", "1234", "12345", "123456", "root", "guest", "tp-link", "tplink")

    private class Wan(val name: String, val web: Boolean, val telnet: Boolean, val icmp: Boolean, val webBlocked: Boolean, val telnetBlocked: Boolean) {
        fun bits(web: Boolean = this.web, telnet: Boolean = this.telnet, icmp: Boolean = this.icmp) = "${web.bit()}${telnet.bit()}${icmp.bit()}"
    }

    private fun key(kind: String, wan: Wan) = "$kind:${wan.name}"

    private fun interfaces(html: String): List<Wan> {
        val start = html.indexOf("var entryList")
        if (start < 0) throw FirmwareParseException("The remote access page has no interface list")
        // The page repeats the array in an "if undefined" fallback; only the first one is real.
        val end = html.indexOf("];", start).takeIf { it > 0 } ?: html.length
        return ENTRY.findAll(html.substring(start, end)).map { m ->
            val (name, access, blocked) = m.destructured
            Wan(name, access[0] == '1', access[1] == '1', access[2] == '1', blocked[0] == '1', blocked[1] == '1')
        }.toList()
    }

    override fun parse(html: String): SettingsPage {
        val wans = interfaces(html)
        if (wans.isEmpty()) {
            return SettingsPage(info, listOf(SettingField.Info("note", "Remote access", "The router reports no WAN interface.")))
        }
        val exposed = wans.filter { it.web || it.telnet }
        val fields = buildList<SettingField> {
            if (exposed.isNotEmpty()) {
                add(
                    SettingField.Info(
                        "exposure", "Reachable from the WAN side",
                        exposed.joinToString("; ") { wan ->
                            "${wan.name}: " + listOfNotNull("web admin page".takeIf { wan.web }, "Telnet".takeIf { wan.telnet }).joinToString(" and ")
                        },
                        summary = "Anyone who can reach the router's WAN address can try to log in. Turn these off unless you need them.",
                    ),
                )
            }
            for (wan in wans) {
                val suffix = if (wans.size > 1) " (${wan.name})" else ""
                add(
                    SettingField.Toggle(
                        key("web", wan), "Web admin page$suffix", wan.web,
                        summary = "Not reachable right now: port 80 is used by a port forwarding rule".takeIf { wan.webBlocked },
                    ),
                )
                add(
                    SettingField.Toggle(
                        key("telnet", wan), "Telnet$suffix", wan.telnet,
                        summary = "Not reachable right now: port 23 is used by a port forwarding rule".takeIf { wan.telnetBlocked },
                    ),
                )
                add(SettingField.Toggle(key("icmp", wan), "Ping (ICMP)$suffix", wan.icmp))
            }
        }
        return SettingsPage(
            info = info,
            fields = fields,
            applyWarning = "Remote web and Telnet access lets anyone who reaches the router's WAN address try to log in. " +
                "Only turn them on with a strong router password.",
        )
    }

    override suspend fun apply(client: PageClient, changes: Map<String, String>, context: SettingsContext) {
        val wans = interfaces(client.get(path))

        class Update(val index: Int, val wan: Wan, val bits: String, val opensMore: Boolean, val firstKey: String)

        val updates = wans.mapIndexedNotNull { index, wan ->
            val web = changes.flag(key("web", wan)) ?: wan.web
            val telnet = changes.flag(key("telnet", wan)) ?: wan.telnet
            val icmp = changes.flag(key("icmp", wan)) ?: wan.icmp
            if (web == wan.web && telnet == wan.telnet && icmp == wan.icmp) return@mapIndexedNotNull null
            Update(
                index, wan, wan.bits(web, telnet, icmp),
                opensMore = (web && !wan.web) || (telnet && !wan.telnet),
                firstKey = if (web && !wan.web) key("web", wan) else key("telnet", wan),
            )
        }
        if (updates.isEmpty()) return

        // Closing doors is always allowed; opening one with a default password is not.
        updates.firstOrNull { it.opensMore }?.let { opening ->
            if (context.password.lowercase() in WEAK_PASSWORDS) {
                throw InvalidSettingException(opening.firstKey, "Change the router password first: remote access with a default password is an open door")
            }
        }

        for (update in updates) {
            val sessionKey = ListRules.freshKey(client, path)
            // btnApply(): the selected interface's index and name, then one bit each for web, Telnet, ping.
            client.get("/remote.cmd?action=set&selIndex=${update.index}&ifname=${update.wan.name}&access=${update.bits}&sessionKey=$sessionKey")
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Date and time (sntpcfg.html -> sntpcfg.cgi)
// ---------------------------------------------------------------------------------------------

/**
 * NTP on/off, up to five NTP servers and the time zone. Manual clock setting and daylight saving
 * are left to the router's own page: they involve date arithmetic the page does in script. When
 * either is in use here the page is shown read-only instead of being resent half-understood.
 */
internal object TimePage : SettingsPageSpec {
    override val info = SettingsPageInfo("time", "Date and time", "System", "NTP servers and time zone")
    override val path = "/sntpcfg.html"

    const val NTP = "ntp"
    const val ZONE = "zone"
    private fun serverKey(n: Int) = "server$n"

    private val SERVER = Regex("""[A-Za-z0-9][A-Za-z0-9._-]{0,63}""")
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val CLOCK = Regex("""\w{3}\s+(\w{3})\s+(\d{1,2})\s+(\d{2}):(\d{2}):(\d{2})\s+(\d{4})""")

    /** The page keeps these in double quotes, inside its load function. */
    private fun quoted(html: String, name: String): String? =
        Regex("""var\s+${Regex.escape(name)}\s*=\s*"([^"]*)"""").find(html)?.groupValues?.get(1)

    private fun number(html: String, name: String): Int? =
        Regex("""(?m)^\s*var\s+${Regex.escape(name)}\s*=\s*(\d+)""").find(html)?.groupValues?.get(1)?.toInt()

    private class State(html: String) {
        val sessionKey = PageData.need(html, "sessionKey")
        val servers = (1..5).map { quoted(html, "ntp$it").orEmpty() }
        val ntpEnabled = quoted(html, "ntp_enabled") == "1"
        val zoneName = quoted(html, "tz_name") ?: throw FirmwareParseException("The time page has no time zone")
        /** Keyed by the router's own array index: the page's offset rule and its form both depend on it. */
        val zones: Map<Int, String> = Regex("""timeZones\[(\d+)]\s*=\s*'([^']*)'""").findAll(html)
            .map { it.groupValues[1].toInt() to it.groupValues[2] }.sortedBy { it.first }.toMap()
        val daylightSaving = PageData.jsVar(html, "use_dst") == "1"
        val jumpBack = number(html, "jumpBack") ?: 0
        val synced = (number(html, "is_sync") ?: 0) == 1
        val scheduling = PageData.jsVar(html, "supportWlSche") == "1"
        val wlIsTimeSet = PageData.jsVar(html, "wlIsTimeSet") ?: "0"
        val clock = clockOf(PageData.jsVar(html, "ntpDate").orEmpty())

        /** The page's own default when the stored zone isn't in its list is index 4. */
        val zoneIndex = zones.entries.firstOrNull { it.value.contains(zoneName) }?.key ?: 4

        val editable get() = !daylightSaving && jumpBack == 0 && !synced && zones.isNotEmpty()
    }

    /** `Thu Jan 01 01:24:55 1970` into the page's `YYYY.MM.DD-HH:MM:SS`, with its own fallback. */
    private fun clockOf(ntpDate: String): String {
        val m = CLOCK.find(ntpDate) ?: return "2000.01.01-00:00:00"
        val (month, day, hour, minute, second, year) = m.destructured
        val monthNumber = MONTHS.indexOf(month) + 1
        if (monthNumber == 0) return "2000.01.01-00:00:00"
        return "%s.%02d.%02d-%s:%s:%s".format(year, monthNumber, day.toInt(), hour, minute, second)
    }

    override fun parse(html: String): SettingsPage {
        val s = State(html)
        // 1970.01.01-01:24:55 -> 1970-01-01 01:24:55
        val shown = s.clock.substringBefore('-').replace('.', '-') + " " + s.clock.substringAfter('-')
        val clock = SettingField.Info(
            "clock", "Router time", shown,
            summary = if (s.clock.startsWith("1970.")) "Not set yet: the router needs internet access to reach its NTP servers" else null,
        )
        if (!s.editable) {
            return SettingsPage(
                info,
                listOf(
                    clock,
                    SettingField.Info("note", "Editing", "This router uses daylight saving or a setup flow, which can only be changed on its own web page."),
                ),
            )
        }
        return SettingsPage(
            info = info,
            fields = buildList {
                add(clock)
                add(SettingField.Toggle(NTP, "Set the time automatically", s.ntpEnabled, summary = "Uses the NTP servers below when the router has internet access"))
                for (n in 1..5) {
                    add(
                        SettingField.Text(
                            serverKey(n), "NTP server $n", s.servers[n - 1],
                            summary = if (n == 1) "Required while automatic time is on" else "Optional",
                            maxLength = 64,
                        ),
                    )
                }
                add(SettingField.Choice(ZONE, "Time zone", s.zoneIndex.toString(), s.zones.map { (i, label) -> ChoiceOption(i.toString(), label) }))
            },
        )
    }

    override fun buildRequest(html: String, changes: Map<String, String>, context: SettingsContext): String {
        val s = State(html)
        if (!s.editable) throw InvalidSettingException(NTP, "The time settings can't be edited from the app on this router")

        val enabled = changes.flag(NTP) ?: s.ntpEnabled
        val servers = (1..5).map { (changes[serverKey(it)] ?: s.servers[it - 1]).trim() }

        val query = StringBuilder("/sntpcfg.cgi?ntp_enabled=")
        if (enabled) {
            if (servers[0].isEmpty()) throw InvalidSettingException(serverKey(1), "Enter the first NTP server")
            servers.forEachIndexed { i, server ->
                if (server.isNotEmpty() && !SERVER.matches(server)) {
                    throw InvalidSettingException(serverKey(i + 1), "\"$server\" is not a valid server name")
                }
            }
            query.append("1&ntpServer1=${servers[0]}")
            for (n in 2..5) query.append("&ntpServer$n=${servers[n - 1]}")
        } else {
            query.append("0")
        }
        // The page always resends the clock it displays; the router ignores it while NTP is on.
        query.append("&sysDate=${s.clock}")

        val wlIsTimeSet = if (s.scheduling && s.clock.substringBefore('-') != "1970.01.01") "1" else s.wlIsTimeSet
        if (enabled) {
            val index = (changes[ZONE] ?: s.zoneIndex.toString()).trim().toIntOrNull()
            val label = index?.let { s.zones[it] } ?: throw InvalidSettingException(ZONE, "Choose a time zone from the list")
            // getTimeZoneOffset(): the 6 characters after "(GMT", except for index 15, which the page leaves empty.
            val offset = if (index != 15) label.substring(4, 10) else ""
            // getTimeZoneName(): everything after "(GMT+00:00) ", with "&" replaced so it survives the query.
            val name = label.substring(12).replace("&", "_and_bit_")
            query.append("&timezone_offset=$offset&timezone=$name")
        }
        query.append("&use_dst=0")
        if (s.scheduling) query.append("&wlIsTimeSet=$wlIsTimeSet")
        return query.append("&sessionKey=${s.sessionKey}").toString()
    }
}
