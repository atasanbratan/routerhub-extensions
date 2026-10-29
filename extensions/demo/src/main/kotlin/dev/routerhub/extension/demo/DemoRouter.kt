package dev.routerhub.extension.demo

import dev.routerhub.api.ChoiceOption
import dev.routerhub.api.ColumnKind
import dev.routerhub.api.ConnectedClient
import dev.routerhub.api.ConnectionMedium
import dev.routerhub.api.DslLineState
import dev.routerhub.api.DslStats
import dev.routerhub.api.InvalidSettingException
import dev.routerhub.api.RouterStatus
import dev.routerhub.api.SettingField
import dev.routerhub.api.SettingsPage
import dev.routerhub.api.SettingsPageInfo
import dev.routerhub.api.TableCodec
import dev.routerhub.api.TableColumn
import dev.routerhub.api.WifiBand
import dev.routerhub.api.WifiRadio
import java.util.concurrent.ConcurrentHashMap

/**
 * The pretend router: its state and its settings pages. Every field type of the settings contract
 * shows up in at least one page, and every write is validated before it changes anything, the way
 * a real extension validates before it sends a request.
 *
 * All addresses come from the documentation ranges (192.0.2.0/24, 198.51.100.0/24, 203.0.113.0/24)
 * or are locally administered MACs, so nothing here can be mistaken for a real device.
 */
internal class DemoRouter {
    private var bootedAtMillis = System.currentTimeMillis()

    private val radios = linkedMapOf("wl0" to true, "wl1" to true)
    private var ssid = "Demo-Network"
    private var hidden = false
    private var security = "wpa2"

    private var dhcpEnabled = true
    private var dhcpStart = "192.168.1.100"
    private var dhcpEnd = "192.168.1.199"
    private var leaseHours = 24L

    private var forwards: List<List<String>> = listOf(
        listOf("Web server", "tcp", "8080", "192.168.1.20", "80"),
        listOf("Game server", "udp", "27015", "192.168.1.40", "27015"),
    )

    // --- live data ----------------------------------------------------------------------------

    @Synchronized
    fun status() = RouterStatus(
        model = "Demo DSL Router",
        firmwareVersion = "1.0.0-demo",
        // Starts at an uptime of 3 days 4 hours, so the screen does not read 0m on first use.
        uptimeSeconds = (System.currentTimeMillis() - bootedAtMillis) / 1000 + 3 * 86_400 + 4 * 3_600,
        wanIpv4 = "203.0.113.10",
        wanConnected = true,
        wifiRadios = radios.map { (id, enabled) ->
            WifiRadio(id, if (id == "wl0") WifiBand.GHZ_2_4 else WifiBand.GHZ_5, enabled, ssid)
        },
        extras = mapOf("Connection type" to "PPPoE (demo)", "Gateway" to "203.0.113.254"),
    )

    fun dslStats() = DslStats(
        lineState = DslLineState.SHOWTIME,
        downstreamKbps = 24_576,
        upstreamKbps = 1_024,
        attainableDownstreamKbps = 26_880,
        attainableUpstreamKbps = 1_280,
        snrMarginDownDb = 9.4,
        snrMarginUpDb = 11.2,
        attenuationDownDb = 18.5,
        attenuationUpDb = 9.8,
        modulation = "ADSL2+ (G.992.5)",
    )

    fun clients() = listOf(
        ConnectedClient("02:00:00:00:00:10", "192.168.1.10", "Living-room-TV", "eth1", ConnectionMedium.WIRED),
        ConnectedClient("02:00:00:00:00:11", "192.168.1.23", "Laptop", "wl1", ConnectionMedium.WIRELESS),
        ConnectedClient("02:00:00:00:00:12", "192.168.1.24", "Phone", "wl1", ConnectionMedium.WIRELESS),
        ConnectedClient("02:00:00:00:00:13", "192.168.1.31", "Printer", "wl0", ConnectionMedium.WIRELESS),
        ConnectedClient("02:00:00:00:00:14", "192.168.1.40", "Game-console", "eth2", ConnectionMedium.WIRED),
    )

    @Synchronized
    fun reboot() {
        bootedAtMillis = System.currentTimeMillis()
    }

    /** Idempotent, like the contract asks: null means every radio. */
    @Synchronized
    fun setWifiEnabled(radioId: String?, enabled: Boolean) {
        val targets = if (radioId == null) radios.keys.toList() else listOf(radioId)
        for (id in targets) {
            if (id !in radios) throw InvalidSettingException("radio", "Unknown radio: $id")
        }
        targets.forEach { radios[it] = enabled }
    }

