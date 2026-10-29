package dev.routerhub.extension.tplink

import dev.routerhub.api.ConnectedClient
import dev.routerhub.api.ConnectionMedium
import dev.routerhub.api.DslLineState
import dev.routerhub.api.DslStats
import dev.routerhub.api.FirmwareParseException
import dev.routerhub.api.RouterStatus
import dev.routerhub.api.WifiBand
import dev.routerhub.api.WifiRadio
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Parsers for TP-Link's Broadcom web UI. Pure functions over page HTML, unit-tested on the JVM
 * against markup captured from a real TD-W8960N.
 *
 * Two kinds of data live on these pages:
 *  - plain `<td>Label:</td><td>value</td>` table rows, matched by label;
 *  - values that only exist inside inline JavaScript and are rendered with document.write() in a
 *    browser (WAN status, Wi-Fi settings, the reboot sessionKey). A DOM scraper never sees these,
 *    so they're pulled out of the script source with regexes.
 */
internal object TpLinkStatusParser {

    private val NUMBER = Regex("""-?\d+(?:\.\d+)?""")
    private val MAC = Regex("""(?i)^[0-9a-f]{2}(?::[0-9a-f]{2}){5}$""")
    private val UPTIME = Regex("""(?i)(?:(\d+)\s*day(?:\(s\)|s)?,?\s*)?(\d+):(\d{2}):(\d{2})""")
    private val WAN_INFO = Regex("""var\s+info\s*=\s*'([^']*)'\s*\.split""")
    private val SESSION_KEY = Regex("""sessionKey\s*=\s*'(\d+)'""")

    private fun jsString(name: String) = Regex("""var\s+$name\s*=\s*'([^']*)'""")

    private data class Row(val label: String, val rawLabel: String, val cells: List<String>)

    fun parseStatus(infoHtml: String, wirelessHtml: String?): RouterStatus {
        val rows = rowsOf(Jsoup.parse(infoHtml))

        // genWanInfo(): 'ETH WAN|eth0|eth0|IPoE|Disconnected|203.0.113.6|255.255.255.0|gateway|uptime|mac|'
        val wan = WAN_INFO.find(infoHtml)?.groupValues?.get(1)?.split('|')
        val wanUp = wan?.getOrNull(4)?.equals("Connected", ignoreCase = true)
        val wanIp = wan?.getOrNull(5)?.takeUnless { it.isBlank() || it == "(null)" || it == "0.0.0.0" }

        return RouterStatus(
            model = rows.text("hardware version"),
            firmwareVersion = rows.text("firmware version"),
            uptimeSeconds = rows.text("system running time", "system up time", "uptime")?.let(::parseUptimeSeconds),
            wanIpv4 = wanIp,
            wanConnected = wanUp,
            wifiRadios = wirelessHtml?.let(::parseWifiRadios).orEmpty(),
            extras = buildMap {
                wan?.getOrNull(0)?.takeIf { it.isNotBlank() }?.let { put("WAN type", it) }
                wan?.getOrNull(3)?.takeIf { it.isNotBlank() }?.let { put("Connection type", it) }
                wan?.getOrNull(7)?.takeUnless { it.isBlank() || it == "(null)" }?.let { put("Gateway", it) }
            },
        )
    }

    /** The TD-W8960N has a single 2.4 GHz radio whose settings live in wlcfg.html's script. */
    fun parseWifiRadios(wirelessHtml: String): List<WifiRadio> {
        val enabled = jsString("enbl").find(wirelessHtml)?.groupValues?.get(1) ?: return emptyList()
        return listOf(
            WifiRadio(
                id = "wl0",
                band = WifiBand.GHZ_2_4,
                enabled = enabled == "1",
                ssid = jsString("ssid").find(wirelessHtml)?.groupValues?.get(1),
            ),
        )
    }

    fun parseDsl(statsHtml: String): DslStats {
        val rows = rowsOf(Jsoup.parse(statsHtml))
        val state = rows.text("status")?.let(::parseLineState) ?: DslLineState.UNKNOWN
        val rate = rows.find("rate")?.values()

        // With DSL disabled (e.g. the router runs in Ethernet-WAN mode) every value cell is empty.
        // That's a valid, reportable state, not a parse failure.
        if (rate == null || rate.size < 2) {
            if (state == DslLineState.SHOWTIME) throw FirmwareParseException("DSL is up but no line rate was found")
            return DslStats(lineState = state, downstreamKbps = 0, upstreamKbps = 0, modulation = rows.text("mode"))
        }

        val attainable = rows.find("attainable rate")?.values()
        val snr = rows.find("snr margin")?.values()
        val attenuation = rows.find("attenuation")?.values()
        return DslStats(
            lineState = state,
            downstreamKbps = rate[0].toInt(),
            upstreamKbps = rate[1].toInt(),
            attainableDownstreamKbps = attainable?.getOrNull(0)?.toInt(),
            attainableUpstreamKbps = attainable?.getOrNull(1)?.toInt(),
            snrMarginDownDb = snr?.getOrNull(0),
            snrMarginUpDb = snr?.getOrNull(1),
            attenuationDownDb = attenuation?.getOrNull(0),
            attenuationUpDb = attenuation?.getOrNull(1),
            modulation = rows.text("mode"),
        )
    }

