package dev.routerhub.extension.ubus

import dev.routerhub.api.CommandFailedException
import dev.routerhub.api.ConnectedClient
import dev.routerhub.api.ConnectionMedium
import dev.routerhub.api.RouterException
import dev.routerhub.api.RouterStatus
import dev.routerhub.api.WifiBand
import dev.routerhub.api.WifiRadio

/** A radio as `network.wireless status` reports it. [accessPoints] are device names like `phy0-ap0`. */
internal class Radio(
    val name: String,
    val band: WifiBand,
    val enabled: Boolean,
    val ssid: String?,
    val accessPoints: List<String>,
    /** Any of its network devices, in whatever mode; what `iwinfo` needs to be asked about the radio. */
    val device: String?,
)

internal object UbusStatusReader {
    private const val ARP_REACHABLE = "0x2"

    suspend fun radios(router: UbusRouter): List<Radio> =
        wirelessDevices(router).map { (name, value) ->
            val radio = value.asObject("Radio $name")
            val config = radio["config"]?.asObject("Radio $name's config").orEmpty()
            val networks = radio.objects("interfaces")
            val accessPoints = networks.filter { it["config"]?.asObject("A network").orEmpty().str("mode") == "ap" }
            Radio(
                name = name,
                band = band(config),
                enabled = radio["disabled"] != true,
                ssid = accessPoints.firstOrNull()?.get("config")?.asObject("A network")?.str("ssid")?.ifEmpty { null },
                accessPoints = accessPoints.map { it.str("ifname") }.filter { it.isNotEmpty() },
                device = networks.map { it.str("ifname") }.firstOrNull { it.isNotEmpty() },
            )
        }

    /**
     * LuCI's own call, which its ACLs always cover. `network.wireless status` answers alike but often
     * isn't allowed. A router without Wi-Fi has neither (OpenWrt 25.12 drops the object), and that is
     * no reason to fail the whole status: it simply has no radios.
     */
    private suspend fun wirelessDevices(router: UbusRouter): JsonObject = try {
        router.api.call("luci-rpc", "getWirelessDevices")
    } catch (_: CommandFailedException) {
        try {
            router.api.call("network.wireless", "status")
        } catch (_: CommandFailedException) {
            emptyMap()
        }
    }

    /** `band` is the current option, `hwmode` the one older configs still have. */
    fun band(config: JsonObject): WifiBand = when (config.str("band")) {
        "2g" -> WifiBand.GHZ_2_4
        "5g" -> WifiBand.GHZ_5
        "6g" -> WifiBand.GHZ_6
        else -> when (config.str("hwmode")) {
            "11b", "11g" -> WifiBand.GHZ_2_4
            "11a" -> WifiBand.GHZ_5
            else -> WifiBand.UNKNOWN
        }
    }

    suspend fun status(router: UbusRouter): RouterStatus {
        val board = router.api.call("system", "board")
        val info = router.api.call("system", "info")
        val release = board["release"]?.asObject("The release").orEmpty()
        val interfaces = interfaces(router)
        val wan = interfaces.firstOrNull(::hasDefaultRoute)
        val memory = info["memory"]?.asObject("The memory").orEmpty()
        return RouterStatus(
            model = board.str("model").ifEmpty { null },
            firmwareVersion = release.str("description").ifEmpty { release.str("version") }.ifEmpty { null },
            uptimeSeconds = info.num("uptime"),
            wanIpv4 = wan?.let(::ipv4)?.substringBefore('/'),
            wanConnected = wan != null,
            wifiRadios = radios(router).map { WifiRadio(it.name, it.band, it.enabled, it.ssid) },
            extras = buildMap {
                wan?.let { put("WAN interface", it.str("interface")) }
                board.str("hostname").takeIf { it.isNotEmpty() }?.let { put("Hostname", it) }
                release.str("target").takeIf { it.isNotEmpty() }?.let { put("Target", it) }
                board.str("kernel").takeIf { it.isNotEmpty() }?.let { put("Kernel", it) }
                val total = memory.num("total")
                val available = memory.num("available") ?: memory.num("free")
                if (total != null && available != null) {
                    put("Free memory", "${available / MEBIBYTE} of ${total / MEBIBYTE} MiB")
                }
            },
        )
    }

