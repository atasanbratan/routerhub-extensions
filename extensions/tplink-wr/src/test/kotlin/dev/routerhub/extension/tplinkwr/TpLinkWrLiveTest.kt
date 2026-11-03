package dev.routerhub.extension.tplinkwr

import dev.routerhub.api.ConnectionConfig
import dev.routerhub.api.WrongCredentialsException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Reads a real router; changes nothing. Skipped unless ROUTERHUB_LIVE_HOST is set:
 *
 *     ROUTERHUB_LIVE_HOST=192.168.0.1 ROUTERHUB_LIVE_PASS=... ./gradlew :extensions:tplink-wr:testDebugUnitTest --tests '*TpLinkWrLiveTest*'
 *
 * Logging in ends a session open in a browser; the test logs out at the end. Prints no names or addresses.
 */
class TpLinkWrLiveTest {
    private val host: String? = System.getenv("ROUTERHUB_LIVE_HOST")
    private val user = System.getenv("ROUTERHUB_LIVE_USER") ?: "admin"
    private val pass = System.getenv("ROUTERHUB_LIVE_PASS").orEmpty()

    private fun firmware(password: String = pass) =
        TpLinkWrFactory(WrModel.WR940N).create(ConnectionConfig.Web(host!!, user, password))

    @Test
    fun `reads the status and the clients`() = runBlocking {
        assumeTrue("ROUTERHUB_LIVE_HOST not set", !host.isNullOrBlank())
        val firmware = firmware()
        try {
            firmware.login()
            val status = firmware.getStatus()
            assertNotNull(status.firmwareVersion)
            assertNotNull(status.uptimeSeconds)
            val clients = firmware.getConnectedClients()
            assertTrue(clients.all { it.macAddress.matches(Regex("([0-9a-f]{2}:){5}[0-9a-f]{2}")) })
            println("LIVE    ${status.model}, firmware ${status.firmwareVersion}, up ${status.uptimeSeconds} s, internet ${status.wanConnected}")
            println("LIVE    Wi-Fi radios ${status.wifiRadios.size}, on ${status.wifiRadios.count { it.enabled }}; clients ${clients.size}, by medium ${clients.groupingBy { it.medium }.eachCount()}")
        } finally {
            firmware.close()
        }
    }

    @Test
    fun `a wrong password is refused`() {
        assumeTrue("ROUTERHUB_LIVE_HOST not set", !host.isNullOrBlank())
        val firmware = firmware(password = "not-the-password-$pass")
        assertThrows(WrongCredentialsException::class.java) { runBlocking { firmware.login() } }
        runBlocking { firmware.close() }
    }
}
