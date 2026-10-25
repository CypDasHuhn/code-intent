package dev.cint.contracts.data

import kotlinx.serialization.Serializable

@Serializable
data class Contribution(
    val rule: String,
    val role: Role,
    val intents: Set<Intent>,
    val span: Span,
    val source: String,
    val confidence: Confidence,
    val startByte: Int,
    val endByte: Int,
    val provenance: Provenance = Provenance.RULE,
    val certifiesReturn: Boolean = false,
    val provider: String? = null,
    val model: String? = null,
    val promptVersion: String? = null,
)
