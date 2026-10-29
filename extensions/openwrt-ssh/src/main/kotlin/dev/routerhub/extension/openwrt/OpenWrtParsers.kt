package dev.routerhub.extension.openwrt

import dev.routerhub.api.ConnectedClient
import dev.routerhub.api.WifiBand
import dev.routerhub.api.WifiRadio

/**
 * Parsers for raw OpenWrt shell output. Pure string-in, model-out functions, so they're
 * unit-tested on the JVM without a router.
 */
internal object OpenWrtParsers {

    private const val MARKER = "@@routerhub:"

    private val MAC = Regex("""(?i)[0-9a-f]{2}(?::[0-9a-f]{2}){5}""")
    private val WHITESPACE = Regex("""\s+""")
    private val UCI_LINE = Regex("""^wireless\.([^.=]+)(?:\.([^=]+))?=(.*)$""")

    data class DefaultRoute(val device: String, val gateway: String?, val sourceAddress: String?)

    /** Joins commands into one script whose output [splitSections] can take apart again. */
    fun script(vararg sections: Pair<String, String>): String =
        sections.joinToString("\n") { (name, command) -> "echo '$MARKER$name'; $command 2>/dev/null" }

    fun splitSections(output: String): Map<String, String> {
        val sections = linkedMapOf<String, StringBuilder>()
        var current: StringBuilder? = null
        output.lineSequence().forEach { line ->
            if (line.startsWith(MARKER)) {
                current = StringBuilder().also { sections[line.removePrefix(MARKER).trim()] = it }
            } else {
                current?.appendLine(line)
            }
        }
        return sections.mapValues { it.value.toString().trimEnd() }
    }

    /** `/proc/uptime`: "350735.47 234388.90" (uptime, idle) in seconds. */
    fun parseUptimeSeconds(text: String): Long? =
        text.trim().substringBefore(' ').toDoubleOrNull()?.toLong()

    /** Shell-style KEY='value' files such as /etc/openwrt_release. */
    fun parseKeyValues(text: String): Map<String, String> =
        text.lineSequence()
            .mapNotNull { line ->
                val key = line.substringBefore('=', missingDelimiterValue = "").trim()
                if (key.isEmpty()) null else key to line.substringAfter('=').trim().trim('\'', '"')
            }
            .toMap()

    /** `ip -4 route get 1.1.1.1`: "1.1.1.1 via 192.0.2.1 dev wan src 192.0.2.23 uid 0". */
    fun parseDefaultRoute(text: String): DefaultRoute? {
        val tokens = text.trim().split(WHITESPACE)
        fun valueAfter(keyword: String) = tokens.indexOf(keyword).takeIf { it >= 0 }?.let { tokens.getOrNull(it + 1) }
        val device = valueAfter("dev") ?: return null
        return DefaultRoute(device = device, gateway = valueAfter("via"), sourceAddress = valueAfter("src"))
    }

    /**
     * `uci -q show wireless`, e.g.
     * ```
     * wireless.radio0=wifi-device
     * wireless.radio0.band='5g'
     * wireless.radio0.disabled='1'
     * wireless.default_radio0=wifi-iface
     * wireless.default_radio0.device='radio0'
     * wireless.default_radio0.ssid='OpenWrt'
     * ```
     */
    fun parseWifiRadios(uciShow: String): List<WifiRadio> {
        val types = linkedMapOf<String, String>()
        val options = mutableMapOf<String, MutableMap<String, String>>()
        uciShow.lineSequence().forEach { line ->
            val match = UCI_LINE.matchEntire(line.trim()) ?: return@forEach
            val (section, option, rawValue) = match.destructured
            val value = rawValue.trim('\'')
            if (option.isEmpty()) types[section] = value
            else options.getOrPut(section) { mutableMapOf() }[option] = value
        }

        val ssidByRadio = types.filterValues { it == "wifi-iface" }.keys
            .mapNotNull { iface -> options[iface]?.let { it["device"] to it["ssid"] } }
            .filter { it.first != null }
            .groupBy({ it.first!! }, { it.second })
            .mapValues { (_, ssids) -> ssids.firstOrNull { it != null } }

        return types.filterValues { it == "wifi-device" }.keys.map { radio ->
            val o = options[radio].orEmpty()
            WifiRadio(
                id = radio,
                band = bandOf(o),
                enabled = o["disabled"] != "1",
                ssid = ssidByRadio[radio],
            )
        }
    }

    /**
     * Joins the kernel ARP table (who is actually present right now) with dnsmasq's lease file
     * (what they call themselves). `ip neigh show` would work too; /proc/net/arp needs no extra
     * package on minimal images.
     */
    fun parseClients(arp: String, leases: String): List<ConnectedClient> {
        val hostnames = leases.lineSequence()
            .map { it.trim().split(WHITESPACE) }
            // <expiry> <mac> <ip> <hostname> <client-id>; hostname is "*" when unknown
            .filter { it.size >= 4 && MAC.matches(it[1]) }
            .associate { it[1].lowercase() to it[3].takeUnless { name -> name == "*" } }

        return arp.lineSequence()
            .map { it.trim().split(WHITESPACE) }
            // IP address | HW type | Flags | HW address | Mask | Device   (the header line fails the MAC check)
            .filter { it.size >= 6 && MAC.matches(it[3]) }
            .filter { it[2] != "0x0" && it[3] != "00:00:00:00:00:00" } // incomplete entries
            .map { fields ->
                val mac = fields[3].lowercase()
                ConnectedClient(
                    macAddress = mac,
                    ipAddress = fields[0],
                    hostname = hostnames[mac],
                    interfaceName = fields[5],
                )
            }
            .distinctBy { it.macAddress }
            .toList()
    }

    private fun bandOf(options: Map<String, String>): WifiBand = when (options["band"]) {
        "2g" -> WifiBand.GHZ_2_4
        "5g" -> WifiBand.GHZ_5
        "6g" -> WifiBand.GHZ_6
        else -> when (options["hwmode"]) { // pre-21.02 configs
            "11a" -> WifiBand.GHZ_5
            "11b", "11g" -> WifiBand.GHZ_2_4
            else -> WifiBand.UNKNOWN
        }
    }
}
