package dev.routerhub.extension.tplinkwr

import dev.routerhub.api.ChoiceOption
import dev.routerhub.api.FirmwareParseException
import dev.routerhub.api.InvalidSettingException
import dev.routerhub.api.SettingField
import dev.routerhub.api.SettingsGroup
import dev.routerhub.api.SettingsPage
import dev.routerhub.api.SettingsPageInfo

internal const val WIFI_RESTART_WARNING =
    "Applying restarts Wi-Fi. Wireless devices disconnect for a few seconds, including this phone if it is connected over Wi-Fi."

/**
 * Wi-Fi spans two router pages: `WlanNetworkRpm.htm` (name, radio, broadcast, channel) and
 * `WlanSecurityRpm.htm` (security and password). Each saves its whole form, so a save sends back every
 * value the page holds with only the changed ones replaced, and a page nothing changed on isn't sent.
 */
internal object WifiPage : WrPage {
    const val ENABLED = "enabled"
    const val SSID = "ssid"
    const val HIDDEN = "hidden"
    const val CHANNEL = "channel"
    const val SECURITY = "security"
    const val PASSWORD = "password"

    private const val NETWORK_PAGE = "WlanNetworkRpm.htm"
    private const val SECURITY_PAGE = "WlanSecurityRpm.htm"
    const val AUTO_CHANNEL = "15"

    const val NONE = "none"
    const val WPA2 = "wpa2"
    const val WPA_WPA2 = "wpa-wpa2"
    const val OTHER = "other"

    override val info = SettingsPageInfo("wifi", "Wi-Fi", SettingsGroup.WIRELESS, "Network name, password and channel")

    /**
     * `wlanPara` of the network page, by position as its script reads it: 3 name, 5 region, 7 mode,
     * 8 radio on, 9 broadcast on, 10 channel, 11 channel width, 12 rate, 16 to 18 extra names, 19 to
     * 21 extra names on, 22 WDS on, 23 to 27 WDS bridge, 32 bridge authentication, 56 WDS address mode.
     */
    class Network(val para: List<String>, val channels: List<Int>) {
        val ssid get() = para.at(3)
        val enabled get() = para.at(8) == "1"
        val broadcast get() = para.at(9) == "1"
        val channel get() = para.at(10)
    }

    /**
     * `wlanPara` of the security page: 2 the security type (0 none, 1 WEP, 2 WPA enterprise, 3 WPA
     * personal), 3 three digits for the WEP, WPA and personal versions, 4 WEP key format, 6 to 8 RADIUS,
     * 9 the password, 10 the WEP key in use, 11 and 15 key renewal, 13 and 14 the enterprise and
     * personal ciphers, 22 how many networks it serves. `wlanList` holds four WEP keys and lengths.
     */
    class Security(val para: List<String>, val wepKeys: List<String>) {
        val type get() = para.at(2)
        private val versions get() = para.at(3).padEnd(3, '3')
        val wepVersion get() = versions[0].toString()
        val wpaVersion get() = versions[1].toString()
        val pskVersion get() = versions[2].toString()
        val password get() = para.at(9)

        val choice: String
            get() = when {
                type == "0" -> NONE
                type == "3" && pskVersion == "2" -> WPA2
                type == "3" && pskVersion == "3" -> WPA_WPA2
                else -> OTHER
            }
    }

    private fun List<String>.at(index: Int) = getOrNull(index).orEmpty()

    suspend fun network(api: WrApi): Network {
        val html = api.page(NETWORK_PAGE)
        val para = WrPages.arrays(html)["wlanPara"] ?: throw FirmwareParseException("The page has no wlanPara")
        return Network(para, Channels.allowed(html, para))
    }

    private suspend fun security(api: WrApi): Security {
        val arrays = WrPages.arrays(api.page(SECURITY_PAGE))
        return Security(arrays["wlanPara"] ?: throw FirmwareParseException("The page has no wlanPara"), arrays["wlanList"].orEmpty())
    }

