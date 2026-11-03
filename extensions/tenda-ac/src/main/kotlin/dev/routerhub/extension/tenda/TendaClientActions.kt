package dev.routerhub.extension.tenda

import dev.routerhub.api.ClientAction
import dev.routerhub.api.ConnectedClient
import dev.routerhub.api.InvalidConfigurationException
import dev.routerhub.api.SettingField
import dev.routerhub.api.TableCodec

/**
 * Block and allow from the clients list, through [MacFilterPage], which does the checking and keeps
 * the device you are using off the list. Only offered while the filter blocks the devices it lists:
 * an allow-only list works the other way round, and adding a device to it would let it in instead.
 */
internal object TendaClientActions {
    const val BLOCK = "block"
    const val ALLOW = "allow"
    private const val BLOCK_MODE = "black"
    private const val NAME_LENGTH = 20

    /** The blocked devices as name and MAC, or `null` when the filter isn't in block mode. */
    suspend fun blocked(api: TendaApi): List<List<String>>? {
        val page = MacFilterPage.read(api)
        val mode = page.fields.firstOrNull { it.key == MacFilterPage.MODE } as? SettingField.Choice
        if (mode?.value != BLOCK_MODE) return null
        return (page.fields.firstOrNull { it.key == MacFilterPage.DEVICES } as? SettingField.Table)?.rows.orEmpty()
    }

    suspend fun offered(api: TendaApi, client: ConnectedClient): List<ClientAction> {
        val blocked = blocked(api) ?: return emptyList()
        return if (blocked.any { it[1] == client.macAddress.lowercase() }) {
            listOf(ClientAction(ALLOW, "Allow on this router", "Removes it from the router's MAC filter"))
        } else {
            listOf(
                ClientAction(
                    BLOCK,
                    "Block on this router",
                    "Adds it to the router's MAC filter",
                    "The router's MAC filter will refuse this device, so it loses this network, not just the internet. " +
                        "A device that changes its MAC address gets around this.",
                ),
            )
        }
    }

    suspend fun run(api: TendaApi, client: ConnectedClient, actionId: String, context: TendaContext) {
        val mac = client.macAddress.lowercase()
        val blocked = blocked(api) ?: throw InvalidConfigurationException("The router's MAC filter is set to allow only its list")
        val rows = when (actionId) {
            BLOCK -> if (blocked.any { it[1] == mac }) blocked else blocked + listOf(listOf(name(client), mac))
            ALLOW -> blocked.filter { it[1] != mac }
            else -> throw InvalidConfigurationException("Unknown action: $actionId")
        }
        MacFilterPage.apply(api, mapOf(MacFilterPage.DEVICES to TableCodec.encode(rows)), context)
    }

    /** [clients] marked as blocked, plus blocked devices that are away, so they can be allowed again. */
    suspend fun withBlocked(api: TendaApi, clients: List<ConnectedClient>): List<ConnectedClient> {
        val blocked = blocked(api) ?: return clients
        val macs = blocked.map { it[1] }.toSet()
        val present = clients.map { it.macAddress }.toSet()
        val away = blocked.filter { it[1] !in present }.map { (name, mac) ->
            ConnectedClient(macAddress = mac, hostname = name.ifEmpty { null }, internetBlocked = true, online = false)
        }
        return away + clients.map { if (it.macAddress in macs) it.copy(internetBlocked = true) else it }
    }

    /** The router keeps names of up to 20 characters. */
    private fun name(client: ConnectedClient) = client.hostname.orEmpty().filterNot { it.isISOControl() }.take(NAME_LENGTH).trim()
}
