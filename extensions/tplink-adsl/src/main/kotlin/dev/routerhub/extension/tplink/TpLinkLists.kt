package dev.routerhub.extension.tplink

import dev.routerhub.api.ChoiceOption
import dev.routerhub.api.ColumnKind
import dev.routerhub.api.FirmwareParseException
import dev.routerhub.api.InvalidSettingException
import dev.routerhub.api.SettingField
import dev.routerhub.api.SettingsPage
import dev.routerhub.api.SettingsPageInfo
import dev.routerhub.api.TableCodec
import dev.routerhub.api.TableColumn

// ---------------------------------------------------------------------------------------------
// Shared rules and helpers for the list pages
// ---------------------------------------------------------------------------------------------

internal object ListRules {
    private val MAC = Regex("""[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}""")

    /** `isValidMacAddress()` from util.js; returns the address in lower case. */
    fun mac(key: String, value: String): String {
        val v = value.trim()
        if (!MAC.matches(v) || v.all { it == '0' || it == ':' }) {
            throw InvalidSettingException(key, "\"$value\" is not a valid MAC address (use aa:bb:cc:dd:ee:ff)")
        }
        return v.lowercase()
    }

    fun port(key: String, value: String, what: String): Int = Rules.number(key, value, 1L..65535L, what).toInt()

    fun ipToLong(ip: String): Long = ip.split('.').fold(0L) { acc, part -> acc * 256 + part.toLong() }

    /** Options of a script-generated interface list such as `var intf = 'ipoe_eth0/eth0|ppp_x/ppp0';`. */
    fun interfaces(html: String, variable: String): List<ChoiceOption> =
        PageData.jsVar(html, variable).orEmpty().split('|').mapNotNull { entry ->
            val parts = entry.split('/')
            if (parts.size > 1 && parts[1].isNotEmpty()) ChoiceOption(parts[1], "${parts[1]} (${parts[0]})") else null
        }

    suspend fun freshKey(client: PageClient, path: String): String = PageData.sessionKey(client.get(path))
}

// ---------------------------------------------------------------------------------------------
// NAT: port forwarding (scvrtsrv.cmd / scvrtsrv.html)
// ---------------------------------------------------------------------------------------------

internal object PortForwardingPage : SettingsPageSpec {
    override val info = SettingsPageInfo("port-forwarding", "Port forwarding", "Network", "Send incoming traffic to a device on your network")
    override val path = "/scvrtsrv.cmd?action=view"
    private const val ADD_PATH = "/scvrtsrv.html"

    const val RULES = "rules"
    private const val MAX_RULES = 80
    private const val COLUMNS = 8
    private val PROTOCOLS = listOf(ChoiceOption("0", "TCP/UDP"), ChoiceOption("1", "TCP"), ChoiceOption("2", "UDP"))

    override suspend fun read(client: PageClient): SettingsPage =
        build(client.get(path), ListRules.interfaces(client.get(ADD_PATH), "interfaceInfo"))

    override fun parse(html: String): SettingsPage = build(html, emptyList())

    fun build(viewHtml: String, interfaces: List<ChoiceOption>): SettingsPage = SettingsPage(
        info = info,
        fields = listOf(
            SettingField.Table(
                key = RULES, title = "Forwarding rules",
                columns = columns(interfaces),
                rows = currentRows(viewHtml, interfaces).map { it.cells },
                summary = "Incoming connections on the external ports go to the server on your network",
                maxRows = MAX_RULES, itemName = "rule", emptyText = "No port forwarding rules",
            ),
        ),
        applyWarning = "Forwarded ports are reachable from the internet. Only forward to devices you keep updated.",
    )

    private fun columns(interfaces: List<ChoiceOption>) = listOf(
        TableColumn("name", "Name", hint = "e.g. Web server"),
        TableColumn("extStart", "External port from", ColumnKind.NUMBER, shortTitle = "Ext. from"),
        TableColumn("extEnd", "External port to", ColumnKind.NUMBER, shortTitle = "Ext. to"),
        TableColumn("proto", "Protocol", ColumnKind.CHOICE, PROTOCOLS, defaultValue = "1", shortTitle = ""),
        TableColumn("intStart", "Internal port from", ColumnKind.NUMBER, shortTitle = "Int. from"),
        TableColumn("intEnd", "Internal port to", ColumnKind.NUMBER, shortTitle = "Int. to"),
        TableColumn("ip", "Server address", ColumnKind.IP_ADDRESS, shortTitle = ""),
        TableColumn(
            "wan", "Interface", ColumnKind.CHOICE, interfaces,
            defaultValue = interfaces.firstOrNull()?.value.orEmpty(), shortTitle = "",
        ),
    )

