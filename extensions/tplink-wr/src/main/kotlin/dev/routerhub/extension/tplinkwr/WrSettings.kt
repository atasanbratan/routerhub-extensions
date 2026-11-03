package dev.routerhub.extension.tplinkwr

import dev.routerhub.api.ChoiceOption
import dev.routerhub.api.ColumnKind
import dev.routerhub.api.InvalidSettingException
import dev.routerhub.api.SettingField
import dev.routerhub.api.SettingsGroup
import dev.routerhub.api.SettingsPage
import dev.routerhub.api.SettingsPageInfo
import dev.routerhub.api.TableCodec
import dev.routerhub.api.TableColumn
import java.net.URLEncoder

/** A settings page of the router: how to read it, and how to send the user's changes. */
internal interface WrPage {
    val info: SettingsPageInfo
    suspend fun read(api: WrApi): SettingsPage
    suspend fun apply(api: WrApi, changes: Map<String, String>)
}

internal object WrSettings {
    val pages: List<WrPage> = listOf(WifiPage, DhcpServerPage, ReservationsPage, PortForwardsPage, DmzPage, UpnpPage)

    fun page(id: String): WrPage = pages.firstOrNull { it.info.id == id } ?: throw InvalidSettingException("page", "Unknown page: $id")

    private val ERROR = Regex("""errCode\s*=\s*"?(\d+)""")

    /** The router answers a refused save with an error page carrying `errCode`; nothing was saved then. */
    fun check(key: String, answer: String) {
        ERROR.find(answer)?.let { throw InvalidSettingException(key, "The router refused this (error ${it.groupValues[1]})") }
    }

    fun mac(key: String, value: String): String {
        val mac = WrPages.mac(value)
        if (!Regex("([0-9a-f]{2}:){5}[0-9a-f]{2}").matches(mac)) throw InvalidSettingException(key, "$value isn't a MAC address")
        return mac
    }

    /** As the router's forms write it: "02-00-00-AA-BB-CC". */
    fun routerMac(mac: String) = mac.replace(':', '-').uppercase()

    fun ip(key: String, value: String): String {
        val parts = value.trim().split('.')
        if (parts.size != 4 || parts.any { part -> part.toIntOrNull()?.let { it in 0..255 } != true }) {
            throw InvalidSettingException(key, "$value isn't an IPv4 address")
        }
        return value.trim()
    }

    /** "80" or "8000-8010", within 1 to 65535. */
    fun ports(key: String, value: String, what: String): IntRange {
        val bounds = value.trim().split('-').map { it.trim().toIntOrNull() }
        val range = when {
            bounds.size == 1 && bounds[0] != null -> bounds[0]!!..bounds[0]!!
            bounds.size == 2 && bounds.all { it != null } -> bounds[0]!!..bounds[1]!!
            else -> throw InvalidSettingException(key, "$what must be a port like 80 or a range like 8000-8010")
        }
        if (range.first !in 1..65535 || range.last !in range.first..65535) {
            throw InvalidSettingException(key, "$what must be between 1 and 65535, the first port first")
        }
        return range
    }

    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

/**
 * A list page split into pages of eight: `<para>[pageIndex]` is the page shown, `[nextIndex]` says
 * whether another follows, `[countIndex]` how many entries this page holds and `[sizeIndex]` how many
 * values one entry has. Entries are numbered across pages from 0, which `Del=` takes.
 */
internal class WrList(
    private val page: String,
    private val para: String,
    private val list: String,
    private val nextIndex: Int,
    private val countIndex: Int,
    private val sizeIndex: Int,
    private val extraQuery: String = "",
) {
    class Entry(val id: Int, val values: List<String>)

    suspend fun read(api: WrApi): List<Entry> {
        val entries = mutableListOf<Entry>()
        for (number in 1..MAX_PAGES) {
            val arrays = WrPages.arrays(api.page(page, "Page=$number$extraQuery"))
            val info = arrays[para].orEmpty()
            val count = info.getOrNull(countIndex)?.toIntOrNull() ?: 0
            val size = info.getOrNull(sizeIndex)?.toIntOrNull()?.takeIf { it > 0 } ?: break
            arrays[list].orEmpty().chunked(size).take(count).forEach { entries += Entry(entries.size, it) }
            if (info.getOrNull(nextIndex) != "1") break
        }
        return entries
    }

    /** Highest number first, so the numbers of the others don't move under us. */
    suspend fun delete(api: WrApi, ids: Collection<Int>) {
        ids.sortedDescending().forEach { WrSettings.check(page, api.page(page, "Del=$it&Page=1$extraQuery")) }
    }

    private companion object {
        const val MAX_PAGES = 32
    }
}

/** Takes away from [current] what [wanted] still has; what is left of each is removed and added. */
internal fun <T> diff(current: List<Pair<T, List<String>>>, wanted: List<List<String>>): Pair<List<T>, List<List<String>>> {
    val remaining = current.toMutableList()
    val added = wanted.filter { row ->
        val match = remaining.indexOfFirst { it.second == row }
        if (match >= 0) remaining.removeAt(match)
        match < 0
    }
    return remaining.map { it.first } to added
}

/** `FixMapCfgRpm.htm`: each entry is MAC, address and on or off. */
internal object ReservationsPage : WrPage {
    const val RESERVATIONS = "reservations"
    private const val PAGE = "FixMapCfgRpm.htm"
    private val list = WrList(PAGE, "DHCPStaticPara", "dhcpList", nextIndex = 1, countIndex = 2, sizeIndex = 3)

