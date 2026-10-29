package dev.routerhub.extension.tplink

import dev.routerhub.api.Capability
import dev.routerhub.api.CommandFailedException
import dev.routerhub.api.ConnectionConfig
import dev.routerhub.api.DslStats
import dev.routerhub.api.InvalidConfigurationException
import dev.routerhub.api.RouterFirmware
import dev.routerhub.api.RouterStatus
import dev.routerhub.api.WifiRadio

/**
 * The same routers driven through their telnet CLI instead of the web UI.
 *
 * The CLI is restricted: no shell, and `cat` can't read /proc, so there is no uptime and no ARP
 * table (hence no CONNECTED_CLIENTS). In exchange it offers things the web UI only hides behind
 * forms, such as switching the radio with `wl up` / `wl down`.
 */
internal class TpLinkTelnetFirmware(
    config: ConnectionConfig.Telnet,
    private val model: TpLinkModel,
    override val capabilities: Set<Capability>,
) : RouterFirmware {

    private val cli = TelnetCli(config)

    override suspend fun login() {
        cli.connect()
    }

    override suspend fun getStatus(): RouterStatus {
        val route = TpLinkCliParser.parseDefaultRoute(cli.exec("route"))
        // The interface name comes from router output and goes back into a command line.
        val wanIp = route?.interfaceName?.takeIf { it.matches(INTERFACE_NAME) }
            ?.let { TpLinkCliParser.parseInetAddress(cli.exec("ifconfig $it")) }
        val radioUp = TpLinkCliParser.parseWlIsUp(cli.exec("wl isup"))

        return RouterStatus(
            model = model.displayName, // the CLI has no hardware-version command
            firmwareVersion = cli.exec("swversion").lineSequence().firstOrNull { it.isNotBlank() }?.trim(),
            uptimeSeconds = null, // /proc/uptime isn't readable from the restricted CLI
            wanIpv4 = wanIp,
            wanConnected = route != null,
            wifiRadios = radioUp?.let { up ->
                listOf(
                    WifiRadio(
                        id = RADIO_ID,
                        band = TpLinkCliParser.parseWlBand(cli.exec("wl status")),
                        enabled = up,
                        ssid = TpLinkCliParser.parseWlSsid(cli.exec("wl ssid")),
                    ),
                )
            }.orEmpty(),
            extras = route?.let { mapOf("WAN interface" to it.interfaceName, "Gateway" to it.gateway) }.orEmpty(),
        )
    }

    override suspend fun getDslStats(): DslStats =
        TpLinkCliParser.parseXdsl(cli.exec("xdslctl info --show"))

    override suspend fun reboot() {
        cli.sendAndHangUp("reboot")
    }

    /**
     * Brings the radio up or down immediately. This is a runtime switch: the saved configuration
     * is untouched, so the radio returns to its configured state after a reboot.
     */
    override suspend fun setWifiEnabled(radioId: String?, enabled: Boolean) {
        if (radioId != null && radioId != RADIO_ID) throw InvalidConfigurationException("Unknown radio: $radioId")
        val command = if (enabled) "wl up" else "wl down"
        val output = cli.exec(command)
        // `wl` prints nothing on success.
        if (FAILURE.containsMatchIn(output)) throw CommandFailedException(command, null, output)
    }

    override suspend fun close() {
        cli.close()
    }

    private companion object {
        const val RADIO_ID = "wl0"
        val INTERFACE_NAME = Regex("[A-Za-z0-9._-]+")
        val FAILURE = Regex("(?i)error|usage|unknown|cannot|not (found|permitted)")
    }
}
