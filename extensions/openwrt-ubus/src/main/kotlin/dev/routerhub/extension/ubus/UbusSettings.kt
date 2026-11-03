package dev.routerhub.extension.ubus

import dev.routerhub.api.ChoiceOption
import dev.routerhub.api.InvalidSettingException
import dev.routerhub.api.SettingsPage
import dev.routerhub.api.SettingsPageInfo

/** The router as the pages see it. */
internal class UbusRouter(val api: UbusApi, val uci: Uci = Uci(api))

/**
 * One configuration page. Nearly all of OpenWrt's configuration is uci, so a page reads sections into
 * typed fields and writes the changed fields back as uci changes.
 *
 * Everything is validated before the first call, so a bad value can never leave a page half changed.
 */
internal interface UbusPage {
    val info: SettingsPageInfo

    suspend fun read(router: UbusRouter): SettingsPage

    /**
     * @param changes only the fields the user changed, key to new value
     * @param username who is logged in
     * @throws InvalidSettingException when a value breaks a rule, before anything is sent
     */
    suspend fun apply(router: UbusRouter, changes: Map<String, String>, username: String)
}

/** A page that only shows things. The host offers no way to save it. */
internal interface ReadOnlyPage : UbusPage {
    override suspend fun apply(router: UbusRouter, changes: Map<String, String>, username: String) {
        throw InvalidSettingException("page", "${info.title} can't be changed")
    }
}

internal object UbusSettings {
    private val fixedPages = listOf(
        LanPage,
        WanPage,
        InterfacesPage,
        ReservationsPage,
        PortForwardsPage,
        BlockedDevicesPage,
        SchedulesPage,
        FirewallPage,
        SystemPage,
        SystemLogPage,
        LoginPasswordPage,
    )

    /** In the order shown in the app. How many Wi-Fi pages there are depends on the router. */
    suspend fun pages(router: UbusRouter): List<UbusPage> =
        WifiPages.pages(router) + fixedPages.filter { it !== WanPage || router.uci.section("network", "wan") != null }
}

/** Validation, so a bad value is caught before sending. */
internal object Rules {
    private val HOSTNAME = Regex("""[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*""")
    private val PORT_RANGE = Regex("""(\d{1,5})(?:-(\d{1,5}))?""")
    private val LEASE_TIME = Regex("""infinite|\d+[smhdw]?""")
    private val COUNTRY = Regex("[A-Z0-9]{2}")
    private val HEX = Regex("[0-9a-fA-F]{64}")

    /** A network name: 1 to 32 bytes of UTF-8. */
    fun ssid(key: String, value: String) {
        val size = value.toByteArray().size
        if (size !in 1..32) throw InvalidSettingException(key, "The network name must be 1 to 32 bytes")
    }

    /** WPA pre-shared key: 8 to 63 ASCII characters, or exactly 64 hexadecimal digits. */
    fun wpaKey(key: String, value: String) {
        val valid = HEX.matches(value) || (value.length in 8..63 && value.all { it.code in 32..126 })
        if (!valid) throw InvalidSettingException(key, "Use 8 to 63 ASCII characters, or exactly 64 hexadecimal digits")
    }

    fun oneOf(key: String, value: String, allowed: Collection<String>, what: String) {
        if (value !in allowed) throw InvalidSettingException(key, "$what must be one of: ${allowed.joinToString(", ")}")
    }

    fun number(key: String, value: String, range: LongRange, what: String): Long {
        val number = value.trim().toLongOrNull() ?: throw InvalidSettingException(key, "$what must be a whole number")
        if (number !in range) throw InvalidSettingException(key, "$what must be between ${range.first} and ${range.last}")
        return number
    }

    private fun octets(value: String): List<Int>? {
        val parts = value.trim().split('.')
        if (parts.size != 4) return null
        val numbers = parts.map { part -> part.takeIf { it.matches(Regex("""\d{1,3}""")) }?.toInt() ?: return null }
        return numbers.takeIf { list -> list.all { it <= 255 } }
    }

