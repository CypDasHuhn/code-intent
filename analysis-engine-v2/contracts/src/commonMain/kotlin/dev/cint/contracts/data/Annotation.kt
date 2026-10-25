package dev.cint.contracts.data

import kotlinx.serialization.Serializable

@Serializable
data class Annotation(
    val role: Role,
    val intents: Set<Intent>,
    val span: Span,
    val source: String,
    val confidence: Confidence,
    val startByte: Int,
    val endByte: Int,
    val provenance: List<Contribution>,
    val nodeAnchor: NodeAnchor? = null,
) {
    init {
        require(provenance.isNotEmpty()) { "annotation requires at least one contributor" }
    }

    val rule: String
        get() = provenance.first().rule

    val primaryProvenance: Provenance
        get() = provenance.first().provenance

    fun withNodeAnchor(anchor: NodeAnchor): Annotation =
        copy(nodeAnchor = anchor)

    companion object {
        fun fromContribution(contribution: Contribution, allContributions: List<Contribution>): Annotation =
            Annotation(
                role = contribution.role,
                intents = contribution.intents,
                span = contribution.span,
                source = contribution.source,
                confidence = contribution.confidence,
                startByte = contribution.startByte,
                endByte = contribution.endByte,
                provenance = allContributions,
            )
    }
}