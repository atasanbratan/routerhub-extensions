package dev.routerhub.extension.tenda

import dev.routerhub.api.ChoiceOption
import dev.routerhub.api.ColumnKind
import dev.routerhub.api.InvalidSettingException
import dev.routerhub.api.SettingField
import dev.routerhub.api.SettingsPage
import dev.routerhub.api.SettingsPageInfo
import dev.routerhub.api.TableCodec
import dev.routerhub.api.TableColumn

/** The rows of a table field as submitted, each with exactly [columns] cells. */
internal fun decodeRows(key: String, encoded: String, columns: Int): List<List<String>> {
    val rows = TableCodec.decode(encoded)
    rows.firstOrNull { it.size != columns }?.let {
        throw InvalidSettingException(key, "A row has ${it.size} values; expected $columns")
    }
    return rows
}

// ---------------------------------------------------------------------------------------------
// LAN and DHCP server: AdvGetLanIp / AdvSetLanip
// ---------------------------------------------------------------------------------------------

internal object LanPage : TendaPage {
    override val info = SettingsPageInfo("lan", "LAN and DHCP", "Network", "Router address and the DHCP server")

    const val LAN_IP = "lanIp"
    const val MASK = "mask"
    const val DHCP = "dhcp"
    const val START = "start"
    const val END = "end"
    const val LEASE = "lease"
    const val CUSTOM_DNS = "customDns"
    const val DNS_1 = "dns1"
    const val DNS_2 = "dns2"

    private val LEASES = listOf(
        "604800" to "7 days",
        "172800" to "2 days",
        "86400" to "1 day",
        "21600" to "6 hours",
        "3600" to "1 hour",
    )

    private class State(g: JsonObject) {
        val lanIp = g.str("lanIp")
        val mask = g.str("lanMask")
        val dhcp = g.isOn("dhcpEn")
        val start = g.str("startIp")
        val end = g.str("endIp")
        val lease = g.str("leaseTime").ifEmpty { "86400" }

        // "lanDnsAuto" is 1 when the DHCP clients are told the router's own DNS, 0 when custom ones are set
        val customDns = g.str("lanDnsAuto") == "0"
        val dns1 = g.str("lanDns1")
        val dns2 = g.str("lanDns2")
        val wanIp = g.str("wanIp")
    }

    private suspend fun state(api: TendaApi) = State(api.read("AdvGetLanIp").asObject("AdvGetLanIp"))

    private fun leaseOptions(current: String) =
        (LEASES + listOfNotNull(current.takeIf { c -> LEASES.none { it.first == c } }?.let { it to "$it seconds" }))
            .map { ChoiceOption(it.first, it.second) }

    override suspend fun read(api: TendaApi): SettingsPage {
        val s = state(api)
        return SettingsPage(
            info = info,
            fields = listOf(
                SettingField.Text(LAN_IP, "Router address", s.lanIp, keyboard = SettingField.Keyboard.IP_ADDRESS,
                    summary = "Changing it moves the router to a new address"),
                SettingField.Text(MASK, "Subnet mask", s.mask, keyboard = SettingField.Keyboard.IP_ADDRESS),
                SettingField.Toggle(DHCP, "DHCP server", s.dhcp, summary = "Hands out addresses to the devices on your network"),
                SettingField.Text(START, "First address", s.start, keyboard = SettingField.Keyboard.IP_ADDRESS),
                SettingField.Text(END, "Last address", s.end, keyboard = SettingField.Keyboard.IP_ADDRESS),
                SettingField.Choice(LEASE, "Lease time", s.lease, leaseOptions(s.lease)),
                SettingField.Toggle(CUSTOM_DNS, "Custom DNS servers", s.customDns, summary = "Otherwise devices use the router as their DNS server"),
                SettingField.Text(DNS_1, "Primary DNS", s.dns1, keyboard = SettingField.Keyboard.IP_ADDRESS),
                SettingField.Text(DNS_2, "Secondary DNS", s.dns2, summary = "Optional", keyboard = SettingField.Keyboard.IP_ADDRESS),
            ),
            applyWarning = "Applying restarts the network. If you change the router address, this phone loses " +
                "the connection until it gets a new address, and the router must be added again in the app under the new one.",
        )
    }

