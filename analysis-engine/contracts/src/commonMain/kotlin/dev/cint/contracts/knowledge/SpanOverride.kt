package dev.cint.contracts.knowledge

import dev.cint.contracts.data.Confidence
import dev.cint.contracts.data.Intent
import dev.cint.contracts.data.Provenance
import dev.cint.contracts.data.Status
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class SpanOverride(
    val selector: SpanSelector,
    val intents: Set<Intent>,
    val provenance: Provenance,
    val confidence: Confidence,
    val status: Status,
    val rationale: String,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    fun withStatus(
        newStatus: Status,
        updatedAt: Instant,
    ): SpanOverride = copy(status = newStatus, updatedAt = updatedAt)

    fun withIntents(
        newIntents: Set<Intent>,
        updatedAt: Instant,
    ): SpanOverride = copy(intents = newIntents.toSet(), updatedAt = updatedAt)

    companion object {
        fun create(
            selector: SpanSelector,
            intents: Set<Intent>,
            provenance: Provenance = Provenance.USER,
            confidence: Confidence = Confidence.HIGH,
            status: Status = Status.SUGGESTED,
            rationale: String = "",
            createdAt: Instant,
            updatedAt: Instant = createdAt,
        ): SpanOverride =
            SpanOverride(
                selector = selector,
                intents = intents.toSet(),
                provenance = provenance,
                confidence = confidence,
                status = status,
                rationale = rationale,
                createdAt = createdAt,
                updatedAt = updatedAt,
            )
    }
}
