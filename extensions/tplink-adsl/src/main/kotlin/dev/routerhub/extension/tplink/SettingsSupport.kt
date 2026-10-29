package dev.routerhub.extension.tplink

import dev.routerhub.api.CommandFailedException
import dev.routerhub.api.FirmwareParseException
import dev.routerhub.api.InvalidSettingException
import dev.routerhub.api.SettingsPage
import dev.routerhub.api.SettingsPageInfo

/** Who is logged in; a few requests (changing the admin password) need the current credentials. */
internal class SettingsContext(val username: String, val password: String)

/**
 * One configuration page of the router's web UI.
 *
 * The firmware has no API: its pages are static HTML whose current values sit in inline
 * JavaScript, and saving means requesting a URL that the page's own script assembles. Each spec
 * therefore does two things, both pure functions so they can be unit-tested without a router:
 *  - [parse] turns the page into typed fields;
 *  - [buildRequest] reproduces, byte for byte, the URL the page's "Save/Apply" button would
 *    request, including its anti-CSRF `sessionKey`.
 */
internal interface SettingsPageSpec {
    val info: SettingsPageInfo
    val path: String

    /**
     * What to request to obtain the page, for reading and before writing. Normally the page itself;
     * a page that depends on server-side selection state loads through a URL that pins it first.
     */
    val loadPath: String get() = path

    fun parse(html: String): SettingsPage

    /** Reads the page. List pages override this to fetch their "add" page too. */
    suspend fun read(client: PageClient): SettingsPage = parse(client.get(loadPath))

    /**
     * Path and query that apply [changes] on top of the values currently in [html].
     *
     * @throws InvalidSettingException when a value breaks a rule the page itself enforces.
     */
    fun buildRequest(html: String, changes: Map<String, String>, context: SettingsContext): String =
        throw InvalidSettingException("page", "This page applies its changes in several steps")

    /**
     * Applies [changes]. A single-request page only provides [buildRequest]; list pages override
     * this, because adding and removing entries takes one request each, and every request needs
     * the fresh `sessionKey` of the page it comes from.
     */
    suspend fun apply(client: PageClient, changes: Map<String, String>, context: SettingsContext) {
        val html = client.get(loadPath)
        client.get(buildRequest(html, changes, context))
    }
}

/** How a page spec talks to the router. Tests supply a fake; the firmware supplies HTTP. */
internal interface PageClient {
    suspend fun get(path: String): String
}

/**
 * The firmware answers a refused change with an ordinary page (HTTP 200) whose title ends in
 * "Error", for example "Url Filter Entry Add Error" with the reason underneath. Without this, a
 * rejected save looks exactly like a successful one. Found on real hardware: adding a website
 * while the filter was off was refused and the app said nothing.
 */
internal class CheckedPageClient(private val inner: PageClient) : PageClient {
    override suspend fun get(path: String): String {
        val html = inner.get(path)
        val title = ERROR_TITLE.find(html)?.groupValues?.get(1)?.trim() ?: return html
        val reason = REASON.find(html)?.groupValues?.get(1)
            ?.replace(Regex("<[^>]*>"), " ")?.replace(Regex("""\s+"""), " ")?.trim()
            .orEmpty()
        // Only the path, never the query: it may carry a password the user just typed.
        throw CommandFailedException(path.substringBefore('?'), null, reason.ifEmpty { title }.take(240))
    }

    private companion object {
        val ERROR_TITLE = Regex("""(?is)class=['"]?h1['"]?[^>]*>([^<]*\berror\b[^<]*)<""")
        val REASON = Regex("""(?is)class=['"]?blue['"]?\s*></td></tr><tr><td>(.*?)</td>""")
    }
}

internal object PageData {
    /** The first `var name = '...'` line of the page's inline script. */
    fun jsVar(html: String, name: String): String? =
        Regex("""(?m)^\s*var\s+${Regex.escape(name)}\s*=\s*'([^']*)'""").find(html)?.groupValues?.get(1)

    fun need(html: String, name: String): String =
        jsVar(html, name) ?: throw FirmwareParseException("The page has no '$name' value (new firmware revision?)")

    /** `var sessionKey='123'` or the literal inside the save URL (`'&sessionKey=123'`). */
    fun sessionKey(html: String): String =
        Regex("""sessionKey\s*=\s*'?(\d+)""").find(html)?.groupValues?.get(1)
            ?: throw FirmwareParseException("The page carries no sessionKey")
}

/**
 * The firmware's own `encodeUrl()` from util.js: everything outside the printable range 33..122,
 * plus `"<>%\^[]`+$,'#&`, becomes `%XX`. Replicated exactly because the router decodes with a
 * matching, not fully standard, routine.
 */
internal object FirmwareUrl {
    private const val UNSAFE = "\"<>%\\^[]`+$,'#&"

    fun encode(value: String): String = buildString {
        for (c in value) {
            if (c.code in 33..122 && c !in UNSAFE) append(c)
            else append('%').append(String.format("%02X", c.code))
        }
    }
}

/** Validation rules copied from the pages' own scripts, so a bad value is caught before sending. */
internal object Rules {
    fun isAscii(value: String) = value.all { it.code <= 127 }

    fun ssid(key: String, value: String) {
        if (value.isEmpty()) throw InvalidSettingException(key, "The network name can't be empty")
        if (value.length > 32) throw InvalidSettingException(key, "The network name can be at most 32 characters")
        if (!isAscii(value)) throw InvalidSettingException(key, "The network name may only use plain ASCII characters")
    }

    /** WPA pre-shared key: 8 to 63 ASCII characters, or exactly 64 hexadecimal digits. */
    fun wpaPassphrase(key: String, value: String) {
        val valid = when (value.length) {
            in 8..63 -> isAscii(value)
            64 -> value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
            else -> false
        }
        if (!valid) throw InvalidSettingException(key, "Use 8 to 63 ASCII characters, or exactly 64 hexadecimal digits")
    }

    fun number(key: String, value: String, range: LongRange, what: String): Long {
        val number = value.trim().toLongOrNull()
            ?: throw InvalidSettingException(key, "$what must be a whole number")
        if (number !in range) throw InvalidSettingException(key, "$what must be between ${range.first} and ${range.last}")
        return number
    }

    /** `isValidIpAddress()` from util.js. */
    fun ipv4(key: String, value: String) {
        val parts = value.split('.')
        val numbers = parts.mapNotNull { part -> part.takeIf { it.matches(Regex("""\d{1,3}""")) }?.toInt() }
        val valid = value != "0.0.0.0" && value != "255.255.255.255" &&
            parts.size == 4 && numbers.size == 4 && numbers.all { it <= 255 } &&
            numbers[0] < 224 && numbers[0] != 127 && numbers[0] != 169 &&
            numbers[3] != 0 && numbers[3] != 255
        if (!valid) throw InvalidSettingException(key, "\"$value\" is not a valid host address")
    }
}

internal fun Map<String, String>.flag(key: String): Boolean? = this[key]?.let { it == "true" }

internal fun Boolean.bit(): Int = if (this) 1 else 0

internal const val WIFI_RESTART_WARNING =
    "Applying restarts the Wi-Fi radio. Wireless devices disconnect for a few seconds, " +
        "including this phone if it is connected over Wi-Fi."
