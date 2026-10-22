package dev.cint.suggest.session

import dev.cint.api.Span
import java.nio.charset.StandardCharsets

internal object SessionText {
    fun spanFor(
        text: String,
        startByte: Int,
        endByte: Int,
    ): Span? {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        if (startByte < 0 || endByte < startByte || endByte > bytes.size) return null
        val start = position(bytes, startByte)
        val end = position(bytes, endByte)
        return Span(start.first, start.second, end.first, end.second)
    }

    fun boundedRange(
        text: String,
        startByte: Int,
        endByte: Int,
        maxBytes: Int,
    ): Pair<Int, Int>? {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        if (startByte < 0 || endByte < startByte || startByte >= bytes.size) return null
        var start = startByte.coerceAtMost(bytes.size)
        var end = endByte.coerceAtMost(bytes.size)
        while (start in 1..<bytes.size && bytes[start].isContinuationByte()) start -= 1
        while (end < bytes.size && end > start && bytes[end].isContinuationByte()) end += 1
        val limit = maxBytes.coerceAtLeast(1)
        if (end - start > limit) {
            end = start + limit
            while (end < bytes.size && bytes[end].isContinuationByte()) end += 1
        }
        return start to end
    }

    private fun position(
        bytes: ByteArray,
        offset: Int,
    ): Pair<Int, Int> {
        var line = 0
        var col = 0
        for (index in 0 until offset.coerceIn(0, bytes.size)) {
            if (bytes[index].toInt() == '\n'.code) {
                line += 1
                col = 0
            } else {
                col += 1
            }
        }
        return line to col
    }

    private fun Byte.isContinuationByte(): Boolean = (toInt() and 0xC0) == 0x80
}
