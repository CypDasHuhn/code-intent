package dev.cint.api

typealias Intent = String

const val UNTAGGED_INTENT: Intent = "UNTAGGED"

/**
 * Pack API v3. Removing `Emit.nodeAnchor`, the DSL/Predicate helpers, and the
 * Scheduler/Schedule types is a breaking change from v2. The loader requires
 * exact version equality, so v2 packs are rejected and must be recompiled.
 * Artifact and live annotation payloads are unchanged.
 */
const val CINT_API_VERSION: Int = 3

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

/**
 * Reserved identity for future client-authored annotations (ticket 030).
 * The engine never populates it; serializers always emit the `nodeAnchor` key.
 */
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
    val certifiesReturn: Boolean = false,
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
    /** Reserved for client-authored annotations (030); the engine always leaves this null. */
    val nodeAnchor: NodeAnchor? = null,
) {
    init {
        require(provenance.isNotEmpty()) { "annotation requires at least one contributor" }
    }

    val rule: String get() = provenance.first().rule
}
