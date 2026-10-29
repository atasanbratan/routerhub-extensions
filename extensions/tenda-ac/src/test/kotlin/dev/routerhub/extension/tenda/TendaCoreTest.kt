package dev.routerhub.extension.tenda

import dev.routerhub.api.FirmwareParseException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TendaCoreTest {

    // --- Json --------------------------------------------------------------------------------

    @Test
    fun `json reads what the router sends`() {
        val value = Json.parse(
            """{"a":"1","n":5,"f":1.5,"neg":-3,"t":true,"z":null,"list":[0, 1, 2],"o":{"k":"v"},"s":"q\"uote\\ é \n"}""",
        ).asObject("test")
        assertEquals("1", value.str("a"))
        assertEquals(5L, value.num("n"))
        assertEquals("5", value.str("n"))
        assertEquals("1.5", value.str("f"))
        assertEquals(-3L, value.num("neg"))
        assertEquals("true", value.str("t"))
        assertTrue(value.isOn("t"))
        assertEquals("", value.str("z"))
        assertEquals("", value.str("missing"))
        assertEquals(listOf(0L, 1L, 2L), value["list"])
        assertEquals("v", (value["o"] as Map<*, *>)["k"])
        assertEquals("q\"uote\\ é \n", value.str("s"))
    }

    @Test
    fun `json reads arrays of objects and skips what is not an object`() {
        val list = Json.parse("""[{"upnpEn":"1"}, 7, {"upnpEn":"0"}]""").asList("test")
        assertEquals(3, list.size)
        val holder = mapOf("items" to list)
        assertEquals(listOf("1", "0"), holder.objects("items").map { it.str("upnpEn") })
    }

    @Test
    fun `json accepts unescaped control characters like the firmware sends them`() {
        // device names are not escaped by the router
        val name = Json.parse("{\"n\":\"tab\there\"}").asObject("test").str("n")
        assertEquals("tab\there", name)
    }

    @Test
    fun `the firmware's switches are one and zero`() {
        val o = Json.parse("""{"on":"1","off":"0","word":"true","empty":""}""").asObject("test")
        assertTrue(o.isOn("on"))
        assertFalse(o.isOn("off"))
        assertTrue(o.isOn("word"))
        assertFalse(o.isOn("empty"))
        assertFalse(o.isOn("missing"))
    }

    @Test
    fun `json refuses what is not json`() {
        for (bad in listOf("", "<html>", "{", "{\"a\":}", "[1,]x", "{\"a\":1} trailing", "nul", "\"open")) {
            try {
                Json.parse(bad)
                fail("expected a FirmwareParseException for: $bad")
            } catch (_: FirmwareParseException) {
            }
        }
    }

    // --- Crypto ------------------------------------------------------------------------------

    // Produced by Node's crypto (an independent AES-128-CBC) with the key below and the firmware's IV.
    private val key = "0123456789abcdef"

    @Test
    fun `encryption matches an independent implementation`() {
        assertEquals("93oyIXZiOqvt9x19NYXjBA==", TendaCrypto.encrypt("""{"errCode":0}""", key))
        assertEquals("KrhQNCLE3gFBfWfMgBzByzUB8T+Pc2mARrsv6SIhBrM=", TendaCrypto.encrypt("ssid=RouterHub-Test&power=high", key))
        assertEquals("WDo9IzkDDLjnlCe+RLl9daNcZza2+LYTFGLaiUHrZ/0=", TendaCrypto.encrypt("name=Café über&x=1", key))
        assertEquals("ShXKs949neipoFYu2BKPBA==", TendaCrypto.encrypt("", key))
    }

    @Test
    fun `decryption reverses it, with or without a trailing newline`() {
        assertEquals("""{"errCode":0}""", TendaCrypto.decrypt("93oyIXZiOqvt9x19NYXjBA==", key))
        assertEquals("""{"errCode":0}""", TendaCrypto.decrypt("93oyIXZiOqvt9x19NYXjBA==\r\n", key))
        assertEquals("name=Café über&x=1", TendaCrypto.decrypt("WDo9IzkDDLjnlCe+RLl9daNcZza2+LYTFGLaiUHrZ/0=", key))
    }

    @Test
    fun `decryption says no instead of throwing for anything that is not ours`() {
        assertNull("not base64", TendaCrypto.decrypt("this is not base64!", key))
        assertNull("not whole blocks", TendaCrypto.decrypt("AAAA", key))
        assertNull("a bad key length", TendaCrypto.decrypt("93oyIXZiOqvt9x19NYXjBA==", "short"))
        // a wrong key usually fails the padding check, and when it does not, the text is garbage
        val withOtherKey = TendaCrypto.decrypt("93oyIXZiOqvt9x19NYXjBA==", "fedcba9876543210")
        assertTrue(withOtherKey == null || withOtherKey != """{"errCode":0}""")
    }

    @Test
    fun `a stray byte after an answer is ignored, as the web UI's decoder ignores it`() {
        // seen on a real AC10: "...rw==!"
        assertEquals("""{"errCode":0}""", TendaCrypto.decrypt("93oyIXZiOqvt9x19NYXjBA==!", key))
        // three blocks need no padding, so stray letters right after the Base64 must be cut off too
        val plain = "x".repeat(40)
        val wire = TendaCrypto.encrypt(plain, key)
        assertEquals(64, wire.length)
        assertEquals(plain, TendaCrypto.decrypt(wire + "A", key))
        assertEquals(plain, TendaCrypto.decrypt("$wire!x", key))
    }

    @Test
    fun `a round trip works with a key the router hands out`() {
        val sign = "G97W0BqZfHrBAQfl"
        val plain = "list=192.168.0.20,80,8080,1~192.168.0.21,443,8443,0"
        assertEquals(plain, TendaCrypto.decrypt(TendaCrypto.encrypt(plain, sign), sign))
        assertNotEquals(TendaCrypto.encrypt(plain, sign), TendaCrypto.encrypt(plain, "3ItIHW0OEvEn71Tk"))
    }

    @Test
    fun `md5 is the lower case hex the login page sends`() {
        assertEquals("21232f297a57a5a743894a0e4a801fc3", TendaCrypto.md5("admin"))
        assertEquals("b9b86dad668f10fa8e4a1c4b29d104b3", TendaCrypto.md5("p@ss word"))
    }

    // --- Form --------------------------------------------------------------------------------

    @Test
    fun `values are escaped like encodeURIComponent`() {
        // each expectation is the output of the real JavaScript function
        assertEquals("Hello%20World!", Form.component("Hello World!"))
        assertEquals("a%26b%3Dc%2Fd%3Fe%23f", Form.component("a&b=c/d?e#f"))
        assertEquals("caf%C3%A9%20%E4%B8%AD%E6%96%87", Form.component("café 中文"))
        assertEquals("~'()*!-_.", Form.component("~'()*!-_."))
        assertEquals("%2B%25", Form.component("+%"))
        assertEquals("line1%0D%0Aline2", Form.component("line1\r\nline2"))
    }

    @Test
    fun `a form joins escaped values and leaves the keys alone`() {
        assertEquals("ssid=My%20Net&wrlPassword=p%26w&power=high", Form.of("ssid" to "My Net", "wrlPassword" to "p&w", "power" to "high"))
        assertEquals("", Form.of())
    }
}
