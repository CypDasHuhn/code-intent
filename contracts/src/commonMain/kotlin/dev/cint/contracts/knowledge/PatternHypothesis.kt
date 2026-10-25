package dev.cint.contracts.knowledge

import dev.cint.contracts.data.Confidence
import dev.cint.contracts.data.Intent
import dev.cint.contracts.data.Provenance
import dev.cint.contracts.data.Status
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class PatternHypothesis(
    val selector: PatternSelector,
    val scope: AnnotationScope,
    val evidence: List<String>,
    val examples: List<String>,
    val counterexamples: List<String>,
    val proposedIntents: Set<Intent>,
    val provenance: Provenance,
    val confidence: Confidence,
    val status: Status,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    fun withStatus(
        newStatus: Status,
        updatedAt: Instant,
    ): PatternHypothesis = copy(status = newStatus, updatedAt = updatedAt)

    companion object {
        fun create(
            selector: PatternSelector,
            scope: AnnotationScope,
            evidence: List<String>,
            examples: List<String>,
            counterexamples: List<String>,
            proposedIntents: Set<Intent>,
            provenance: Provenance = Provenance.AI_FALLBACK,
            confidence: Confidence = Confidence.LOW,
            status: Status = Status.SUGGESTED,
            createdAt: Instant,
            updatedAt: Instant = createdAt,
        ): PatternHypothesis =
            PatternHypothesis(
                selector = selector,
                scope = scope,
                evidence = evidence.toList(),
                examples = examples.toList(),
                counterexamples = counterexamples.toList(),
                proposedIntents = proposedIntents.toSet(),
                provenance = provenance,
                confidence = confidence,
                status = status,
                createdAt = createdAt,
                updatedAt = updatedAt,
            )
    }
}
