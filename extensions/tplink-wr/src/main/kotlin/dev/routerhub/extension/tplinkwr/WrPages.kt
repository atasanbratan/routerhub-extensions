package dev.routerhub.extension.tplinkwr

import dev.routerhub.api.ConnectedClient
import dev.routerhub.api.ConnectionMedium
import dev.routerhub.api.FirmwareParseException
import dev.routerhub.api.RouterStatus
import dev.routerhub.api.WifiBand
import dev.routerhub.api.WifiRadio

/**
 * Reads TP-Link's classic web pages (the `.htm` pages under `/userRpm/`), which carry their data as JavaScript
 * arrays, e.g. `var lanPara = new Array("00-11-22-33-44-55", "192.168.0.1", "255.255.255.0", 0,0 );`.
 * The page's own script renders them, so the positions below come from that script.
 */
internal object WrPages {
    private val ARRAY = Regex("""var\s+(\w+)\s*=\s*new Array\(([\s\S]*?)\);""")
    private val VALUE = Regex(""""((?:[^"\\]|\\.)*)"|(-?\d+)""")

    /** Every `new Array(...)` of [html] whose items are plain strings and numbers, by name. */
    fun arrays(html: String): Map<String, List<String>> = ARRAY.findAll(html).associate { match ->
        match.groupValues[1] to VALUE.findAll(match.groupValues[2]).map { value ->
            if (value.groups[1] != null) unescape(value.groupValues[1]) else value.groupValues[2]
        }.toList()
    }

    private fun unescape(text: String) = text.replace(Regex("""\\(.)"""), "$1")

    private fun Map<String, List<String>>.need(name: String): List<String> =
        this[name] ?: throw FirmwareParseException("The page has no $name")

    private fun List<String>.at(index: Int): String = getOrNull(index).orEmpty()

    /** `StatusRpm.htm`. */
    fun status(html: String): RouterStatus {
        val arrays = arrays(html)
        val status = arrays.need("statusPara")
        val wlan = arrays["wlanPara"].orEmpty()
        // One WAN entry per row of statusPara[2] items; the first is the internet connection.
        val wan = arrays["wanPara"].orEmpty()
        val wanIp = wan.at(2).takeIf { it.isNotBlank() && it != "0.0.0.0" }
        return RouterStatus(
            model = status.at(6).substringBefore(" 0000").trim().ifEmpty { null }?.let { "TL-$it" },
            firmwareVersion = status.at(5).trim().ifEmpty { null },
            uptimeSeconds = status.at(4).toLongOrNull(),
            wanIpv4 = wanIp,
            wanConnected = wan.isNotEmpty() && wanIp != null,
            wifiRadios = if (wlan.isEmpty()) emptyList() else listOf(
                WifiRadio(RADIO, WifiBand.GHZ_2_4, enabled = wlan.at(0) == "1", ssid = wlan.at(1).ifEmpty { null }),
            ),
            extras = buildMap {
                arrays["lanPara"]?.let { lan -> lan.at(1).ifEmpty { null }?.let { put("LAN address", it) } }
                wan.at(7).takeIf { wanIp != null && it.isNotBlank() }?.let { put("Gateway", it) }
                wan.at(11).takeIf { wanIp != null && it.isNotBlank() }?.let { put("DNS servers", it.replace(" , ", ", ")) }
                status.at(6).trim().ifEmpty { null }?.let { put("Hardware", it.substringBefore(" 0000")) }
            },
        )
    }

    /**
     * `AssignedIpAddrListRpm.htm` lists every device with a DHCP lease (name, MAC, address, lease);
     * `WlanStationRpm.htm` lists the MACs connected over Wi-Fi.
     */
    fun clients(dhcpHtml: String, stationsHtml: String?): List<ConnectedClient> {
        val dhcp = arrays(dhcpHtml)
        val list = dhcp.need("DHCPDynList")
        val perEntry = dhcp["DHCPDynPara"]?.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 } ?: 4
        val wireless = stationsHtml?.let(::wirelessMacs)
        return list.chunked(perEntry).filter { it.size >= 3 && it[1].isNotBlank() }.map { entry ->
            val mac = mac(entry[1])
            ConnectedClient(
                macAddress = mac,
                ipAddress = entry[2].ifBlank { null },
                hostname = entry[0].ifBlank { null },
                medium = when {
                    wireless == null -> ConnectionMedium.UNKNOWN
                    mac in wireless -> ConnectionMedium.WIRELESS
                    else -> ConnectionMedium.WIRED
                },
            )
        }
    }

    /** The MACs in the Wi-Fi station list; each station is `wlanHostPara[4]` items, MAC first. */
    fun wirelessMacs(html: String): Set<String> {
        val arrays = arrays(html)
        val hosts = arrays["hostList"].orEmpty()
        val perEntry = arrays["wlanHostPara"]?.getOrNull(4)?.toIntOrNull()?.takeIf { it > 0 } ?: 5
        return hosts.chunked(perEntry).mapNotNull { it.firstOrNull()?.takeIf(String::isNotBlank)?.let(::mac) }.toSet()
    }

    /** "00-11-22-AA-BB-CC" as "00:11:22:aa:bb:cc". */
    fun mac(text: String) = text.trim().replace('-', ':').lowercase()

    /** The page a login answers with names the session's own path: `http://host/ABCDEFGHIJKLMNOP/userRpm/Index.htm`. */
    fun sessionToken(loginAnswer: String): String? = TOKEN.find(loginAnswer)?.groupValues?.get(1)

    /** A short answer that only sends the browser to the router's start page, without a session. */
    fun isSentHome(html: String) = sessionToken(html) == null && html.length < 400 && SENT_HOME.containsMatchIn(html)

    /** A page asked for with a stale token answers with a script that drops the cookie and leaves. */
    fun isSessionGone(html: String) = html.contains("document.cookie=\"Authorization=;")

    const val RADIO = "wlan0"
    private val SENT_HOME = Regex("""location\.href\s*=\s*"https?://[^/"]+/?"""")
    private val TOKEN = Regex("""/([A-Z]{16})/userRpm/Index\.htm""")
}
