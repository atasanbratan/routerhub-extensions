package dev.routerhub.extension.tenda

import dev.routerhub.api.ConnectedClient
import dev.routerhub.api.ConnectionMedium
import dev.routerhub.api.RouterStatus
import dev.routerhub.api.WifiBand
import dev.routerhub.api.WifiRadio

/** Turns the router's status answers into the host's models. Pure, so it is tested without a router. */
internal object TendaStatus {
    private val MAC = Regex("[0-9a-f]{2}(:[0-9a-f]{2}){5}")

    private val CONNECTION_TYPES = listOf("Dynamic IP", "Static IP", "PPPoE", "PPTP", "L2TP", "PPPoE (mode 2)")

    /** `adv_connect_status` as the router's own status page words it. */
    private fun connection(status: Long?) = when (status) {
        0L -> "Cable unplugged"
        1L -> "Disconnected"
        2L -> "Connecting"
        3L -> "Connected"
        else -> null
    }

    /**
     * @param system answer of `GetSystemStatus`
     * @param router answer of `GetRouterStatus`
     */
    fun parseStatus(system: Any?, router: Any?): RouterStatus {
        val sys = system.asObject("GetSystemStatus")
        val rt = router.asObject("GetRouterStatus")
        val wan = sys.objects("wanInfo").firstOrNull().orEmpty()
        val wanStatus = wan.num("adv_connect_status")

        return RouterStatus(
            model = modelName(rt.str("deviceName"), sys.str("adv_hard_ver")),
            firmwareVersion = sys.str("adv_firm_ver").ifEmpty { null },
            uptimeSeconds = sys.num("adv_run_time"),
            wanIpv4 = wan.str("adv_ip").ifEmpty { null },
            wanConnected = wanStatus?.let { it == 3L },
            wifiRadios = listOf(
                WifiRadio(WifiPage.RADIO_24, WifiBand.GHZ_2_4, rt.isOn("wl24gEn"), rt.str("wl24gName").ifEmpty { null }),
                WifiRadio(WifiPage.RADIO_5, WifiBand.GHZ_5, rt.isOn("wl5gEn"), rt.str("wl5gName").ifEmpty { null }),
            ),
            extras = buildMap {
                connection(wanStatus)?.let { put("Internet", it) }
                CONNECTION_TYPES.getOrNull(wan.num("adv_connect_type")?.toInt() ?: -1)?.let { put("Connection type", it) }
                wan.str("adv_gateway").takeIf { it.isNotEmpty() }?.let { put("Gateway", it) }
                wan.str("adv_dns1").takeIf { it.isNotEmpty() }?.let { put("DNS", it) }
                sys.str("adv_lan_ip").takeIf { it.isNotEmpty() }?.let { put("LAN address", it) }
                sys.str("adv_wrl_channel").takeIf { it.isNotEmpty() }?.let { put("2.4 GHz channel", "$it (${sys.str("adv_wrl_band")} MHz)") }
                sys.str("adv_wrl_channel_5g").takeIf { it.isNotEmpty() }?.let { put("5 GHz channel", "$it (${sys.str("adv_wrl_band_5g")} MHz)") }
                rt.num("clientNum")?.let { put("Connected devices", it.toString()) }
            },
        )
    }

    /** `ac10v6` with hardware `V6.0` is the "AC10 V6.0". */
    internal fun modelName(product: String, hardware: String): String? {
        if (product.isEmpty()) return null
        val name = Regex("""^(.*?)v\d+$""").find(product)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() } ?: product
        return listOf(name.uppercase(), hardware).filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** @param onlineList answer of `getOnlineList`: a summary object first, then one object per device. */
    fun parseClients(onlineList: Any?): List<ConnectedClient> =
        onlineList.asList("getOnlineList").mapNotNull { item ->
            val device = (item as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } ?: return@mapNotNull null
            val mac = device.str("deviceId").lowercase().takeIf { MAC.matches(it) } ?: return@mapNotNull null
            // A device on the blacklist is not on the network, whatever the list still says.
            if (device.str("black") == "1") return@mapNotNull null
            val guest = device.str("isGuestClient") == "true"
            val (medium, band) = when (device.str("line")) {
                "0" -> ConnectionMedium.WIRED to "Wired"
                "1" -> ConnectionMedium.WIRELESS to "2.4 GHz"
                "2" -> ConnectionMedium.WIRELESS to "5 GHz"
                else -> ConnectionMedium.UNKNOWN to null
            }
            ConnectedClient(
                macAddress = mac,
                ipAddress = device.str("ip").ifEmpty { null },
                hostname = device.str("devName").ifEmpty { null },
                interfaceName = band?.let { if (guest) "$it guest" else it },
                medium = medium,
            )
        }
}
