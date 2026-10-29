package dev.routerhub.extension.tenda

import dev.routerhub.api.FirmwareParseException

/**
 * A small JSON reader. The router answers in plain JSON, and the host shares no JSON library with
 * extensions (`org.json` exists on Android only, which would also break plain-JVM unit tests).
 *
 * Objects become `Map<String, Any?>`, arrays `List<Any?>`, numbers `Long` or `Double`.
 */
internal object Json {
    fun parse(text: String): Any? {
        val reader = Reader(text)
        reader.skipWhitespace()
        val value = reader.readValue()
        reader.skipWhitespace()
        if (!reader.atEnd) throw reader.fail("unexpected characters after the value")
        return value
    }

    private class Reader(private val text: String) {
        private var pos = 0

        val atEnd: Boolean get() = pos >= text.length

        fun fail(why: String) = FirmwareParseException("The router sent invalid JSON ($why at character $pos)")

        fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        private fun peek(): Char = if (atEnd) throw fail("unexpected end") else text[pos]

        private fun expect(c: Char) {
            if (peek() != c) throw fail("expected '$c'")
            pos++
        }

        fun readValue(): Any? = when (val c = peek()) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> readString()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> if (c == '-' || c.isDigit()) readNumber() else throw fail("unexpected '$c'")
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!text.startsWith(word, pos)) throw fail("expected $word")
            pos += word.length
            return value
        }

        private fun readObject(): Map<String, Any?> {
            expect('{')
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return map
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') throw fail("expected a key")
                val key = readString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                map[key] = readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return map
                    }
                    else -> throw fail("expected ',' or '}'")
                }
            }
        }

        private fun readArray(): List<Any?> {
            expect('[')
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return list
            }
            while (true) {
                skipWhitespace()
                list += readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return list
                    }
                    else -> throw fail("expected ',' or ']'")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                val c = peek()
                pos++
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        val escaped = peek()
                        pos++
                        when (escaped) {
                            '"', '\\', '/' -> out.append(escaped)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                val hex = text.substring(pos, minOf(pos + 4, text.length))
                                val code = hex.takeIf { it.length == 4 }?.toIntOrNull(16) ?: throw fail("bad \\u escape")
                                out.append(code.toChar())
                                pos += 4
                            }
                            else -> throw fail("bad escape \\$escaped")
                        }
                    }
                    // Anything else is taken literally; the firmware does not escape every control character.
                    else -> out.append(c)
                }
            }
        }

        private fun readNumber(): Any {
            val start = pos
            if (peek() == '-') pos++
            while (!atEnd && text[pos].isDigit()) pos++
            var integral = true
            if (!atEnd && text[pos] == '.') {
                integral = false
                pos++
                while (!atEnd && text[pos].isDigit()) pos++
            }
            if (!atEnd && (text[pos] == 'e' || text[pos] == 'E')) {
                integral = false
                pos++
                if (!atEnd && (text[pos] == '+' || text[pos] == '-')) pos++
                while (!atEnd && text[pos].isDigit()) pos++
            }
            val token = text.substring(start, pos)
            return (if (integral) token.toLongOrNull() else null) ?: token.toDoubleOrNull() ?: throw fail("bad number '$token'")
        }
    }
}

internal typealias JsonObject = Map<String, Any?>

internal fun Any?.asObject(what: String): JsonObject =
    (this as? Map<*, *>)?.let { map -> map.entries.associate { it.key.toString() to it.value } }
        ?: throw FirmwareParseException("$what is not a JSON object")

internal fun Any?.asList(what: String): List<Any?> =
    (this as? List<*>) ?: throw FirmwareParseException("$what is not a JSON array")

/** The value as text: the firmware sends most numbers as strings and some as numbers, and `null` as absent. */
internal fun JsonObject.str(key: String): String = when (val value = this[key]) {
    null -> ""
    is Double -> if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
    else -> value.toString()
}

/** The firmware's switches are `"1"` and `"0"`. */
internal fun JsonObject.isOn(key: String): Boolean = str(key).let { it == "1" || it == "true" }

internal fun JsonObject.num(key: String): Long? = str(key).trim().toLongOrNull()

/** The objects of an array property; entries that are not objects are skipped. */
internal fun JsonObject.objects(key: String): List<JsonObject> =
    (this[key] as? List<*>).orEmpty().mapNotNull { item ->
        (item as? Map<*, *>)?.let { map -> map.entries.associate { it.key.toString() to it.value } }
    }
