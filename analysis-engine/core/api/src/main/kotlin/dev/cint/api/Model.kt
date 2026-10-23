package dev.cint.api

typealias Intent = String

const val UNTAGGED_INTENT: Intent = "UNTAGGED"

/**
 * Pack API v5. Rule output is a `RuleEmit` list split into established `Emit`s
 * and review-only `CandidateEmit`s; v5 is a breaking change from v4.
 * The loader requires exact version equality, so v4 packs are rejected and
 * must be recompiled.
 *
 * The config descriptor surface (`ConfigSchemaProvider`, `ConfigSchema.describe`,
 * `RulePack.configDescriptor`, and the descriptor data classes) is additive at
 * v4: all new accessors have defaults and do not require a version bump.
 * Additive user overlays are opt-in through
 * `ConfigurableRulePack.configureAdditive` and `ConfigSchema.decodeAdditive`;
 * the default rejects additive config instead of replacing project values.
 * `Rule.optionalReads` is another additive v5 surface: it is a JVM default
 * accessor whose absent-owner dependency is ignored, while required `reads`
 * keep the hard unknown-dependency error. It does not require a version bump.
 */
const val CINT_API_VERSION: Int = 5

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
    val span: Span,
    val source: String,
    val confidence: Double,
    val startByte: Int,
    val endByte: Int,
    val certifiesReturn: Boolean = false,
    val provider: String? = null,
    val model: String? = null,
    val promptVersion: String? = null,
)

data class Annotation(
    val role: Role,
    val intents: Set<Intent>,
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
