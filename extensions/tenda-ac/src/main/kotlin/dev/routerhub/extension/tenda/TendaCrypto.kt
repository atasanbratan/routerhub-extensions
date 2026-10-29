package dev.routerhub.extension.tenda

import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The scrambling the firmware's own web UI applies to every request and answer (`js/aes/util.js`):
 * AES-CBC with PKCS#7 padding, a fixed IV, and the 16 character `sign` that `goform/stokCfg` hands
 * out as the key, Base64 on the wire.
 *
 * This is obfuscation more than security (the key is served to whoever asks, and the IV never
 * changes), but the router refuses anything that is not wrapped this way, so it has to be
 * reproduced exactly.
 */
internal object TendaCrypto {
    private val IV = "EU5H62G9ICGRNI43".toByteArray(Charsets.US_ASCII)

    private fun cipher(mode: Int, sign: String): Cipher =
        Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(mode, SecretKeySpec(sign.toByteArray(Charsets.UTF_8), "AES"), IvParameterSpec(IV))
        }

    fun encrypt(plain: String, sign: String): String =
        Base64.getEncoder().encodeToString(cipher(Cipher.ENCRYPT_MODE, sign).doFinal(plain.toByteArray(Charsets.UTF_8)))

    /**
     * @return the plain text, or `null` if [text] was not produced with [sign]: not Base64, not a
     *   whole number of blocks, bad padding. A wrong key can still decrypt to garbage by chance (about
     *   one time in 256), so callers must also check that the result makes sense.
     */
    fun decrypt(text: String, sign: String): String? = try {
        String(cipher(Cipher.DECRYPT_MODE, sign).doFinal(Base64.getDecoder().decode(payload(text))), Charsets.UTF_8)
    } catch (_: GeneralSecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private val BASE64_PREFIX = Regex("^[A-Za-z0-9+/]*={0,2}")

    /**
     * The Base64 part of an answer. Now and then the router sends a stray byte after it (seen on a real
     * AC10: `...rw==!`, about one answer in 25), which a strict decoder refuses; the web UI's decoder
     * skips it. So keep the Base64 characters, cut to whole groups of four.
     */
    private fun payload(text: String): String {
        val prefix = BASE64_PREFIX.find(text.trim())?.value.orEmpty()
        return prefix.substring(0, prefix.length - prefix.length % 4)
    }

    /** Lower case hex MD5, which is how the login page sends passwords. */
    fun md5(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/**
 * Builds the URL-encoded bodies the pages post. Values use the same escaping as JavaScript's
 * `encodeURIComponent`, which the UI applies to every value; keys are sent as they are.
 */
internal object Form {
    private const val UNRESERVED_MARKS = "-_.!~*'()"

    fun component(value: String): String = buildString {
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val c = (byte.toInt() and 0xFF).toChar()
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in UNRESERVED_MARKS) {
                append(c)
            } else {
                append('%').append("%02X".format(byte.toInt() and 0xFF))
            }
        }
    }

    fun of(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (key, value) -> "$key=${component(value)}" }
}
