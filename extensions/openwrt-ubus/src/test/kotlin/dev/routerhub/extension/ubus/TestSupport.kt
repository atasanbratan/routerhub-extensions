package dev.routerhub.extension.ubus

import dev.routerhub.api.InvalidSettingException
import dev.routerhub.api.SettingField
import dev.routerhub.api.SettingsPage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail

/**
 * rpcd with a working uci store. Answers are parsed from JSON text in the shape OpenWrt 24.10 uses, so
 * the tests exercise the real parsing, and uci calls change the store like the router's would.
 */
internal class FakeRpcd(vararg answers: Pair<String, String>) : UbusApi {
    private val answers = (Fixtures.ANSWERS + answers).toMap().toMutableMap()
    private val configs = Fixtures.UCI.mapValues { (_, text) ->
        Json.parse(text).asObject("config").mapValuesTo(LinkedHashMap()) { (_, value) -> value.asObject("section").toMutableMap() }
    }.toMutableMap()
    private var added = 0

    /** Every call that changes something, in order, as "target method". */
    val writes = mutableListOf<String>()
    val lastArgs = mutableMapOf<String, JsonObject>()

    /** Calls ("target method") to fail, with the status to fail them with. */
    val failing = mutableMapOf<String, Exception>()

    fun answer(call: String, json: String) {
        answers[call] = json
    }

    fun section(config: String, name: String): JsonObject = configs.getValue(config).getValue(name)

    fun sections(config: String, type: String): List<JsonObject> =
        configs.getValue(config).values.filter { it[".type"] == type }

    override suspend fun call(target: String, method: String, args: JsonObject, quick: Boolean): JsonObject {
        val name = "$target $method"
        lastArgs[name] = args
        if (target == "uci" && method != "get") writes += name
        failing[name]?.let { throw it }
        if (target == "uci") return uci(method, args)
        if (name in setOf("system reboot", "luci setPassword")) writes += name
        return Json.parse(answers[name] ?: error("The fake router has no answer for $name")).asObject(name)
    }

    @Suppress("UNCHECKED_CAST")
    private fun uci(method: String, args: JsonObject): JsonObject {
        val config = args.str("config")
        val section = args.str("section")
        when (method) {
            "get" -> return mapOf("values" to (configs[config] ?: throw UbusCallFailedException("uci get", UbusStatus.NOT_FOUND)))
            "set" -> configs.getValue(config).getValue(section).putAll(args["values"] as JsonObject)
            "add" -> {
                val name = "cfg${added++}"
                val values = args["values"] as JsonObject
                configs.getValue(config)[name] = (values + mapOf(".name" to name, ".type" to args.str("type"), ".index" to 100L + added)).toMutableMap()
            }
            "delete" -> when (val option = args["option"]) {
                null -> configs.getValue(config).remove(section)
                else -> configs.getValue(config).getValue(section).remove(option)
            }
        }
        return emptyMap()
    }
}

internal fun router(rpcd: UbusApi) = UbusRouter(rpcd, Uci(rpcd) { })

internal fun UbusPage.readPage(rpcd: UbusApi): SettingsPage = runBlocking { read(router(rpcd)) }

internal fun UbusPage.applyChanges(rpcd: UbusApi, vararg changes: Pair<String, String>) =
    runBlocking { apply(router(rpcd), mapOf(*changes), "root") }

internal fun SettingsPage.field(key: String): SettingField = fields.first { it.key == key }

internal fun SettingsPage.rows(key: String): List<List<String>> = (field(key) as SettingField.Table).rows

/** Runs [block] and checks it is refused for [key]; returns the message. */
internal fun rejected(key: String, block: () -> Unit): String {
    try {
        block()
    } catch (e: InvalidSettingException) {
        assertEquals(key, e.key)
        return e.message.orEmpty()
    }
    fail("expected an InvalidSettingException for $key")
    error("unreachable")
}

internal object Fixtures {
    const val WIFI_KEY = "stored-wifi-key"
    const val PPPOE_KEY = "stored-pppoe-key"

