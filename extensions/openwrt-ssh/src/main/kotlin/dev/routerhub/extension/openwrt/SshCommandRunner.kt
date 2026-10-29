package dev.routerhub.extension.openwrt

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import dev.routerhub.api.ConnectionConfig
import dev.routerhub.api.FirmwareTimeoutException
import dev.routerhub.api.RouterException
import dev.routerhub.api.RouterSessionExpiredException
import dev.routerhub.api.RouterUnreachableException
import dev.routerhub.api.SshAuth
import dev.routerhub.api.SshAuthenticationException
import dev.routerhub.api.SshCommandFailedException
import dev.routerhub.api.SshHostKeyMismatchException
import dev.routerhub.api.SshHostKeyUnknownException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Base64

internal class CommandResult(
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    fun requireSuccess(): CommandResult {
        if (exitCode != 0) throw SshCommandFailedException(command, exitCode, stderr)
        return this
    }
}

/**
 * Coroutine-friendly wrapper around one JSch session: connect once, then run many short commands
 * over separate `exec` channels.
 */
internal class SshCommandRunner(private val config: ConnectionConfig.Ssh) {

    private val jsch = JSch()

    @Volatile
    private var session: Session? = null

    init {
        val auth = config.auth
        if (auth is SshAuth.PrivateKey) {
            jsch.addIdentity("routerhub", auth.privateKey, null, auth.passphrase?.toByteArray())
        }
    }

    suspend fun connect() = withContext(Dispatchers.IO) {
        disconnect()
        val hostKeys = PinnedHostKeyRepository(config.hostKeyFingerprint)
        jsch.hostKeyRepository = hostKeys

        val newSession = jsch.getSession(config.username, config.host, config.port).apply {
            (config.auth as? SshAuth.Password)?.let { setPassword(it.password) }
            // "yes" + our repository = connect only to the pinned key; unknown keys are surfaced
            // to the user via SshHostKeyUnknownException instead of being silently accepted.
            setConfig("StrictHostKeyChecking", "yes")
            setConfig(
                "PreferredAuthentications",
                if (config.auth is SshAuth.PrivateKey) "publickey" else "password",
            )
            // Very old Dropbear builds only offer ssh-rsa (SHA-1) host keys, which this JSch fork
            // disables by default. Opt in per firmware if you must support them:
            // setConfig("server_host_key", getConfig("server_host_key") + ",ssh-rsa")
            setServerAliveInterval(15_000)
        }

        try {
            newSession.connect(config.timeoutMillis.toInt())
        } catch (e: JSchException) {
            throw translate(e, hostKeys)
        }
        session = newSession
    }

    /**
     * Runs [command] and collects its output.
     *
     * runInterruptible interrupts this thread when the coroutine is cancelled. JSch's channel
     * streams are PipedInputStreams, which then throw InterruptedIOException, so a hanging command
     * can't outlive the host's timeout. The channel is always closed in `finally`.
     */
    suspend fun exec(command: String): CommandResult = runInterruptible(Dispatchers.IO) {
        val current = session?.takeIf { it.isConnected }
            ?: throw RouterSessionExpiredException("SSH session is not connected")
        val channel = try {
            current.openChannel("exec") as ChannelExec
        } catch (e: JSchException) {
            throw RouterSessionExpiredException("SSH session dropped", e)
        }

        val stderr = ByteArrayOutputStream()
        try {
            channel.setCommand(command)
            // Careful, JSch's naming is from the channel's point of view: setInputStream() is the
            // remote STDIN we send, getInputStream() is the remote STDOUT we read.
            channel.setInputStream(null)
            channel.setErrStream(stderr, true)
            val stdout = channel.getInputStream() // must be obtained BEFORE connect()
            channel.connect(config.timeoutMillis.toInt())

            val output = stdout.readBytes().toString(Charsets.UTF_8)
            // EOF on stdout can arrive a moment before the exit-status message.
            while (!channel.isClosed) Thread.sleep(10)

            CommandResult(command, channel.exitStatus, output, stderr.toByteArray().toString(Charsets.UTF_8))
        } catch (e: InterruptedIOException) {
            // Cancelled mid-read. Rethrown as InterruptedException so runInterruptible reports a
            // CancellationException, not a misleading session error.
            throw InterruptedException("SSH read interrupted").apply { initCause(e) }
        } catch (e: IOException) {
            throw RouterSessionExpiredException("SSH connection lost", e)
        } catch (e: JSchException) {
            throw RouterSessionExpiredException("SSH channel failed: ${e.message}", e)
        } finally {
            channel.disconnect()
        }
    }

    fun disconnect() {
        session?.disconnect()
        session = null
    }

    private fun translate(e: JSchException, hostKeys: PinnedHostKeyRepository): RouterException {
        val presented = hostKeys.presentedFingerprint
        val message = e.message.orEmpty()
        return when {
            hostKeys.verdict == HostKeyVerdict.UNKNOWN && presented != null ->
                SshHostKeyUnknownException(config.host, presented)
            hostKeys.verdict == HostKeyVerdict.MISMATCH && presented != null ->
                SshHostKeyMismatchException(config.host, config.hostKeyFingerprint.orEmpty(), presented)
            message.startsWith("Auth fail") || message.startsWith("Auth cancel") ->
                SshAuthenticationException(cause = e)
            e.cause is SocketTimeoutException || message.contains("timeout", ignoreCase = true) ->
                FirmwareTimeoutException("SSH handshake with ${config.host} timed out", e)
            e.cause is IOException ->
                RouterUnreachableException("Cannot reach ${config.host}:${config.port}", e)
            else -> RouterException("SSH connection failed: $message", e)
        }
    }
}

internal enum class HostKeyVerdict { NOT_CHECKED, TRUSTED, UNKNOWN, MISMATCH }

/**
 * Host key store holding exactly one pinned fingerprint, managed by the host app (the equivalent
 * of a one-line known_hosts). JSch reports the key during the handshake through [check]; we record
 * what we saw, so a failure can be turned into a precise, user-actionable exception.
 */
internal class PinnedHostKeyRepository(pinned: String?) : HostKeyRepository {

    private val pinned = pinned?.let(::normalize)

    @Volatile
    var verdict = HostKeyVerdict.NOT_CHECKED
        private set

    @Volatile
    var presentedFingerprint: String? = null
        private set

    override fun check(host: String?, key: ByteArray): Int {
        val fingerprint = fingerprintOf(key)
        presentedFingerprint = fingerprint
        verdict = when (pinned) {
            null -> HostKeyVerdict.UNKNOWN
            normalize(fingerprint) -> HostKeyVerdict.TRUSTED
            else -> HostKeyVerdict.MISMATCH
        }
        return when (verdict) {
            HostKeyVerdict.TRUSTED -> HostKeyRepository.OK
            HostKeyVerdict.UNKNOWN -> HostKeyRepository.NOT_INCLUDED
            else -> HostKeyRepository.CHANGED
        }
    }

    // Pins are stored by the host app, never written by JSch.
    override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
    override fun remove(host: String?, type: String?) = Unit
    override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
    override fun getKnownHostsRepositoryID(): String = "routerhub-pinned"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()

    companion object {
        /** Same format as `ssh-keygen -lf`: "SHA256:" + unpadded base64 of SHA-256(key blob). */
        fun fingerprintOf(keyBlob: ByteArray): String =
            "SHA256:" + Base64.getEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(keyBlob))

        private fun normalize(fingerprint: String) = fingerprint.trim().removePrefix("SHA256:").trimEnd('=')
    }
}
