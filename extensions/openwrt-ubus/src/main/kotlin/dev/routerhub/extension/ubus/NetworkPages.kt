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

private const val NETWORK = "network"
private const val DHCP = "dhcp"
private const val FIREWALL = "firewall"
private const val GROUP = SettingsGroup.NETWORK
private const val SECURITY_GROUP = SettingsGroup.FIREWALL

/**
 * The router's address stays read-only: after changing it the phone would have to confirm the change
 * from an address this connection doesn't know, so the router would always roll it back.
 */
internal object LanPage : UbusPage {
    const val DHCP_ON = "dhcp"
    const val START = "start"
    const val LIMIT = "limit"
    const val LEASE = "leasetime"

    override val info = SettingsPageInfo("lan", "LAN and DHCP", GROUP, "Address range and lease time")

    private suspend fun pool(router: UbusRouter): UciSection? =
        router.uci.sections(DHCP, "dhcp").firstOrNull { it.str("interface") == "lan" }

    override suspend fun read(router: UbusRouter): SettingsPage {
        val lan = router.uci.section(NETWORK, "lan")
        val pool = pool(router)
        return SettingsPage(
            info = info,
            fields = buildList {
                add(SettingField.Info("address", "Router address", lan?.str("ipaddr").orEmpty(), "Change it in LuCI, which can follow the router to its new address"))
                lan?.str("netmask")?.takeIf { it.isNotEmpty() }?.let { add(SettingField.Info("netmask", "Subnet mask", it)) }
                if (pool != null) {
                    add(SettingField.Toggle(DHCP_ON, "DHCP server", !pool.flag("ignore")))
                    add(SettingField.Number(START, "First address", pool.str("start", "100").toLongOrNull() ?: 100, 1, 65534, "Counted from the network's start"))
                    add(SettingField.Number(LIMIT, "Number of addresses", pool.str("limit", "150").toLongOrNull() ?: 150, 1, 65534))
                    add(SettingField.Text(LEASE, "Lease time", pool.str("leasetime", "12h"), "Like 12h, 30m or 7d, or infinite"))
                }
            },
        )
    }

    override suspend fun apply(router: UbusRouter, changes: Map<String, String>, username: String) {
        val pool = pool(router) ?: throw InvalidSettingException("page", "This router has no DHCP pool for the LAN")
        val values = buildMap {
            changes[START]?.let { put("start", Rules.number(START, it, 1L..65534L, "The first address").toString()) }
            changes[LIMIT]?.let { put("limit", Rules.number(LIMIT, it, 1L..65534L, "The number of addresses").toString()) }
            changes[LEASE]?.let { put("leasetime", Rules.leaseTime(LEASE, it)) }
            if (changes.flag(DHCP_ON) == false) put("ignore", "1")
        }
        router.uci.change(DHCP) {
            set(pool.name, values)
            if (changes.flag(DHCP_ON) == true && pool.has("ignore")) deleteOption(pool.name, "ignore")
        }
    }
}

internal object WanPage : UbusPage {
    const val PROTOCOL = "proto"
    const val ADDRESS = "ipaddr"
    const val MASK = "netmask"
    const val GATEWAY = "gateway"
    const val DNS = "dns"
    const val USER = "username"
    const val PASSWORD = "password"

    private val PROTOCOLS = listOf(
        ChoiceOption("dhcp", "Automatic (DHCP)"),
        ChoiceOption("static", "Static address"),
        ChoiceOption("pppoe", "PPPoE"),
        ChoiceOption("none", "Not configured"),
    )

    override val info = SettingsPageInfo("wan", "Internet connection", GROUP, "How the WAN port connects")

    override suspend fun read(router: UbusRouter): SettingsPage {
        val wan = wan(router)
        val state = UbusStatusReader.interfaces(router).firstOrNull { it.str("interface") == "wan" }
        val protocol = wan.str("proto", "none")
        return SettingsPage(
            info = info,
            fields = listOf(
                SettingField.Info("current", "Current address", state?.let(UbusStatusReader::ipv4) ?: "None"),
                SettingField.Choice(PROTOCOL, "Connection type", protocol, optionsWith(protocol, PROTOCOLS)),
                SettingField.Text(ADDRESS, "Static address", wan.str("ipaddr"), "Static only", keyboard = SettingField.Keyboard.IP_ADDRESS),
                SettingField.Text(MASK, "Subnet mask", wan.str("netmask"), "Static only", keyboard = SettingField.Keyboard.IP_ADDRESS),
                SettingField.Text(GATEWAY, "Gateway", wan.str("gateway"), "Static only", keyboard = SettingField.Keyboard.IP_ADDRESS),
                SettingField.Text(DNS, "DNS servers", wan.str("dns"), "Separated by spaces. Empty uses the provider's"),
                SettingField.Text(USER, "PPPoE user name", wan.str("username"), "PPPoE only"),
                SettingField.Secret(PASSWORD, "New PPPoE password", "PPPoE only. Leave empty to keep the current one"),
            ),
            applyWarning = "If the connection to the router is lost, the router undoes this change by itself within a minute.",
        )
    }

