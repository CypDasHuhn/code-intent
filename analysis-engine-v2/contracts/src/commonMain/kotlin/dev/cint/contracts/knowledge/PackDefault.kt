package dev.cint.contracts.knowledge

import dev.cint.contracts.data.Confidence
import dev.cint.contracts.data.Intent
import dev.cint.contracts.data.Provenance
import dev.cint.contracts.data.Status
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class PackDefault(
    val selector: FunctionSelector,
    val intents: Set<Intent>,
    val packId: String,
    val packVersion: String,
    val provenance: Provenance = Provenance.PACK_DEFAULT,
    val confidence: Confidence = Confidence.MEDIUM,
    val status: Status = Status.SUGGESTED,
    val createdAt: Instant = Clock.System.now(),
) {
    fun toFunctionAnnotation(): FunctionAnnotation =
        FunctionAnnotation.create(
            selector = selector,
            intents = intents,
            provenance = provenance,
            confidence = confidence,
            status = status,
            packId = packId,
            packVersion = packVersion,
        )
}