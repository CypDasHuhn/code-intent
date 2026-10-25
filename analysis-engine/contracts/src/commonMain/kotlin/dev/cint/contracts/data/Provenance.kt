package dev.cint.contracts.data

import kotlinx.serialization.Serializable

@Serializable
enum class Provenance {
    RULE,
    USER,
    PACK_DEFAULT,
    AI_FALLBACK,
    MCP_AGENT,
    DERIVED,
}
