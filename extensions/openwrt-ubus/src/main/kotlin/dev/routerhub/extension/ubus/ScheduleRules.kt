package dev.routerhub.extension.ubus

import dev.routerhub.api.ChoiceOption
import dev.routerhub.api.ColumnKind
import dev.routerhub.api.InvalidSettingException
import dev.routerhub.api.SettingField
import dev.routerhub.api.SettingsGroup
import dev.routerhub.api.SettingsPage
import dev.routerhub.api.SettingsPageInfo
import dev.routerhub.api.TableCodec
import dev.routerhub.api.TableColumn
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private const val FIREWALL = "firewall"
private const val RULE = "rule"
private const val LAN_ZONE = "lan"
private const val WAN_ZONE = "wan"

/** A firewall rule refusing what one device forwards to the WAN, like [BlockedDevicesPage]'s. */
private fun refuseRule(name: String, mac: String, extra: Map<String, String>): JsonObject = mapOf(
    "name" to name,
    "src" to LAN_ZONE,
    "dest" to WAN_ZONE,
    "src_mac" to mac,
    "proto" to "all",
    "target" to "REJECT",
) + extra

private suspend fun requireZones(router: UbusRouter, key: String) {
    val zones = router.uci.sections(FIREWALL, "zone").map { it.str("name") }
    if (LAN_ZONE !in zones || WAN_ZONE !in zones) throw InvalidSettingException(key, "This router has no firewall zones named lan and wan")
}

/**
 * Keeps chosen devices off the internet at set times of the week, with one firewall rule per row.
 * firewall4 turns a window that crosses midnight, like 22:00 to 07:00, into the hours outside it.
 */
internal object SchedulesPage : UbusPage {
    const val SCHEDULES = "schedules"
    private const val PREFIX = "RouterHub schedule"
    private const val EVERY_DAY = "every"
    private const val WEEKDAYS = "Mon Tue Wed Thu Fri"
    private const val WEEKENDS = "Sat Sun"
    private val TIME = Regex("""([01]?\d|2[0-3]):([0-5]\d)""")

    override val info = SettingsPageInfo("schedules", "Internet schedules", SettingsGroup.FIREWALL, "Keep devices off the internet at set times")

    private val DAYS = listOf(
        ChoiceOption(EVERY_DAY, "Every day"),
        ChoiceOption(WEEKDAYS, "Monday to Friday"),
        ChoiceOption(WEEKENDS, "Saturday and Sunday"),
    )

    private fun columns(rows: List<List<String>>) = listOf(
        TableColumn("name", "Name", optional = true),
        TableColumn("mac", "MAC address", ColumnKind.MAC_ADDRESS, shortTitle = ""),
        TableColumn("from", "Off from", hint = "22:00", shortTitle = "from"),
        TableColumn("until", "Back at", hint = "07:00", shortTitle = "until"),
        // Days set elsewhere, like "Mon Wed", are kept and shown as they are.
        TableColumn("days", "Days", ColumnKind.CHOICE, rows.map { it[4] }.distinct().fold(DAYS) { all, days -> optionsWith(days, all) }, defaultValue = EVERY_DAY, shortTitle = ""),
    )

    private fun row(section: UciSection) = listOf(
        section.str("name").removePrefix(PREFIX).removePrefix(": "),
        section.str("src_mac").lowercase(),
        section.str("start_time").take(5),
        section.str("stop_time").take(5),
        section.str("weekdays").ifEmpty { EVERY_DAY },
    )

    private suspend fun schedules(router: UbusRouter) =
        router.uci.sections(FIREWALL, RULE).filter { it.str("name").startsWith(PREFIX) && it.flag("enabled", true) }

    override suspend fun read(router: UbusRouter): SettingsPage {
        val rows = schedules(router).map(::row)
        return SettingsPage(
            info = info,
            fields = listOf(
                SettingField.Table(
                    key = SCHEDULES,
                    title = "Schedules",
                    summary = "Times follow the router's clock and time zone. Days count hour by hour: a 22:00 to 07:00 " +
                        "schedule on Monday to Friday also covers early Monday, but not early Saturday.",
                    columns = columns(rows),
                    rows = rows,
                    itemName = "schedule",
                    emptyText = "No schedules",
                ),
            ),
        )
    }

