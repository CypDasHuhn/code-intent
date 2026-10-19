package dev.cint.core.model

import dev.cint.api.Contribution
import dev.cint.api.Intent
import dev.cint.api.Role
import dev.cint.api.Span

data class SourceFile(
    val path: String,
    val text: String,
)

internal data class RawAnnotation(
    val span: Span,
    val startByte: Int,
    val endByte: Int,
    val provenance: List<Contribution>,
) {
    val role: Role get() = provenance.first().role
    val intents: Set<Intent> get() = provenance.flatMap { it.intents }.toSet()
    val evidence: String get() = provenance.maxByOrNull { it.evidence.length }?.evidence.orEmpty()
    val source: String get() = provenance.map { it.source }.distinct().singleOrNull() ?: "mixed"
    val confidence: Double get() = provenance.maxOf { it.confidence }

    fun merge(other: RawAnnotation): RawAnnotation =
        copy(provenance = (provenance + other.provenance).distinct())

    companion object {
        fun of(contribution: Contribution): RawAnnotation =
            RawAnnotation(
                span = contribution.span,
                startByte = contribution.startByte,
                endByte = contribution.endByte,
                provenance = listOf(contribution),
            )
    }
}

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