    private suspend fun wan(router: UbusRouter) = router.uci.section(NETWORK, "wan")
        ?: throw InvalidSettingException("page", "This router has no WAN interface")

    override suspend fun apply(router: UbusRouter, changes: Map<String, String>, username: String) {
        val wan = wan(router)
        val protocol = changes[PROTOCOL] ?: wan.str("proto", "none")
        val value = { key: String -> changes[key] ?: wan.str(key) }
        changes[ADDRESS]?.takeIf { it.isNotBlank() }?.let { Rules.ip(ADDRESS, it) }
        changes[MASK]?.takeIf { it.isNotBlank() }?.let { Rules.mask(MASK, it) }
        changes[GATEWAY]?.takeIf { it.isNotBlank() }?.let { Rules.ip(GATEWAY, it, "The gateway") }
        val dns = changes[DNS]?.trim()?.split(Regex("""[\s,]+"""))?.filter { it.isNotEmpty() }
            ?.onEach { Rules.ip(DNS, it, "The DNS server") }
        if (protocol == "static") {
            if (value(ADDRESS).isBlank()) throw InvalidSettingException(ADDRESS, "A static connection needs an address")
            if (value(MASK).isBlank()) throw InvalidSettingException(MASK, "A static connection needs a subnet mask")
        }

        val values = buildMap<String, Any?> {
            changes[PROTOCOL]?.let { put("proto", it) }
            for (key in listOf(ADDRESS, MASK, GATEWAY, USER)) {
                changes[key]?.trim()?.takeIf { it.isNotEmpty() }?.let { put(key, it) }
            }
            changes[PASSWORD]?.takeIf { it.isNotEmpty() }?.let { put("password", it) }
            if (!dns.isNullOrEmpty()) {
                put("dns", dns)
                put("peerdns", "0")
            }
        }
        val removed = listOf(ADDRESS, MASK, GATEWAY, USER).filter { changes[it]?.isBlank() == true && wan.has(it) } +
            if (dns?.isEmpty() == true) listOf("dns", "peerdns").filter(wan::has) else emptyList()
        router.uci.change(NETWORK) {
            set(wan.name, values)
            removed.forEach { deleteOption(wan.name, it) }
        }
    }
}

internal object InterfacesPage : ReadOnlyPage {
    override val info = SettingsPageInfo("interfaces", "Interfaces", GROUP, "State of every network interface")

    private val COLUMNS = listOf(
        TableColumn("name", "Name"),
        TableColumn("state", "State", shortTitle = ""),
        TableColumn("protocol", "Protocol", shortTitle = ""),
        TableColumn("address", "Address", shortTitle = ""),
        TableColumn("device", "Device", shortTitle = ""),
    )

    override suspend fun read(router: UbusRouter) = SettingsPage(
        info = info,
        fields = listOf(
            SettingField.Table(
                key = "interfaces",
                title = "Interfaces",
                columns = COLUMNS,
                rows = UbusStatusReader.interfaces(router).map {
                    listOf(
                        it.str("interface"),
                        if (it["up"] == true) "Up" else "Down",
                        it.str("proto"),
                        UbusStatusReader.ipv4(it).orEmpty(),
                        it.str("l3_device").ifEmpty { it.str("device") },
                    )
                },
                itemName = "interface",
                readOnly = true,
            ),
        ),
    )
}

internal object ReservationsPage : UbusPage {
    const val HOSTS = "hosts"
    private const val TYPE = "host"

    override val info = SettingsPageInfo("reservations", "Address reservations", GROUP, "Fixed addresses for your devices")

    private val COLUMNS = listOf(
        TableColumn("name", "Name", optional = true),
        TableColumn("mac", "MAC address", ColumnKind.MAC_ADDRESS, shortTitle = ""),
        TableColumn("ip", "Address", ColumnKind.IP_ADDRESS, shortTitle = ""),
    )

