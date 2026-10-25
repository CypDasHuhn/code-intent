package dev.cint.contracts.data

import kotlinx.serialization.Serializable

@Serializable
enum class CandidateReason {
    GAP,
    LOW_CONFIDENCE,
    PACK_DOMAIN,
    AMBIGUOUS_RECEIVER,
    AMBIGUOUS_INTENT,
    MISSING_SEMANTICS,
}
