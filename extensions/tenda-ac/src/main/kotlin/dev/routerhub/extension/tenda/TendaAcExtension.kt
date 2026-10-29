package dev.routerhub.extension.tenda

import dev.routerhub.api.Capability
import dev.routerhub.api.ConnectionConfig
import dev.routerhub.api.InvalidConfigurationException
import dev.routerhub.api.Protocol
import dev.routerhub.api.RouterExtension
import dev.routerhub.api.RouterFirmware
import dev.routerhub.api.RouterFirmwareFactory

/** Entry point named in AndroidManifest.xml. Must keep a public no-arg constructor. */
class TendaAcExtension : RouterExtension {
    override val factories: List<RouterFirmwareFactory> = TendaModel.entries.map { TendaAcFactory(it) }
}

/**
 * Models sharing Tenda's AC-series web UI (`Server: Http Server`, `goform` calls scrambled with AES,
 * cookie login at `/login/Auth`). Verified against an AC10 V6.0 (AC1200) running firmware
 * V16.03.61.08. Other models with the same web UI (AC5, AC6, AC8, AC15...) are likely to work with
 * the same calls; add them here only after checking on real hardware.
 */
internal enum class TendaModel(val slug: String, val displayName: String) {
    AC10_V6("ac10v6", "AC10 (AC1200)"),
}

internal class TendaAcFactory(private val model: TendaModel) : RouterFirmwareFactory {
    override val id = "tenda.ac.${model.slug}"
    override val displayName = model.displayName
    override val vendor = "Tenda"
    override val protocol = Protocol.WEB
    override val capabilities = setOf(
        Capability.STATUS,
        Capability.CONNECTED_CLIENTS,
        Capability.REBOOT,
        Capability.WIFI_TOGGLE,
        Capability.SETTINGS,
    )

    override fun create(config: ConnectionConfig): RouterFirmware {
        val web = config as? ConnectionConfig.Web
            ?: throw InvalidConfigurationException("$displayName is managed over HTTP, got ${config.protocol}")
        return TendaFirmware(web, capabilities)
    }
}
