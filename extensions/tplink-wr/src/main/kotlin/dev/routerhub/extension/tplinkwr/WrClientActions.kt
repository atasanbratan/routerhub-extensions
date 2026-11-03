package dev.routerhub.extension.tplinkwr

import dev.routerhub.api.ClientAction
import dev.routerhub.api.ConnectedClient
import dev.routerhub.api.InvalidConfigurationException

/**
 * Block and allow from the clients list, through the Wi-Fi MAC filter (`WlanMacFilterRpm.htm`).
 * `wlanFilterPara` is: filter on, rule (0 refuses the listed devices, 1 lets only them in), unused,
 * page, another page follows, entries on this page, values per entry; an entry is MAC, on or off,
 * type, key, description. Only offered while the rule refuses the listed devices: under the other
 * rule, adding a device would let it in, and switching the filter on would shut everyone else out.
 */
internal object WrClientActions {
    const val BLOCK = "block"
    const val ALLOW = "allow"
    private const val PAGE = "WlanMacFilterRpm.htm"
    private const val VAP = "&vapIdx=1"
    private const val NAME_LENGTH = 30
    private val list = WrList(PAGE, "wlanFilterPara", "wlanFilterList", nextIndex = 4, countIndex = 5, sizeIndex = 6, extraQuery = VAP)

    private class Filter(val on: Boolean, val refusesListed: Boolean, val entries: List<WrList.Entry>) {
        fun idOf(mac: String) = entries.firstOrNull { WrPages.mac(it.values[0]) == mac }?.id
    }

    private suspend fun filter(api: WrApi): Filter {
        val para = WrPages.arrays(api.page(PAGE, "Page=1$VAP"))["wlanFilterPara"].orEmpty()
        return Filter(para.getOrNull(0) == "1", para.getOrNull(1) == "0", list.read(api))
    }

    suspend fun offered(api: WrApi, client: ConnectedClient): List<ClientAction> {
        val filter = filter(api)
        if (!filter.refusesListed) return emptyList()
        return if (filter.on && filter.idOf(client.macAddress) != null) {
            listOf(ClientAction(ALLOW, "Allow on this router", "Removes it from the router's Wi-Fi MAC filter"))
        } else {
            listOf(
                ClientAction(
                    BLOCK,
                    "Block on this router",
                    "Adds it to the router's Wi-Fi MAC filter",
                    "The router will refuse this device on Wi-Fi, so it loses this network, not just the internet. If it is the " +
                        "phone you're using, you'll need another device to allow it again. A device that changes its MAC address gets around this.",
                ),
            )
        }
    }

    suspend fun run(api: WrApi, client: ConnectedClient, actionId: String) {
        val filter = filter(api)
        if (!filter.refusesListed) throw InvalidConfigurationException("The router's MAC filter lets only its list in")
        val mac = client.macAddress
        when (actionId) {
            BLOCK -> {
                if (filter.idOf(mac) == null) {
                    val name = WrSettings.encode(client.hostname.orEmpty().filterNot { it.isISOControl() }.take(NAME_LENGTH))
                    val query = "Mac=${WrSettings.routerMac(mac)}&Desc=$name&Type=1&key=&entryEnabled=1&Changed=0&SelIndex=0&Page=1$VAP&Save=Save"
                    WrSettings.check(BLOCK, api.page(PAGE, query))
                }
                if (!filter.on) api.page(PAGE, "Page=1&Enfilter=1$VAP")
            }
            ALLOW -> {
                filter.idOf(mac)?.let { list.delete(api, listOf(it)) }
                // With nobody left on the list, the filter goes back off, as it was before the first block.
                if (filter.entries.size <= 1 && filter.on) api.page(PAGE, "Page=1&Disfilter=1$VAP")
            }
            else -> throw InvalidConfigurationException("Unknown action: $actionId")
        }
    }

    /** [clients] marked as blocked while the filter refuses them, plus blocked devices that are away. */
    suspend fun withBlocked(api: WrApi, clients: List<ConnectedClient>): List<ConnectedClient> {
        val filter = filter(api)
        if (!filter.on || !filter.refusesListed) return clients
        val blocked = filter.entries.associate { WrPages.mac(it.values[0]) to it.values.getOrNull(4).orEmpty() }
        val present = clients.map { it.macAddress }.toSet()
        val away = blocked.filterKeys { it !in present }.map { (mac, name) ->
            ConnectedClient(macAddress = mac, hostname = name.ifEmpty { null }, internetBlocked = true, online = false)
        }
        return away + clients.map { if (it.macAddress in blocked) it.copy(internetBlocked = true) else it }
    }
}
