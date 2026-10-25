package dev.cint.contracts.ports

import dev.cint.contracts.facts.SemanticRequest
import dev.cint.contracts.facts.SemanticResult
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory
import kotlinx.serialization.Serializable

interface SemanticProvider {
    val language: String

    fun request(request: SemanticRequest): ContractResult<SemanticResult>

    fun openDocument(
        uri: String,
        text: String,
        version: Long,
    ): ContractResult<Unit> = unsupportedDocumentSync()

    fun changeDocument(
        uri: String,
        text: String,
        version: Long,
    ): ContractResult<Unit> = unsupportedDocumentSync()

    fun closeDocument(uri: String): ContractResult<Unit> = unsupportedDocumentSync()

    fun shutdown(): ContractResult<Unit>

    private fun unsupportedDocumentSync(): ContractResult<Unit> =
        ResultFactory.failure(
            ContractError.PreconditionFailed("Semantic provider does not support document synchronization"),
        )
}

@Serializable
data class SemanticProviderConfig(
    val mode: ProviderMode,
    val command: String? = null,
    val endpoint: String? = null,
    val rootPath: String? = null,
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

    fun openDocument(
        uri: String,
        text: String,
        version: Long,
    ): ContractResult<Unit> = ResultFactory.success(Unit)

    fun changeDocument(
        uri: String,
        text: String,
        version: Long,
    ): ContractResult<Unit> = ResultFactory.success(Unit)

    fun closeDocument(uri: String): ContractResult<Unit> = ResultFactory.success(Unit)

    fun close(): ContractResult<Unit>
}

interface HostSemanticChannelProvider {
    fun open(config: HostChannelConfig): ContractResult<HostSemanticChannel>
}