    private fun row(section: UciSection) =
        listOf(section.str("name"), section.str("mac").lowercase(), section.str("ip"))

    /** Each as name, MAC and address. */
    suspend fun rows(router: UbusRouter): List<List<String>> = router.uci.sections(DHCP, TYPE).map(::row)

    override suspend fun read(router: UbusRouter) = SettingsPage(
        info = info,
        fields = listOf(
            SettingField.Table(
                key = HOSTS,
                title = "Reservations",
                columns = COLUMNS,
                rows = router.uci.sections(DHCP, TYPE).map(::row),
                itemName = "reservation",
            ),
        ),
    )

    override suspend fun apply(router: UbusRouter, changes: Map<String, String>, username: String) {
        val submitted = TableCodec.decode(changes[HOSTS] ?: return).map { (name, mac, ip) ->
            listOf(Rules.label(HOSTS, name, "The name"), Rules.mac(HOSTS, mac), Rules.ip(HOSTS, ip))
        }
        val current = router.uci.sections(DHCP, TYPE).map { it to row(it) }
        router.uci.change(DHCP) {
            syncRows(current, submitted, TYPE) { (name, mac, ip) ->
                buildMap {
                    if (name.isNotEmpty()) put("name", name)
                    put("mac", mac)
                    put("ip", ip)
                }
            }
        }
    }
}

/**
 * One firewall rule per device, refusing what it forwards from the LAN to the WAN. They carry a name
 * of their own, so rules the user made elsewhere are never listed here or removed.
 */
internal object BlockedDevicesPage : UbusPage {
    const val DEVICES = "devices"
    private const val TYPE = "rule"
    private const val PREFIX = "RouterHub block"
    private const val LAN_ZONE = "lan"
    private const val WAN_ZONE = "wan"

    override val info = SettingsPageInfo("blocked-devices", "Blocked devices", SECURITY_GROUP, "Devices kept off the internet")

    private val COLUMNS = listOf(
        TableColumn("name", "Name", optional = true),
        TableColumn("mac", "MAC address", ColumnKind.MAC_ADDRESS, shortTitle = ""),
    )

    private fun row(section: UciSection) =
        listOf(section.str("name").removePrefix(PREFIX).removePrefix(": "), section.str("src_mac").lowercase())

    private suspend fun blocks(router: UbusRouter) =
        router.uci.sections(FIREWALL, TYPE).filter { it.str("name").startsWith(PREFIX) && it.flag("enabled", true) }

    /** Each as name and MAC. */
    suspend fun rows(router: UbusRouter): List<List<String>> = blocks(router).map(::row)

    suspend fun macs(router: UbusRouter): Set<String> = rows(router).map { it[1] }.toSet()

    override suspend fun read(router: UbusRouter) = SettingsPage(
        info = info,
        fields = listOf(
            SettingField.Table(
                key = DEVICES,
                title = "Blocked devices",
                columns = COLUMNS,
                rows = rows(router),
                itemName = "device",
                emptyText = "No device is blocked",
            ),
        ),
    )

    override suspend fun apply(router: UbusRouter, changes: Map<String, String>, username: String) {
        val submitted = TableCodec.decode(changes[DEVICES] ?: return).map { (name, mac) ->
            listOf(Rules.label(DEVICES, name, "The name"), Rules.mac(DEVICES, mac))
        }
        val zones = router.uci.sections(FIREWALL, "zone").map { it.str("name") }
        if (submitted.isNotEmpty() && (LAN_ZONE !in zones || WAN_ZONE !in zones)) {
            throw InvalidSettingException(DEVICES, "This router has no firewall zones named lan and wan")
        }
        val current = blocks(router).map { it to row(it) }
        router.uci.change(FIREWALL) {
            syncRows(current, submitted, TYPE) { (name, mac) ->
                mapOf(
                    "name" to if (name.isEmpty()) PREFIX else "$PREFIX: $name",
                    "src" to LAN_ZONE,
                    "dest" to WAN_ZONE,
                    "src_mac" to mac,
                    "proto" to "all",
                    "target" to "REJECT",
                )
            }
        }
    }
}

internal object PortForwardsPage : UbusPage {
    const val FORWARDS = "forwards"
    private const val TYPE = "redirect"
    private val PROTOCOLS = listOf(
        ChoiceOption("tcp", "TCP"),
        ChoiceOption("udp", "UDP"),
        ChoiceOption("tcp udp", "TCP and UDP"),
    )

