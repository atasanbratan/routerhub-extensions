package dev.routerhub.extension.ubus

import dev.routerhub.api.ClientAction
import dev.routerhub.api.ConnectedClient
import dev.routerhub.api.InvalidConfigurationException
import dev.routerhub.api.RouterException
import dev.routerhub.api.TableCodec
import java.time.format.DateTimeFormatter

/** Shortcuts onto the Blocked devices and Address reservations pages, which do the validating and applying. */
internal object ClientActions {
    const val BLOCK = "block"
    const val ALLOW = "allow"
    const val RESERVE = "reserve"
    const val RELEASE = "release"
    const val PAUSE = "pause"
    const val RESUME = "resume"
    private const val PAUSE_MINUTES = 60L
    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

    private val DNS_NAME = Regex("[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?")

    suspend fun offered(router: UbusRouter, client: ConnectedClient): List<ClientAction> = buildList {
        val mac = client.macAddress.lowercase()
        // Without the router's clock, pausing is left out rather than every action with it.
        val pauses = try {
            Pauses.active(router, Pauses.routerNow(router))
        } catch (_: RouterException) {
            null
        }
        val pausedUntil = pauses?.get(mac)
        if (mac in BlockedDevicesPage.macs(router)) {
            add(ClientAction(ALLOW, "Allow internet", "Removes the block on this device"))
        } else if (pausedUntil != null) {
            add(ClientAction(RESUME, "Resume internet", "Paused until ${pausedUntil.format(CLOCK)}, router time"))
        } else {
            if (pauses != null) add(ClientAction(PAUSE, "Pause internet for 1 hour", "The router lifts the pause by itself"))
            add(
                ClientAction(
                    BLOCK,
                    "Block internet",
                    "It stays on your network",
                    "This device loses internet access but can still reach your network. Connections it already has open " +
                        "can keep working until they end, and a device that changes its MAC address gets around this.",
                ),
            )
        }
        val reserved = ReservationsPage.rows(router).firstOrNull { it[1] == mac }
        when {
            reserved != null -> add(ClientAction(RELEASE, "Remove address reservation", "Now always gets ${reserved[2]}"))
            client.ipAddress != null -> add(ClientAction(RESERVE, "Reserve this address", "Always give it ${client.ipAddress}"))
        }
    }

    suspend fun run(router: UbusRouter, client: ConnectedClient, actionId: String, username: String) {
        val mac = client.macAddress.lowercase()
        when (actionId) {
            BLOCK -> blocked(router, BlockedDevicesPage.rows(router) + listOf(listOf(client.hostname.orEmpty(), mac)), username)
            ALLOW -> blocked(router, BlockedDevicesPage.rows(router).filter { it[1] != mac }, username)
            RESERVE -> reserve(router, client, mac, username)
            RELEASE -> reservations(router, ReservationsPage.rows(router).filter { it[1] != mac }, username)
            PAUSE -> Pauses.pause(router, mac, Rules.label(PAUSE, client.hostname.orEmpty().filterNot { it.isISOControl() }.take(64), "The name"), PAUSE_MINUTES)
            RESUME -> Pauses.resume(router, mac)
            else -> throw InvalidConfigurationException("Unknown action: $actionId")
        }
    }

    private suspend fun reserve(router: UbusRouter, client: ConnectedClient, mac: String, username: String) {
        val ip = client.ipAddress ?: throw InvalidConfigurationException("This device has no IPv4 address to reserve")
        val rows = ReservationsPage.rows(router)
        if (rows.any { it[2] == ip }) throw InvalidConfigurationException("$ip is already reserved for another device")
        // dnsmasq refuses to start on a host name that isn't a valid DNS label.
        val name = client.hostname?.takeIf { DNS_NAME.matches(it) }.orEmpty()
        reservations(router, rows + listOf(listOf(name, mac, ip)), username)
    }

    private suspend fun blocked(router: UbusRouter, rows: List<List<String>>, username: String) =
        BlockedDevicesPage.apply(router, mapOf(BlockedDevicesPage.DEVICES to TableCodec.encode(rows)), username)

    private suspend fun reservations(router: UbusRouter, rows: List<List<String>>, username: String) =
        ReservationsPage.apply(router, mapOf(ReservationsPage.HOSTS to TableCodec.encode(rows)), username)
}
