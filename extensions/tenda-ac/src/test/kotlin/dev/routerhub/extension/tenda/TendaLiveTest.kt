package dev.routerhub.extension.tenda

import dev.routerhub.api.ConnectionConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Read-only smoke test against a real router. It never reboots, toggles Wi-Fi or changes settings.
 * Skipped unless the environment provides a target, e.g.:
 *
 *   ROUTERHUB_LIVE_HOST=192.168.0.1 ROUTERHUB_LIVE_USER=admin ROUTERHUB_LIVE_PASS=... \
 *     ./gradlew :extensions:tenda-ac:cleanTestDebugUnitTest :extensions:tenda-ac:testDebugUnitTest \
 *     --tests '*TendaLiveTest*' -i
 *
 * The router locks its login after 5 wrong passwords, so double-check the password. It also holds
 * one session per address: close any browser tab that is logged in from this machine first.
 */
class TendaLiveTest {

    private val host: String? = System.getenv("ROUTERHUB_LIVE_HOST")
    private val user = System.getenv("ROUTERHUB_LIVE_USER") ?: "admin"
    private val pass = System.getenv("ROUTERHUB_LIVE_PASS").orEmpty()

    @Test
    fun `reads the status, the clients and every settings page`() = runBlocking {
        assumeTrue("ROUTERHUB_LIVE_HOST not set", !host.isNullOrBlank())
        val firmware = TendaAcFactory(TendaModel.AC10_V6)
            .create(ConnectionConfig.Web(host = host!!, username = user, password = pass, timeoutMillis = 30_000))
        try {
            firmware.login()

            val status = firmware.getStatus()
            println("STATUS  model=${status.model} firmware=${status.firmwareVersion} uptime=${status.uptimeSeconds}s " +
                "wanConnected=${status.wanConnected} radios=${status.wifiRadios.map { "${it.id}:${it.enabled}" }}")
            println("EXTRAS  ${status.extras}")

            val clients = firmware.getConnectedClients()
            println("CLIENTS ${clients.size} connected: ${clients.map { "${it.medium}/${it.interfaceName}" }}")

            val pages = firmware.listSettingsPages()
            assertTrue("the router offers settings pages", pages.isNotEmpty())
            for (info in pages) {
                val page = firmware.readSettings(info.id)
                assertTrue("${info.id} has fields", page.fields.isNotEmpty())
                println("PAGE    ${info.id.padEnd(22)} ${page.fields.size} fields")
            }
        } finally {
            firmware.close()
        }
    }
}
