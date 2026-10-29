package dev.routerhub.extension.tplink

import dev.routerhub.api.ConnectionConfig
import dev.routerhub.api.RouterFirmware
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Read-only smoke tests against a real router, over both transports. They never reboot, toggle
 * Wi-Fi or change settings. Skipped unless the environment provides a target, e.g.:
 *
 *   ROUTERHUB_LIVE_HOST=192.168.1.1 ROUTERHUB_LIVE_USER=admin ROUTERHUB_LIVE_PASS=... \
 *     ./gradlew :extensions:tplink-adsl:testDebugUnitTest --tests '*TpLinkLiveTest*'
 *
 * The firmware locks its web UI after 5 failed logins, so double-check the password.
 */
class TpLinkLiveTest {

    private val host: String? = System.getenv("ROUTERHUB_LIVE_HOST")
    private val user = System.getenv("ROUTERHUB_LIVE_USER") ?: "admin"
    private val pass = System.getenv("ROUTERHUB_LIVE_PASS").orEmpty()

    @Test
    fun `reads status over the web UI`() = runBlocking {
        assumeTrue("ROUTERHUB_LIVE_HOST not set", !host.isNullOrBlank())
        val firmware = TpLinkAdslFactory(TpLinkModel.TD_W8960N)
            .create(ConnectionConfig.Web(host = host!!, username = user, password = pass))
        smokeTest("WEB", firmware, clients = true)
    }

    @Test
    fun `reads status over telnet`() = runBlocking {
        assumeTrue("ROUTERHUB_LIVE_HOST not set", !host.isNullOrBlank())
        val firmware = TpLinkTelnetFactory(TpLinkModel.TD_W8960N)
            .create(ConnectionConfig.Telnet(host = host!!, username = user, password = pass))
        smokeTest("TELNET", firmware, clients = false)
    }

    private suspend fun smokeTest(label: String, firmware: RouterFirmware, clients: Boolean) {
        try {
            firmware.login()
            println("$label STATUS  ${firmware.getStatus()}")
            println("$label DSL     ${firmware.getDslStats()}")
            if (clients) println("$label CLIENTS ${firmware.getConnectedClients().size} connected")
        } finally {
            firmware.close()
        }
    }
}
