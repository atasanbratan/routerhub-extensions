package dev.routerhub.extension.ubus

import dev.routerhub.api.Capability
import dev.routerhub.api.ClientAction
import dev.routerhub.api.ConnectedClient
import dev.routerhub.api.ConnectionConfig
import dev.routerhub.api.FirmwareParseException
import dev.routerhub.api.InvalidConfigurationException
import dev.routerhub.api.InvalidSettingException
import dev.routerhub.api.RouterFirmware
import dev.routerhub.api.RouterStatus
import dev.routerhub.api.RouterUnreachableException
import dev.routerhub.api.SettingsPage
import dev.routerhub.api.SettingsPageInfo
import dev.routerhub.api.WrongCredentialsException
import java.net.ConnectException

internal class UbusFirmware(
    config: ConnectionConfig.Web,
    override val capabilities: Set<Capability>,
    private val http: UbusClient = UbusClient(config),
    private val router: UbusRouter = UbusRouter(http),
) : RouterFirmware {

    private val username = config.username.ifBlank { "root" }

    /** Follows the router's login password when it is changed through [writeSettings]. */
    @Volatile
    private var password = config.password

    override suspend fun login() {
        http.login()
    }

    /** Whether this user's ACLs cover a call. */
    suspend fun mayCall(target: String, method: String): Boolean =
        http.call("session", "access", mapOf("scope" to "ubus", "object" to target, "function" to method))["access"] == true

    override suspend fun getStatus(): RouterStatus = UbusStatusReader.status(router)

    override suspend fun getConnectedClients(): List<ConnectedClient> = UbusStatusReader.clients(router)

    override suspend fun clientActions(client: ConnectedClient): List<ClientAction> = ClientActions.offered(router, client)

    override suspend fun runClientAction(client: ConnectedClient, actionId: String) =
        ClientActions.run(router, client, actionId, username)

    override suspend fun reboot() {
        try {
            http.call("system", "reboot")
        } catch (e: RouterUnreachableException) {
            // The router may restart before finishing its answer. Only a failure to connect at all is a real error.
            if (e.cause is ConnectException) throw e
        }
    }

    override suspend fun setWifiEnabled(radioId: String?, enabled: Boolean) {
        val radios = UbusStatusReader.radios(router).map { it.name }
        if (radioId != null && radioId !in radios) throw InvalidConfigurationException("Unknown radio: $radioId")
        val disabled = if (enabled) "0" else "1"
        router.uci.change("wireless", Apply.DIRECT) {
            (radioId?.let(::listOf) ?: radios).forEach { set(it, mapOf("disabled" to disabled)) }
        }
    }

    override suspend fun listSettingsPages(): List<SettingsPageInfo> = UbusSettings.pages(router).map { it.info }

    override suspend fun readSettings(pageId: String): SettingsPage = page(pageId).read(router)

    override suspend fun writeSettings(pageId: String, changes: Map<String, String>) {
        val page = page(pageId)
        page.apply(router, changes, username)
        if (page === LoginPasswordPage) {
            changes[LoginPasswordPage.PASSWORD]?.let { followNewPassword(it) }
        }
    }

    /** Logging in with the new password is the proof that the router really took it. */
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

    private suspend fun page(pageId: String): UbusPage =
        UbusSettings.pages(router).firstOrNull { it.info.id == pageId }
            ?: throw InvalidSettingException("page", "Unknown settings page: $pageId")
}
