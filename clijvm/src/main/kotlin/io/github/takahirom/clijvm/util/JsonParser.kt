package io.github.takahirom.clijvm.util

/** Raised when text is not well-formed JSON. */
class JsonParseException(message: String) : RuntimeException(message)

/**
 * A minimal recursive-descent JSON parser producing plain Kotlin values
 * (`Map<String, Any?>`, `List<Any?>`, `String`, `Double`, `Boolean`, `null`).
 *
 * It is the read counterpart to [Json]; we keep both hand-rolled to avoid an
 * external serialization dependency. Callers that must tolerate malformed input
 * should catch [JsonParseException].
 */
object JsonParser {

    fun parse(text: String): Any? {
        val state = State(text)
        state.skipWhitespace()
        val value = state.parseValue()
        state.skipWhitespace()
        if (!state.atEnd()) throw JsonParseException("Unexpected trailing content at offset ${state.pos}")
        return value
    }

    private class State(private val s: String) {
        var pos = 0

        fun atEnd() = pos >= s.length
        private fun peek() = s[pos]

        fun skipWhitespace() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun parseValue(): Any? {
            skipWhitespace()
            if (atEnd()) throw JsonParseException("Unexpected end of input")
            return when (peek()) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't', 'f' -> parseBoolean()
                'n' -> parseNull()
                else -> parseNumber()
            }
        }

        private fun expect(c: Char) {
            if (atEnd() || s[pos] != c) throw JsonParseException("Expected '$c' at offset $pos")
            pos++
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            skipWhitespace()
            val result = LinkedHashMap<String, Any?>()
            if (!atEnd() && peek() == '}') {
                pos++
                return result
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                result[key] = parseValue()
                skipWhitespace()
                if (atEnd()) throw JsonParseException("Unterminated object")
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return result
                    }
                    else -> throw JsonParseException("Expected ',' or '}' at offset $pos")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            skipWhitespace()
            val result = ArrayList<Any?>()
            if (!atEnd() && peek() == ']') {
                pos++
                return result
            }
            while (true) {
                result.add(parseValue())
                skipWhitespace()
                if (atEnd()) throw JsonParseException("Unterminated array")
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return result
                    }
                    else -> throw JsonParseException("Expected ',' or ']' at offset $pos")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw JsonParseException("Unterminated string")
                when (val c = s[pos++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) throw JsonParseException("Unterminated escape sequence")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (pos + 4 > s.length) throw JsonParseException("Truncated unicode escape")
                                val hex = s.substring(pos, pos + 4)
                                pos += 4
                                sb.append(hex.toInt(16).toChar())
                            }
                            else -> throw JsonParseException("Invalid escape sequence '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun parseBoolean(): Boolean = when {
            s.startsWith("true", pos) -> {
                pos += 4
                true
            }
            s.startsWith("false", pos) -> {
                pos += 5
                false
            }
            else -> throw JsonParseException("Invalid literal at offset $pos")
        }

        private fun parseNull(): Any? {
            if (s.startsWith("null", pos)) {
                pos += 4
                return null
            }
            throw JsonParseException("Invalid literal at offset $pos")
        }

        private fun parseNumber(): Double {
            val start = pos
            while (!atEnd() && (peek().isDigit() || peek() in "+-.eE")) pos++
            val token = s.substring(start, pos)
            return token.toDoubleOrNull() ?: throw JsonParseException("Invalid number '$token'")
        }
    }
}
