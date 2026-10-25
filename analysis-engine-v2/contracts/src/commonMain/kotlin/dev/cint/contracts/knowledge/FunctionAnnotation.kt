package dev.cint.contracts.knowledge

import dev.cint.contracts.data.Confidence
import dev.cint.contracts.data.Intent
import dev.cint.contracts.data.Provenance
import dev.cint.contracts.data.Status
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class FunctionAnnotation(
    val selector: FunctionSelector,
    val intents: Set<Intent>,
    val scope: AnnotationScope,
    val provenance: Provenance,
    val confidence: Confidence,
    val status: Status,
    val createdAt: Instant,
    val updatedAt: Instant,
    val packId: String? = null,
    val packVersion: String? = null,
    val rationale: String? = null,
) {
    fun withStatus(newStatus: Status): FunctionAnnotation =
        copy(status = newStatus, updatedAt = Clock.System.now())

    fun withIntents(newIntents: Set<Intent>): FunctionAnnotation =
        copy(intents = newIntents, updatedAt = Clock.System.now())

    fun withConfidence(newConfidence: Confidence): FunctionAnnotation =
        copy(confidence = newConfidence, updatedAt = Clock.System.now())

    companion object {
        fun create(
            selector: FunctionSelector,
            intents: Set<Intent>,
            scope: AnnotationScope = AnnotationScope.GLOBAL,
            provenance: Provenance = Provenance.USER,
            confidence: Confidence = Confidence.HIGH,
            status: Status = Status.SUGGESTED,
            packId: String? = null,
            packVersion: String? = null,
            rationale: String? = null,
        ): FunctionAnnotation = FunctionAnnotation(
            selector = selector,
            intents = intents,
            scope = scope,
            provenance = provenance,
            confidence = confidence,
            status = status,
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now(),
            packId = packId,
            packVersion = packVersion,
            rationale = rationale,
        )
    }
}

@Serializable
enum class AnnotationScope {
    GLOBAL,
    FILE,
    PROJECT,
}