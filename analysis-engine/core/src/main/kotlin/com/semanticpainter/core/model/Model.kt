package com.semanticpainter.core.model

const val UNTAGGED_INTENT = "UNTAGGED"

data class SourceFile(
    val path: String,
    val text: String,
)

enum class Role {
    SOURCE_INTENT,
    DERIVATIVE_USE,
    SCAFFOLD,
}

data class Span(
    val startLine: Int,
    val startCol: Int,
    val endLine: Int,
    val endCol: Int,
)

data class RawAnnotation(
    val role: Role,
    val intents: Set<String>,
    val rule: String,
    val evidence: String,
    val span: Span,
    val source: String,
    val confidence: Double,
    val startByte: Int,
    val endByte: Int,
) {
    fun merge(other: RawAnnotation): RawAnnotation =
        copy(
            intents = intents + other.intents,
            rule = if (rule == other.rule) rule else "$rule+${other.rule}",
            evidence = if (evidence.length >= other.evidence.length) evidence else other.evidence,
            source = if (source == other.source) source else "mixed",
            confidence = maxOf(confidence, other.confidence),
        )
}

data class Annotation(
    val role: Role,
    val intents: Set<String>,
    val rule: String,
    val evidence: String,
    val span: Span,
    val source: String,
    val confidence: Double,
    val startByte: Int,
    val endByte: Int,
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