    override val info = SettingsPageInfo("dhcp-reservations", "DHCP reservations", SettingsGroup.NETWORK, "Always give a device the same address")

    private val columns = listOf(
        TableColumn("mac", "MAC address", ColumnKind.MAC_ADDRESS, shortTitle = ""),
        TableColumn("ip", "IP address", ColumnKind.IP_ADDRESS, shortTitle = ""),
    )

    private fun row(entry: WrList.Entry) = listOf(WrPages.mac(entry.values[0]), entry.values[1])

    override suspend fun read(api: WrApi) = SettingsPage(
        info,
        listOf(SettingField.Table(RESERVATIONS, "Reserved addresses", columns, list.read(api).map(::row), itemName = "reservation", emptyText = "No reservations")),
    )

    override suspend fun apply(api: WrApi, changes: Map<String, String>) {
        val wanted = TableCodec.decode(changes[RESERVATIONS] ?: return).map { (mac, ip) ->
            listOf(WrSettings.mac(RESERVATIONS, mac), WrSettings.ip(RESERVATIONS, ip))
        }
        val (removed, added) = diff(list.read(api).map { it.id to row(it) }, wanted)
        list.delete(api, removed)
        added.forEach { (mac, ip) ->
            val query = "Mac=${WrSettings.routerMac(mac)}&Ip=$ip&State=1&Changed=0&SelIndex=0&Page=1&Save=Save"
            WrSettings.check(RESERVATIONS, api.page(PAGE, query))
        }
    }
}

/** `VirtualServerRpm.htm`: each entry is external first and last port, internal first and last, address, protocol, on or off. */
internal object PortForwardsPage : WrPage {
    const val FORWARDS = "forwards"
    private const val PAGE = "VirtualServerRpm.htm"
    private val list = WrList(PAGE, "virServerPara", "virServerListPara", nextIndex = 1, countIndex = 2, sizeIndex = 3)
    private val PROTOCOLS = listOf(ChoiceOption("1", "TCP and UDP"), ChoiceOption("2", "TCP"), ChoiceOption("3", "UDP"))

    override val info = SettingsPageInfo("port-forwarding", "Port forwarding", SettingsGroup.NETWORK, "Send incoming traffic to a device on your network")

    private val columns = listOf(
        TableColumn("external", "External port", hint = "80 or 8000-8010", shortTitle = "from"),
        TableColumn("ip", "Device address", ColumnKind.IP_ADDRESS, shortTitle = "to"),
        TableColumn("internal", "Device port", optional = true, hint = "Empty uses the external port", shortTitle = "port"),
        TableColumn("protocol", "Protocol", ColumnKind.CHOICE, PROTOCOLS, defaultValue = "1", shortTitle = ""),
    )

    private fun span(first: String, last: String) = if (first == last) first else "$first-$last"

    private fun row(entry: WrList.Entry): List<String> {
        val v = entry.values
        val external = span(v[0], v[1])
        val internal = span(v[2], v[3]).takeIf { it != external }.orEmpty()
        return listOf(external, v[4], internal, v[5])
    }

    override suspend fun read(api: WrApi) = SettingsPage(
        info,
        listOf(
            SettingField.Table(
                FORWARDS, "Forwarding rules", columns, list.read(api).map(::row),
                summary = "Forwarded ports are reachable from the internet. Only forward to devices you keep updated.",
                itemName = "rule", emptyText = "No port forwarding rules",
            ),
        ),
    )