    suspend fun interfaces(router: UbusRouter): List<JsonObject> =
        router.api.call("network.interface", "dump").objects("interface")

    private fun hasDefaultRoute(network: JsonObject): Boolean =
        network["up"] == true && network.objects("route").any { it.str("target") == "0.0.0.0" && it.num("mask") == 0L }

    /** The first address as `192.0.2.1/24`, if the interface has one. */
    fun ipv4(network: JsonObject): String? =
        network.objects("ipv4-address").firstOrNull()?.let { "${it.str("address")}/${it.str("mask")}" }

    /**
     * Who is connected now. Stations are asked from the radios and wired devices from the ARP table;
     * DHCP leases and host hints only add names. A lease alone says little, as it outlives the device,
     * so leases only count when the ARP table can't be read.
     */
    suspend fun clients(router: UbusRouter): List<ConnectedClient> {
        val interfaces = interfaces(router)
        val lanDevice = interfaces.firstOrNull { it.str("interface") == "lan" }?.str("l3_device").orEmpty()
        val stations = LinkedHashMap<String, String>()
        for (radio in radios(router)) {
            for (device in radio.accessPoints) {
                router.api.call("iwinfo", "assoclist", mapOf("device" to device)).objects("results").forEach {
                    stations[it.str("mac").lowercase()] = device
                }
            }
        }
        val leases = router.api.call("luci-rpc", "getDHCPLeases", mapOf("family" to 4)).objects("dhcp_leases")
            .associateBy { it.str("macaddr").lowercase() }
        val hints = router.api.call("luci-rpc", "getHostHints").entries
            .associate { (mac, value) -> mac.lowercase() to value.asObject("A host hint") }
        val neighbours = arp(router)?.filter { lanDevice.isEmpty() || it.device == lanDevice }?.associateBy { it.mac }

        val blockedRows = BlockedDevicesPage.rows(router)
        // A paused device is blocked for now too. Losing the pauses isn't worth losing the list over.
        val paused = runCatching { Pauses.active(router, Pauses.routerNow(router)).keys }.getOrDefault(emptySet())
        val blocked = blockedRows.map { it[1] }.toSet() + paused

        val macs = stations.keys + (neighbours?.keys ?: leases.keys)
        val away = blockedRows.filter { it[1] !in macs }.map { (name, mac) ->
            ConnectedClient(macAddress = mac, hostname = name.ifEmpty { null }, internetBlocked = true, online = false)
        }
        return away + macs.map { mac ->
            val lease = leases[mac]
            val hint = hints[mac]
            val neighbour = neighbours?.get(mac)
            ConnectedClient(
                macAddress = mac,
                ipAddress = lease?.str("ipaddr")?.ifEmpty { null }
                    ?: neighbour?.ip
                    ?: (hint?.get("ipaddrs") as? List<*>)?.firstOrNull()?.toString(),
                hostname = lease?.str("hostname")?.ifEmpty { null } ?: hint?.str("name")?.ifEmpty { null },
                interfaceName = stations[mac] ?: neighbour?.device,
                medium = when {
                    mac in stations -> ConnectionMedium.WIRELESS
                    neighbour != null -> ConnectionMedium.WIRED
                    else -> ConnectionMedium.UNKNOWN
                },
                internetBlocked = mac in blocked,
            )
        }
    }

    private class Neighbour(val ip: String, val mac: String, val device: String)

    /** The reachable entries of the ARP table, or `null` where this user may not read it. */
    private suspend fun arp(router: UbusRouter): List<Neighbour>? {
        val text = try {
            router.api.call("file", "read", mapOf("path" to "/proc/net/arp")).str("data")
        } catch (_: RouterException) {
            return null
        }
        return parseArp(text).map { Neighbour(it[0], it[1], it[2]) }
    }

    /** `/proc/net/arp`, as ip, mac and device of each reachable entry. */
    fun parseArp(text: String): List<List<String>> = text.lines().drop(1).mapNotNull { line ->
        val cells = line.trim().split(Regex("""\s+"""))
        if (cells.size < 6 || cells[2] != ARP_REACHABLE) null else listOf(cells[0], cells[3].lowercase(), cells[5])
    }

    private const val MEBIBYTE = 1024 * 1024
}
