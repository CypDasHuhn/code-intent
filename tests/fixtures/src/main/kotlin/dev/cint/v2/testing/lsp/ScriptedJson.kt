package dev.cint.v2.testing.lsp

internal object ScriptedJson {
    fun parse(text: String): Any? = Parser(text).parseDocument()

    fun write(value: Any?): String =
        buildString {
            writeValue(this, value)
        }

    private fun writeValue(
        out: StringBuilder,
        value: Any?,
    ) {
        when (value) {
            null -> {
                out.append("null")
            }

            is String -> {
                writeString(out, value)
            }

            is Boolean -> {
                out.append(if (value) "true" else "false")
            }

            is Number -> {
                writeNumber(out, value)
            }

            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((key, item) in value) {
                    if (!first) out.append(',')
                    first = false
                    writeString(out, key.toString())
                    out.append(':')
                    writeValue(out, item)
                }
                out.append('}')
            }

            is Iterable<*> -> {
                out.append('[')
                var first = true
                for (item in value) {
                    if (!first) out.append(',')
                    first = false
                    writeValue(out, item)
                }
                out.append(']')
            }

            is Array<*> -> {
                writeValue(out, value.asIterable())
            }

            is IntArray -> {
                writeValue(out, value.asIterable())
            }

            is LongArray -> {
                writeValue(out, value.asIterable())
            }

            is DoubleArray -> {
                writeValue(out, value.asIterable())
            }

            is BooleanArray -> {
                writeValue(out, value.asIterable())
            }

            else -> {
                writeString(out, value.toString())
            }
        }
    }

    private fun writeNumber(
        out: StringBuilder,
        value: Number,
    ) {
        when (value) {
            is Double -> {
                if (value.isFinite()) out.append(value) else out.append("null")
            }

            is Float -> {
                if (value.isFinite()) out.append(value) else out.append("null")
            }

            else -> {
                out.append(value.toString())
            }
        }
    }

    private fun writeString(
        out: StringBuilder,
        value: String,
    ) {
        out.append('"')
        for (char in value) {
            when (char) {
                '"' -> {
                    out.append("\\\"")
                }

                '\\' -> {
                    out.append("\\\\")
                }

                '\b' -> {
                    out.append("\\b")
                }

                '\u000C' -> {
                    out.append("\\f")
                }

                '\n' -> {
                    out.append("\\n")
                }

                '\r' -> {
                    out.append("\\r")
                }

                '\t' -> {
                    out.append("\\t")
                }

                else -> {
                    if (char < ' ') {
                        out.append("\\u")
                        out.append(char.code.toString(16).padStart(4, '0'))
                    } else {
                        out.append(char)
                    }
                }
            }
        }
        out.append('"')
    }

    private class Parser(
        private val text: String,
    ) {
        private var index = 0

        fun parseDocument(): Any? {
            val value = parseValue(0)
            skipWhitespace()
            if (index != text.length) {
                throw JsonParseException("Unexpected trailing content at offset $index")
            }
            return value
        }

        private fun parseValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw JsonParseException("JSON nesting exceeds $MAX_DEPTH")
            skipWhitespace()
            if (index >= text.length) throw JsonParseException("Unexpected end of JSON")
            return when (text[index]) {
                '{' -> parseObject(depth + 1)
                '[' -> parseArray(depth + 1)
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                '-', in '0'..'9' -> parseNumber()
                else -> throw JsonParseException("Unexpected character '${text[index]}' at offset $index")
            }
        }

        private fun parseObject(depth: Int): Map<String, Any?> {
            expect('{')
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek('}')) {
                index++
                return result
            }
            while (true) {
                skipWhitespace()
                if (!peek('"')) throw JsonParseException("Expected object key at offset $index")
                val key = parseString()
                skipWhitespace()
                expect(':')
                result[key] = parseValue(depth)
                skipWhitespace()
                when {
                    peek(',') -> {
                        index++
                    }

                    peek('}') -> {
                        index++
                        return result
                    }

                    else -> {
                        throw JsonParseException("Expected ',' or '}' at offset $index")
                    }
                }
            }
        }

        private fun parseArray(depth: Int): List<Any?> {
            expect('[')
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (peek(']')) {
                index++
                return result
            }
            while (true) {
                result.add(parseValue(depth))
                skipWhitespace()
                when {
                    peek(',') -> {
                        index++
                    }

                    peek(']') -> {
                        index++
                        return result
                    }

                    else -> {
                        throw JsonParseException("Expected ',' or ']' at offset $index")
                    }
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val result = StringBuilder()
            while (index < text.length) {
                val char = text[index++]
                when (char) {
                    '"' -> {
                        return result.toString()
                    }

                    '\\' -> {
                        result.append(parseEscape())
                    }

                    else -> {
                        if (char < ' ') throw JsonParseException("Unescaped control character at offset ${index - 1}")
                        result.append(char)
                    }
                }
            }
            throw JsonParseException("Unterminated string")
        }

        private fun parseEscape(): Char {
            if (index >= text.length) throw JsonParseException("Unterminated escape")
            return when (val escaped = text[index++]) {
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> parseUnicode()
                else -> throw JsonParseException("Invalid escape '\\$escaped' at offset ${index - 1}")
            }
        }

        private fun parseUnicode(): Char {
            if (index + 4 > text.length) throw JsonParseException("Truncated unicode escape")
            val digits = text.substring(index, index + 4)
            val value =
                digits.toIntOrNull(16)
                    ?: throw JsonParseException("Invalid unicode escape '$digits'")
            index += 4
            return value.toChar()
        }

        private fun parseNumber(): Number {
            val start = index
            if (peek('-')) index++
            if (index >= text.length) throw JsonParseException("Truncated number")
            if (peek('0')) {
                index++
            } else {
                if (text[index] !in '1'..'9') throw JsonParseException("Invalid number at offset $index")
                while (index < text.length && text[index] in '0'..'9') index++
            }
            var fractional = false
            if (peek('.')) {
                fractional = true
                index++
                if (index >= text.length || text[index] !in '0'..'9') throw JsonParseException("Invalid fraction")
                while (index < text.length && text[index] in '0'..'9') index++
            }
            if (peek('e') || peek('E')) {
                fractional = true
                index++
                if (peek('+') || peek('-')) index++
                if (index >= text.length || text[index] !in '0'..'9') throw JsonParseException("Invalid exponent")
                while (index < text.length && text[index] in '0'..'9') index++
            }
            val token = text.substring(start, index)
            return if (fractional) token.toDouble() else token.toLong()
        }

        private fun <T> parseLiteral(
            literal: String,
            value: T,
        ): T {
            if (!text.startsWith(literal, index)) throw JsonParseException("Invalid literal at offset $index")
            index += literal.length
            return value
        }

        private fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        private fun peek(char: Char): Boolean = index < text.length && text[index] == char

        private fun expect(char: Char) {
            if (!peek(char)) throw JsonParseException("Expected '$char' at offset $index")
            index++
        }

        private companion object {
            const val MAX_DEPTH = 128
        }
    }
}

internal class JsonParseException(
    message: String,
) : RuntimeException(message)

@Suppress("UNCHECKED_CAST")
internal fun Any?.asObjectOrNull(): Map<String, Any?>? = this as? Map<String, Any?>

internal fun Any?.asListOrNull(): List<Any?>? = this as? List<Any?>

internal fun Map<String, Any?>.string(key: String): String? = this[key] as? String

internal fun Map<String, Any?>.number(key: String): Number? = this[key] as? Number

internal fun Map<String, Any?>.int(key: String): Int? = number(key)?.toInt()

internal fun Map<String, Any?>.objectOrNull(key: String): Map<String, Any?>? = this[key].asObjectOrNull()

internal fun Map<String, Any?>.listOrNull(key: String): List<Any?>? = this[key].asListOrNull()

internal fun Map<String, Any?>.boolean(key: String): Boolean? = this[key] as? Boolean