    private fun securityOptions(current: String) = buildList {
        add(ChoiceOption(NONE, "None (open network)"))
        add(ChoiceOption(WPA2, "WPA2-PSK (AES)"))
        add(ChoiceOption(WPA_WPA2, "WPA/WPA2-PSK (AES)"))
        if (current == OTHER) add(ChoiceOption(OTHER, "Other (as set on the router)"))
    }

    override suspend fun read(api: WrApi): SettingsPage {
        val network = network(api)
        val security = security(api)
        val channels = (network.channels + listOfNotNull(network.channel.toIntOrNull()?.takeIf { network.channel != AUTO_CHANNEL })).distinct().sorted()
        return SettingsPage(
            info,
            listOf(
                SettingField.Toggle(ENABLED, "Wi-Fi", network.enabled, "Turning it off disconnects every wireless device"),
                SettingField.Text(SSID, "Network name (SSID)", network.ssid, maxLength = 32),
                SettingField.Toggle(HIDDEN, "Hide network name", !network.broadcast, "Devices must type the name to join"),
                SettingField.Choice(CHANNEL, "Channel", network.channel, listOf(ChoiceOption(AUTO_CHANNEL, "Auto")) + channels.map { ChoiceOption("$it", "$it") }),
                SettingField.Choice(SECURITY, "Security", security.choice, securityOptions(security.choice)),
                SettingField.Secret(PASSWORD, "Wi-Fi password", "Leave empty to keep the current password", minLength = 8, maxLength = 64, requiresConfirmation = true),
            ),
            applyWarning = WIFI_RESTART_WARNING,
        )
    }

    override suspend fun apply(api: WrApi, changes: Map<String, String>) {
        val network = network(api)
        val enabled = changes[ENABLED]?.let { it == "true" } ?: network.enabled
        val ssid = changes[SSID] ?: network.ssid
        val broadcast = changes[HIDDEN]?.let { it != "true" } ?: network.broadcast
        val channel = changes[CHANNEL]?.trim() ?: network.channel
        if (ssid != network.ssid) ssid(SSID, ssid)
        if (channel != network.channel && channel != AUTO_CHANNEL && channel.toIntOrNull() !in network.channels) {
            throw InvalidSettingException(CHANNEL, "Channel $channel isn't allowed in the router's region")
        }

        val security = security(api)
        val choice = changes[SECURITY] ?: security.choice
        val typed = changes[PASSWORD].orEmpty()
        if (choice !in listOf(NONE, WPA2, WPA_WPA2, OTHER) || (choice == OTHER && security.choice != OTHER)) {
            throw InvalidSettingException(SECURITY, "Unknown security")
        }
        // As on the other routers: never quietly turn a protected network into an open one.
        if (choice == NONE && security.choice != NONE) {
            throw InvalidSettingException(SECURITY, "Refusing to remove the Wi-Fi password: anyone nearby could join. Use the router's own page if you really want that")
        }
        if (typed.isNotEmpty()) {
            if (choice == NONE) throw InvalidSettingException(PASSWORD, "An open network has no password; choose WPA2 first")
            if (choice == OTHER) throw InvalidSettingException(SECURITY, "Choose WPA2 to set a password here")
            wpaPassphrase(PASSWORD, typed)
        }
        if (choice != security.choice && choice != NONE && typed.isEmpty() && security.password.isEmpty()) {
            throw InvalidSettingException(PASSWORD, "Enter a password (8 to 63 characters)")
        }

        // The password first: if the router refuses it, the network keeps its old name and channel.
        if (choice != security.choice || typed.isNotEmpty()) {
            WrSettings.check(PASSWORD, api.page(SECURITY_PAGE, securityQuery(security, choice, typed.ifEmpty { security.password })))
        }
        if (enabled != network.enabled || ssid != network.ssid || broadcast != network.broadcast || channel != network.channel) {
            WrSettings.check(SSID, api.page(NETWORK_PAGE, networkQuery(network, enabled, ssid, broadcast, channel)))
        }
    }