    override suspend fun apply(api: WrApi, changes: Map<String, String>) {
        val wanted = TableCodec.decode(changes[FORWARDS] ?: return).map { (external, ip, internal, protocol) ->
            val outside = WrSettings.ports(FORWARDS, external, "The external port")
            val inside = internal.takeIf { it.isNotBlank() }?.let { WrSettings.ports(FORWARDS, it, "The device port") }
            if (inside != null && inside.count() != outside.count()) {
                throw InvalidSettingException(FORWARDS, "The device port must cover as many ports as the external one")
            }
            if (protocol !in PROTOCOLS.map { it.value }) throw InvalidSettingException(FORWARDS, "Unknown protocol")
            fun text(range: IntRange) = if (range.first == range.last) "${range.first}" else "${range.first}-${range.last}"
            listOf(text(outside), WrSettings.ip(FORWARDS, ip), inside?.let(::text)?.takeIf { it != text(outside) }.orEmpty(), protocol)
        }
        val (removed, added) = diff(list.read(api).map { it.id to row(it) }, wanted)
        list.delete(api, removed)
        added.forEach { (external, ip, internal, protocol) ->
            val query = "ExPort=$external&InPort=${internal.ifEmpty { external }}&Ip=$ip&Protocol=$protocol&State=1&Commonport=0&Changed=0&SelIndex=0&Page=1&Save=Save"
            WrSettings.check(FORWARDS, api.page(PAGE, query))
        }
    }
}

/** `DMZRpm.htm`: `DMZInf` is on or off, then the host's address. */
internal object DmzPage : WrPage {
    const val ENABLED = "enabled"
    const val HOST = "host"
    private const val PAGE = "DMZRpm.htm"

    override val info = SettingsPageInfo("dmz", "DMZ host", SettingsGroup.NETWORK, "Expose one device to the internet")

    private suspend fun current(api: WrApi): List<String> = WrPages.arrays(api.page(PAGE))["DMZInf"].orEmpty()

    override suspend fun read(api: WrApi): SettingsPage {
        val dmz = current(api)
        return SettingsPage(
            info,
            listOf(
                SettingField.Toggle(ENABLED, "DMZ host", dmz.getOrNull(0) == "1", "Every port of that device is reachable from the internet"),
                SettingField.Text(HOST, "DMZ host address", dmz.getOrNull(1).orEmpty().takeIf { it != "0.0.0.0" }.orEmpty(), keyboard = SettingField.Keyboard.IP_ADDRESS),
            ),
        )
    }

    override suspend fun apply(api: WrApi, changes: Map<String, String>) {
        val dmz = current(api)
        val enabled = changes[ENABLED]?.let { it == "true" } ?: (dmz.getOrNull(0) == "1")
        // The router keeps 0.0.0.0 for "no address".
        val host = (changes[HOST]?.trim() ?: dmz.getOrNull(1).orEmpty()).takeIf { it != "0.0.0.0" }.orEmpty()
        val address = when {
            host.isNotEmpty() -> WrSettings.ip(HOST, host)
            enabled -> throw InvalidSettingException(HOST, "Enter the address of the device to expose")
            else -> "0.0.0.0"
        }
        WrSettings.check(HOST, api.page(PAGE, "enable=${if (enabled) 1 else 0}&ipAddr=$address&Save=Save"))
    }
}

/** `UpnpCfgRpm.htm`: `uPnPPara[0]` is on or off; its two buttons send the new state. */
internal object UpnpPage : WrPage {
    const val ENABLED = "enabled"
    private const val PAGE = "UpnpCfgRpm.htm"

    override val info = SettingsPageInfo("upnp", "UPnP", SettingsGroup.NETWORK, "Let devices open ports by themselves")

    override suspend fun read(api: WrApi) = SettingsPage(
        info,
        listOf(
            SettingField.Toggle(
                ENABLED, "UPnP", WrPages.arrays(api.page(PAGE))["uPnPPara"]?.getOrNull(0) == "1",
                "Games and apps can forward ports without you doing it; switch off if you don't need that",
            ),
        ),
    )

    override suspend fun apply(api: WrApi, changes: Map<String, String>) {
        val enabled = changes[ENABLED]?.let { it == "true" } ?: return
        val query = if (enabled) "upnpenb=Enable&Upnpenable=Enable" else "upnpenb=Disable&Upnpdisable=Disable"
        WrSettings.check(ENABLED, api.page(PAGE, query))
    }
}
