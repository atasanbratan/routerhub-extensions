package dev.routerhub.extension.tenda

import dev.routerhub.api.InvalidSettingException
import dev.routerhub.api.SettingField
import dev.routerhub.api.SettingsPage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail

/**
 * A router that holds some answers and records every write. The answers are parsed from JSON text
 * shaped like the real router's, so the tests exercise the real parsing, not hand-built maps.
 */
internal class FakeTendaApi(vararg answers: Pair<String, String>) : TendaApi {
    private val reads = answers.associate { (name, json) -> name to json }.toMutableMap()

    /** Every `write`, in order: the page name and the (not yet scrambled) body. */
    val writes = mutableListOf<Pair<String, String>>()
    val plainPosts = mutableListOf<Pair<String, String>>()

    fun answer(name: String, json: String) {
        reads[name] = json
    }

    override suspend fun read(name: String): Any? =
        Json.parse(reads[name] ?: error("The fake router has no answer for goform/$name"))

    override suspend fun write(name: String, body: String) {
        writes += name to body
    }

    override suspend fun postPlain(name: String, body: String): PlainAnswer {
        plainPosts += name to body
        return PlainAnswer(302, "/system_password.html", "")
    }

    val onlyWrite: Pair<String, String>
        get() = writes.single()
}

internal val TENDA_CONTEXT = TendaContext("admin", "current-login-password")

internal fun TendaPage.readPage(api: TendaApi): SettingsPage = runBlocking { read(api) }

internal fun TendaPage.applyChanges(api: TendaApi, vararg changes: Pair<String, String>) =
    runBlocking { apply(api, mapOf(*changes), TENDA_CONTEXT) }

internal fun SettingsPage.field(key: String): SettingField = fields.first { it.key == key }

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

/** Answers copied from a real AC10 V6.0, with the names, passwords and addresses replaced. */
internal object Fixtures {
    const val WIFI_BASIC = """{"doubleBand":"0","if_in_wifiSchedule":"0","wrlEn":"1","wrlEn_5g":"1","ssid":"HomeNet","hideSsid":"0","wpapsk_type":"psk2","security":"wpa2psk","wpapsk_crypto":"aes","wrlPwd":"correct-horse","uptime":"0","ssid_5g":"HomeNet_5G","hideSsid_5g":"0","wpapsk_type_5g":"psk2","security_5g":"wpa2psk","wpapsk_crypto_5g":"aes","wrlPwd_5g":"battery-staple","uptime_5g":"0"}"""

    const val WIFI_GUEST = """{"wl_en":"1","wl_mode":"ap","guestEn":"0","hideSsid":"0","guestSsid":"Visitors","guestWrlPwd":"","guestEn_5g":"0","hideSsid_5g":"0","guestSsid_5g":"Visitors_5G","guestWrlPwd_5g":"","effectiveTime":"8","shareSpeed":"0"}"""

    const val WIFI_RADIO = """{"adv_country":"CN","adv_current_band":"20","adv_current_channel":"3","channel":[0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13],"adv_band":"auto","adv_channel":"0","adv_extend_channel":"none","adv_mode":"bgn","adv_current_band_5g":"80","adv_current_channel_5g":"48","adv_channel_5g":"0","adv_band_5g":"auto","adv_extend_channel_5g":"none","adv_mode_5g":"ac","adv_country_5g":"CN","channel_5g":{"20":[0, 36, 40, 44, 48, 149, 153, 157, 161, 165],"40":[0, 36, 40, 44, 48, 149, 153, 157, 161],"80":[0, 36, 40, 44, 48, 149, 153, 157, 161]}}"""

    const val WIFI_POWER = """{"power":"high","power_5g":"middle"}"""
    const val WIFI_WPS = """{"wpsEn":"1","pinCode":"00000000","wl_mode":"ap","wl_en":"1"}"""
    const val WIFI_BEAMFORMING = """{"beamformingEn":"1"}"""