    /** The Wi-Fi switch of the router screen; the router has one radio. */
    suspend fun setEnabled(api: WrApi, enabled: Boolean) {
        val network = network(api)
        if (network.enabled == enabled) return
        WrSettings.check(ENABLED, api.page(NETWORK_PAGE, networkQuery(network, enabled, network.ssid, network.broadcast, network.channel)))
    }

    /** The network page's form, in its own order; unchecked boxes are left out as a browser does. */
    fun networkQuery(network: Network, enabled: Boolean, ssid: String, broadcast: Boolean, channel: String): String {
        val p = network.para
        val e = WrSettings::encode
        val mode = p.at(7)
        return buildList {
            add("ssid1=${e(ssid)}")
            for (n in 2..4) {
                add("ssid$n=${e(p.at(14 + n))}")
                if (p.at(17 + n) == "1") add("enssid$n=2")
            }
            add("region=${p.at(5)}")
            add("band=${if (mode in listOf("7", "8", "9")) 1 else 0}")
            add("mode=$mode")
            add("chanWidth=${p.at(11)}")
            add("channel=$channel")
            add("rate=${p.at(12)}")
            if (enabled) add("ap=1")
            if (broadcast) add("broadcast=2")
            if (p.at(22) == "1") add("wdsbrl=2")
            add("brlssid=${e(p.at(23))}")
            add("brlbssid=${e(p.at(24))}")
            add("addrType=${p.at(56).ifEmpty { "1" }}")
            add("keytype=${p.at(25).ifEmpty { "1" }}")
            add("wepindex=${p.at(27).ifEmpty { "1" }}")
            add("authtype=${p.at(32).ifEmpty { "1" }}")
            add("keytext=${e(p.at(26))}")
            add("Save=Save")
        }.joinToString("&")
    }

    /** The security page's form with every section filled in, as its script sends it. */
    fun securityQuery(security: Security, choice: String, password: String): String {
        val p = security.para
        val e = WrSettings::encode
        val (type, pskVersion, pskCipher) = when (choice) {
            NONE -> Triple("0", security.pskVersion, p.at(14))
            WPA2 -> Triple("3", "2", "3")
            WPA_WPA2 -> Triple("3", "3", "3")
            else -> Triple(security.type, security.pskVersion, p.at(14))
        }
        val keys = security.wepKeys
        return buildList {
            if ((p.at(22).toIntOrNull() ?: 0) > 1) add("vapIdx=${p.at(17).toIntOrNull()?.takeIf { it in 1..4 } ?: 1}")
            add("secType=$type")
            add("pskSecOpt=$pskVersion")
            add("pskCipher=$pskCipher")
            add("pskSecret=${e(password)}")
            add("interval=${p.at(11)}")
            add("wpaSecOpt=${security.wpaVersion}")
            add("wpaCipher=${p.at(13)}")
            add("radiusIp=${p.at(6)}")
            add("radiusPort=${p.at(7)}")
            add("radiusSecret=${e(p.at(8))}")
            add("intervalWpa=${p.at(15)}")
            add("wepSecOpt=${security.wepVersion}")
            add("keytype=${p.at(4)}")
            add("keynum=${p.at(10)}")
            for (n in 1..4) add("key$n=${e(keys.getOrNull(2 * n - 2).orEmpty())}")
            for (n in 1..4) add("length$n=${keys.getOrNull(2 * n - 1).orEmpty().ifEmpty { "0" }}")
            add("Save=Save")
        }.joinToString("&")
    }

    fun ssid(key: String, value: String) {
        if (value.isEmpty()) throw InvalidSettingException(key, "The network name can't be empty")
        if (value.toByteArray().size > 32) throw InvalidSettingException(key, "The network name can be at most 32 bytes (a non-ASCII character counts as 2 or 3)")
    }

    /** WPA pre-shared key: 8 to 63 printable ASCII characters, or exactly 64 hexadecimal digits. */
    fun wpaPassphrase(key: String, value: String) {
        val valid = when (value.length) {
            in 8..63 -> value.all { it.code in 0x20..0x7E }
            64 -> value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
            else -> false
        }
        if (!valid) throw InvalidSettingException(key, "Use 8 to 63 ASCII characters, or exactly 64 hexadecimal digits")
    }
}

