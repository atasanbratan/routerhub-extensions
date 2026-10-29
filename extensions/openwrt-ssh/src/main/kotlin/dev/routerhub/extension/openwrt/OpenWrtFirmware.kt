package dev.routerhub.extension.openwrt

import dev.routerhub.api.Capability
import dev.routerhub.api.ConnectedClient
import dev.routerhub.api.ConnectionConfig
import dev.routerhub.api.FirmwareParseException
import dev.routerhub.api.InvalidConfigurationException
import dev.routerhub.api.RouterFirmware
import dev.routerhub.api.RouterStatus
import dev.routerhub.extension.openwrt.OpenWrtParsers.script
import dev.routerhub.extension.openwrt.OpenWrtParsers.splitSections

internal class OpenWrtFirmware(
    config: ConnectionConfig.Ssh,
    override val capabilities: Set<Capability>,
) : RouterFirmware {

    private val ssh = SshCommandRunner(config)

    override suspend fun login() {
        ssh.connect()
        // Fail fast on a non-OpenWrt box instead of returning garbage later.
        if (ssh.exec("test -f /etc/openwrt_release").exitCode != 0) {
            throw FirmwareParseException("Not an OpenWrt system: /etc/openwrt_release is missing")
        }
    }

    override suspend fun getStatus(): RouterStatus {
        val sections = splitSections(ssh.exec(STATUS_SCRIPT).stdout)
        val release = OpenWrtParsers.parseKeyValues(sections["release"].orEmpty())
        val route = OpenWrtParsers.parseDefaultRoute(sections["route"].orEmpty())
        return RouterStatus(
            model = sections["model"]?.trim()?.ifEmpty { null },
            firmwareVersion = release["DISTRIB_DESCRIPTION"] ?: release["DISTRIB_RELEASE"],
            uptimeSeconds = OpenWrtParsers.parseUptimeSeconds(sections["uptime"].orEmpty()),
            wanIpv4 = route?.sourceAddress,
            wanConnected = route != null,
            wifiRadios = OpenWrtParsers.parseWifiRadios(sections["wireless"].orEmpty()),
            extras = buildMap {
                route?.let { put("WAN interface", it.device) }
                release["DISTRIB_TARGET"]?.let { put("Target", it) }
            },
        )
    }

    override suspend fun getConnectedClients(): List<ConnectedClient> {
        val sections = splitSections(ssh.exec(CLIENTS_SCRIPT).stdout)
        return OpenWrtParsers.parseClients(
            arp = sections["arp"].orEmpty(),
            leases = sections["leases"].orEmpty(),
        )
    }

    override suspend fun reboot() {
        // Detached and delayed, so the command exits 0 and the channel closes cleanly before the
        // router goes down. A bare `reboot` would kill the connection mid-command.
        ssh.exec("(sleep 2; reboot) >/dev/null 2>&1 &").requireSuccess()
    }

    override suspend fun setWifiEnabled(radioId: String?, enabled: Boolean) {
        val radios = radioId?.let { listOf(it) } ?: getStatus().wifiRadios.map { it.id }
        // Radio ids get interpolated into a shell command. Never trust them, even though they
        // normally come from our own getStatus().
        radios.firstOrNull { !it.matches(UCI_SECTION_NAME) }?.let {
            throw InvalidConfigurationException("Invalid radio id: $it")
        }
        if (radios.isEmpty()) return

        val disabled = if (enabled) 0 else 1
        val command = radios.joinToString(" && ") { "uci set wireless.$it.disabled=$disabled" } +
            " && uci commit wireless && wifi reload"
        ssh.exec(command).requireSuccess()
    }

    override suspend fun close() {
        ssh.disconnect()
    }

    private companion object {
        val UCI_SECTION_NAME = Regex("[A-Za-z0-9_]+")

        // One round-trip per call: several commands, separated by markers we split on locally.
        val STATUS_SCRIPT = script(
            "uptime" to "cat /proc/uptime",
            "release" to "cat /etc/openwrt_release",
            "model" to "cat /tmp/sysinfo/model",
            // The route the router itself would use to reach the internet: gives WAN device + IP.
            "route" to "ip -4 route get 1.1.1.1",
            "wireless" to "uci -q show wireless",
        )

        val CLIENTS_SCRIPT = script(
            "arp" to "cat /proc/net/arp",
            "leases" to "cat /tmp/dhcp.leases",
        )
    }
}
