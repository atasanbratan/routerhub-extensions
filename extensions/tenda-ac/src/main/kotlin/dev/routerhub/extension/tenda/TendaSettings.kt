package dev.routerhub.extension.tenda

import dev.routerhub.api.InvalidSettingException
import dev.routerhub.api.SettingsPage
import dev.routerhub.api.SettingsPageInfo

/** Who is logged in; changing the login password needs the current one. */
internal class TendaContext(val username: String, val password: String)

/**
 * One configuration page of the router's web UI.
 *
 * Unlike firmwares that only have HTML, this one has a small JSON API (see [TendaHttpClient]), so a
 * page is two plain functions over [TendaApi]: [read] turns the router's current values into typed
 * fields and [apply] turns the changed fields into the router's own requests.
 *
 * Everything is validated before the first request, so a bad value can never leave a page half changed.
 */
internal interface TendaPage {
    val info: SettingsPageInfo

    suspend fun read(api: TendaApi): SettingsPage

    /**
     * @param changes only the fields the user changed, key to new value
     * @throws InvalidSettingException when a value breaks a rule, before anything is sent
     */
    suspend fun apply(api: TendaApi, changes: Map<String, String>, context: TendaContext)
}

internal object TendaSettings {
    // In the order shown in the app: the host groups pages by their `group`, in this order.
    val pages: List<TendaPage> = listOf(
        WifiPage,
        GuestWifiPage,
        WifiChannelPage,
        WifiExtrasPage,
        LanPage,
        DhcpReservationsPage,
        PortForwardingPage,
        DmzPage,
        UpnpPage,
        FirewallPage,
        MacFilterPage,
        DdnsPage,
        RemoteWebPage,
        TimePage,
        LedPage,
        AutoRebootPage,
        SystemLogPage,
        LoginPasswordPage,
    )

    val byId: Map<String, TendaPage> = pages.associateBy { it.info.id }
}

/** Validation rules copied from the web UI's own scripts, so a bad value is caught before sending. */
internal object Rules {
    fun isAscii(value: String) = value.all { it.code <= 127 }

    /**
     * A network name: 1 to 32 "bytes", counted like the UI counts them (a character outside the
     * printable ASCII range counts as three).
     */
    fun ssid(key: String, value: String) {
        if (value.isEmpty()) throw InvalidSettingException(key, "The network name can't be empty")
        val bytes = value.length + 2 * value.count { it.code !in 0x20..0x7E }
        if (bytes > 32) throw InvalidSettingException(key, "The network name can be at most 32 bytes (a non-ASCII character counts as 3)")
    }

    /** WPA pre-shared key: 8 to 63 ASCII characters, or exactly 64 hexadecimal digits. */
    fun wpaPassphrase(key: String, value: String) {
        val valid = when (value.length) {
            in 8..63 -> isAscii(value)
            64 -> value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
            else -> false
        }
        if (!valid) throw InvalidSettingException(key, "Use 8 to 63 ASCII characters, or exactly 64 hexadecimal digits")
    }

    fun number(key: String, value: String, range: LongRange, what: String): Long {
        val number = value.trim().toLongOrNull() ?: throw InvalidSettingException(key, "$what must be a whole number")
        if (number !in range) throw InvalidSettingException(key, "$what must be between ${range.first} and ${range.last}")
        return number
    }

    fun oneOf(key: String, value: String, allowed: Collection<String>, what: String) {
        if (value !in allowed) throw InvalidSettingException(key, "$what must be one of: ${allowed.joinToString(", ")}")
    }

    private fun octets(value: String): List<Int>? {
        val parts = value.trim().split('.')
        if (parts.size != 4) return null
        val numbers = parts.map { part -> part.takeIf { it.matches(Regex("""\d{1,3}""")) }?.toInt() ?: return null }
        return numbers.takeIf { list -> list.all { it <= 255 } }
    }

    /** A host address as the UI accepts it: dotted quad, not multicast, loopback, link-local or a network/broadcast end. */
    fun hostIp(key: String, value: String, what: String = "The address"): String {
        val o = octets(value)
        val valid = o != null && o[0] in 1..223 && o[0] != 127 && !(o[0] == 169 && o[1] == 254) && o[3] != 0 && o[3] != 255
        if (!valid) throw InvalidSettingException(key, "$what \"$value\" is not a valid host address")
        return value.trim()
    }

    /** A subnet mask: ones followed by zeros. */
    fun mask(key: String, value: String): String {
        val o = octets(value)
        val bits = o?.fold(0L) { acc, octet -> (acc shl 8) or octet.toLong() }
        val valid = bits != null && bits != 0L && (bits.inv() and 0xFFFFFFFFL).let { host -> host and (host + 1) == 0L }
        if (!valid) throw InvalidSettingException(key, "\"$value\" is not a valid subnet mask")
        return value.trim()
    }

    private fun asLong(ip: String): Long = octets(ip)?.fold(0L) { acc, octet -> (acc shl 8) or octet.toLong() } ?: -1

    fun sameSubnet(a: String, b: String, mask: String): Boolean {
        val m = asLong(mask)
        val x = asLong(a)
        val y = asLong(b)
        return m >= 0 && x >= 0 && y >= 0 && (x and m) == (y and m)
    }

    fun ipOrder(a: String, b: String): Int = asLong(a).compareTo(asLong(b))

    /** A MAC address in any of the usual spellings, as the lower case colon-separated form the router lists. */
    fun mac(key: String, value: String): String {
        val digits = value.trim().lowercase().replace(Regex("""[:\-.]"""), "")
        if (!digits.matches(Regex("[0-9a-f]{12}")) || value.trim().length != 17) {
            throw InvalidSettingException(key, "\"$value\" is not a MAC address (use 00:11:22:33:44:55)")
        }
        // A device address is neither all zeros nor a group (multicast or broadcast) address, whose first byte is odd.
        val multicast = digits.substring(0, 2).toInt(16) and 1 == 1
        if (multicast || digits == "000000000000") throw InvalidSettingException(key, "\"$value\" is not a device MAC address")
        return digits.chunked(2).joinToString(":")
    }

    /** Free text names (device names, rule names): the UI refuses control characters in them. */
    fun label(key: String, value: String, what: String, maxLength: Int): String {
        val text = value.trim()
        if (text.length > maxLength) throw InvalidSettingException(key, "$what can be at most $maxLength characters")
        if (text.any { it.isISOControl() }) throw InvalidSettingException(key, "$what can't contain control characters")
        return text
    }
}

internal fun Map<String, String>.flag(key: String): Boolean? = this[key]?.let { it == "true" }

internal fun Boolean.bit(): String = if (this) "1" else "0"

internal const val WIFI_RESTART_WARNING =
    "Applying restarts the Wi-Fi radios. Wireless devices disconnect for a few seconds, " +
        "including this phone if it is connected over Wi-Fi."
