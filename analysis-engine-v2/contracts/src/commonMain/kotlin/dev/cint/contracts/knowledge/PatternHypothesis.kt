package dev.cint.contracts.knowledge

import dev.cint.contracts.data.Confidence
import dev.cint.contracts.data.Intent
import dev.cint.contracts.data.Provenance
import dev.cint.contracts.data.Status
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class PatternHypothesis(
    val id: String,
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
    fun withStatus(newStatus: Status): PatternHypothesis =
        copy(status = newStatus, updatedAt = Clock.System.now())

    fun materialize(): List<FunctionAnnotation> =
        proposedIntents.map { intent ->
            FunctionAnnotation.create(
                selector = FunctionSelector(qualifiedName = id),
                intents = setOf(intent),
                scope = scope,
                provenance = Provenance.DERIVED,
                confidence = confidence,
                status = Status.ACCEPTED,
                rationale = "Materialized from pattern hypothesis $id",
            )
        }

    companion object {
        fun create(
            id: String,
            scope: AnnotationScope,
            evidence: List<String>,
            examples: List<String>,
            counterexamples: List<String>,
            proposedIntents: Set<Intent>,
            provenance: Provenance = Provenance.AI_FALLBACK,
            confidence: Confidence = Confidence.LOW,
            status: Status = Status.SUGGESTED,
        ): PatternHypothesis = PatternHypothesis(
            id = id,
            scope = scope,
            evidence = evidence,
            examples = examples,
            counterexamples = counterexamples,
            proposedIntents = proposedIntents,
            provenance = provenance,
            confidence = confidence,
            status = status,
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now(),
        )
    }
}