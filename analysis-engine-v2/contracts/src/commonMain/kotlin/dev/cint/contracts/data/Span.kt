package dev.cint.contracts.data

import kotlinx.serialization.Serializable

@Serializable
data class Span(
    val startLine: Int,
    val startCol: Int,
    val endLine: Int,
    val endCol: Int,
) {
    val startOffset: Int
        get() = startLine * 10000 + startCol
    val endOffset: Int
        get() = endLine * 10000 + endCol

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