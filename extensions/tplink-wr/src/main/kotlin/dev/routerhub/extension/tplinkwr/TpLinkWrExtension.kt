package dev.routerhub.extension.tplinkwr

import dev.routerhub.api.Capability
import dev.routerhub.api.ClientAction
import dev.routerhub.api.ConnectedClient
import dev.routerhub.api.ConnectionConfig
import dev.routerhub.api.InvalidConfigurationException
import dev.routerhub.api.Protocol
import dev.routerhub.api.RouterException
import dev.routerhub.api.RouterExtension
import dev.routerhub.api.RouterFirmware
import dev.routerhub.api.RouterFirmwareFactory
import dev.routerhub.api.RouterStatus
import dev.routerhub.api.SettingsPage
import dev.routerhub.api.SettingsPageInfo

/** Entry point named in AndroidManifest.xml. Must keep a public no-arg constructor. */
class TpLinkWrExtension : RouterExtension {
    override val factories: List<RouterFirmwareFactory> = WrModel.entries.map(::TpLinkWrFactory)
}

/**
 * Models with TP-Link's classic web UI (`Server: Router Webserver`, pages under `/userRpm/`).
 * Verified on a TL-WR940N v4/v5 with firmware 3.17.1 Build 170207. Siblings such as the TL-WR840N and
 * TL-WR841N use the same pages; add them here once someone has tried one.
 */
internal enum class WrModel(val slug: String, val displayName: String) {
    WR940N("wr940n", "TL-WR940N"),
}

internal class TpLinkWrFactory(private val model: WrModel) : RouterFirmwareFactory {
    override val id = "tplink.wr.${model.slug}"
    override val displayName = model.displayName
    override val vendor = "TP-Link"
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
        return TpLinkWrFirmware(WrHttpClient(web), capabilities) { (it as? WrHttpClient)?.close() }
    }
}

internal class TpLinkWrFirmware(
    private val api: WrApi,
    override val capabilities: Set<Capability>,
    private val release: (WrApi) -> Unit = {},
) : RouterFirmware {
    override suspend fun login() = api.login()

    override suspend fun getStatus(): RouterStatus = WrPages.status(api.page("StatusRpm.htm"))

    override suspend fun getConnectedClients(): List<ConnectedClient> {
        val dhcp = api.page("AssignedIpAddrListRpm.htm")
        // Only tells wired from Wi-Fi apart; the list is still worth having without it.
        val stations = try {
            api.page("WlanStationRpm.htm")
        } catch (_: RouterException) {
            null
        }
        val clients = WrPages.clients(dhcp, stations)
        return try {
            WrClientActions.withBlocked(api, clients)
        } catch (_: RouterException) {
            // The block list only adds to the list; it isn't worth losing the clients over.
            clients
        }
    }

    override suspend fun clientActions(client: ConnectedClient): List<ClientAction> = WrClientActions.offered(api, client)

    override suspend fun runClientAction(client: ConnectedClient, actionId: String) = WrClientActions.run(api, client, actionId)

    override suspend fun listSettingsPages(): List<SettingsPageInfo> = WrSettings.pages.map { it.info }

    override suspend fun readSettings(pageId: String): SettingsPage = WrSettings.page(pageId).read(api)

    override suspend fun writeSettings(pageId: String, changes: Map<String, String>) = WrSettings.page(pageId).apply(api, changes)

    override suspend fun setWifiEnabled(radioId: String?, enabled: Boolean) = WifiPage.setEnabled(api, enabled)

    override suspend fun reboot() {
        api.page("SysRebootRpm.htm", "Reboot=Reboot")
    }

    override suspend fun close() {
        try {
            api.logout()
        } finally {
            release(api)
        }
    }
}
