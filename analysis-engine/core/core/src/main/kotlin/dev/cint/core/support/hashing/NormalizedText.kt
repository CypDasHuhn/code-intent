package dev.cint.core.support.hashing

data class SourceToken(
    val text: String,
    val startByte: Int,
    val endByte: Int,
)

object SourceNormalizer {
    fun normalize(
        text: String,
        stripComments: Boolean = true,
    ): String = tokens(text, stripComments).joinToString(" ")

    fun digest(text: String): String = contentHash(normalize(text))

    fun tokens(
        text: String,
        stripComments: Boolean = true,
    ): List<String> = tokenSpans(text, stripComments).map { it.text }

    fun normalizedLengthAtByte(
        text: String,
        byteOffset: Int,
    ): Int {
        val tokens = tokenSpans(text)
        if (tokens.isEmpty()) return 0
        val starts = normalizedStarts(tokens)
        if (byteOffset <= tokens.first().startByte) return 0
        for (index in tokens.indices) {
            val token = tokens[index]
            if (byteOffset in token.startByte..token.endByte) {
                val charOffset = charOffsetForByte(token, byteOffset - token.startByte)
                return starts[index] + charOffset
            }
            if (index + 1 < tokens.size) {
                val next = tokens[index + 1]
                if (byteOffset in (token.endByte + 1) until next.startByte) {
                    return starts[index] + token.text.length
                }
            }
        }
        return starts.last() + tokens.last().text.length
    }

    fun rawByteAtNormalizedOffset(
        text: String,
        normalizedOffset: Int,
        stripComments: Boolean = true,
    ): Int {
        if (normalizedOffset <= 0) {
            return tokenSpans(text, stripComments).firstOrNull()?.startByte ?: 0
        }
        val tokens = tokenSpans(text, stripComments)
        if (tokens.isEmpty()) return text.toByteArray(Charsets.UTF_8).size
        val starts = normalizedStarts(tokens)
        for (index in tokens.indices) {
            val token = tokens[index]
            val start = starts[index]
            val end = start + token.text.length
            if (normalizedOffset <= end) {
                val charOffset = (normalizedOffset - start).coerceIn(0, token.text.length)
                return token.startByte +
                    token.text
                        .substring(0, charOffset)
                        .toByteArray(Charsets.UTF_8)
                        .size
            }
            if (index + 1 < tokens.size && normalizedOffset < starts[index + 1]) {
                return token.endByte
            }
        }
        return text.toByteArray(Charsets.UTF_8).size
    }

    private fun normalizedStarts(tokens: List<SourceToken>): IntArray {
        val starts = IntArray(tokens.size)
        var offset = 0
        for (index in tokens.indices) {
            starts[index] = offset
            offset += tokens[index].text.length + 1
        }
        return starts
    }

    private fun charOffsetForByte(
        token: SourceToken,
        byteOffset: Int,
    ): Int {
        val bytes = token.text.toByteArray(Charsets.UTF_8)
        val prefix = String(bytes, 0, byteOffset.coerceIn(0, bytes.size), Charsets.UTF_8)
        return prefix.length.coerceAtMost(token.text.length)
    }

    fun tokenSpans(
        text: String,
        stripComments: Boolean = true,
    ): List<SourceToken> {
        val result = mutableListOf<SourceToken>()
        var index = 0
        var byteIndex = 0
        while (index < text.length) {
            val char = text[index]
            when {
                char.isWhitespace() -> {
                    byteIndex += byteLength(text, index, index + 1)
                    index += 1
                }

                stripComments && startsWith(text, index, "//") -> {
                    val end = text.indexOf('\n', index).let { if (it < 0) text.length else it }
                    byteIndex += byteLength(text, index, end)
                    index = end
                }

                stripComments && startsWith(text, index, "/*") -> {
                    val end = text.indexOf("*/", index + 2).let { if (it < 0) text.length else it + 2 }
                    byteIndex += byteLength(text, index, end)
                    index = end
                }

                char == '"' || char == '\'' -> {
                    val end = stringEnd(text, index, char)
                    val token = text.substring(index, end)
                    val bytes = byteLength(text, index, end)
                    result += SourceToken(token, byteIndex, byteIndex + bytes)
                    byteIndex += bytes
                    index = end
                }

                char.isLetterOrDigit() || char == '_' || char == '$' -> {
                    val start = index
                    while (index < text.length && isWordPart(text[index])) index += 1
                    val token = text.substring(start, index)
                    val bytes = byteLength(text, start, index)
                    result += SourceToken(token, byteIndex, byteIndex + bytes)
                    byteIndex += bytes
                }

                else -> {
                    val operator = operatorAt(text, index)
                    val bytes = operator.toByteArray(Charsets.UTF_8).size
                    result += SourceToken(operator, byteIndex, byteIndex + bytes)
                    byteIndex += bytes
                    index += operator.length
                }
            }
        }
        return result
    }

    private fun byteLength(
        text: String,
        start: Int,
        end: Int,
    ): Int = text.substring(start, end).toByteArray(Charsets.UTF_8).size

    private fun startsWith(
        text: String,
        index: Int,
        value: String,
    ): Boolean = text.regionMatches(index, value, 0, value.length)

    private fun isWordPart(char: Char): Boolean = char.isLetterOrDigit() || char == '_' || char == '$'

    private fun stringEnd(
        text: String,
        start: Int,
        quote: Char,
    ): Int {
        if (quote == '"' && startsWith(text, start, "\"\"\"")) {
            val end = text.indexOf("\"\"\"", start + 3)
            return if (end < 0) text.length else end + 3
        }
        var index = start + 1
        while (index < text.length) {
            val char = text[index]
            if (char == '\\') {
                index += 2
                continue
            }
            if (char == quote) return index + 1
            index += 1
        }
        return text.length
    }

    private fun operatorAt(
        text: String,
        index: Int,
    ): String {
        for (operator in MULTI_CHAR_OPERATORS) {
            if (startsWith(text, index, operator)) return operator
        }
        return text[index].toString()
    }

    private val MULTI_CHAR_OPERATORS =
        listOf(
            "...",
            "===",
            "!==",
            "??=",
            "?.",
            "?:",
            "::",
            "->",
            "=>",
            "==",
            "!=",
            "<=",
            ">=",
            "&&",
            "||",
            "++",
            "--",
            "+=",
            "-=",
            "*=",
            "/=",
            "%=",
            "..",
            "!!",
        )
}

fun normalizedSourceText(text: String): String = SourceNormalizer.normalize(text)
