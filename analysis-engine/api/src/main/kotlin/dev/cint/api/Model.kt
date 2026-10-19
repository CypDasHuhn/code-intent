package dev.cint.api

typealias Intent = String

const val UNTAGGED_INTENT: Intent = "UNTAGGED"

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

@JvmInline
value class NodeAnchor(
    val id: String,
)

data class Contribution(
    val rule: String,
    val role: Role,
    val intents: Set<Intent>,
    val evidence: String,
    val span: Span,
    val source: String,
    val confidence: Double,
    val startByte: Int,
    val endByte: Int,
)

data class Annotation(
    val role: Role,
    val intents: Set<Intent>,
    val evidence: String,
    val span: Span,
    val source: String,
    val confidence: Double,
    val startByte: Int,
    val endByte: Int,
    val provenance: List<Contribution>,
    val nodeAnchor: NodeAnchor? = null,
) {
    init {
        require(provenance.isNotEmpty()) { "annotation requires at least one contributor" }
    }

    val rule: String get() = provenance.first().rule
}