    /**
     * ARP table (who is present right now), named from DHCP leases, with wireless stations marked
     * as such. Everything else on the LAN bridge is reported as wired.
     */
    fun parseClients(arpHtml: String, dhcpHtml: String, stationsHtml: String?): List<ConnectedClient> {
        val hostnames = tableRows(dhcpHtml)
            .filter { it.size >= 3 && MAC.matches(it[1]) } // Hostname | MAC | IP | Expires
            .associate { it[1].lowercase() to it[0].ifBlank { null } }

        val wireless = stationsHtml?.let { html ->
            tableRows(html).mapNotNull { row -> row.firstOrNull { MAC.matches(it) }?.lowercase() }.toSet()
        }.orEmpty()

        return tableRows(arpHtml)
            .filter { it.size >= 4 && MAC.matches(it[2]) } // IP | Flags | HW Address | Device
            .filter { it[1].equals("Complete", ignoreCase = true) }
            .map { row ->
                val mac = row[2].lowercase()
                ConnectedClient(
                    macAddress = mac,
                    ipAddress = row[0],
                    hostname = hostnames[mac],
                    interfaceName = row[3],
                    medium = if (mac in wireless) ConnectionMedium.WIRELESS else ConnectionMedium.WIRED,
                )
            }
            .distinctBy { it.macAddress }
    }

    fun parseSessionKey(html: String): String? = SESSION_KEY.find(html)?.groupValues?.get(1)

    internal fun parseUptimeSeconds(text: String): Long? {
        val match = UPTIME.find(text) ?: return null
        val (days, hours, minutes, seconds) = match.destructured
        return (days.toLongOrNull() ?: 0) * 86_400 + hours.toLong() * 3_600 + minutes.toLong() * 60 + seconds.toLong()
    }

    private fun parseLineState(text: String): DslLineState {
        val s = text.lowercase()
        return when {
            "showtime" in s || s == "up" || s == "connected" -> DslLineState.SHOWTIME
            listOf("train", "handshak", "activat", "init").any { it in s } -> DslLineState.TRAINING
            listOf("down", "idle", "disable", "disconnect", "no signal").any { it in s } -> DslLineState.DOWN
            else -> DslLineState.UNKNOWN
        }
    }

    /**
     * Every `<tr>` as (label, remaining non-empty cells), using only its direct cells. The label is
     * lower-cased with its colon and any unit suffix removed ("SNR Margin (0.1 dB):" becomes
     * "snr margin"); rawLabel keeps the unit so values can be scaled.
     */
    private fun rowsOf(root: Element): List<Row> = root.select("tr").mapNotNull { tr ->
        val cells = tr.children()
            .filter { it.tagName() == "td" || it.tagName() == "th" }
            .map { it.text().replace(' ', ' ').trim() }
        val labelIndex = cells.indexOfFirst { it.isNotEmpty() }
        if (labelIndex < 0) return@mapNotNull null
        val raw = cells[labelIndex].lowercase().removeSuffix(":").trim()
        Row(
            label = raw.replace(Regex("""\s*\(.*\)"""), "").trim(),
            rawLabel = raw,
            cells = cells.drop(labelIndex + 1).filter { it.isNotEmpty() },
        )
    }

    /** Data rows of every table as trimmed cell texts (header rows included; callers filter). */
    private fun tableRows(html: String): List<List<String>> =
        Jsoup.parse(html).select("tr").map { tr ->
            tr.children().filter { it.tagName() == "td" }.map { it.text().replace(' ', ' ').trim() }
        }

    private fun List<Row>.find(vararg labels: String): Row? = firstOrNull { it.label in labels }

    private fun List<Row>.text(vararg labels: String): String? = find(*labels)?.cells?.firstOrNull()

    /** Numeric cells, scaled when the label declares tenth units such as "(0.1 dB)". */
    private fun Row.values(): List<Double> {
        val scale = if ("(0.1" in rawLabel) 0.1 else 1.0
        return cells.mapNotNull { NUMBER.find(it)?.value?.toDoubleOrNull()?.times(scale) }
    }
}
