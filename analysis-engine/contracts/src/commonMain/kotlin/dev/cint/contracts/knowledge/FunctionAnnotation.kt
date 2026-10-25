package dev.cint.contracts.knowledge

import dev.cint.contracts.data.Confidence
import dev.cint.contracts.data.Intent
import dev.cint.contracts.data.Provenance
import dev.cint.contracts.data.Status
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
    val path: String? = null,
    val patternId: String? = null,
    val evidence: List<String> = emptyList(),
) {
    val identityKey: String
        get() =
            if (scope == AnnotationScope.FILE) {
                "${selector.identityKey}|FILE|${path.orEmpty()}"
            } else {
                selector.identityKey
            }

    fun withStatus(
        newStatus: Status,
        updatedAt: Instant,
    ): FunctionAnnotation = copy(status = newStatus, updatedAt = updatedAt)

    fun withIntents(
        newIntents: Set<Intent>,
        updatedAt: Instant,
    ): FunctionAnnotation = copy(intents = newIntents.toSet(), updatedAt = updatedAt)

    fun withConfidence(
        newConfidence: Confidence,
        updatedAt: Instant,
    ): FunctionAnnotation = copy(confidence = newConfidence, updatedAt = updatedAt)

    companion object {
        fun create(
            selector: FunctionSelector,
            intents: Set<Intent>,
            scope: AnnotationScope = AnnotationScope.GLOBAL,
            provenance: Provenance = Provenance.USER,
            confidence: Confidence = Confidence.HIGH,
            status: Status = Status.SUGGESTED,
            createdAt: Instant,
            updatedAt: Instant = createdAt,
            path: String? = null,
            patternId: String? = null,
            evidence: List<String> = emptyList(),
        ): FunctionAnnotation =
            FunctionAnnotation(
                selector = selector,
                intents = intents.toSet(),
                scope = scope,
                provenance = provenance,
                confidence = confidence,
                status = status,
                createdAt = createdAt,
                updatedAt = updatedAt,
                path = path,
                patternId = patternId,
                evidence = evidence.toList(),
            )
    }
}

@Serializable
enum class AnnotationScope {
    GLOBAL,
    FILE,
    PROJECT,
}
