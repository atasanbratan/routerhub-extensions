package dev.routerhub.extension.demo

import dev.routerhub.api.Capability
import dev.routerhub.api.ConnectedClient
import dev.routerhub.api.ConnectionConfig
import dev.routerhub.api.DslStats
import dev.routerhub.api.InvalidConfigurationException
import dev.routerhub.api.Protocol
import dev.routerhub.api.RouterExtension
import dev.routerhub.api.RouterFirmware
import dev.routerhub.api.RouterFirmwareFactory
import dev.routerhub.api.RouterStatus
import dev.routerhub.api.SettingsPage
import dev.routerhub.api.SettingsPageInfo
import dev.routerhub.api.WrongCredentialsException
import kotlinx.coroutines.delay

/**
 * A router that does not exist. It never touches the network, so it works anywhere, and it keeps
 * its state in memory: change a setting and it stays changed until the app process dies.
 *
 * Two uses. Try RouterHub without owning a compatible router (add a router, pick "Demo router",
 * type any host and password), and start a new extension from it: copy this module, rename the
 * package, and replace [DemoRouter] with real requests.
 *
 * Entry point named in AndroidManifest.xml. Must keep a public no-arg constructor.
 */
class DemoExtension : RouterExtension {
    override val factories: List<RouterFirmwareFactory> = listOf(DemoFactory())
}

internal class DemoFactory : RouterFirmwareFactory {
    override val id = "demo.router"
    override val displayName = "Demo router"
    override val vendor = "RouterHub"
    override val protocol = Protocol.WEB
    override val capabilities = setOf(
        Capability.STATUS,
        Capability.DSL_STATS,
        Capability.CONNECTED_CLIENTS,
        Capability.REBOOT,
        Capability.WIFI_TOGGLE,
        Capability.SETTINGS,
    )

    override fun create(config: ConnectionConfig): RouterFirmware {
        val web = config as? ConnectionConfig.Web
            ?: throw InvalidConfigurationException("$displayName pretends to be a web UI, got ${config.protocol}")
        return DemoFirmware(DemoRouter.forHost(web.host), web.password, capabilities)
    }
}

internal class DemoFirmware(
    private val router: DemoRouter,
    private val password: String,
    override val capabilities: Set<Capability>,
) : RouterFirmware {

    override suspend fun login() {
        pause()
        // Handy for seeing the wrong-credentials flow of the app without a real router.
        if (password == WRONG_PASSWORD) throw WrongCredentialsException()
    }

    override suspend fun getStatus(): RouterStatus {
        pause()
        return router.status()
    }

    override suspend fun getDslStats(): DslStats {
        pause()
        return router.dslStats()
    }

    override suspend fun getConnectedClients(): List<ConnectedClient> {
        pause()
        return router.clients()
    }

    override suspend fun reboot() {
        pause(600)
        router.reboot()
    }

    override suspend fun setWifiEnabled(radioId: String?, enabled: Boolean) {
        pause()
        router.setWifiEnabled(radioId, enabled)
    }

    override suspend fun listSettingsPages(): List<SettingsPageInfo> = router.pages()

    override suspend fun readSettings(pageId: String): SettingsPage {
        pause()
        return router.read(pageId)
    }

    override suspend fun writeSettings(pageId: String, changes: Map<String, String>) {
        pause(400)
        router.write(pageId, changes)
    }

    override suspend fun close() = Unit

    /** A little latency, so progress indicators show up the way they would for a real router. */
    private suspend fun pause(millis: Long = 150) = delay(millis)

    companion object {
        /** Logging in with this password fails, to demonstrate the error handling. */
        const val WRONG_PASSWORD = "wrong"
    }
}
