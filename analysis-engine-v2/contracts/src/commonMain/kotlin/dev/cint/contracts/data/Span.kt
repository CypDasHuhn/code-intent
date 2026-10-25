package dev.cint.contracts.data

import kotlinx.serialization.Serializable

@Serializable
data class Span(
    val startLine: Int,
    val startCol: Int,
    val endLine: Int,
    val endCol: Int,
) {
    init {
        require(startLine >= 0 && startCol >= 0 && endLine >= 0 && endCol >= 0) {
            "Span coordinates must not be negative: $this"
        }
        require(endLine > startLine || (endLine == startLine && endCol >= startCol)) {
            "Span end must not precede its start: $this"
        }
    }

    val startOffset: Long
        get() = packPosition(startLine, startCol)
    val endOffset: Long
        get() = packPosition(endLine, endCol)

    fun contains(other: Span): Boolean =
        this.startOffset <= other.startOffset && this.endOffset >= other.endOffset

    fun intersects(other: Span): Boolean =
        this.startOffset <= other.endOffset && this.endOffset >= other.startOffset

    fun union(other: Span): Span =
        Span(
            startLine = minOf(startLine, other.startLine),
            startCol = if (startLine < other.startLine) startCol else minOf(startCol, other.startCol),
            endLine = maxOf(endLine, other.endLine),
            endCol = if (endLine > other.endLine) endCol else maxOf(endCol, other.endCol),
        )

    companion object {
        val ZERO = Span(0, 0, 0, 0)
    }
}

private const val COLUMN_MASK = 0xFFFF_FFFFL

private fun packPosition(line: Int, column: Int): Long =
    (line.toLong() shl 32) or (column.toLong() and COLUMN_MASK)