    const val SYSTEM_STATUS = """{"adv_sys_time":"2000-01-01 00:11:23","adv_run_time":694,"adv_firm_ver":"V16.03.61.08_cn","adv_hard_ver":"V6.0","wanInfo":[{"adv_connect_status":3,"adv_connect_time":"120","adv_ip":"203.0.113.9","adv_mask":"255.255.255.0","adv_gateway":"203.0.113.1","wanUploadSpeed":"0","wanDownloadSpeed":"0","adv_dns1":"198.51.100.1","adv_dns2":"","adv_connect_type":"0","adv_mac":"02:00:00:00:00:02"}],"adv_lan_ip":"192.168.0.1","adv_lan_mask":"255.255.255.0","adv_lan_mac":"02:00:00:00:00:01","wifi_enable_5g":"1","adv_wrl_en_5g":"0","adv_wrl_ssid_5g":"HomeNet_5G","adv_wrl_sec_5g":"wpa2psk","adv_wrl_channel_5g":"48","adv_wrl_band_5g":"80","wifi_enable":"1","adv_wrl_en":"0","adv_wrl_ssid":"HomeNet","adv_wrl_sec":"wpa2psk","adv_wrl_channel":"3","adv_wrl_band":"20"}"""

    const val ROUTER_STATUS = """{"doubleBand":"0","wl24gEn":"1","wl24gName":"HomeNet","wl5gEn":"0","wl5gName":"HomeNet_5G","lineup":"1|0|0","clientNum":3,"blackNum":1,"listNum":0,"deviceName":"ac10v6","lanIP":"192.168.0.1","lanMAC":"02:00:00:00:00:01","workMode":"router","apStatus":"2100001","wanInfo":[{"wanStatus":"2300001","wanIp":"203.0.113.9","wanUploadSpeed":"0","wanDownloadSpeed":"0"}],"countryCode":"CN"}"""

    const val ONLINE_LIST = """[{"blackNum":1,"macFilterType":"black","localhostIP":"192.168.0.125","localhostName":"LAPTOP","localhostMac":"02:00:00:00:00:10","isWirelessConnect":"false"}, {"deviceId":"02:00:00:00:00:10","ip":"192.168.0.125","devName":"LAPTOP","line":"0","uploadSpeed":"0","downloadSpeed":"0","linkType":"unknown","black":0,"isGuestClient":"false"}, {"deviceId":"02:00:00:00:00:11","ip":"192.168.0.126","devName":"PHONE","line":"2","uploadSpeed":"0","downloadSpeed":"0","linkType":"unknown","black":0,"isGuestClient":"false"}, {"deviceId":"02:00:00:00:00:12","ip":"192.168.10.5","devName":"","line":"1","uploadSpeed":"0","downloadSpeed":"0","linkType":"unknown","black":0,"isGuestClient":"true"}, {"deviceId":"02:00:00:00:00:13","ip":"192.168.0.130","devName":"BLOCKED","line":"1","uploadSpeed":"0","downloadSpeed":"0","linkType":"unknown","black":1,"isGuestClient":"false"}]"""

    const val LAN = """{"lanIp":"192.168.0.1","lanMask":"255.255.255.0","ip":"","mask":"","startIp":"192.168.0.100","endIp":"192.168.0.200","leaseTime":"86400","dhcpEn":"1","lanDnsAuto":"1","lanDns1":"","lanDns2":"","guestIp":"192.168.10.1","guestMask":"255.255.255.0","serverIp":"","vlan2Ip":"","vlan2Mask":"","wanIp":"","wanMask":"","pptpSvrIp":"","pptpSvrMask":"255.255.255.0","wl_mode":"ap","remoteIp":"0.0.0.0"}"""

