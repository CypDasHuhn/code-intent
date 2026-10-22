package dev.cint.suggest.model

import dev.cint.api.CandidateEmit
import dev.cint.api.Span

data class CandidateContext(
    val path: String,
    val version: Int,
    val candidate: CandidateEmit,
    val documentLength: Int,
    val neighboringIntents: Set<String> = emptySet(),
    val knownIntents: Set<String> = emptySet(),
    val promptVersion: String? = null,
    val language: String? = null,
    val sourceExcerpt: SourceExcerpt? = null,
)

data class SourceExcerpt(
    val text: String,
    val startByte: Int,
    val endByte: Int,
    val startLine: Int,
    val startCol: Int,
) {
    fun spanFor(
        startByte: Int,
        endByte: Int,
    ): Span? {
        if (startByte < this.startByte || endByte < startByte || endByte > this.endByte) return null
        val start = position(startByte)
        val end = position(endByte)
        return Span(start.first, start.second, end.first, end.second)
    }

    private fun position(offset: Int): Pair<Int, Int> {
        val bytes = text.toByteArray(Charsets.UTF_8)
        var line = startLine
        var col = startCol
        val limit = (offset - startByte).coerceIn(0, bytes.size)
        for (index in 0 until limit) {
            if (bytes[index].toInt() == '\n'.code) {
                line += 1
                col = 0
            } else {
                col += 1
            }
        }
        return line to col
    }

    companion object {
        const val DEFAULT_RADIUS_BYTES: Int = 400

        fun around(
            text: String,
            startByte: Int,
            endByte: Int,
            radiusBytes: Int = DEFAULT_RADIUS_BYTES,
        ): SourceExcerpt {
            val bytes = text.toByteArray(Charsets.UTF_8)
            val safeStart = startByte.coerceIn(0, bytes.size)
            val safeEnd = endByte.coerceIn(safeStart, bytes.size)
            val radius = radiusBytes.coerceAtLeast(0)
            var start = (safeStart - radius).coerceAtLeast(0)
            var end = (safeEnd + radius).coerceAtMost(bytes.size)
            while (start in 1..<safeStart && bytes[start].isContinuationByte()) {
                start -= 1
            }
            while (end < bytes.size && end > safeEnd && bytes[end].isContinuationByte()) {
                end += 1
            }
            val (line, col) = positionOf(bytes, start)
            return SourceExcerpt(
                text = String(bytes, start, end - start, Charsets.UTF_8),
                startByte = start,
                endByte = end,
                startLine = line,
                startCol = col,
            )
        }

        private fun positionOf(
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
}
