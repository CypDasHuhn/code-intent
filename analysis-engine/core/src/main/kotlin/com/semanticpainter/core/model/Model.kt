package com.semanticpainter.core.model

data class SourceFile(
    val path: String,
    val text: String,
)

enum class TagKind {
    INTERACTION,
    CONTROL_FLOW,
}

data class Span(
    val startLine: Int,
    val startCol: Int,
    val endLine: Int,
    val endCol: Int,
)

data class TaggedSpan(
    val tag: String,
    val kind: TagKind,
    val rule: String,
    val evidence: String,
    val span: Span,
    val source: String,
    val confidence: Double,
    val startByte: Int,
    val endByte: Int,
)

class LineIndex(private val text: String) {
    private val lineStarts: IntArray = buildList {
        add(0)
        text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) }
    }.toIntArray()

    fun lineOf(offset: Int): Int {
        var lo = 0
        var hi = lineStarts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (lineStarts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun colOf(offset: Int): Int = offset - lineStarts[lineOf(offset)]
}
