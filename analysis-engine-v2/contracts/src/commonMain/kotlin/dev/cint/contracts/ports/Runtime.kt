package dev.cint.contracts.ports

import dev.cint.contracts.data.AnalysisResult
import dev.cint.contracts.facts.IndexedSource
import dev.cint.contracts.knowledge.KnowledgeSnapshot
import dev.cint.contracts.knowledge.KnowledgeChange
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractResult
import kotlinx.serialization.Serializable

interface Runtime {
    fun analyze(
        request: AnalysisRequest,
        cancellation: CancellationToken = CancellationToken.NONE,
    ): ContractResult<AnalysisResult>

    fun knowledge(): KnowledgeStore

    fun materializeDefaults(
        changes: KnowledgeChange.MaterializeDefaults,
        cancellation: CancellationToken = CancellationToken.NONE,
    ): ContractResult<Unit>

    fun reload(cancellation: CancellationToken = CancellationToken.NONE): ContractResult<Unit>

    fun publish(
        result: AnalysisResult,
        cancellation: CancellationToken = CancellationToken.NONE,
    ): ContractResult<Unit>

    fun status(cancellation: CancellationToken = CancellationToken.NONE): ContractResult<RuntimeStatus>

    fun shutdown(): ContractResult<Unit>
}

@Serializable
data class RuntimeStatus(
    val isInitialized: Boolean,
    val indexedFiles: Int,
    val knowledgeEntries: Int,
    val lastAnalysis: Long? = null,
    val errors: List<String> = emptyList(),
)