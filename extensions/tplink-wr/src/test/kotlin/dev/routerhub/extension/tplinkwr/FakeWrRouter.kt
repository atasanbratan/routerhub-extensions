package dev.routerhub.extension.tplinkwr

import dev.routerhub.api.RouterSessionExpiredException

/**
 * A pretend TL-WR940N: keeps the lists, answers list pages eight entries at a time in the router's
 * format, and handles the add, delete and save requests the way the real one was seen to.
 */
internal class FakeWrRouter : WrApi {
    val requests = mutableListOf<String>()
    var loggedOut = false
    val failing = mutableSetOf<String>()
    val pages = mutableMapOf(
        "StatusRpm.htm" to Fixtures.STATUS,
        "AssignedIpAddrListRpm.htm" to Fixtures.DHCP,
        "WlanStationRpm.htm" to Fixtures.STATIONS,
    )

    val reservations = mutableListOf<List<String>>()
    val forwards = mutableListOf<List<String>>()
    val filterEntries = mutableListOf<List<String>>()
    var filterOn = false
    var filterRefusesListed = true
    var dmz = listOf("0", "0.0.0.0")
    var upnp = true

    /** The Wi-Fi pages' `wlanPara`, made-up values in the router's positions. */
    val network = WifiFixtures.network()
    val security = WifiFixtures.security()
    val dhcp = mutableListOf("1", "192.168.0.100", "192.168.0.199", "120", "0.0.0.0", "", "0.0.0.0", "0.0.0.0", "0", "0", "1", "0")

    override suspend fun login() {
        requests += "login"
    }

    override suspend fun logout() {
        loggedOut = true
    }

    override suspend fun page(name: String, query: String): String {
        requests += if (query.isEmpty()) name else "$name?$query"
        if (name in failing) throw RouterSessionExpiredException()
        val q = query.split('&').filter { it.isNotEmpty() }.associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val page = q["Page"]?.toIntOrNull() ?: 1
        return when (name) {
            "FixMapCfgRpm.htm" -> {
                when {
                    "Save" in q && !q.getValue("Ip").startsWith("192.168.0.") -> return """<script>var errCode = "26106";</script>"""
                    "Save" in q -> reservations += listOf(q.getValue("Mac"), q.getValue("Ip"), q.getValue("State"))
                    "Del" in q -> reservations.removeAt(q.getValue("Del").toInt())
                }
                list("DHCPStaticPara", "dhcpList", reservations, page) { para, more, count -> "$para, $more, $count, 3, 8, 0" }
            }
            "VirtualServerRpm.htm" -> {
                when {
                    "Save" in q -> {
                        val (exFirst, exLast) = bounds(q.getValue("ExPort"))
                        val (inFirst, inLast) = bounds(q.getValue("InPort"))
                        forwards += listOf(exFirst, exLast, inFirst, inLast, q.getValue("Ip"), q.getValue("Protocol"), q.getValue("State"))
                    }
                    "Del" in q -> forwards.removeAt(q.getValue("Del").toInt())
                }
                list("virServerPara", "virServerListPara", forwards, page) { para, more, count -> "$para, $more, $count, 7, 8" }
            }
            "WlanMacFilterRpm.htm" -> {
                when {
                    "Save" in q -> filterEntries += listOf(q.getValue("Mac"), q.getValue("entryEnabled"), q.getValue("Type"), "", decode(q.getValue("Desc")))
                    "Del" in q -> filterEntries.removeAt(q.getValue("Del").toInt())
                    "Enfilter" in q -> filterOn = true
                    "Disfilter" in q -> filterOn = false
                }
                val on = if (filterOn) 1 else 0
                val rule = if (filterRefusesListed) 0 else 1
                list("wlanFilterPara", "wlanFilterList", filterEntries, page) { para, more, count -> "$on, $rule, 0, $para, $more, $count, 5, 8" }
            }
            "DMZRpm.htm" -> {
                if ("Save" in q) dmz = listOf(q.getValue("enable"), q.getValue("ipAddr"))
                """<script>var DMZInf = new Array(${dmz[0]}, "${dmz[1]}", "192.168.0.1", "255.255.255.0", 0,0 );</script>"""
            }
            "UpnpCfgRpm.htm" -> {
                if ("Upnpenable" in q) upnp = true
                if ("Upnpdisable" in q) upnp = false
                """<script>var uPnPPara = new Array(${if (upnp) 1 else 0}, 0, 6, 0,0 );</script>"""
            }
            "WlanNetworkRpm.htm" -> {
                if ("Save" in q) {
                    network[3] = decode(q.getValue("ssid1"))
                    network[8] = if ("ap" in q) "1" else "0"
                    network[9] = if ("broadcast" in q) "1" else "0"
                    network[10] = q.getValue("channel")
                }
                WifiFixtures.networkPage(network)
            }
            "WlanSecurityRpm.htm" -> {
                if ("Save" in q) {
                    security[2] = q.getValue("secType")
                    security[3] = security[3].take(2) + q.getValue("pskSecOpt")
                    security[14] = q.getValue("pskCipher")
                    security[9] = decode(q.getValue("pskSecret"))
                }
                WifiFixtures.securityPage(security)
            }
            "LanDhcpServerRpm.htm" -> {
                if ("Save" in q && q.getValue("ip1").startsWith("10.")) return """<script>var errCode = "26106";</script>"""
                if ("Save" in q) {
                    listOf("dhcpserver", "ip1", "ip2", "Lease", "gateway", "domain", "dnsserver", "dnsserver2").forEachIndexed { i, name -> dhcp[i] = decode(q.getValue(name)) }
                }
                """<script>var DHCPPara = new Array(${dhcp.joinToString(", ") { if (it.toIntOrNull() != null) it else "\"$it\"" }}, 0,0 );</script>"""
            }
            "SysRebootRpm.htm" -> ""
            else -> pages[name] ?: error("No page $name")
        }
    }

    private fun bounds(ports: String) = ports.split('-').let { it.first() to it.last() }

    private fun decode(text: String) = java.net.URLDecoder.decode(text, "UTF-8")

    private fun list(
        paraName: String,
        listName: String,
        entries: List<List<String>>,
        page: Int,
        para: (page: Int, more: Int, count: Int) -> String,
    ): String {
        val shown = entries.drop((page - 1) * 8).take(8)
        val more = if (entries.size > page * 8) 1 else 0
        val values = shown.flatten().joinToString(", ") { if (it.toIntOrNull() != null) it else "\"$it\"" }
        return """
            <script>var $listName = new Array(${if (values.isEmpty()) "" else "$values, "}0,0 );</script>
            <script>var $paraName = new Array(${para(page, more, shown.size)}, 0,0 );</script>
        """.trimIndent()
    }
}