    const val IP_MAC_BIND = """{"lanIp":"192.168.0.1","lanMask":"255.255.255.0","dhttpIP":"172.31.131.54","dhcpClientList":[{"ipaddr":"192.168.0.125","macaddr":"02:00:00:00:00:10","devname":"LAPTOP","status":"1"}],"bindList":[{"devname":"Printer","macaddr":"02:00:00:00:00:21","ipaddr":"192.168.0.50","status":"0"}]}"""

    const val VIRTUAL_SERVER = """{"lanIp":"192.168.0.1","lanMask":"255.255.255.0","usbport":"","virtualList":[{"ip":"192.168.0.20","inPort":"80","outPort":"8080","protocol":"1"}]}"""

    const val DMZ = """{"lanIp":"192.168.0.1","lanMask":"255.255.255.0","dmzEn":"0","dmzIp":"192.168.0.100"}"""
    const val UPNP = """[{"upnpEn":"1"}]"""
    const val FIREWALL = """{"firewallEn":"1101"}"""
    const val DDNS = """{"ddnsEn":"0","serverName":"no-ip.com","ddnsUser":"","ddnsPwd":"","ddnsDomain":"","ddnsStatus":"0"}"""
    const val REMOTE_WEB = """{"syspwdflag":"1","lanIp":"192.168.0.1","lanMask":"255.255.255.0","wlGuestIp":"192.168.10.1","remoteWebEn":"0","remoteIp":"0.0.0.0","remotePort":"8888"}"""

    const val MAC_FILTER = """{"localhostIP":"192.168.0.125","localhostName":"LAPTOP","localhostMac":"02:00:00:00:00:10","macFilterType":"black","blackList":[{"devName":"Tablet","devMac":"02:00:00:00:00:30"}],"whiteList":[{"devName":"Phone","devMac":"02:00:00:00:00:11"},{"devName":"LAPTOP","devMac":"02:00:00:00:00:10"}],"onlineList":[{"devName":"LAPTOP","devMac":"02:00:00:00:00:10"}]}"""

    const val SYS_TIME = """{"timeType":"sync","timeZone":"15:00","timePeriod":"86400","ntpServer":"time.example.org","time":"2026-10-29 12:34:56","isSyncInternetTime":"true"}"""
    const val LED = """{"ledType":"open","time":"00:00-07:00","ledCloseType":"allClose","powerSaveTime":"","timeUp":"0"}"""
    const val AUTO_REBOOT = """{"autoRebootEn":"1","time":"02:00-05:30","rebootTime":"02:00","delayRebootEn":"true","timeUp":"0","speed":"3"}"""
    const val SYSTEM_LOG = """[{"index":1,"time":"2000-01-01 00:00:01","type":"system","log":"System start."}, {"index":2,"time":"2000-01-01 00:00:02","type":"system","log":"DHCP Server Start"}]"""
    const val PASSWORD_INFO = """{"ispwd":"1","remoteEn":"0"}"""

    /** A router that can answer for every page, to read them all. */
    fun everything() = FakeTendaApi(
        "WifiBasicGet" to WIFI_BASIC, "WifiGuestGet" to WIFI_GUEST, "WifiRadioGet" to WIFI_RADIO,
        "WifiPowerGet" to WIFI_POWER, "WifiWpsGet" to WIFI_WPS, "WifiBeamformingGet" to WIFI_BEAMFORMING,
        "AdvGetLanIp" to LAN, "GetIpMacBind" to IP_MAC_BIND, "GetVirtualServerCfg" to VIRTUAL_SERVER,
        "GetDMZCfg" to DMZ, "GetUpnpCfg" to UPNP, "GetFirewallCfg" to FIREWALL, "getMacFilterCfg" to MAC_FILTER,
        "GetDDNSCfg" to DDNS, "GetRemoteWebCfg" to REMOTE_WEB, "GetSysTimeCfg" to SYS_TIME, "GetLEDCfg" to LED,
        "GetSysAutoRebbotCfg" to AUTO_REBOOT, "GetSySLogCfg" to SYSTEM_LOG, "SysToolpassword" to PASSWORD_INFO,
    )
}