/**
 * The channels the network page offers: `Region_chan_table` has, for each region, a label and one
 * bit mask per channel of `chan_index_map`, saying which modes may use it. The page lists those whose
 * mask has the current mode's bit and leaves out radar (DFS) channels.
 */
internal object Channels {
    private val TABLE = Regex("""var\s+Region_chan_table\s*=\s*new Array\(([\s\S]*?)\);""")
    private val MAP = Regex("""var\s+chan_index_map\s*=\s*new Array\(([\s\S]*?)\);""")
    private val TOKEN = Regex(""""(?:[^"\\]|\\.)*"|0x[0-9A-Fa-f]+|\d+""")
    private const val PER_REGION = 53
    private const val DFS = 15

    fun allowed(html: String, wlanPara: List<String>): List<Int> {
        val table = TABLE.find(html)?.groupValues?.get(1)?.let { body ->
            TOKEN.findAll(body.replace(Regex("""/\*[\s\S]*?\*/"""), "")).map { it.value }.toList()
        }
        val map = MAP.find(html)?.groupValues?.get(1)?.let { body -> Regex("""\d+""").findAll(body).map { it.value.toInt() }.toList() }
        val region = wlanPara.getOrNull(5)?.toIntOrNull()
        if (table == null || map == null || region == null || table.size < (region + 1) * PER_REGION) return FALLBACK
        val mask = mask(wlanPara.getOrNull(7), wlanPara.getOrNull(11)) ?: return FALLBACK
        return (1 until PER_REGION).mapNotNull { i ->
            val bits = table[region * PER_REGION + i].let { if (it.startsWith("0x")) it.drop(2).toLong(16) else it.toLongOrNull() } ?: 0L
            map.getOrNull(i - 1)?.takeIf { bits and mask != 0L && bits and (1L shl DFS) == 0L }
        }
    }

    /** The page's `updateChannelList`: 11b 2, 11g 3, 11n 2.4 GHz 20 MHz 8 and 40 MHz 13, 5 GHz 1, 7 and 14. */
    private fun mask(mode: String?, width: String?): Long? {
        fun ht(ht20: Int, ht40: Int) = when (width) {
            "1" -> 1L shl ht20
            "3" -> 1L shl ht40
            "2" -> (1L shl ht20) or (1L shl ht40)
            else -> null
        }
        return when (mode) {
            "1" -> 1L shl 2
            "2", "4" -> 1L shl 3
            "7" -> 1L shl 1
            "3", "5", "6" -> ht(8, 13)
            "8", "9" -> ht(7, 14)
            else -> null
        }
    }

    private val FALLBACK = (1..13).toList()
}

/**
 * `LanDhcpServerRpm.htm`: `DHCPPara` is 0 on, 1 and 2 the address pool, 3 the lease in minutes, 4
 * gateway, 5 domain, 6 and 7 DNS servers, 9 set when the router runs DHCP by itself ("smart DHCP")
 * and the page is locked.
 */
internal object DhcpServerPage : WrPage {
    const val ENABLED = "enabled"
    const val START = "start"
    const val END = "end"
    const val LEASE = "lease"
    const val GATEWAY = "gateway"
    const val DOMAIN = "domain"
    const val DNS1 = "dns1"
    const val DNS2 = "dns2"
    private const val PAGE = "LanDhcpServerRpm.htm"
    private const val NO_ADDRESS = "0.0.0.0"

    override val info = SettingsPageInfo("dhcp-server", "DHCP server", SettingsGroup.NETWORK, "Addresses the router hands out")

    private suspend fun current(api: WrApi): List<String> =
        WrPages.arrays(api.page(PAGE))["DHCPPara"] ?: throw FirmwareParseException("The page has no DHCPPara")

    private fun List<String>.at(index: Int) = getOrNull(index).orEmpty()