    fun ip(key: String, value: String, what: String = "The address"): String {
        if (octets(value) == null) throw InvalidSettingException(key, "$what \"$value\" is not an IPv4 address")
        return value.trim()
    }

    fun mask(key: String, value: String): String {
        val bits = octets(value)?.fold(0L) { acc, octet -> (acc shl 8) or octet.toLong() }
        val valid = bits != null && bits != 0L && (bits.inv() and 0xFFFFFFFFL).let { host -> host and (host + 1) == 0L }
        if (!valid) throw InvalidSettingException(key, "\"$value\" is not a valid subnet mask")
        return value.trim()
    }

    /** A MAC address in any of the usual spellings, as the lower case colon-separated form. */
    fun mac(key: String, value: String): String {
        val digits = value.trim().lowercase().replace(Regex("""[:\-.]"""), "")
        if (!digits.matches(Regex("[0-9a-f]{12}"))) {
            throw InvalidSettingException(key, "\"$value\" is not a MAC address (use 00:11:22:33:44:55)")
        }
        // A device address is neither all zeros nor a group (multicast or broadcast) address, whose first byte is odd.
        val multicast = digits.substring(0, 2).toInt(16) and 1 == 1
        if (multicast || digits == "000000000000") throw InvalidSettingException(key, "\"$value\" is not a device MAC address")
        return digits.chunked(2).joinToString(":")
    }

    fun hostname(key: String, value: String): String {
        if (value.length > 253 || !HOSTNAME.matches(value)) {
            throw InvalidSettingException(key, "Use letters, digits, hyphens and dots, and don't start or end with a hyphen")
        }
        return value
    }

    /** One port or `first-last`, the way firewall4 writes ranges. */
    fun ports(key: String, value: String, what: String): String {
        val text = value.trim()
        val match = PORT_RANGE.matchEntire(text) ?: throw InvalidSettingException(key, "$what must be a port or a range like 8000-8010")
        val first = match.groupValues[1].toInt()
        val last = match.groupValues[2].ifEmpty { match.groupValues[1] }.toInt()
        if (first !in 1..65535 || last !in first..65535) {
            throw InvalidSettingException(key, "$what must be between 1 and 65535, lowest first")
        }
        return text
    }

    fun leaseTime(key: String, value: String): String {
        val text = value.trim()
        if (!LEASE_TIME.matches(text)) throw InvalidSettingException(key, "Use a time like 12h, 30m or 7d, or infinite")
        return text
    }

    fun country(key: String, value: String): String {
        val text = value.trim().uppercase()
        if (!COUNTRY.matches(text)) throw InvalidSettingException(key, "Use a two-letter country code, like DE")
        return text
    }

    fun label(key: String, value: String, what: String): String {
        val text = value.trim()
        if (text.length > 64) throw InvalidSettingException(key, "$what can be at most 64 characters")
        if (text.any { it.isISOControl() }) throw InvalidSettingException(key, "$what can't contain control characters")
        return text
    }
}

internal fun Map<String, String>.flag(key: String): Boolean? = this[key]?.let { it == "true" }

/** [choices] with [current] added when the router holds something this app has no name for. */
internal fun optionsWith(current: String, choices: List<ChoiceOption>): List<ChoiceOption> =
    if (choices.any { it.value == current }) choices else choices + ChoiceOption(current, current)

/**
 * Brings a list of uci sections in line with a submitted table: sections whose row is gone are
 * deleted and new rows added, so the sections that stay keep any options this app doesn't show.
 */
internal suspend fun UciEdit.syncRows(
    current: List<Pair<UciSection, List<String>>>,
    submitted: List<List<String>>,
    type: String,
    build: (List<String>) -> JsonObject,
) {
    val added = submitted.toMutableList()
    current.forEach { (section, row) -> if (!added.remove(row)) delete(section.name) }
    added.forEach { add(type, build(it)) }
}