    // --- settings pages -----------------------------------------------------------------------

    fun pages() = listOf(
        SettingsPageInfo("wifi", "Wi-Fi", "Wireless", "Name, visibility and password"),
        SettingsPageInfo("dhcp", "DHCP server", "Network", "Hands out addresses to the devices on your network"),
        SettingsPageInfo("port-forwarding", "Port forwarding", "Network", "Send incoming traffic to a device on your network"),
        SettingsPageInfo("log", "System log", "System", "Recent messages from the router"),
        SettingsPageInfo("password", "Router password", "System", "The login the app uses for this router"),
    )

    @Synchronized
    fun read(pageId: String): SettingsPage {
        val info = pages().firstOrNull { it.id == pageId }
            ?: throw InvalidSettingException("page", "Unknown settings page: $pageId")
        return when (pageId) {
            "wifi" -> SettingsPage(
                info = info,
                fields = listOf(
                    SettingField.Toggle("enable", "Wi-Fi", radios.values.any { it }),
                    SettingField.Text("ssid", "Network name", ssid, summary = "Shown to nearby devices", maxLength = 32),
                    SettingField.Toggle("hide", "Hide network name", hidden, summary = "Devices have to type the name to connect"),
                    SettingField.Choice(
                        "security", "Security", security,
                        options = listOf(
                            ChoiceOption("open", "Open (no password)"),
                            ChoiceOption("wpa2", "WPA2-PSK"),
                            ChoiceOption("wpa3", "WPA3-SAE"),
                        ),
                    ),
                    SettingField.Secret(
                        "password", "Password",
                        summary = "Leave empty to keep the current one",
                        minLength = 8, maxLength = 63, requiresConfirmation = true,
                    ),
                ),
                applyWarning = "Applying restarts the Wi-Fi radio. (In this demo nothing really restarts.)",
            )

            "dhcp" -> SettingsPage(
                info = info,
                fields = listOf(
                    SettingField.Info("lan", "Router address", "192.168.1.1 / 255.255.255.0"),
                    SettingField.Toggle("enable", "DHCP server", dhcpEnabled),
                    SettingField.Text("start", "First address", dhcpStart, keyboard = SettingField.Keyboard.IP_ADDRESS),
                    SettingField.Text("end", "Last address", dhcpEnd, keyboard = SettingField.Keyboard.IP_ADDRESS),
                    SettingField.Number("lease", "Lease time", leaseHours, min = 1, max = 160, unit = "hours"),
                ),
            )

            "port-forwarding" -> SettingsPage(
                info = info,
                fields = listOf(
                    SettingField.Table(
                        key = "rules",
                        title = "Rules",
                        summary = "Incoming traffic on the external port goes to the device on your network",
                        columns = listOf(
                            TableColumn("name", "Name", shortTitle = ""),
                            TableColumn(
                                "protocol", "Protocol", ColumnKind.CHOICE,
                                options = listOf(ChoiceOption("tcp", "TCP"), ChoiceOption("udp", "UDP"), ChoiceOption("both", "TCP + UDP")),
                                defaultValue = "tcp", shortTitle = "",
                            ),
                            TableColumn("external", "External port", ColumnKind.NUMBER, shortTitle = "external"),
                            TableColumn("ip", "Device address", ColumnKind.IP_ADDRESS, shortTitle = "to"),
                            TableColumn("internal", "Internal port", ColumnKind.NUMBER, shortTitle = "port"),
                        ),
                        rows = forwards,
                        maxRows = MAX_RULES,
                        itemName = "rule",
                        emptyText = "No rules",
                    ),
                ),
            )

            "log" -> SettingsPage(
                info = info,
                fields = listOf(
                    SettingField.Table(
                        key = "log",
                        title = "Messages",
                        columns = listOf(TableColumn("time", "Time", shortTitle = ""), TableColumn("message", "Message", shortTitle = "")),
                        rows = LOG,
                        itemName = "message",
                        readOnly = true,
                    ),
                ),
            )

            else -> SettingsPage(
                info = info,
                fields = listOf(
                    SettingField.Secret(
                        "password", "New password",
                        minLength = 6, required = true, requiresConfirmation = true,
                    ),
                ),
                applyWarning = "The app will use the new password from now on.",
                // The host updates the password it stores for this router once this field is saved.
                loginPasswordKey = "password",
            )
        }
    }

