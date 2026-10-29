package dev.routerhub.extension.tplink

import dev.routerhub.api.Capability
import dev.routerhub.api.ConnectionConfig
import dev.routerhub.api.InvalidConfigurationException
import dev.routerhub.api.Protocol
import dev.routerhub.api.RouterExtension
import dev.routerhub.api.RouterFirmware
import dev.routerhub.api.RouterFirmwareFactory

/** Entry point named in AndroidManifest.xml. Must keep a public no-arg constructor. */
class TpLinkAdslExtension : RouterExtension {
    // Each model is offered twice: over its web UI and over its telnet CLI.
    override val factories: List<RouterFirmwareFactory> =
        TpLinkModel.entries.flatMap { listOf(TpLinkAdslFactory(it), TpLinkTelnetFactory(it)) }
}

/**
 * Models sharing TP-Link's Broadcom-based web UI (`Server: micro_httpd`, cookie login,
 * `info.html` / `statsadsl.html` / `arpview.cmd` pages). Verified against a TD-W8960N V7 running
 * firmware 1.1.0 Build 171106. Other Broadcom-era models likely work with the same paths; add them
 * here only after checking on real hardware.
 */
internal enum class TpLinkModel(val slug: String, val displayName: String) {
    TD_W8960N("td-w8960n", "TD-W8960N"),
}

internal class TpLinkAdslFactory(private val model: TpLinkModel) : RouterFirmwareFactory {
    override val id = "tplink.adsl.${model.slug}"
    override val displayName = model.displayName
    override val vendor = "TP-Link"
    override val protocol = Protocol.WEB

    // Wi-Fi on/off lives in the SETTINGS pages (Wi-Fi > "Wi-Fi" toggle), not as a quick toggle.
    override val capabilities = setOf(
        Capability.STATUS,
        Capability.DSL_STATS,
        Capability.CONNECTED_CLIENTS,
        Capability.REBOOT,
        Capability.SETTINGS,
    )

    override fun create(config: ConnectionConfig): RouterFirmware {
        val web = config as? ConnectionConfig.Web
            ?: throw InvalidConfigurationException("$displayName is managed over HTTP, got ${config.protocol}")
        return TpLinkAdslFirmware(web, capabilities)
    }
}

internal class TpLinkTelnetFactory(private val model: TpLinkModel) : RouterFirmwareFactory {
    override val id = "tplink.adsl.${model.slug}.telnet"
    override val displayName = model.displayName
    override val vendor = "TP-Link"
    override val protocol = Protocol.TELNET
    override val capabilities = setOf(
        Capability.STATUS,
        Capability.DSL_STATS,
        Capability.REBOOT,
        Capability.WIFI_TOGGLE,
    )

    override fun create(config: ConnectionConfig): RouterFirmware {
        val telnet = config as? ConnectionConfig.Telnet
            ?: throw InvalidConfigurationException("$displayName (telnet) needs a TELNET configuration, got ${config.protocol}")
        return TpLinkTelnetFirmware(telnet, model, capabilities)
    }
}
