package dev.routerhub.extension.openwrt

import dev.routerhub.api.Capability
import dev.routerhub.api.ConnectionConfig
import dev.routerhub.api.InvalidConfigurationException
import dev.routerhub.api.Protocol
import dev.routerhub.api.RouterExtension
import dev.routerhub.api.RouterFirmware
import dev.routerhub.api.RouterFirmwareFactory

/** Entry point named in AndroidManifest.xml. Must keep a public no-arg constructor. */
class OpenWrtExtension : RouterExtension {
    override val factories: List<RouterFirmwareFactory> = listOf(OpenWrtFactory())
}

internal class OpenWrtFactory : RouterFirmwareFactory {
    override val id = "openwrt.ssh"
    override val displayName = "OpenWrt"
    override val vendor = "OpenWrt"
    override val protocol = Protocol.SSH
    override val capabilities = setOf(
        Capability.STATUS,
        Capability.CONNECTED_CLIENTS,
        Capability.REBOOT,
        Capability.WIFI_TOGGLE,
    )

    override fun create(config: ConnectionConfig): RouterFirmware {
        val ssh = config as? ConnectionConfig.Ssh
            ?: throw InvalidConfigurationException("$displayName is managed over SSH, got ${config.protocol}")
        return OpenWrtFirmware(ssh, capabilities)
    }
}