    override suspend fun apply(api: TendaApi, changes: Map<String, String>, context: TendaContext) {
        val s = state(api)
        val lanIp = changes[LAN_IP]?.trim() ?: s.lanIp
        val mask = changes[MASK]?.trim() ?: s.mask
        val dhcp = changes.flag(DHCP) ?: s.dhcp
        val start = changes[START]?.trim() ?: s.start
        val end = changes[END]?.trim() ?: s.end
        val lease = changes[LEASE] ?: s.lease
        val customDns = changes.flag(CUSTOM_DNS) ?: s.customDns
        val dns1 = changes[DNS_1]?.trim() ?: s.dns1
        val dns2 = changes[DNS_2]?.trim() ?: s.dns2

        Rules.hostIp(LAN_IP, lanIp, "The router address")
        Rules.mask(MASK, mask)
        // A LAN that overlaps the network on the internet side would cut the router off from the internet.
        if (s.wanIp.isNotEmpty() && Rules.sameSubnet(lanIp, s.wanIp, mask)) {
            throw InvalidSettingException(LAN_IP, "The router address would overlap the network on the internet side (${s.wanIp})")
        }
        if (dhcp) {
            Rules.hostIp(START, start, "The first address")
            Rules.hostIp(END, end, "The last address")
            if (!Rules.sameSubnet(start, lanIp, mask)) throw InvalidSettingException(START, "The first address must be in the router's network")
            if (!Rules.sameSubnet(end, lanIp, mask)) throw InvalidSettingException(END, "The last address must be in the router's network")
            if (Rules.ipOrder(start, end) > 0) throw InvalidSettingException(END, "The last address must not come before the first")
            if (Rules.ipOrder(start, lanIp) <= 0 && Rules.ipOrder(lanIp, end) <= 0) {
                throw InvalidSettingException(START, "The address range must not include the router's own address")
            }
        }
        Rules.oneOf(LEASE, lease, leaseOptions(s.lease).map { it.value }, "The lease time")
        if (customDns) {
            Rules.hostIp(DNS_1, dns1, "The DNS server")
            if (dns2.isNotEmpty()) Rules.hostIp(DNS_2, dns2, "The DNS server")
        }

        api.write(
            "AdvSetLanip",
            Form.of(
                "lanIp" to lanIp,
                "lanMask" to mask,
                "dhcpEn" to dhcp.bit(),
                // with the server off the page keeps sending the range it had
                "startIp" to if (dhcp) start else s.start,
                "endIp" to if (dhcp) end else s.end,
                "leaseTime" to lease,
                "lanDnsAuto" to if (customDns) "0" else "1",
                "lanDns1" to dns1,
                "lanDns2" to dns2,
            ),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// DHCP reservations ("IP & MAC binding"): GetIpMacBind / SetIpMacBind
// ---------------------------------------------------------------------------------------------

internal object DhcpReservationsPage : TendaPage {
    override val info = SettingsPageInfo("dhcp-reservations", "DHCP reservations", "Network", "Always give a device the same address")

    const val RESERVATIONS = "reservations"
    private const val MAX_ROWS = 32
    private const val NAME_LENGTH = 20

    private val COLUMNS = listOf(
        TableColumn("name", "Name", ColumnKind.TEXT, optional = true, shortTitle = ""),
        TableColumn("mac", "MAC address", ColumnKind.MAC_ADDRESS, shortTitle = ""),
        TableColumn("ip", "IP address", ColumnKind.IP_ADDRESS, shortTitle = ""),
    )

    override suspend fun read(api: TendaApi): SettingsPage {
        val g = api.read("GetIpMacBind").asObject("GetIpMacBind")
        val rows = g.objects("bindList").map { row ->
            listOf(
                row.str("devname").ifEmpty { row.str("devName") },
                row.str("macaddr").lowercase(),
                row.str("ipaddr"),
            )
        }
        return SettingsPage(
            info = info,
            fields = listOf(
                SettingField.Table(
                    RESERVATIONS, "Reserved addresses", COLUMNS, rows,
                    maxRows = MAX_ROWS, itemName = "reservation", emptyText = "No reservations",
                    summary = "Takes effect when the device next connects to the router",
                ),
            ),
        )
    }

    override suspend fun apply(api: TendaApi, changes: Map<String, String>, context: TendaContext) {
        val encoded = changes[RESERVATIONS] ?: return
        val g = api.read("GetIpMacBind").asObject("GetIpMacBind")
        val lanIp = g.str("lanIp")
        val mask = g.str("lanMask")
        val unavailable = g.str("dhttpIP")

        val rows = decodeRows(RESERVATIONS, encoded, COLUMNS.size)
        if (rows.size > MAX_ROWS) throw InvalidSettingException(RESERVATIONS, "The router holds at most $MAX_ROWS reservations")

        val macs = mutableSetOf<String>()
        val ips = mutableSetOf<String>()
        val clean = rows.map { (name, mac, ip) ->
            val cleanName = Rules.label(RESERVATIONS, name, "A name", NAME_LENGTH)
            val cleanMac = Rules.mac(RESERVATIONS, mac)
            val cleanIp = Rules.hostIp(RESERVATIONS, ip, "The address")
            if (!Rules.sameSubnet(cleanIp, lanIp, mask)) throw InvalidSettingException(RESERVATIONS, "$cleanIp is not in the router's network ($lanIp)")
            if (cleanIp == lanIp) throw InvalidSettingException(RESERVATIONS, "$cleanIp is the router's own address")
            if (unavailable.isNotEmpty() && cleanIp == unavailable) throw InvalidSettingException(RESERVATIONS, "$cleanIp can't be reserved")
            if (!macs.add(cleanMac)) throw InvalidSettingException(RESERVATIONS, "$cleanMac is listed twice")
            if (!ips.add(cleanIp)) throw InvalidSettingException(RESERVATIONS, "$cleanIp is reserved for two devices")
            Triple(cleanName, cleanMac, cleanIp)
        }

        // The page posts every reservation as: name (escaped), CR, MAC, CR, address, rows separated by LF.
        val list = clean.joinToString("\n") { (name, mac, ip) -> "${Form.component(name)}\r$mac\r$ip" }
        api.write("SetIpMacBind", "bindnum=${clean.size}&list=$list")
    }
}

// ---------------------------------------------------------------------------------------------
// Port forwarding ("virtual server"): GetVirtualServerCfg / SetVirtualServerCfg
// ---------------------------------------------------------------------------------------------

internal object PortForwardingPage : TendaPage {
    override val info = SettingsPageInfo("port-forwarding", "Port forwarding", "Network", "Send incoming traffic to a device on your network")

    const val RULES = "rules"
    private const val MAX_RULES = 16

    private val PROTOCOLS = listOf(
        ChoiceOption("both", "TCP and UDP"),
        ChoiceOption("tcp", "TCP"),
        ChoiceOption("udp", "UDP"),
    )

    /** The router's own codes for the protocol column. */
    private val CODES = mapOf("both" to "0", "tcp" to "1", "udp" to "2")
    private val NAMES = CODES.entries.associate { (name, code) -> code to name }

    private val COLUMNS = listOf(
        TableColumn("ip", "Device address", ColumnKind.IP_ADDRESS, shortTitle = ""),
        TableColumn("lanPort", "LAN port", ColumnKind.NUMBER, shortTitle = "LAN port"),
        TableColumn("wanPort", "WAN port", ColumnKind.NUMBER, shortTitle = "WAN port"),
        TableColumn("protocol", "Protocol", ColumnKind.CHOICE, PROTOCOLS, defaultValue = "both", shortTitle = ""),
    )

    override suspend fun read(api: TendaApi): SettingsPage {
        val g = api.read("GetVirtualServerCfg").asObject("GetVirtualServerCfg")
        val rows = g.objects("virtualList").map { rule ->
            listOf(rule.str("ip"), rule.str("inPort"), rule.str("outPort"), NAMES[rule.str("protocol")] ?: "both")
        }
        return SettingsPage(
            info = info,
            fields = listOf(
                SettingField.Table(
                    RULES, "Forwarded ports", COLUMNS, rows,
                    maxRows = MAX_RULES, itemName = "rule", emptyText = "No ports are forwarded",
                    summary = "Traffic arriving at the WAN port goes to the LAN port of that device",
                ),
            ),
        )
    }

    override suspend fun apply(api: TendaApi, changes: Map<String, String>, context: TendaContext) {
        val encoded = changes[RULES] ?: return
        val g = api.read("GetVirtualServerCfg").asObject("GetVirtualServerCfg")
        val lanIp = g.str("lanIp")
        val mask = g.str("lanMask")

        val rows = decodeRows(RULES, encoded, COLUMNS.size)
        if (rows.size > MAX_RULES) throw InvalidSettingException(RULES, "The router holds at most $MAX_RULES rules")

        val wanPorts = mutableSetOf<Long>()
        val lines = rows.map { (ip, lanPort, wanPort, protocol) ->
            val cleanIp = Rules.hostIp(RULES, ip, "The device address")
            if (!Rules.sameSubnet(cleanIp, lanIp, mask)) throw InvalidSettingException(RULES, "$cleanIp is not in the router's network ($lanIp)")
            if (cleanIp == lanIp) throw InvalidSettingException(RULES, "$cleanIp is the router's own address")
            val lan = Rules.number(RULES, lanPort, 1L..65535L, "The LAN port")
            val wan = Rules.number(RULES, wanPort, 1L..65535L, "The WAN port")
            // One WAN port can be mapped only once, whatever the protocol: the router refuses a second rule.
            if (!wanPorts.add(wan)) throw InvalidSettingException(RULES, "WAN port $wan is forwarded twice")
            val code = CODES[protocol] ?: throw InvalidSettingException(RULES, "The protocol must be one of: tcp, udp, both")
            "$cleanIp,$lan,$wan,$code"
        }

        // Like the page, send the complete list; the separators are sent as they are.
        api.write("SetVirtualServerCfg", "list=${lines.joinToString("~")}")
    }
}

// ---------------------------------------------------------------------------------------------
// DMZ host: GetDMZCfg / SetDMZCfg
// ---------------------------------------------------------------------------------------------

internal object DmzPage : TendaPage {
    override val info = SettingsPageInfo("dmz", "DMZ host", "Network", "Expose one device to the internet")

    const val ENABLE = "enable"
    const val IP = "ip"

    override suspend fun read(api: TendaApi): SettingsPage {
        val g = api.read("GetDMZCfg").asObject("GetDMZCfg")
        return SettingsPage(
            info = info,
            fields = listOf(
                SettingField.Toggle(ENABLE, "DMZ host", g.isOn("dmzEn"), summary = "Every port of that device is reachable from the internet"),
                SettingField.Text(IP, "DMZ host address", g.str("dmzIp"), keyboard = SettingField.Keyboard.IP_ADDRESS),
            ),
        )
    }

    override suspend fun apply(api: TendaApi, changes: Map<String, String>, context: TendaContext) {
        val g = api.read("GetDMZCfg").asObject("GetDMZCfg")
        val enable = changes.flag(ENABLE) ?: g.isOn("dmzEn")
        val ip = changes[IP]?.trim() ?: g.str("dmzIp")
        if (enable) {
            Rules.hostIp(IP, ip, "The DMZ host address")
            if (!Rules.sameSubnet(ip, g.str("lanIp"), g.str("lanMask"))) throw InvalidSettingException(IP, "$ip is not in the router's network (${g.str("lanIp")})")
            if (ip == g.str("lanIp")) throw InvalidSettingException(IP, "The DMZ host can't be the router itself")
        }
        api.write("SetDMZCfg", Form.of("dmzEn" to enable.bit(), "dmzIp" to if (enable) ip else g.str("dmzIp")))
    }
}

// ---------------------------------------------------------------------------------------------
// UPnP: GetUpnpCfg / SetUpnpCfg
// ---------------------------------------------------------------------------------------------

internal object UpnpPage : TendaPage {
    override val info = SettingsPageInfo("upnp", "UPnP", "Network", "Let devices open ports by themselves")

    const val ENABLE = "enable"

    // The router answers with a one-element array.
    private suspend fun enabled(api: TendaApi): Boolean =
        api.read("GetUpnpCfg").asList("GetUpnpCfg").firstOrNull().asObject("GetUpnpCfg").isOn("upnpEn")

    override suspend fun read(api: TendaApi): SettingsPage = SettingsPage(
        info = info,
        fields = listOf(
            SettingField.Toggle(ENABLE, "UPnP", enabled(api), summary = "Games and apps can forward ports without you doing it; switch off if you don't need that"),
        ),
    )

    override suspend fun apply(api: TendaApi, changes: Map<String, String>, context: TendaContext) {
        val enable = changes.flag(ENABLE) ?: return
        api.write("SetUpnpCfg", Form.of("upnpEn" to enable.bit()))
    }
}

// ---------------------------------------------------------------------------------------------
// Firewall: GetFirewallCfg / SetFirewallCfg
// ---------------------------------------------------------------------------------------------

internal object FirewallPage : TendaPage {
    override val info = SettingsPageInfo("firewall", "Firewall", "Network", "Flood protection and ping from the internet")

    const val ICMP = "icmp"
    const val TCP = "tcp"
    const val UDP = "udp"
    const val WAN_PING = "wanPing"

    private val ORDER = listOf(ICMP, TCP, UDP, WAN_PING)

    /** The router keeps the four switches as one string of digits, in the order of [ORDER]. */
    private suspend fun switches(api: TendaApi): List<Boolean> {
        val digits = api.read("GetFirewallCfg").asObject("GetFirewallCfg").str("firewallEn")
        return ORDER.indices.map { digits.getOrNull(it) == '1' }
    }

    override suspend fun read(api: TendaApi): SettingsPage {
        val on = switches(api)
        return SettingsPage(
            info = info,
            fields = listOf(
                SettingField.Toggle(ICMP, "ICMP flood attack defense", on[0]),
                SettingField.Toggle(TCP, "TCP flood attack defense", on[1]),
                SettingField.Toggle(UDP, "UDP flood attack defense", on[2]),
                SettingField.Toggle(WAN_PING, "Ignore ping from the internet", on[3], summary = "Makes the router invisible to ping on its internet side"),
            ),
        )
    }

    override suspend fun apply(api: TendaApi, changes: Map<String, String>, context: TendaContext) {
        if (ORDER.none { it in changes }) return
        val current = switches(api)
        val digits = ORDER.indices.joinToString("") { (changes.flag(ORDER[it]) ?: current[it]).bit() }
        api.write("SetFirewallCfg", "firewallEn=$digits")
    }
}

// ---------------------------------------------------------------------------------------------
// Dynamic DNS: GetDDNSCfg / SetDDNSCfg
// ---------------------------------------------------------------------------------------------

internal object DdnsPage : TendaPage {
    override val info = SettingsPageInfo("ddns", "Dynamic DNS", "Network", "A fixed name for a changing internet address")

    const val ENABLE = "enable"
    const val PROVIDER = "provider"
    const val USER = "user"
    const val PASSWORD = "password"
    const val DOMAIN = "domain"

    private val PROVIDERS = listOf(
        ChoiceOption("no-ip.com", "No-IP"),
        ChoiceOption("dyn.com/dns/", "DynDNS"),
        ChoiceOption("88ip.cn", "88IP"),
        ChoiceOption("oray.com", "Oray"),
    )

    override suspend fun read(api: TendaApi): SettingsPage {
        val g = api.read("GetDDNSCfg").asObject("GetDDNSCfg")
        return SettingsPage(
            info = info,
            fields = listOf(
                SettingField.Toggle(ENABLE, "Dynamic DNS", g.isOn("ddnsEn")),
                SettingField.Choice(PROVIDER, "Service provider", g.str("serverName").ifEmpty { PROVIDERS.first().value }, PROVIDERS),
                SettingField.Text(USER, "User name", g.str("ddnsUser")),
                SettingField.Secret(PASSWORD, "Password", summary = "Leave empty to keep the current password"),
                SettingField.Text(DOMAIN, "Domain name", g.str("ddnsDomain")),
                SettingField.Info("status", "Status", if (g.str("ddnsStatus") == "1") "Connected" else "Not connected"),
            ),
        )
    }

    override suspend fun apply(api: TendaApi, changes: Map<String, String>, context: TendaContext) {
        val g = api.read("GetDDNSCfg").asObject("GetDDNSCfg")
        val enable = changes.flag(ENABLE) ?: g.isOn("ddnsEn")
        val provider = changes[PROVIDER] ?: g.str("serverName")
        val user = changes[USER]?.trim() ?: g.str("ddnsUser")
        val password = changes[PASSWORD].orEmpty().ifEmpty { g.str("ddnsPwd") }
        val domain = changes[DOMAIN]?.trim() ?: g.str("ddnsDomain")

        if (enable) {
            Rules.oneOf(PROVIDER, provider, PROVIDERS.map { it.value }, "The service provider")
            if (user.isEmpty()) throw InvalidSettingException(USER, "Enter the user name of your account with the provider")
            if (password.isEmpty()) throw InvalidSettingException(PASSWORD, "Enter the password of your account with the provider")
            if (domain.isEmpty()) throw InvalidSettingException(DOMAIN, "Enter the domain name you registered")
            if (domain.any { it.isWhitespace() || it.isISOControl() }) throw InvalidSettingException(DOMAIN, "A domain name can't contain spaces")
        }
        // Switched off, the page sends back what the router already held.
        api.write(
            "SetDDNSCfg",
            if (enable) {
                Form.of("ddnsEn" to "1", "serverName" to provider, "ddnsUser" to user, "ddnsPwd" to password, "ddnsDomain" to domain)
            } else {
                Form.of("ddnsEn" to "0", "serverName" to g.str("serverName"), "ddnsUser" to g.str("ddnsUser"), "ddnsPwd" to g.str("ddnsPwd"), "ddnsDomain" to g.str("ddnsDomain"))
            },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Remote web management: GetRemoteWebCfg / SetRemoteWebCfg
// ---------------------------------------------------------------------------------------------

internal object RemoteWebPage : TendaPage {
    override val info = SettingsPageInfo("remote-web", "Remote management", "Network", "Open the router's web page from the internet")

    const val ENABLE = "enable"
    const val IP = "ip"
    const val PORT = "port"

    override suspend fun read(api: TendaApi): SettingsPage {
        val g = api.read("GetRemoteWebCfg").asObject("GetRemoteWebCfg")
        return SettingsPage(
            info = info,
            fields = listOf(
                SettingField.Toggle(ENABLE, "Remote management", g.isOn("remoteWebEn"),
                    summary = "Anyone who can reach this address and port can try to log in. Needs a login password"),
                SettingField.Text(IP, "Allowed address", g.str("remoteIp"), keyboard = SettingField.Keyboard.IP_ADDRESS,
                    summary = "0.0.0.0 allows every address"),
                SettingField.Number(PORT, "Port", g.num("remotePort") ?: 8888, 1, 65535),
            ),
        )
    }

    override suspend fun apply(api: TendaApi, changes: Map<String, String>, context: TendaContext) {
        val g = api.read("GetRemoteWebCfg").asObject("GetRemoteWebCfg")
        val enable = changes.flag(ENABLE) ?: g.isOn("remoteWebEn")
        val ip = changes[IP]?.trim() ?: g.str("remoteIp").ifEmpty { "0.0.0.0" }
        val port = changes[PORT]?.let { Rules.number(PORT, it, 1L..65535L, "The port") } ?: (g.num("remotePort") ?: 8888)

        if (enable) {
            // The router's own page refuses this too: an open admin page without a password is a gift to the internet.
            if (g.str("syspwdflag") == "0") {
                throw InvalidSettingException(ENABLE, "Set a login password first: remote management without one would let anyone in")
            }
            if (ip != "0.0.0.0") Rules.hostIp(IP, ip, "The allowed address")
        }
        api.write(
            "SetRemoteWebCfg",
            if (enable) {
                Form.of("remoteWebEn" to "1", "remoteIp" to ip, "remotePort" to port.toString())
            } else {
                Form.of("remoteWebEn" to "0", "remoteIp" to g.str("remoteIp"), "remotePort" to g.str("remotePort"))
            },
        )
    }
}
