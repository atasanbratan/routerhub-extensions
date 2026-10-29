package dev.routerhub.extension.tenda

import dev.routerhub.api.Capability
import dev.routerhub.api.ConnectedClient
import dev.routerhub.api.ConnectionConfig
import dev.routerhub.api.FirmwareParseException
import dev.routerhub.api.InvalidSettingException
import dev.routerhub.api.RouterFirmware
import dev.routerhub.api.RouterStatus
import dev.routerhub.api.RouterUnreachableException
import dev.routerhub.api.SettingsPage
import dev.routerhub.api.SettingsPageInfo
import dev.routerhub.api.WrongCredentialsException
import java.net.ConnectException

internal class TendaFirmware(
    private val config: ConnectionConfig.Web,
    override val capabilities: Set<Capability>,
) : RouterFirmware {

    private val http = TendaHttpClient(config)

    /** Follows the router's login password when it is changed through [writeSettings]. */
    @Volatile
    private var password = config.password

    override suspend fun login() {
        http.login()
    }

    override suspend fun getStatus(): RouterStatus =
        TendaStatus.parseStatus(system = http.read("GetSystemStatus"), router = http.read("GetRouterStatus"))

    override suspend fun getConnectedClients(): List<ConnectedClient> =
        TendaStatus.parseClients(http.read("getOnlineList"))

    override suspend fun reboot() {
        // The reboot is a plain form post, which the router answers with a redirect to its login page
        // when the session is gone: nothing would happen, and it would look like success. A read first
        // proves the session is alive.
        http.read("GetSysStatus")
        try {
            http.postPlain("SysToolReboot", "action=0")
        } catch (e: RouterUnreachableException) {
            // The router may restart before finishing its answer. Only a failure to connect at all is a real error.
            if (e.cause is ConnectException) throw e
        }
    }

    override suspend fun setWifiEnabled(radioId: String?, enabled: Boolean) {
        WifiPage.setEnabled(http, radioId, enabled)
    }

    override suspend fun listSettingsPages(): List<SettingsPageInfo> = TendaSettings.pages.map { it.info }

    override suspend fun readSettings(pageId: String): SettingsPage = page(pageId).read(http)

    override suspend fun writeSettings(pageId: String, changes: Map<String, String>) {
        val page = page(pageId)
        page.apply(http, changes, TendaContext(config.username, password))
        if (page === LoginPasswordPage) {
            changes[LoginPasswordPage.PASSWORD]?.let { followNewPassword(it) }
        }
    }

    /**
     * The router ends every session when its password changes. Logging in with the new one is the only
     * reliable proof that the change really went through: the form answers with a redirect either way.
     */
    private suspend fun followNewPassword(new: String) {
        val old = password
        http.updatePassword(new)
        try {
            http.login()
            password = new
        } catch (e: WrongCredentialsException) {
            http.updatePassword(old)
            throw FirmwareParseException("The router did not accept the password change", e)
        }
    }

    override suspend fun close() {
        http.close()
    }

    private fun page(pageId: String): TendaPage =
        TendaSettings.byId[pageId] ?: throw InvalidSettingException("page", "Unknown settings page: $pageId")
}