    override suspend fun apply(router: UbusRouter, changes: Map<String, String>, username: String) {
        val submitted = TableCodec.decode(changes[SCHEDULES] ?: return).map { (name, mac, from, until, days) ->
            val start = time(from, "The start")
            val stop = time(until, "The end")
            if (start == stop) throw InvalidSettingException(SCHEDULES, "The start and the end can't be the same time")
            listOf(Rules.label(SCHEDULES, name, "The name"), Rules.mac(SCHEDULES, mac), start, stop, days.trim().ifEmpty { EVERY_DAY })
        }
        if (submitted.isNotEmpty()) requireZones(router, SCHEDULES)
        val current = schedules(router).map { it to row(it) }
        router.uci.change(FIREWALL) {
            syncRows(current, submitted, RULE) { (name, mac, start, stop, days) ->
                refuseRule(
                    if (name.isEmpty()) PREFIX else "$PREFIX: $name",
                    mac,
                    mapOf("start_time" to "$start:00", "stop_time" to "$stop:00") +
                        if (days == EVERY_DAY) emptyMap() else mapOf("weekdays" to days),
                )
            }
        }
    }

    /** "7:05" as "07:05". */
    private fun time(value: String, what: String): String {
        val match = TIME.matchEntire(value.trim()) ?: throw InvalidSettingException(SCHEDULES, "$what must be a time like 22:00")
        return "%02d:%s".format(match.groupValues[1].toInt(), match.groupValues[2])
    }
}

/**
 * Pausing a device: a firewall rule that stops applying at a date, so the router lifts it by itself
 * even if this phone is gone. The date is in the router's own local time, read from the router, so a
 * wrong clock or another time zone on the phone doesn't matter.
 */
internal object Pauses {
    private const val PREFIX = "RouterHub pause"
    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    /** The router's local time, as `system info` reports it. */
    suspend fun routerNow(router: UbusRouter): LocalDateTime {
        val seconds = router.api.call("system", "info").num("localtime") ?: throw InvalidSettingException("pause", "The router didn't say what time it is")
        return LocalDateTime.ofEpochSecond(seconds, 0, ZoneOffset.UTC)
    }

    private fun until(section: UciSection): LocalDateTime? =
        runCatching { LocalDateTime.parse(section.str("stop_date"), DATE) }.getOrNull()

    private suspend fun rules(router: UbusRouter) = router.uci.sections(FIREWALL, RULE).filter { it.str("name").startsWith(PREFIX) }

    /** Paused devices by MAC, with the router time the pause ends. */
    suspend fun active(router: UbusRouter, now: LocalDateTime): Map<String, LocalDateTime> =
        rules(router).mapNotNull { section -> until(section)?.takeIf { it > now }?.let { section.str("src_mac").lowercase() to it } }.toMap()

    /** Pauses [mac] until [minutes] from the router's now. Ended pauses are cleared along the way. */
    suspend fun pause(router: UbusRouter, mac: String, name: String, minutes: Long) {
        requireZones(router, "pause")
        val now = routerNow(router)
        val stale = rules(router).filter { it.str("src_mac").lowercase() == mac || (until(it)?.let { end -> end <= now } ?: true) }
        router.uci.change(FIREWALL) {
            stale.forEach { delete(it.name) }
            add(RULE, refuseRule(if (name.isEmpty()) PREFIX else "$PREFIX: $name", mac, mapOf("stop_date" to now.plusMinutes(minutes).format(DATE))))
        }
    }

    suspend fun resume(router: UbusRouter, mac: String) {
        val matching = rules(router).filter { it.str("src_mac").lowercase() == mac }
        if (matching.isEmpty()) return
        router.uci.change(FIREWALL) { matching.forEach { delete(it.name) } }
    }
}
