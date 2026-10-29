package dev.routerhub.extension.tplink

import dev.routerhub.api.Capability
import dev.routerhub.api.ConnectedClient
import dev.routerhub.api.ConnectionConfig
import dev.routerhub.api.DslStats
import dev.routerhub.api.FirmwareParseException
import dev.routerhub.api.InvalidSettingException
import dev.routerhub.api.RouterException
import dev.routerhub.api.RouterFirmware
import dev.routerhub.api.RouterStatus
import dev.routerhub.api.RouterUnreachableException
import dev.routerhub.api.SettingsPage
import dev.routerhub.api.SettingsPageInfo
import dev.routerhub.api.WrongCredentialsException
import java.net.ConnectException

internal class TpLinkAdslFirmware(
    private val config: ConnectionConfig.Web,
    override val capabilities: Set<Capability>,
) : RouterFirmware {

    private val http = TpLinkHttpClient(config)

    /** Follows the router's admin password when it is changed through [writeSettings]. */
    @Volatile
    private var password = config.password

    override suspend fun login() {
        http.login()
    }

    override suspend fun getStatus(): RouterStatus =
        TpLinkStatusParser.parseStatus(
            infoHtml = http.fetchHtml(Pages.INFO),
            wirelessHtml = http.fetchHtml(Pages.WIRELESS),
        )

    override suspend fun getDslStats(): DslStats =
        TpLinkStatusParser.parseDsl(http.fetchHtml(Pages.DSL_STATS))

    override suspend fun getConnectedClients(): List<ConnectedClient> =
        TpLinkStatusParser.parseClients(
            arpHtml = http.fetchHtml(Pages.ARP),
            dhcpHtml = http.fetchHtml(Pages.DHCP_LEASES),
            stationsHtml = http.fetchHtml(Pages.WIRELESS_STATIONS),
        )

    override suspend fun reboot() {
        // The reboot CGI demands a one-time sessionKey (anti-CSRF) embedded in the reboot page.
        val sessionKey = TpLinkStatusParser.parseSessionKey(http.fetchHtml(Pages.REBOOT))
            ?: throw FirmwareParseException("No sessionKey on ${Pages.REBOOT}")
        try {
            http.fetchHtml("${Pages.REBOOT_ACTION}?sessionKey=$sessionKey")
        } catch (e: RouterUnreachableException) {
            // The router may restart before finishing its response. Only a failure to connect at
            // all is a real error.
            if (e.cause is ConnectException) throw e
        }
    }

    override suspend fun listSettingsPages(): List<SettingsPageInfo> = TpLinkSettings.specs.map { it.info }

    private val pages: PageClient = CheckedPageClient(
        object : PageClient {
            override suspend fun get(path: String): String = http.fetchHtml(path)
        },
    )

    override suspend fun readSettings(pageId: String): SettingsPage {
        val spec = spec(pageId)
        try {
            return spec.read(pages)
        } finally {
            restoreMainNetworkSelection(spec)
        }
    }

    override suspend fun writeSettings(pageId: String, changes: Map<String, String>) {
        val spec = spec(pageId)
        try {
            // Every request is built from the page as it is NOW: session keys change with every
            // load, and the values resent for untouched fields must be the router's current ones.
            spec.apply(pages, changes, SettingsContext(config.username, password))
        } finally {
            restoreMainNetworkSelection(spec)
        }

        if (spec === AdminPasswordPage) {
            val newPassword = changes.getValue(AdminPasswordPage.PASSWORD)
            password = newPassword
            http.updatePassword(newPassword)
            // The router may have dropped every session. Proving the new password works is the only
            // reliable confirmation that the change really went through.
            try {
                http.login()
            } catch (e: WrongCredentialsException) {
                throw FirmwareParseException("The router did not accept the password change", e)
            }
        }
    }

    override suspend fun close() {
        http.close()
    }

    private fun spec(pageId: String): SettingsPageSpec =
        TpLinkSettings.byId[pageId] ?: throw InvalidSettingException("page", "Unknown settings page: $pageId")

    /**
     * Opening the guest network page leaves the router's remembered selection on the guest, which
     * makes the router's own Wi-Fi security page show (and resend) the guest's values. Put it back
     * so the router is left as we found it. Best effort: a failure here must not mask the result.
     */
    private suspend fun restoreMainNetworkSelection(spec: SettingsPageSpec) {
        if (spec !== WirelessGuestPage) return
        try {
            http.fetchHtml(WirelessSecurityPage.SELECT_MAIN_NETWORK)
        } catch (_: RouterException) {
        }
    }

    private object Pages {
        const val INFO = "/info.html"
        const val WIRELESS = "/wlcfg.html"
        const val DSL_STATS = "/statsadsl.html"
        const val ARP = "/arpview.cmd"
        const val DHCP_LEASES = "/dhcpinfo.html"
        const val WIRELESS_STATIONS = "/wlstationlist.cmd"
        const val REBOOT = "/resetrouter.html"
        const val REBOOT_ACTION = "/rebootinfo.cgi"
    }
}
