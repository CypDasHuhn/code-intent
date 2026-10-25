package dev.cint.contracts.ports

import dev.cint.contracts.facts.SemanticRequest
import dev.cint.contracts.facts.SemanticResult
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractResult
import kotlinx.serialization.Serializable

interface SemanticProvider {
    val language: String

    fun request(request: SemanticRequest): ContractResult<SemanticResult>

    fun shutdown(): ContractResult<Unit>
}

@Serializable
data class SemanticProviderConfig(
    val mode: ProviderMode,
    val command: String? = null,
    val endpoint: String? = null,
    val hostChannelConfig: HostChannelConfig? = null,
    val languageOverrides: Map<String, String> = emptyMap(),
    val timeoutMs: Long = 5000,
    val initTimeoutMs: Long = 30000,
)

@Serializable
enum class ProviderMode {
    EDITOR,
    MANAGED,
    ATTACHED,
}

@Serializable
data class HostChannelConfig(
    val type: String,
    val config: Map<String, String> = emptyMap(),
)

interface HostSemanticChannel {
    fun send(request: SemanticRequest): ContractResult<SemanticResult>
    fun close(): ContractResult<Unit>
}