package dev.cint.core.model

data class SourceFile(
    val path: String,
    val text: String,
)

class LineIndex(
    private val text: String,
) {
    private val lineStarts: IntArray =
        buildList {
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