    private fun currentRows(viewHtml: String, interfaces: List<ChoiceOption>): List<ListRow> =
        ListPages.checkboxRows(viewHtml).map { row ->
            if (row.cells.size != COLUMNS) throw FirmwareParseException("Unexpected layout of the port forwarding table (${row.cells.size} columns)")
            val cells = row.cells.toMutableList()
            cells[3] = when (cells[3].uppercase().replace(" ", "")) {
                "TCP/UDP", "TCPORUDP", "BOTH" -> "0"
                "TCP" -> "1"
                "UDP" -> "2"
                else -> cells[3]
            }
            cells[7] = interfaces.firstOrNull { it.value == cells[7] || it.label == cells[7] }?.value ?: cells[7]
            ListRow(cells, row.removeId)
        }

    private class Rule(val name: String, val proto: String, val extStart: Int, val extEnd: Int, val intStart: Int, val intEnd: Int, val ip: String, val wan: String)

    /** Everything the page itself checks before it sends an add request. */
    private fun validated(row: List<String>, interfaces: List<ChoiceOption>): Rule {
        val name = row[0]
        val extStart = row[1]
        val extEnd = row[2]
        val proto = row[3]
        val intStart = row[4]
        val intEnd = row[5]
        val ip = row[6]
        val wan = row[7]
        if (name.isBlank()) throw InvalidSettingException(RULES, "Give every rule a name")
        if (name.length > 30 || !Rules.isAscii(name) || name.contains(',') || name.contains('|')) {
            throw InvalidSettingException(RULES, "Rule names can have up to 30 plain characters, without commas or |")
        }
        if (PROTOCOLS.none { it.value == proto }) throw InvalidSettingException(RULES, "Choose TCP, UDP or TCP/UDP")
        if (interfaces.none { it.value == wan }) throw InvalidSettingException(RULES, "Choose a WAN interface")
        Rules.ipv4(RULES, ip.trim())

        val es = ListRules.port(RULES, extStart, "External port")
        val ee = ListRules.port(RULES, extEnd, "External port")
        val ins = ListRules.port(RULES, intStart, "Internal port")
        val ie = ListRules.port(RULES, intEnd, "Internal port")
        if (es > ee) throw InvalidSettingException(RULES, "External port range $es-$ee is backwards")
        if (ins > ie) throw InvalidSettingException(RULES, "Internal port range $ins-$ie is backwards")

        // The router's own page silently rewrites these to 8080 / 2323 because its management
        // services use them. Refuse instead, so the rule that is saved is the rule that was typed.
        if (es == 80 && ee == 80 && ins == 80 && ie == 80) {
            throw InvalidSettingException(RULES, "Port 80 is used by the router itself. Use external port 8080 with internal port 80")
        }
        if (es == 23 && ee == 23 && ins == 23 && ie == 23) {
            throw InvalidSettingException(RULES, "Port 23 is used by the router itself. Use external port 2323 with internal port 23")
        }
        return Rule(name.trim(), proto, es, ee, ins, ie, ip.trim(), wan)
    }

