package dev.cint.contracts.data

import kotlinx.serialization.Serializable

@Serializable
data class Candidate(
    val intents: Set<Intent>,
    val span: Span,
    val startByte: Int,
    val endByte: Int,
    val source: String,
    val confidence: Confidence,
    val reason: CandidateReason,
    val aiEligible: Boolean = true,
    val rationale: String? = null,
    val provider: String? = null,
    val model: String? = null,
    val promptVersion: String? = null,
    val path: String? = null,
) {
    fun toContribution(rule: String, role: Role = Role.SOURCE_INTENT): Contribution =
        Contribution(
            rule = rule,
            role = role,
            intents = intents,
            span = span,
            source = source,
            confidence = confidence,
            startByte = startByte,
            endByte = endByte,
            provenance = Provenance.RULE,
        )
}