    private fun optional(value: String) = value.takeIf { it != NO_ADDRESS }.orEmpty()

    private fun locked(p: List<String>) = p.at(9).let { it.isNotEmpty() && it != "0" }

    override suspend fun read(api: WrApi): SettingsPage {
        val p = current(api)
        if (locked(p)) {
            return SettingsPage(
                info,
                listOf(
                    SettingField.Info("locked", "Managed by the router", "The router sets these itself in its current mode"),
                    SettingField.Info(START, "First address", p.at(1)),
                    SettingField.Info(END, "Last address", p.at(2)),
                ),
            )
        }
        return SettingsPage(
            info,
            listOf(
                SettingField.Toggle(ENABLED, "DHCP server", p.at(0) != "0", "Off means devices joining get no address unless set up by hand"),
                SettingField.Text(START, "First address", p.at(1), keyboard = SettingField.Keyboard.IP_ADDRESS),
                SettingField.Text(END, "Last address", p.at(2), keyboard = SettingField.Keyboard.IP_ADDRESS),
                SettingField.Number(LEASE, "Lease time", p.at(3).toLongOrNull() ?: 120, 1, 2880, "How long a device keeps its address before asking again", unit = "min"),
                SettingField.Text(GATEWAY, "Default gateway", optional(p.at(4)), "Empty uses the router", keyboard = SettingField.Keyboard.IP_ADDRESS),
                SettingField.Text(DOMAIN, "Domain", p.at(5), "Optional", maxLength = 30),
                SettingField.Text(DNS1, "Primary DNS server", optional(p.at(6)), "Empty uses the router", keyboard = SettingField.Keyboard.IP_ADDRESS),
                SettingField.Text(DNS2, "Secondary DNS server", optional(p.at(7)), "Optional", keyboard = SettingField.Keyboard.IP_ADDRESS),
            ),
        )
    }

    override suspend fun apply(api: WrApi, changes: Map<String, String>) {
        val p = current(api)
        if (locked(p)) throw InvalidSettingException(START, "The router manages its DHCP server itself in its current mode")
        val enabled = changes[ENABLED]?.let { it == "true" } ?: (p.at(0) != "0")
        val start = WrSettings.ip(START, changes[START] ?: p.at(1))
        val end = WrSettings.ip(END, changes[END] ?: p.at(2))
        if (start.split('.').take(3) != end.split('.').take(3)) throw InvalidSettingException(END, "The first and last address must be on the same network")
        if (start.substringAfterLast('.').toInt() > end.substringAfterLast('.').toInt()) {
            throw InvalidSettingException(END, "The last address must come after the first")
        }
        if (start.substringAfterLast('.').toInt() !in 1..254 || end.substringAfterLast('.').toInt() !in 1..254) {
            throw InvalidSettingException(START, "Addresses must end in 1 to 254")
        }
        val leaseText = changes[LEASE]?.trim() ?: p.at(3)
        val lease = leaseText.toLongOrNull()?.takeIf { it in 1..2880 } ?: throw InvalidSettingException(LEASE, "The lease time must be 1 to 2880 minutes")
        fun address(key: String, index: Int) =
            (changes[key]?.trim() ?: optional(p.at(index))).let { if (it.isEmpty() || it == NO_ADDRESS) NO_ADDRESS else WrSettings.ip(key, it) }
        val domain = (changes[DOMAIN] ?: p.at(5)).trim()
        if (domain.length > 30 || !domain.all { it.isLetterOrDigit() && it.code < 0x80 || it == '.' || it == '-' }) {
            throw InvalidSettingException(DOMAIN, "Use up to 30 letters, digits, dots and hyphens")
        }
        val query = "dhcpserver=${if (enabled) 1 else 0}&ip1=$start&ip2=$end&Lease=$lease&gateway=${address(GATEWAY, 4)}" +
            "&domain=${WrSettings.encode(domain)}&dnsserver=${address(DNS1, 6)}&dnsserver2=${address(DNS2, 7)}&Save=Save"
        WrSettings.check(START, api.page(PAGE, query))
    }
}