    override suspend fun apply(client: PageClient, changes: Map<String, String>, context: SettingsContext) {
        val wanted = TableCodec.decode(changes[RULES] ?: return)
        ListPages.requireShape(RULES, wanted, COLUMNS)

        val interfaces = ListRules.interfaces(client.get(ADD_PATH), "interfaceInfo")
        val viewHtml = client.get(path)
        val current = currentRows(viewHtml, interfaces)
        val change = ListPages.diff(current, wanted)

        // Validate everything first: a bad row must not leave the list half changed.
        val rules = change.toAdd.map { validated(it, interfaces) }
        if (current.size - change.removeIds.size + rules.size > MAX_RULES) {
            throw InvalidSettingException(RULES, "The router holds at most $MAX_RULES port forwarding rules")
        }

        if (change.removeIds.isNotEmpty()) {
            // removeClick(): every checked value followed by a comma.
            val list = change.removeIds.joinToString("") { "$it," }
            client.get("/scvrtsrv.cmd?action=remove&rmLst=$list&sessionKey=${PageData.sessionKey(viewHtml)}")
        }
        for (rule in rules) {
            val key = ListRules.freshKey(client, ADD_PATH)
            // btnApply(): one named server with one comma-terminated entry per port range.
            client.get(
                "/scvrtsrv.cmd?action=add&srvName=${FirmwareUrl.encode(rule.name)}&dstWanIf=${rule.wan}" +
                    "&srvAddr=${rule.ip}&proto=${rule.proto},&eStart=${rule.extStart},&eEnd=${rule.extEnd}," +
                    "&iStart=${rule.intStart},&iEnd=${rule.intEnd},&sessionKey=$key",
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Wireless: MAC filter (wlmacflt.cmd / wlmacflt.html)
// ---------------------------------------------------------------------------------------------

internal object WirelessMacFilterPage : SettingsPageSpec {
    override val info = SettingsPageInfo("wireless-mac-filter", "MAC filter", "Wireless", "Allow or block specific devices on Wi-Fi")
    override val path = "/wlmacflt.cmd?action=view"
    private const val ADD_PATH = "/wlmacflt.html"

    const val MODE = "mode"
    const val DEVICES = "devices"

    private val MODES = listOf(
        ChoiceOption("disabled", "Off"),
        ChoiceOption("allow", "Allow only the listed devices"),
        ChoiceOption("deny", "Block the listed devices"),
    )
    private val MODE_LITERAL = Regex("""if\s*\(\s*'(\w+)'\s*==\s*'disabled'\s*\)""")
    private val RADIO_OFF = Regex("""if\s*\(\s*'(\d)'\s*==\s*'0'\s*\)\s*\{\s*alert\('Cannot change MAC filter mode""")

    private class State(val html: String) {
        val mode = MODE_LITERAL.find(html)?.groupValues?.get(1) ?: throw FirmwareParseException("No MAC filter mode on the page")
        val radioOn = RADIO_OFF.find(html)?.groupValues?.get(1) != "0"
        val devices = ListPages.checkboxRows(html).map { ListRow(listOf(it.cells.firstOrNull().orEmpty().lowercase()), it.removeId) }
    }

    override fun parse(html: String): SettingsPage {
        val s = State(html)
        if (!s.radioOn) {
            return SettingsPage(info, listOf(SettingField.Info("note", "MAC filter", "Wi-Fi is off. Turn it on under Wi-Fi first.")))
        }
        return SettingsPage(
            info = info,
            fields = listOf(
                SettingField.Choice(MODE, "Mode", s.mode, MODES),
                SettingField.Table(
                    key = DEVICES, title = "Devices",
                    columns = listOf(TableColumn("mac", "MAC address", ColumnKind.MAC_ADDRESS)),
                    rows = s.devices.map { it.cells },
                    itemName = "device", emptyText = "No devices listed",
                ),
            ),
            applyWarning = "\"Allow only\" blocks every device that isn't on the list, including this phone if its Wi-Fi " +
                "address isn't listed. It takes effect for the Wi-Fi radio immediately.",
        )
    }

    override suspend fun apply(client: PageClient, changes: Map<String, String>, context: SettingsContext) {
        val s = State(client.get(path))
        if (!s.radioOn) throw InvalidSettingException(MODE, "Wi-Fi is off. Turn it on first")

        val mode = changes[MODE] ?: s.mode
        if (MODES.none { it.value == mode }) throw InvalidSettingException(MODE, "Unknown mode")

        val wanted = changes[DEVICES]?.let { TableCodec.decode(it) } ?: s.devices.map { it.cells }
        ListPages.requireShape(DEVICES, wanted, 1)
        val normalized = wanted.map { listOf(ListRules.mac(DEVICES, it.single())) }
        val change = ListPages.diff(s.devices, normalized)

        // "Allow only" with nobody listed would lock every wireless device out, this phone included.
        if (mode == "allow" && normalized.isEmpty()) {
            throw InvalidSettingException(MODE, "Add at least one device first: \"Allow only\" with an empty list blocks every device")
        }

        // Adds, then the mode, then removals: the allow list is never empty while it matters.
        for (row in change.toAdd) {
            val key = ListRules.freshKey(client, ADD_PATH)
            client.get("/wlmacflt.cmd?action=add&wlFltMacAddr=${row.single()}&wlSyncNvram=1&sessionKey=$key")
        }
        if (mode != s.mode) {
            client.get("/wlmacflt.cmd?action=save&wlFltMacMode=$mode&sessionKey=${ListRules.freshKey(client, path)}")
        }
        if (change.removeIds.isNotEmpty()) {
            // removeClick(): every checked value followed by a comma and a space.
            val list = change.removeIds.joinToString("") { "$it, " }
            client.get("/wlmacflt.cmd?action=remove&rmLst=$list&sessionKey=${ListRules.freshKey(client, path)}")
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Website filter (urlfilter.cmd / url_add.html)
// ---------------------------------------------------------------------------------------------

internal object UrlFilterPage : SettingsPageSpec {
    override val info = SettingsPageInfo("url-filter", "Website filter", "Network", "Block or allow websites for chosen devices")
    override val path = "/urlfilter.cmd?action=view"
    private const val ADD_URL_PATH = "/url_add.html"

    const val MODE = "mode"
    const val DEVICES = "devices"
    const val URLS = "urls"
    private const val MAX_DEVICES = 4
    private const val MAX_URLS = 100

    private val MODES = listOf(
        ChoiceOption("Disable", "Off"),
        ChoiceOption("Include", "Allow only the listed websites"),
        ChoiceOption("Exclude", "Block the listed websites"),
    )
    private val MODE_LITERAL = Regex("""if\s*\(\s*'(\w+)'\s*==\s*'Disable'\s*\)""")

    // The page does not encode this field, so only characters that need no encoding are accepted.
    private val URL_CHARS = Regex("""[A-Za-z0-9._~:/-]{1,128}""")

    private class State(html: String) {
        val mode = MODE_LITERAL.find(html)?.groupValues?.get(1) ?: throw FirmwareParseException("No filter mode on the page")
        val urls = ListPages.checkboxRows(html, "rml").map { ListRow(listOf(it.cells.firstOrNull().orEmpty()), it.removeId) }
        val devices = ListPages.checkboxRows(html, "rmlmac")
            .map { ListRow(listOf(it.cells.firstOrNull().orEmpty().lowercase()), it.removeId) }
    }

    override fun parse(html: String): SettingsPage {
        val s = State(html)
        return SettingsPage(
            info = info,
            fields = listOf(
                SettingField.Choice(MODE, "Mode", s.mode, MODES),
                SettingField.Table(
                    key = DEVICES, title = "Devices under control",
                    columns = listOf(TableColumn("mac", "MAC address", ColumnKind.MAC_ADDRESS)),
                    rows = s.devices.map { it.cells },
                    summary = "The filter only applies to these devices",
                    maxRows = MAX_DEVICES, itemName = "device", emptyText = "No devices: the filter affects nobody",
                ),
                SettingField.Table(
                    key = URLS, title = "Websites",
                    columns = listOf(TableColumn("address", "Address", hint = "e.g. example.com")),
                    rows = s.urls.map { it.cells },
                    maxRows = MAX_URLS, itemName = "website", emptyText = "No websites listed",
                ),
            ),
            applyWarning = "The filter only affects the devices listed under control, and only while a WAN service with NAT is active.",
        )
    }

    override suspend fun apply(client: PageClient, changes: Map<String, String>, context: SettingsContext) {
        val s = State(client.get(path))
        val mode = changes[MODE] ?: s.mode
        if (MODES.none { it.value == mode }) throw InvalidSettingException(MODE, "Unknown mode")

        val wantedDevices = (changes[DEVICES]?.let { TableCodec.decode(it) } ?: s.devices.map { it.cells })
        val wantedUrls = (changes[URLS]?.let { TableCodec.decode(it) } ?: s.urls.map { it.cells })
        ListPages.requireShape(DEVICES, wantedDevices, 1)
        ListPages.requireShape(URLS, wantedUrls, 1)

        val devices = wantedDevices.map { listOf(ListRules.mac(DEVICES, it.single())) }
        val urls = wantedUrls.map { listOf(it.single().trim()) }
        if (devices.size > MAX_DEVICES) throw InvalidSettingException(DEVICES, "The router can control up to $MAX_DEVICES devices")
        if (urls.size > MAX_URLS) throw InvalidSettingException(URLS, "The router holds up to $MAX_URLS websites")

        val deviceChange = ListPages.diff(s.devices, devices)
        val urlChange = ListPages.diff(s.urls, urls)
        urlChange.toAdd.forEach {
            if (!URL_CHARS.matches(it.single())) {
                throw InvalidSettingException(URLS, "\"${it.single()}\" can only use letters, digits and . - _ ~ : /, up to 128 characters")
            }
        }
        // Found on real hardware: the router refuses to ADD a website while the filter is off.
        if (urlChange.toAdd.isNotEmpty() && mode == "Disable") {
            throw InvalidSettingException(URLS, "Websites can only be added while the filter is on. Choose \"Allow only\" or \"Block\" as well")
        }
        // "Allow only" with no websites blocks the whole internet for the controlled devices.
        if (mode == "Include" && urls.isEmpty() && devices.isNotEmpty()) {
            throw InvalidSettingException(MODE, "Add at least one website first: \"Allow only\" with an empty list blocks everything")
        }

        for (row in deviceChange.toAdd) {
            client.get("/urlfilter.cmd?action=add_mac&macaddr=${row.single()}&sessionKey=${ListRules.freshKey(client, path)}")
        }

        // Websites can only be added while the filter is on. If it is off, "Block" goes on first:
        // with an empty or just-filled list it affects nothing but the entries being added.
        var effectiveMode = s.mode
        if (urlChange.toAdd.isNotEmpty() && effectiveMode == "Disable") {
            client.get("/urlfilter.cmd?action=save&listtype=Exclude&sessionKey=${ListRules.freshKey(client, path)}")
            effectiveMode = "Exclude"
        }
        for (row in urlChange.toAdd) {
            client.get("/urlfilter.cmd?action=set_url&TodUrlAdd=${row.single()}&port_num=80&sessionKey=${ListRules.freshKey(client, ADD_URL_PATH)}")
        }
        if (mode != effectiveMode) {
            client.get("/urlfilter.cmd?action=save&listtype=$mode&sessionKey=${ListRules.freshKey(client, path)}")
        }
        // removeClick() / btnRemove(): every checked value followed by a space.
        if (deviceChange.removeIds.isNotEmpty()) {
            val list = deviceChange.removeIds.joinToString("") { "$it " }
            client.get("/urlfilter.cmd?action=remove_mac&rmLst=$list&sessionKey=${ListRules.freshKey(client, path)}")
        }
        if (urlChange.removeIds.isNotEmpty()) {
            val list = urlChange.removeIds.joinToString("") { "$it " }
            client.get("/urlfilter.cmd?action=remove_url&rmLst=$list&sessionKey=${ListRules.freshKey(client, path)}")
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Static routes (rtroutecfg.cmd / routeadd.html)
// ---------------------------------------------------------------------------------------------

internal object StaticRoutesPage : SettingsPageSpec {
    override val info = SettingsPageInfo("static-routes", "Static routes", "Network", "Send traffic for a network through a chosen gateway")
    override val path = "/rtroutecfg.cmd?action=viewcfg"
    private const val ADD_PATH = "/routeadd.html"

    const val ROUTES = "routes"
    private const val MAX_ROUTES = 32
    private const val COLUMNS = 5
    private val NONE = ChoiceOption("", "None")

    /** `[ipVersion, id, 'dst/prefix', 'gateway', 'interface', metric, enabled]` */
    private val ENTRY = Regex("""\[\s*(\d+)\s*,\s*(\d+)\s*,\s*'([^']*)'\s*,\s*'([^']*)'\s*,\s*'([^']*)'\s*,\s*(-?\d+)\s*,\s*(\d+)\s*]""")

    override suspend fun read(client: PageClient): SettingsPage =
        build(client.get(path), interfaces(client.get(ADD_PATH)))

    override fun parse(html: String): SettingsPage = build(html, listOf(NONE, ChoiceOption("br0", "br0 (LAN)")))

    private fun interfaces(addHtml: String) =
        listOf(NONE) + ListRules.interfaces(addHtml, "intf") + ChoiceOption("br0", "br0 (LAN)")

    private fun entries(html: String): List<ListRow> {
        val start = html.indexOf("var entryList")
        if (start < 0) throw FirmwareParseException("The static route page has no entry list")
        // The page repeats the array in an "if undefined" fallback; only the first one is real.
        val end = html.indexOf("];", start).takeIf { it > 0 } ?: html.length
        return ENTRY.findAll(html.substring(start, end)).map { m ->
            val (version, id, destination, gateway, iface, metric, enabled) = m.destructured
            ListRow(
                listOf(destination, gateway, iface, if (metric.toInt() < 0) "" else metric, if (enabled == "1") "Enabled" else "Disabled"),
                "$version|$id",
            )
        }.toList()
    }

    fun build(html: String, interfaces: List<ChoiceOption>): SettingsPage = SettingsPage(
        info = info,
        fields = listOf(
            SettingField.Table(
                key = ROUTES, title = "Routes",
                columns = listOf(
                    TableColumn("dst", "Destination network", hint = "e.g. 192.168.2.0/24"),
                    TableColumn("gateway", "Gateway", ColumnKind.IP_ADDRESS),
                    TableColumn("interface", "Interface", ColumnKind.CHOICE, interfaces, optional = true),
                    TableColumn("metric", "Metric", ColumnKind.NUMBER, optional = true),
                    TableColumn("status", "Status", readOnly = true),
                ),
                rows = entries(html).map { it.cells },
                maxRows = MAX_ROUTES, itemName = "route", emptyText = "No static routes",
            ),
        ),
        applyWarning = "A wrong route can cut traffic to the internet or to parts of your network. Remove the route again to undo it.",
    )

    private class Route(val destination: String, val gateway: String, val iface: String, val metric: String)

    private fun validated(row: List<String>, interfaces: List<ChoiceOption>): Route {
        val (destination, gateway, iface, metric) = row
        val parts = destination.trim().split('/')
        if (parts.size != 2) throw InvalidSettingException(ROUTES, "The destination needs a prefix length, like 192.168.2.0/24")
        // isValidIpAddressForStRoute()
        val octets = parts[0].split('.').mapNotNull { it.takeIf { p -> p.matches(Regex("""\d{1,3}""")) }?.toInt() }
        val addressOk = parts[0] != "0.0.0.0" && parts[0] != "255.255.255.255" &&
            octets.size == 4 && parts[0].split('.').size == 4 && octets.all { it <= 255 } && octets[0] < 224 && octets[0] != 127
        if (!addressOk) throw InvalidSettingException(ROUTES, "\"${parts[0]}\" is not a valid destination address")
        val prefix = parts[1].toIntOrNull()
        if (prefix == null || prefix !in 1..32) throw InvalidSettingException(ROUTES, "The prefix length must be 1 to 32")

        if (interfaces.none { it.value == iface }) throw InvalidSettingException(ROUTES, "Choose an interface from the list")
        val gw = gateway.trim()
        if (gw.contains('/')) throw InvalidSettingException(ROUTES, "The gateway can't have a prefix length")
        // With an interface chosen the gateway is still required for IP-based interfaces; without one it is the only target.
        if (gw.isEmpty()) throw InvalidSettingException(ROUTES, "Enter the gateway address")
        Rules.ipv4(ROUTES, gw)

        val m = metric.trim()
        if (m.isNotEmpty()) Rules.number(ROUTES, m, 0L..255L, "The metric")
        return Route(destination.trim(), gw, iface, m)
    }

    override suspend fun apply(client: PageClient, changes: Map<String, String>, context: SettingsContext) {
        val wanted = TableCodec.decode(changes[ROUTES] ?: return)
        ListPages.requireShape(ROUTES, wanted, COLUMNS)

        val interfaces = interfaces(client.get(ADD_PATH))
        val viewHtml = client.get(path)
        val current = entries(viewHtml)
        val change = ListPages.diff(current, wanted)

        val routes = change.toAdd.map { validated(it, interfaces) }
        if (current.size - change.removeIds.size + routes.size > MAX_ROUTES) {
            throw InvalidSettingException(ROUTES, "The router holds at most $MAX_ROUTES static routes")
        }

        if (change.removeIds.isNotEmpty()) {
            // removeClick(): lst.join(', ')
            client.get("/rtroutecfg.cmd?action=remove&rmLst=${change.removeIds.joinToString(", ")}&sessionKey=${PageData.need(viewHtml, "sessionKey")}")
        }
        for (route in routes) {
            val key = ListRules.freshKey(client, ADD_PATH)
            val query = StringBuilder("/rtroutecfg.cmd?action=add&ipver=4&dstAddr=${route.destination}")
            if (route.iface.isNotEmpty()) query.append("&dstWanIf=${route.iface}")
            query.append("&dstGtwy=${route.gateway}")
            if (route.metric.isNotEmpty()) query.append("&metric=${route.metric}")
            client.get(query.append("&sessionKey=$key").toString())
        }
    }
}

// ---------------------------------------------------------------------------------------------
// LAN: DHCP reservations (lancfg2.html, dhcpdstaticlease.cmd / StaticIpAdd.html)
// ---------------------------------------------------------------------------------------------

internal object DhcpReservationsPage : SettingsPageSpec {
    override val info = SettingsPageInfo("dhcp-reservations", "DHCP reservations", "Network", "Always give a device the same address")
    override val path = "/lancfg2.html"
    private const val ADD_PATH = "/StaticIpAdd.html"

    const val RESERVATIONS = "reservations"
    private const val COLUMNS = 3

    /** The router keeps its reservations as `mac/ip/enabled` entries joined by `|`. */
    private fun entries(html: String): List<ListRow> =
        PageData.jsVar(html, "info").orEmpty().split('|').filter { it.isNotBlank() }.map { entry ->
            val parts = entry.split('/')
            if (parts.size < 3) throw FirmwareParseException("Unexpected DHCP reservation entry")
            ListRow(listOf(parts[0].lowercase(), parts[1], if (parts[2] == "1") "Enabled" else "Disabled"), removeId = parts[0])
        }

    private fun maxEntries(html: String) =
        Regex("""(?m)^\s*var\s+MAX_ENTRY\s*=\s*(\d+)""").find(html)?.groupValues?.get(1)?.toIntOrNull() ?: 32

    override fun parse(html: String): SettingsPage = SettingsPage(
        info = info,
        fields = listOf(
            SettingField.Table(
                key = RESERVATIONS, title = "Reserved addresses",
                columns = listOf(
                    TableColumn("mac", "MAC address", ColumnKind.MAC_ADDRESS),
                    TableColumn("ip", "IP address", ColumnKind.IP_ADDRESS),
                    TableColumn("status", "Status", readOnly = true),
                ),
                rows = entries(html).map { it.cells },
                summary = "The device with this MAC address always gets this IP address from the DHCP server",
                maxRows = maxEntries(html), itemName = "reservation", emptyText = "No reservations",
            ),
        ),
        applyWarning = "Two devices must never share an address. The router doesn't stop you from reserving one that another device already uses.",
    )

    override suspend fun apply(client: PageClient, changes: Map<String, String>, context: SettingsContext) {
        val wanted = TableCodec.decode(changes[RESERVATIONS] ?: return)
        ListPages.requireShape(RESERVATIONS, wanted, COLUMNS)

        val html = client.get(path)
        val current = entries(html)
        val change = ListPages.diff(current, wanted)

        val lanIp = ListRules.ipToLong(PageData.need(html, "lanIpAddr"))
        val mask = ListRules.ipToLong(PageData.need(html, "lanIpMask"))
        val routerIp = PageData.need(html, "lanIpAddr")
        val additions = change.toAdd.map { row ->
            val mac = ListRules.mac(RESERVATIONS, row[0])
            val ip = row[1].trim()
            Rules.ipv4(RESERVATIONS, ip)
            if (ip == routerIp) throw InvalidSettingException(RESERVATIONS, "$ip is the router's own address")
            if ((ListRules.ipToLong(ip) and mask) != (lanIp and mask)) {
                throw InvalidSettingException(RESERVATIONS, "$ip is not on the router's network")
            }
            mac to ip
        }

        // The final list as the router will hold it: nobody may appear twice, by MAC or by address.
        val finalList = wanted.map { it[0].trim().lowercase() to it[1].trim() }
        if (finalList.map { it.first }.toSet().size != finalList.size) {
            throw InvalidSettingException(RESERVATIONS, "A device can only be reserved once")
        }
        if (finalList.map { it.second }.toSet().size != finalList.size) {
            throw InvalidSettingException(RESERVATIONS, "An address can only be reserved for one device")
        }
        if (finalList.size > maxEntries(html)) {
            throw InvalidSettingException(RESERVATIONS, "The router holds at most ${maxEntries(html)} reservations")
        }

        // Removals first, so changing a device's address (remove + add of the same MAC) works.
        if (change.removeIds.isNotEmpty()) {
            // removeClick(): lst.join(', ')
            client.get("/dhcpdstaticlease.cmd?action=remove&rmLst=${change.removeIds.joinToString(", ")}&sessionKey=${PageData.need(html, "sessionKey")}")
        }
        for ((mac, ip) in additions) {
            val key = ListRules.freshKey(client, ADD_PATH)
            client.get("/dhcpdstaticlease.cmd?action=add&mac=$mac&static_ip=$ip&sessionKey=$key")
        }
    }
}

// ---------------------------------------------------------------------------------------------
// LAN: DHCP server (lancfg2.html -> lancfg2.cgi)
// ---------------------------------------------------------------------------------------------

/**
 * Only the DHCP server part of the LAN page is editable. The router's own address is shown but
 * never offered for change: it is the address this app talks to, and the page itself warns that
 * changing it forces a new login.
 */
internal object DhcpPage : SettingsPageSpec {
    override val info = SettingsPageInfo("dhcp", "DHCP server", "Network", "Hands out addresses to the devices on your network")
    override val path = "/lancfg2.html"

    const val MODE = "mode"
    const val START = "start"
    const val END = "end"
    const val LEASE = "lease"

    private val LAN2_ENABLED = Regex("""if\s*\(\s*'(\d)'\s*==\s*'1'\s*\)\s*\{\s*enblLan2\.checked\s*=\s*true""")

    private class State(html: String) {
        val sessionKey = PageData.need(html, "sessionKey")
        val lanIp = PageData.need(html, "lanIpAddr")
        val lanMask = PageData.need(html, "lanIpMask")
        val dhcp = PageData.need(html, "dhcpEnbl")
        val start = PageData.jsVar(html, "dhcpStart").orEmpty()
        val end = PageData.jsVar(html, "dhcpEnd").orEmpty()
        val lease = PageData.jsVar(html, "dhcpLease")?.toLongOrNull() ?: 24
        val firewall = PageData.jsVar(html, "enblLanFirewall") ?: "0"
        val igmpSnooping = PageData.jsVar(html, "enblIgmpSnp") ?: "0"
        val igmpMode = PageData.jsVar(html, "enblIgmpMode") ?: "0"
        val ipExtension = PageData.jsVar(html, "ipExt") == "1"
        val secondLan = LAN2_ENABLED.find(html)?.groupValues?.get(1) == "1"

        /** Server on/off only; relay mode and the second LAN interface have fields this page doesn't resend. */
        val editable get() = !ipExtension && !secondLan && (dhcp == "0" || dhcp == "1")
    }

    override fun parse(html: String): SettingsPage {
        val s = State(html)
        val lan = SettingField.Info("lan", "Router address", "${s.lanIp} / ${s.lanMask}", summary = "Not editable here: it is the address this app uses")
        if (!s.editable) {
            return SettingsPage(
                info,
                listOf(lan, SettingField.Info("note", "Editing", "This router runs DHCP relay, IP extension or a second LAN interface, which can only be changed on its own web page.")),
            )
        }
        return SettingsPage(
            info = info,
            fields = listOf(
                lan,
                SettingField.Choice(MODE, "DHCP server", s.dhcp, listOf(ChoiceOption("1", "On"), ChoiceOption("0", "Off"))),
                SettingField.Text(START, "First address", s.start, keyboard = SettingField.Keyboard.IP_ADDRESS),
                SettingField.Text(END, "Last address", s.end, keyboard = SettingField.Keyboard.IP_ADDRESS),
                SettingField.Number(LEASE, "Lease time", s.lease, 1, 48, unit = "h"),
            ),
            applyWarning = "Devices get their addresses from this server. Turning it off leaves devices without an address once their lease " +
                "ends, and a new range can overlap addresses already in use. Devices with fixed addresses are not affected.",
        )
    }

    override fun buildRequest(html: String, changes: Map<String, String>, context: SettingsContext): String {
        val s = State(html)
        if (!s.editable) throw InvalidSettingException(MODE, "These DHCP settings can't be edited from the app")

        val mode = changes[MODE] ?: s.dhcp
        if (mode != "0" && mode != "1") throw InvalidSettingException(MODE, "Unknown DHCP mode")

        val query = StringBuilder(
            "/lancfg2.cgi?ethIpAddress=${s.lanIp}&ethSubnetMask=${s.lanMask}&enblLanFirewall=${s.firewall}" +
                "&enblIgmpSnp=${s.igmpSnooping}&enblIgmpMode=${s.igmpMode}",
        )
        if (mode == "1") {
            val start = (changes[START] ?: s.start).trim()
            val end = (changes[END] ?: s.end).trim()
            Rules.ipv4(START, start)
            Rules.ipv4(END, end)

            val mask = ListRules.ipToLong(s.lanMask)
            val lan = ListRules.ipToLong(s.lanIp)
            fun sameSubnet(ip: String) = (ListRules.ipToLong(ip) and mask) == (lan and mask)
            if (!sameSubnet(start)) throw InvalidSettingException(START, "The first address must be on the router's network")
            if (!sameSubnet(end)) throw InvalidSettingException(END, "The last address must be on the router's network")
            if (ListRules.ipToLong(end) < ListRules.ipToLong(start)) throw InvalidSettingException(END, "The last address must not come before the first")
            if (start == s.lanIp) throw InvalidSettingException(START, "The range can't start at the router's own address")
            if (end == s.lanIp) throw InvalidSettingException(END, "The range can't end at the router's own address")

            val lease = changes[LEASE]?.let { Rules.number(LEASE, it, 1L..48L, "The lease time") } ?: s.lease
            query.append("&dhcpEthStart=$start&dhcpEthEnd=$end&dhcpLeasedTime=$lease&enblDhcpSrv=1")
        } else {
            query.append("&enblDhcpSrv=0")
        }
        return query.append("&enblLan2=0&sessionKey=${s.sessionKey}").toString()
    }
}
