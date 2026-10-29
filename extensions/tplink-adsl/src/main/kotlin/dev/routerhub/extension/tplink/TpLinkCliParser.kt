package dev.routerhub.extension.tplink

import dev.routerhub.api.DslLineState
import dev.routerhub.api.DslStats
import dev.routerhub.api.WifiBand

/**
 * Parsers for Broadcom's restricted telnet CLI. Inputs are command outputs as returned by
 * [TelnetCli.exec] (no echo, no prompt). Pure functions, unit-tested against captured output.
 */
internal object TpLinkCliParser {

    data class DefaultRoute(val gateway: String, val interfaceName: String)

    private val STATUS = Regex("""(?m)^Status:\s*(.+)$""")
    private val MODE = Regex("""(?m)^Mode:\s*(.+)$""")

    // Broadcom lists UPSTREAM first: "Max:	Upstream rate = 1180 Kbps, Downstream rate = 12400 Kbps"
    private val MAX_RATE = Regex("""Max:\s*Upstream rate = (\d+) Kbps, Downstream rate = (\d+) Kbps""")
    private val BEARER_RATE = Regex("""Bearer:\s*\d+, Upstream rate = (\d+) Kbps, Downstream rate = (\d+) Kbps""")
    private val SNR = Regex("""SNR \(dB\):\s*(-?[\d.]+)\s+(-?[\d.]+)""")
    private val ATTENUATION = Regex("""Attn\(dB\):\s*(-?[\d.]+)\s+(-?[\d.]+)""")

    private val SSID = Regex("""Current SSID: "(.*)"""")
    private val CHANSPEC = Regex("""Chanspec:\s*([\d.]+)GHz""")
    private val INET_ADDR = Regex("""inet addr:(\d{1,3}(?:\.\d{1,3}){3})""")
    private val WHITESPACE = Regex("""\s+""")

    /** `xdslctl info --show`. */
    fun parseXdsl(output: String): DslStats {
        val max = MAX_RATE.find(output)?.groupValues
        val bearer = BEARER_RATE.find(output)?.groupValues
        val snr = SNR.find(output)?.groupValues
        val attenuation = ATTENUATION.find(output)?.groupValues
        return DslStats(
            lineState = STATUS.find(output)?.groupValues?.get(1)?.let(::parseLineState) ?: DslLineState.UNKNOWN,
            downstreamKbps = bearer?.get(2)?.toInt() ?: 0,
            upstreamKbps = bearer?.get(1)?.toInt() ?: 0,
            attainableDownstreamKbps = max?.get(2)?.toInt(),
            attainableUpstreamKbps = max?.get(1)?.toInt(),
            snrMarginDownDb = snr?.get(1)?.toDouble(),
            snrMarginUpDb = snr?.get(2)?.toDouble(),
            attenuationDownDb = attenuation?.get(1)?.toDouble(),
            attenuationUpDb = attenuation?.get(2)?.toDouble(),
            modulation = MODE.find(output)?.groupValues?.get(1)?.trim(),
        )
    }

    /** `wl isup`: "1" or "0". */
    fun parseWlIsUp(output: String): Boolean? = when (output.trim()) {
        "1" -> true
        "0" -> false
        else -> null
    }

    /** `wl ssid`: `Current SSID: "HomeNet"`. */
    fun parseWlSsid(output: String): String? = SSID.find(output)?.groupValues?.get(1)

    /** `wl status`: `Chanspec: 2.4GHz channel 2 20MHz (0x1002)`. */
    fun parseWlBand(output: String): WifiBand = when (CHANSPEC.find(output)?.groupValues?.get(1)) {
        "2.4" -> WifiBand.GHZ_2_4
        "5" -> WifiBand.GHZ_5
        "6" -> WifiBand.GHZ_6
        else -> WifiBand.UNKNOWN
    }

    /**
     * `route`: Destination | Gateway | NetMask | Flags | Used | Hops | Interface. A default route
     * (destination 0.0.0.0) only exists while the WAN connection is up.
     */
    fun parseDefaultRoute(output: String): DefaultRoute? =
        output.lineSequence()
            .map { it.trim().split(WHITESPACE) }
            .firstOrNull { it.size >= 7 && (it[0] == "0.0.0.0" || it[0] == "default") }
            ?.let { DefaultRoute(gateway = it[1], interfaceName = it[6]) }

    /** `ifconfig <iface>`: `inet addr:203.0.113.6  Bcast:… Mask:…`. */
    fun parseInetAddress(output: String): String? = INET_ADDR.find(output)?.groupValues?.get(1)

    private fun parseLineState(text: String): DslLineState {
        val s = text.trim().lowercase()
        return when {
            "showtime" in s -> DslLineState.SHOWTIME
            listOf("train", "g.994", "g.992", "started", "analysis", "exchange").any { it in s } -> DslLineState.TRAINING
            listOf("idle", "down", "disable", "no signal").any { it in s } -> DslLineState.DOWN
            else -> DslLineState.UNKNOWN
        }
    }
}