    override val info = SettingsPageInfo("port-forwards", "Port forwarding", SECURITY_GROUP, "Open ports to devices on the LAN")

    private val COLUMNS = listOf(
        TableColumn("name", "Name"),
        TableColumn("protocol", "Protocol", ColumnKind.CHOICE, PROTOCOLS, defaultValue = "tcp", shortTitle = ""),
        TableColumn("external", "External port", hint = "80 or 8000-8010", shortTitle = "from"),
        TableColumn("ip", "Device address", ColumnKind.IP_ADDRESS, shortTitle = "to"),
        TableColumn("internal", "Device port", optional = true, hint = "Empty uses the external port", shortTitle = "port"),
    )

    /** Rules like masquerading exceptions also use this section type; they are not forwards. */
    private suspend fun sections(router: UbusRouter) =
        router.uci.sections(FIREWALL, TYPE).filter { it.str("target", "DNAT") == "DNAT" }

    private fun row(section: UciSection) = listOf(
        section.str("name"),
        section.str("proto", "tcp udp"),
        section.str("src_dport"),
        section.str("dest_ip"),
        section.str("dest_port").ifEmpty { section.str("src_dport") },
    )

    override suspend fun read(router: UbusRouter) = SettingsPage(
        info = info,
        fields = listOf(
            SettingField.Table(
                key = FORWARDS,
                title = "Port forwards",
                columns = COLUMNS,
                rows = sections(router).map(::row),
                itemName = "forward",
            ),
        ),
    )

    override suspend fun apply(router: UbusRouter, changes: Map<String, String>, username: String) {
        val submitted = TableCodec.decode(changes[FORWARDS] ?: return).map { (name, protocol, external, ip, internal) ->
            val label = Rules.label(FORWARDS, name, "The name")
            if (label.isEmpty()) throw InvalidSettingException(FORWARDS, "A port forward needs a name")
            Rules.oneOf(FORWARDS, protocol, PROTOCOLS.map { it.value }, "The protocol")
            val from = Rules.ports(FORWARDS, external, "The external port")
            val to = internal.ifBlank { from }
            listOf(label, protocol, from, Rules.ip(FORWARDS, ip), Rules.ports(FORWARDS, to, "The device port"))
        }
        val current = sections(router).map { it to row(it) }
        router.uci.change(FIREWALL) {
            syncRows(current, submitted, TYPE) { (name, protocol, external, ip, internal) ->
                mapOf(
                    "name" to name,
                    "target" to "DNAT",
                    "src" to "wan",
                    "dest" to "lan",
                    "proto" to protocol,
                    "src_dport" to external,
                    "dest_ip" to ip,
                    "dest_port" to internal,
                )
            }
        }
    }
}

internal object FirewallPage : ReadOnlyPage {
    override val info = SettingsPageInfo("firewall", "Zones and rules", SECURITY_GROUP, "What the firewall allows")

    private val ZONES = listOf(
        TableColumn("name", "Zone"),
        TableColumn("input", "Input"),
        TableColumn("output", "Output"),
        TableColumn("forward", "Forward"),
        TableColumn("masq", "Masquerading"),
    )
    private val RULES = listOf(
        TableColumn("name", "Name"),
        TableColumn("from", "From"),
        TableColumn("to", "To"),
        TableColumn("proto", "Protocol", shortTitle = ""),
        TableColumn("port", "Port"),
        TableColumn("target", "Action", shortTitle = ""),
    )

    override suspend fun read(router: UbusRouter): SettingsPage {
        val sections = router.uci.sections(FIREWALL)
        return SettingsPage(
            info = info,
            fields = listOf(
                SettingField.Table(
                    key = "zones",
                    title = "Zones",
                    columns = ZONES,
                    rows = sections.filter { it.type == "zone" }.map {
                        listOf(it.str("name"), it.str("input"), it.str("output"), it.str("forward"), if (it.flag("masq")) "On" else "Off")
                    },
                    itemName = "zone",
                    readOnly = true,
                ),
                SettingField.Table(
                    key = "rules",
                    title = "Traffic rules",
                    columns = RULES,
                    rows = sections.filter { it.type == "rule" }.map {
                        listOf(it.str("name"), it.str("src", "Router"), it.str("dest", "Router"), it.str("proto", "any"), it.str("dest_port"), it.str("target"))
                    },
                    itemName = "rule",
                    readOnly = true,
                ),
            ),
        )
    }
}
