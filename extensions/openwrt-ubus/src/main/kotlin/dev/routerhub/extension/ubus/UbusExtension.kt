package dev.routerhub.extension.ubus

import dev.routerhub.api.Capability
import dev.routerhub.api.ConnectionConfig
import dev.routerhub.api.InvalidConfigurationException
import dev.routerhub.api.Protocol
import dev.routerhub.api.RouterExtension
import dev.routerhub.api.RouterFirmware
import dev.routerhub.api.RouterFirmwareFactory

/** Entry point named in AndroidManifest.xml. Must keep a public no-arg constructor. */
class UbusExtension : RouterExtension {
    override val factories: List<RouterFirmwareFactory> = listOf(UbusFactory())
}

/**
 * OpenWrt with LuCI, through the same `/ubus` calls LuCI's own pages use, so it needs no
 * hardware-specific code and nothing installed on the router. docs/openwrt-ubus.md lists the tested versions.
 */
internal class UbusFactory : RouterFirmwareFactory {
    override val id = "openwrt.ubus"
    override val displayName = "OpenWrt (LuCI)"
    override val vendor = "OpenWrt"
    override val protocol = Protocol.WEB
    override val capabilities = setOf(
        Capability.STATUS,
        Capability.CONNECTED_CLIENTS,
        Capability.CLIENT_ACTIONS,
        Capability.REBOOT,
        Capability.WIFI_TOGGLE,
        Capability.SETTINGS,
    )

    override fun create(config: ConnectionConfig): RouterFirmware {
        val web = config as? ConnectionConfig.Web
            ?: throw InvalidConfigurationException("$displayName is managed over HTTP, got ${config.protocol}")
        return UbusFirmware(web, capabilities)
    }
}