    @Synchronized
    fun write(pageId: String, changes: Map<String, String>) {
        when (pageId) {
            "wifi" -> writeWifi(changes)
            "dhcp" -> writeDhcp(changes)
            "port-forwarding" -> writeForwards(changes)
            "password" -> changes["password"]?.let {
                if (it.length < 6) throw InvalidSettingException("password", "Use at least 6 characters")
            }
            "log" -> throw InvalidSettingException("page", "The system log is read-only")
            else -> throw InvalidSettingException("page", "Unknown settings page: $pageId")
        }
    }

    // Each writer checks everything first and only then changes state, so a rejected save never
    // leaves the router half changed.

    private fun writeWifi(changes: Map<String, String>) {
        changes["ssid"]?.let {
            if (it.isEmpty() || it.length > 32) throw InvalidSettingException("ssid", "The network name must be 1 to 32 characters")
        }
        changes["security"]?.let {
            if (it == "open" && security != "open") {
                throw InvalidSettingException("security", "A protected network is never switched to open: that would expose it")
            }
        }
        changes["password"]?.let {
            if (it.length !in 8..63) throw InvalidSettingException("password", "Use 8 to 63 characters")
        }

        changes["enable"]?.let { value -> radios.keys.forEach { radios[it] = value == "true" } }
        changes["ssid"]?.let { ssid = it }
        changes["hide"]?.let { hidden = it == "true" }
        changes["security"]?.let { security = it }
        // The password is write-only: a real extension sends it to the router and keeps no copy.
    }

    private fun writeDhcp(changes: Map<String, String>) {
        val start = changes["start"] ?: dhcpStart
        val end = changes["end"] ?: dhcpEnd
        if (address("end", end) < address("start", start)) {
            throw InvalidSettingException("end", "The last address must come after the first")
        }
        val lease = changes["lease"]?.let { whole("lease", it, 1..160, "The lease time") }

        changes["enable"]?.let { dhcpEnabled = it == "true" }
        dhcpStart = start
        dhcpEnd = end
        lease?.let { leaseHours = it }
    }

    private fun writeForwards(changes: Map<String, String>) {
        // A table arrives as the COMPLETE wanted list; a real extension diffs it against the router.
        val rows = TableCodec.decode(changes["rules"] ?: return)
        if (rows.size > MAX_RULES) throw InvalidSettingException("rules", "The router holds at most $MAX_RULES rules")
        val taken = mutableSetOf<String>()
        for (row in rows) {
            if (row.size != 5) throw InvalidSettingException("rules", "A rule has five cells, got ${row.size}")
            val (name, protocol, external, ip, internal) = row
            if (name.isBlank() || name.length > 24) throw InvalidSettingException("rules", "A rule needs a name of up to 24 characters")
            if (protocol !in PROTOCOLS) throw InvalidSettingException("rules", "Unknown protocol \"$protocol\"")
            whole("rules", external, 1..65535, "The external port")
            whole("rules", internal, 1..65535, "The internal port")
            address("rules", ip)
            if (!taken.add("$protocol/$external")) {
                throw InvalidSettingException("rules", "Two rules use ${protocol.uppercase()} port $external")
            }
        }
        forwards = rows
    }

    private fun whole(key: String, value: String, range: IntRange, what: String): Long {
        val number = value.trim().toLongOrNull() ?: throw InvalidSettingException(key, "$what must be a whole number")
        if (number !in range) throw InvalidSettingException(key, "$what must be between ${range.first} and ${range.last}")
        return number
    }

    /** An IPv4 address as a number, so two of them can be compared. */
    private fun address(key: String, value: String): Long {
        val parts = value.trim().split(".")
        val octets = parts.mapNotNull { it.takeIf { part -> part.length in 1..3 && part.all(Char::isDigit) }?.toInt() }
        if (parts.size != 4 || octets.size != 4 || octets.any { it > 255 }) {
            throw InvalidSettingException(key, "\"$value\" is not an IPv4 address")
        }
        return octets.fold(0L) { acc, octet -> acc * 256 + octet }
    }

    companion object {
        private const val MAX_RULES = 16
        private val PROTOCOLS = setOf("tcp", "udp", "both")

        private val LOG = listOf(
            listOf("09:02:11", "WAN connection established"),
            listOf("09:02:14", "NTP: time synchronised"),
            listOf("09:15:40", "DHCP: lease granted to 192.168.1.23"),
            listOf("11:48:03", "Wi-Fi: 02:00:00:00:00:12 associated"),
            listOf("12:30:27", "Firewall: dropped inbound TCP 198.51.100.77 to port 23"),
        )

        private val routers = ConcurrentHashMap<String, DemoRouter>()

        /** One pretend router per host name, so state survives the app reconnecting. */
        fun forHost(host: String): DemoRouter = routers.getOrPut(host.trim().lowercase()) { DemoRouter() }
    }
}
