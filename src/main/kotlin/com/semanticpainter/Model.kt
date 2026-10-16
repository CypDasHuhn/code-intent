package com.semanticpainter

data class DeclarationFact(
    val name: String,
    val initCallPath: String?,
)

data class CallFact(
    val calleeName: String,
    val receiverText: String?,
    val callPath: String,
    val args: List<String>,
    val startOffset: Int,
    val endOffset: Int,
    val inCatch: Boolean,
)

data class SourceFacts(
    val path: String,
    val text: String,
    val imports: List<String>,
    val declarations: List<DeclarationFact>,
    val calls: List<CallFact>,
)

data class Span(
    val tag: String,
    val rule: String,
    val evidence: String,
    val startLine: Int,
    val startCol: Int,
    val endLine: Int,
    val endCol: Int,
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
