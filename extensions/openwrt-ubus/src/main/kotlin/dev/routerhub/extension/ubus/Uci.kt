package dev.routerhub.extension.ubus

import dev.routerhub.api.FirmwareParseException
import dev.routerhub.api.RouterException
import kotlinx.coroutines.delay

/** One section of a uci config. Values are strings, or lists of strings for list options. */
internal class UciSection(val name: String, val type: String, val index: Long, private val values: JsonObject) {
    fun has(option: String) = values[option] != null

    /** The value; a list option reads as its entries joined by [separator]. */
    fun str(option: String, default: String = "", separator: String = " "): String = when (val value = values[option]) {
        null -> default
        is List<*> -> value.joinToString(separator)
        else -> value.toString()
    }

    /** uci's booleans, in all their spellings. */
    fun flag(option: String, default: Boolean = false): Boolean = when (str(option)) {
        "1", "on", "true", "yes", "enabled" -> true
        "0", "off", "false", "no", "disabled" -> false
        else -> default
    }
}

/**
 * How a change reaches the running router.
 *
 * [SAFE] is LuCI's own "apply with rollback": the router keeps the old config and restores it by
 * itself unless the change is confirmed in time, so a setting that cuts this phone off undoes itself.
 * [DIRECT] commits without that, for changes that are expected to cut the connection, such as
 * renaming the Wi-Fi network this phone is on: a rollback would only undo what the user asked for.
 */
internal enum class Apply { SAFE, DIRECT }

/** What a page may do to a uci config between the reads and the apply. */
internal class UciEdit(private val api: UbusApi, private val config: String) {
    suspend fun set(section: String, values: JsonObject) {
        if (values.isEmpty()) return
        api.call("uci", "set", mapOf("config" to config, "section" to section, "values" to values))
    }

    suspend fun add(type: String, values: JsonObject) {
        api.call("uci", "add", mapOf("config" to config, "type" to type, "values" to values))
    }

    suspend fun delete(section: String) {
        api.call("uci", "delete", mapOf("config" to config, "section" to section))
    }

    suspend fun deleteOption(section: String, option: String) {
        api.call("uci", "delete", mapOf("config" to config, "section" to section, "option" to option))
    }
}

/** The uci calls of rpcd. Changes collect in the session until [change] applies them. */
internal class Uci(private val api: UbusApi, private val pause: suspend (Long) -> Unit = { delay(it) }) {

    /** All sections of [config] in file order, or only those of [type]; none if the file doesn't exist. */
    suspend fun sections(config: String, type: String? = null): List<UciSection> {
        val answer = try {
            api.call("uci", "get", mapOf("config" to config))
        } catch (e: UbusCallFailedException) {
            if (e.status == UbusStatus.NOT_FOUND || e.status == UbusStatus.NO_DATA) return emptyList()
            throw e
        }
        return answer["values"].asObject("uci $config").values
            .map { value ->
                val section = value.asObject("A section of uci $config")
                UciSection(
                    name = section.str(".name"),
                    type = section.str(".type"),
                    index = section.num(".index") ?: Long.MAX_VALUE,
                    values = section.filterKeys { !it.startsWith(".") },
                )
            }
            .filter { type == null || it.type == type }
            .sortedBy { it.index }
    }

    suspend fun section(config: String, name: String): UciSection? = sections(config).firstOrNull { it.name == name }

    /** The only section of [type], like `system`'s, which is usually unnamed. */
    suspend fun first(config: String, type: String): UciSection =
        sections(config, type).firstOrNull() ?: throw FirmwareParseException("uci $config has no $type section")

    /**
     * Makes the changes of [block] in [config] and applies them, or takes them all back if one of
     * them fails.
     */
    suspend fun change(config: String, apply: Apply = Apply.SAFE, block: suspend UciEdit.() -> Unit) {
        try {
            UciEdit(api, config).block()
        } catch (e: RouterException) {
            try {
                api.call("uci", "revert", mapOf("config" to config))
            } catch (_: RouterException) {
                // Already failing: the first error is the one to report.
            }
            throw e
        }
        when (apply) {
            Apply.SAFE -> applySafely()
            // Apply commits too, and LuCI's ACLs don't cover a separate `uci commit`.
            Apply.DIRECT -> if (applyChanges(mapOf("rollback" to false))) waitForRouter()
        }
    }

    /** Reloading Wi-Fi stalls the router's web server for a while. The change is already made either way. */
    private suspend fun waitForRouter() {
        pause(SETTLE_MILLIS)
        repeat(SETTLE_ATTEMPTS) {
            pause(CONFIRM_PAUSE_MILLIS)
            try {
                api.call("system", "info", quick = true)
                return
            } catch (_: RouterException) {
                // Still reloading.
            }
        }
    }

    private suspend fun applySafely() {
        if (!applyChanges(mapOf("rollback" to true, "timeout" to ROLLBACK_SECONDS))) return
        var failure: RouterException? = null
        repeat(CONFIRM_ATTEMPTS) {
            pause(CONFIRM_PAUSE_MILLIS)
            try {
                api.call("uci", "confirm", quick = true)
                return
            } catch (e: RouterException) {
                failure = e
            }
        }
        throw RollbackPendingException(failure)
    }

    /** Applies whatever the session has changed. Returns false if that turned out to be nothing. */
    private suspend fun applyChanges(args: JsonObject): Boolean = try {
        api.call("uci", "apply", args)
        true
    } catch (e: UbusCallFailedException) {
        if (e.status != UbusStatus.NO_DATA) throw e
        false
    }

    private companion object {
        // Together these stay inside the 30 seconds the host gives one call, and the timer outlasts them.
        const val ROLLBACK_SECONDS = 45
        const val CONFIRM_ATTEMPTS = 5
        const val CONFIRM_PAUSE_MILLIS = 2_000L
        const val SETTLE_MILLIS = 3_000L
        const val SETTLE_ATTEMPTS = 4
    }
}

/** The router took the change but stopped answering, so its rollback timer will restore the old settings. */
internal class RollbackPendingException(cause: Throwable?) : RouterException(
    "The router stopped answering after this change, so it will undo it by itself within a minute",
    cause,
)
