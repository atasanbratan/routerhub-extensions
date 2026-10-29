package dev.routerhub.extension.tenda

import dev.routerhub.api.ChoiceOption
import dev.routerhub.api.ColumnKind
import dev.routerhub.api.InvalidSettingException
import dev.routerhub.api.SettingField
import dev.routerhub.api.SettingsPage
import dev.routerhub.api.SettingsPageInfo
import dev.routerhub.api.TableColumn

// ---------------------------------------------------------------------------------------------
// MAC filter: getMacFilterCfg / setMacFilterCfg
// ---------------------------------------------------------------------------------------------

/**
 * The router keeps two lists, a blacklist and a whitelist, but its page shows and saves only the one
 * of the active mode. This page does the same: one list, the active one. Saving the mode and the
 * list together is refused because the list on screen belongs to the old mode.
 */
internal object MacFilterPage : TendaPage {
    override val info = SettingsPageInfo("mac-filter", "MAC filter", "Network", "Allow or block devices by their MAC address")

    const val MODE = "mode"
    const val DEVICES = "devices"
    private const val MAX_ROWS = 30
    private const val NAME_LENGTH = 20

    private const val BLOCK = "black"
    private const val ALLOW = "white"

    private val MODES = listOf(
        ChoiceOption(BLOCK, "Block the devices below"),
        ChoiceOption(ALLOW, "Allow only the devices below"),
    )

    private val COLUMNS = listOf(
        TableColumn("name", "Name", ColumnKind.TEXT, optional = true, shortTitle = ""),
        TableColumn("mac", "MAC address", ColumnKind.MAC_ADDRESS, shortTitle = ""),
    )

    private class State(g: JsonObject) {
        val mode = g.str("macFilterType").let { if (it == ALLOW) ALLOW else BLOCK }
        val blocked = rows(g, "blackList")
        val allowed = rows(g, "whiteList")

        /** The device that is asking, which the router can identify and which must not lock itself out. */
        val localMac = g.str("localhostMac").lowercase()
        val localName = g.str("localhostName")

        fun rowsFor(mode: String) = if (mode == ALLOW) allowed else blocked

        private fun rows(g: JsonObject, key: String) =
            g.objects(key).map { listOf(it.str("devName"), it.str("devMac").lowercase()) }
    }

    private suspend fun state(api: TendaApi) = State(api.read("getMacFilterCfg").asObject("getMacFilterCfg"))

    override suspend fun read(api: TendaApi): SettingsPage {
        val s = state(api)
        return SettingsPage(
            info = info,
            fields = buildList {
                add(SettingField.Choice(MODE, "Mode", s.mode, MODES, summary = "Change the mode and its list in two separate saves"))
                if (s.localMac.isNotEmpty()) {
                    add(SettingField.Info("thisDevice", "This device", "${s.localName.ifEmpty { "Unnamed" }} (${s.localMac})", summary = "The router sees this phone with this address"))
                }
                add(
                    SettingField.Table(
                        DEVICES, if (s.mode == ALLOW) "Allowed devices" else "Blocked devices", COLUMNS, s.rowsFor(s.mode),
                        maxRows = MAX_ROWS, itemName = "device",
                        emptyText = if (s.mode == ALLOW) "No device is allowed yet" else "No device is blocked",
                    ),
                )
            },
        )
    }

    override suspend fun apply(api: TendaApi, changes: Map<String, String>, context: TendaContext) {
        val s = state(api)
        val mode = changes[MODE] ?: s.mode
        Rules.oneOf(MODE, mode, MODES.map { it.value }, "The mode")
        val modeChanged = mode != s.mode
        if (modeChanged && DEVICES in changes) {
            throw InvalidSettingException(DEVICES, "The list on screen belongs to the old mode. Change the mode first, save, then edit the list")
        }

        var rows = changes[DEVICES]?.let { decodeRows(DEVICES, it, COLUMNS.size) } ?: s.rowsFor(mode)
        if (rows.size > MAX_ROWS) throw InvalidSettingException(DEVICES, "The router holds at most $MAX_ROWS devices per list")

        val seen = mutableSetOf<String>()
        rows = rows.map { (name, mac) ->
            val cleanMac = Rules.mac(DEVICES, mac)
            if (!seen.add(cleanMac)) throw InvalidSettingException(DEVICES, "$cleanMac is listed twice")
            listOf(Rules.label(DEVICES, name, "A name", NAME_LENGTH), cleanMac)
        }

        when (mode) {
            BLOCK -> if (s.localMac in seen) {
                throw InvalidSettingException(DEVICES, "This device (${s.localMac}) is the one you are using: blocking it would cut you off")
            }
            ALLOW -> when {
                s.localMac.isEmpty() && rows.isEmpty() ->
                    throw InvalidSettingException(DEVICES, "An allow-only list with nothing in it would block every device")
                s.localMac.isNotEmpty() && s.localMac !in seen -> {
                    // Like the router's own page, which puts the device you are using on a new whitelist
                    // by itself, so that switching the mode cannot lock you out.
                    if (!modeChanged) {
                        throw InvalidSettingException(DEVICES, "Keep this device (${s.localMac}) in the list: removing it would cut you off")
                    }
                    rows = listOf(listOf(Rules.label(DEVICES, s.localName, "A name", NAME_LENGTH), s.localMac)) + rows
                }
            }
        }

        // Each entry is the name (escaped), CR, the address; entries are separated by LF.
        val list = rows.joinToString("\n") { (name, mac) -> "${Form.component(name)}\r$mac" }
        api.write("setMacFilterCfg", "macFilterType=$mode&deviceList=$list")
    }
}
