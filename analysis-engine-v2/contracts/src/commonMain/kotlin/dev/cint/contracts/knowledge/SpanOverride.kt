package dev.cint.contracts.knowledge

import dev.cint.contracts.data.Confidence
import dev.cint.contracts.data.Intent
import dev.cint.contracts.data.Provenance
import dev.cint.contracts.data.Span
import dev.cint.contracts.data.Status
import kotlinx.datetime.Clock
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
    fun withStatus(newStatus: Status): SpanOverride =
        copy(status = newStatus, updatedAt = Clock.System.now())

    fun withIntents(newIntents: Set<Intent>): SpanOverride =
        copy(intents = newIntents, updatedAt = Clock.System.now())

    companion object {
        fun create(
            selector: SpanSelector,
            intents: Set<Intent>,
            provenance: Provenance = Provenance.USER,
            confidence: Confidence = Confidence.HIGH,
            status: Status = Status.SUGGESTED,
            rationale: String = "",
        ): SpanOverride = SpanOverride(
            selector = selector,
            intents = intents,
            provenance = provenance,
            confidence = confidence,
            status = status,
            rationale = rationale,
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now(),
        )
    }
}