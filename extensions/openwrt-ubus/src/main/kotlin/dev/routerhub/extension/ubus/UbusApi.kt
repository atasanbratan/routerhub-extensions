package dev.routerhub.extension.ubus

import dev.routerhub.api.CommandFailedException

/**
 * rpcd's ubus-over-HTTP calls, which is what LuCI itself runs on. Tests supply a fake, the firmware
 * supplies [UbusClient].
 */
internal interface UbusApi {
    /**
     * Calls `method` of ubus object `target`. The session id is added by the implementation.
     *
     * @param quick give up after a few seconds: for calls that are expected to fail while the router
     *   restarts its network and are simply tried again
     * @throws UbusCallFailedException if the router answers with a failure status
     */
    suspend fun call(target: String, method: String, args: JsonObject = emptyMap(), quick: Boolean = false): JsonObject
}

/** The status codes of `enum ubus_msg_status`, which rpcd puts first in every result. */
internal object UbusStatus {
    const val OK = 0
    const val NOT_FOUND = 4
    const val NO_DATA = 5
    const val PERMISSION_DENIED = 6

    private val names = listOf(
        "ok", "invalid command", "invalid argument", "method not found", "not found", "no data",
        "permission denied", "timeout", "not supported", "unknown error", "connection failed",
    )

    fun name(status: Int): String = names.getOrElse(status) { "status $status" }
}

/** The router ran the call and reported failure. */
internal class UbusCallFailedException(call: String, val status: Int) :
    CommandFailedException(call, status, UbusStatus.name(status))