    val UCI = mapOf(
        "wireless" to """{
            "radio0":{".name":"radio0",".type":"wifi-device",".index":0,"type":"mac80211","band":"2g","channel":"1","htmode":"HT20","country":"DE"},
            "radio1":{".name":"radio1",".type":"wifi-device",".index":1,"type":"mac80211","band":"5g","channel":"auto","htmode":"VHT80","disabled":"1"},
            "default_radio0":{".name":"default_radio0",".type":"wifi-iface",".index":2,"device":"radio0","mode":"ap","network":"lan","ssid":"HomeNet","encryption":"psk2","key":"$WIFI_KEY"},
            "default_radio1":{".name":"default_radio1",".type":"wifi-iface",".index":3,"device":"radio1","mode":"ap","network":"lan","ssid":"OpenNet","encryption":"none"},
            "wifinet2":{".name":"wifinet2",".type":"wifi-iface",".index":4,"device":"radio0","mode":"sta","network":"wwan","ssid":"Upstream"}
        }""",
        "network" to """{
            "loopback":{".name":"loopback",".type":"interface",".index":0,"device":"lo","proto":"static","ipaddr":"127.0.0.1","netmask":"255.0.0.0"},
            "lan":{".name":"lan",".type":"interface",".index":1,"device":"br-lan","proto":"static","ipaddr":"192.168.1.1","netmask":"255.255.255.0"},
            "wan":{".name":"wan",".type":"interface",".index":2,"device":"eth1","proto":"pppoe","username":"customer","password":"$PPPOE_KEY"}
        }""",
        "dhcp" to """{
            "lan":{".name":"lan",".type":"dhcp",".index":0,"interface":"lan","start":"100","limit":"150","leasetime":"12h"},
            "wan":{".name":"wan",".type":"dhcp",".index":1,"interface":"wan","ignore":"1"},
            "cfg01":{".name":"cfg01",".anonymous":true,".type":"host",".index":2,"name":"printer","mac":"02:00:00:00:00:21","ip":"192.168.1.50","leasetime":"infinite"}
        }""",
        "firewall" to """{
            "cfg01":{".name":"cfg01",".anonymous":true,".type":"zone",".index":0,"name":"lan","input":"ACCEPT","output":"ACCEPT","forward":"ACCEPT"},
            "cfg02":{".name":"cfg02",".anonymous":true,".type":"zone",".index":1,"name":"wan","input":"REJECT","output":"ACCEPT","forward":"REJECT","masq":"1"},
            "cfg03":{".name":"cfg03",".anonymous":true,".type":"rule",".index":2,"name":"Allow-Ping","src":"wan","proto":"icmp","target":"ACCEPT"},
            "cfg04":{".name":"cfg04",".anonymous":true,".type":"redirect",".index":3,"name":"web","target":"DNAT","src":"wan","dest":"lan","proto":"tcp","src_dport":"8080","dest_ip":"192.168.1.20","dest_port":"80","enabled":"1"},
            "cfg05":{".name":"cfg05",".anonymous":true,".type":"redirect",".index":4,"name":"hairpin","target":"SNAT","src":"lan","dest":"wan","proto":"all"}
        }""",
        "system" to """{
            "cfg01":{".name":"cfg01",".anonymous":true,".type":"system",".index":0,"hostname":"OpenWrt","zonename":"UTC","timezone":"GMT0"},
            "ntp":{".name":"ntp",".type":"timeserver",".index":1,"enabled":"1"}
        }""",
    )

