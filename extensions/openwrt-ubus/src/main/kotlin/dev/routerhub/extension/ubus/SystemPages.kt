package dev.routerhub.extension.ubus

import dev.routerhub.api.ChoiceOption
import dev.routerhub.api.CommandFailedException
import dev.routerhub.api.RouterException
import dev.routerhub.api.RouterSessionExpiredException
import dev.routerhub.api.SettingField
import dev.routerhub.api.SettingsPage
import dev.routerhub.api.SettingsPageInfo
import dev.routerhub.api.TableColumn

private const val SYSTEM = "system"
private const val GROUP = "System"

internal object SystemPage : UbusPage {
    const val HOSTNAME = "hostname"
    const val ZONE = "zone"
    const val NTP = "ntp"

    override val info = SettingsPageInfo("system", "Name and time", GROUP, "Hostname, time zone, time sync")

    override suspend fun read(router: UbusRouter): SettingsPage {
        val system = router.uci.first(SYSTEM, SYSTEM)
        val ntp = router.uci.section(SYSTEM, NTP)
        val zone = system.str("zonename", "UTC")
        val zones = zones(router).keys.sorted().map { ChoiceOption(it, it) }
        return SettingsPage(
            info = info,
            fields = buildList {
                add(SettingField.Text(HOSTNAME, "Router name", system.str("hostname"), maxLength = 253))
                add(SettingField.Choice(ZONE, "Time zone", zone, optionsWith(zone, zones)))
                if (ntp != null) add(SettingField.Toggle(NTP, "Set the time from the internet", ntp.flag("enabled", true)))
            },
        )
    }

    private suspend fun zones(router: UbusRouter): JsonObject = router.api.call("luci", "getTimezones")

    override suspend fun apply(router: UbusRouter, changes: Map<String, String>, username: String) {
        val system = router.uci.first(SYSTEM, SYSTEM)
        val hostname = changes[HOSTNAME]?.let { Rules.hostname(HOSTNAME, it) }
        val zone = changes[ZONE]?.let { name ->
            val zones = zones(router)
            Rules.oneOf(ZONE, name, zones.keys, "The time zone")
            name to zones[name].asObject("Time zone $name").str("tzstring")
        }
        val values = buildMap {
            hostname?.let { put("hostname", it) }
            zone?.let { (name, posix) ->
                put("zonename", name)
                put("timezone", posix)
            }
        }
        router.uci.change(SYSTEM) {
            set(system.name, values)
            changes.flag(NTP)?.let { set(NTP, mapOf("enabled" to if (it) "1" else "0")) }
        }
    }
}

internal object SystemLogPage : ReadOnlyPage {
    private const val LINES = 200
    private val SOURCES = listOf(
        "/usr/libexec/syslog-wrapper" to emptyList(),
        "/sbin/logread" to listOf("-e", "^"),
        "/sbin/logread" to listOf("-l", LINES.toString()),
    )
    private val LINE = Regex("""^(\w{3} \w{3} +\d+ \d\d:\d\d:\d\d \d{4}) (.*)$""")

    override val info = SettingsPageInfo("log", "System log", GROUP, "Recent messages, newest first")

    private val COLUMNS = listOf(TableColumn("time", "Time", shortTitle = ""), TableColumn("message", "Message", shortTitle = ""))

    /** rpcd's ACLs match the whole command line, so only what LuCI's own log page runs is sure to be allowed. */
    private suspend fun readLog(router: UbusRouter): String {
        var failure: RouterException? = null
        for ((command, params) in SOURCES) {
            try {
                val answer = router.api.call("file", "exec", mapOf("command" to command, "params" to params))
                if (answer.num("code") == 0L) return answer.str("stdout")
                failure = CommandFailedException(command, answer.num("code")?.toInt(), answer.str("stderr"))
            } catch (e: RouterSessionExpiredException) {
                throw e
            } catch (e: RouterException) {
                failure = e
            }
        }
        throw failure!!
    }

    override suspend fun read(router: UbusRouter): SettingsPage {
        val text = readLog(router)
        return SettingsPage(
            info = info,
            fields = listOf(
                SettingField.Table(
                    key = "log",
                    title = "System log",
                    columns = COLUMNS,
                    rows = parse(text).take(LINES),
                    itemName = "message",
                    emptyText = "The log is empty",
                    readOnly = true,
                ),
            ),
        )
    }

    fun parse(text: String): List<List<String>> = text.lines().filter { it.isNotBlank() }.reversed().map { line ->
        LINE.find(line)?.let { listOf(it.groupValues[1], it.groupValues[2]) } ?: listOf("", line)
    }
}

internal object LoginPasswordPage : UbusPage {
    const val PASSWORD = "password"

    override val info = SettingsPageInfo("login-password", "Login password", GROUP, "The password for LuCI, SSH and this app")

    override suspend fun read(router: UbusRouter) = SettingsPage(
        info = info,
        fields = listOf(SettingField.Secret(PASSWORD, "New password", minLength = 1, required = true, requiresConfirmation = true)),
        loginPasswordKey = PASSWORD,
    )

    override suspend fun apply(router: UbusRouter, changes: Map<String, String>, username: String) {
        val password = changes[PASSWORD]?.takeIf { it.isNotEmpty() } ?: return
        val answer = router.api.call("luci", "setPassword", mapOf("username" to username, "password" to password))
        if (answer["result"] != true) throw CommandFailedException("luci setPassword", null, "The router refused the new password")
    }
}