    val ANSWERS = mapOf(
        "system board" to """{"kernel":"6.6.73","hostname":"OpenWrt","system":"MediaTek MT7621","model":"Example Router X1","board_name":"example,x1","rootfs_type":"squashfs","release":{"distribution":"OpenWrt","version":"24.10.0","revision":"r28427","target":"ramips/mt7621","description":"OpenWrt 24.10.0 r28427"}}""",
        "system info" to """{"localtime":1800000000,"uptime":3600,"load":[0,0,0],"memory":{"total":134217728,"free":33554432,"shared":0,"buffered":0,"available":67108864,"cached":0}}""",
        "network.interface dump" to """{"interface":[
            {"interface":"lan","up":true,"proto":"static","device":"br-lan","l3_device":"br-lan","ipv4-address":[{"address":"192.168.1.1","mask":24}],"route":[]},
            {"interface":"wan","up":true,"proto":"pppoe","device":"eth1","l3_device":"pppoe-wan","ipv4-address":[{"address":"203.0.113.9","mask":32}],"route":[{"target":"0.0.0.0","mask":0,"nexthop":"203.0.113.1","source":"203.0.113.9/32"}]},
            {"interface":"wan6","up":false,"proto":"dhcpv6","device":"eth1","ipv4-address":[],"route":[]}
        ]}""",
        "luci-rpc getWirelessDevices" to """{
            "radio0":{"up":true,"pending":false,"autostart":true,"disabled":false,"config":{"channel":"1","band":"2g","htmode":"HT20"},"interfaces":[
                {"section":"default_radio0","ifname":"phy0-ap0","config":{"mode":"ap","ssid":"HomeNet","encryption":"psk2","key":"$WIFI_KEY"}},
                {"section":"wifinet2","ifname":"phy0-sta0","config":{"mode":"sta","ssid":"Upstream"}}
            ]},
            "radio1":{"up":false,"pending":false,"autostart":true,"disabled":true,"config":{"channel":"auto","band":"5g","htmode":"VHT80"},"interfaces":[]}
        }""",
        "iwinfo assoclist" to """{"results":[{"mac":"02:00:00:00:00:11","signal":-60,"noise":-90,"inactive":100}]}""",
        "iwinfo info" to """{"phy":"phy0","ssid":"HomeNet","channel":1,"htmodes":["HT20","HT40"]}""",
        "iwinfo freqlist" to """{"results":[{"channel":1,"mhz":2412,"restricted":false},{"channel":6,"mhz":2437,"restricted":false},{"channel":13,"mhz":2472,"restricted":true}]}""",
        "iwinfo txpowerlist" to """{"results":[{"dbm":10,"mw":10},{"dbm":20,"mw":100}]}""",
        "luci-rpc getDHCPLeases" to """{"dhcp_leases":[
            {"expires":3000,"hostname":"phone","macaddr":"02:00:00:00:00:11","ipaddr":"192.168.1.101"},
            {"expires":3000,"hostname":"laptop","macaddr":"02:00:00:00:00:10","ipaddr":"192.168.1.100"},
            {"expires":10,"hostname":"gone","macaddr":"02:00:00:00:00:12","ipaddr":"192.168.1.102"}
        ],"dhcp6_leases":[]}""",
        "luci-rpc getHostHints" to """{"02:00:00:00:00:10":{"ipaddrs":["192.168.1.100"],"name":"laptop"},"02:00:00:00:00:13":{"ipaddrs":["192.168.1.103"],"name":"nas"}}""",
        "file read" to """{"data":"IP address       HW type     Flags       HW address            Mask     Device\n192.168.1.100    0x1         0x2         02:00:00:00:00:10     *        br-lan\n192.168.1.103    0x1         0x2         02:00:00:00:00:13     *        br-lan\n192.168.1.102    0x1         0x0         02:00:00:00:00:12     *        br-lan\n203.0.113.1      0x1         0x2         02:00:00:00:00:99     *        eth1\n"}""",
        "file exec" to """{"code":0,"stdout":"Thu Oct 29 10:00:00 2026 daemon.info dnsmasq[1]: started\nThu Oct 29 10:00:05 2026 kern.info kernel: link up\n"}""",
        "luci getTimezones" to """{"UTC":{"tzstring":"GMT0"},"Asia/Ashgabat":{"tzstring":"<+05>-5"},"Europe/Berlin":{"tzstring":"CET-1CEST,M3.5.0,M10.5.0/3"}}""",
        "luci setPassword" to """{"result":true}""",
        "system reboot" to "{}",
    )
}
