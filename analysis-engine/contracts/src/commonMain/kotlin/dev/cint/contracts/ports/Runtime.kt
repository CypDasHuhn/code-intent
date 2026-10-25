package dev.cint.contracts.ports

import dev.cint.contracts.data.AnalysisResult
import dev.cint.contracts.facts.IndexedSource
import dev.cint.contracts.facts.SourceInput
import dev.cint.contracts.knowledge.KnowledgeChange
import dev.cint.contracts.knowledge.KnowledgeSnapshot
import dev.cint.contracts.knowledge.PackDefault
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory
import kotlinx.serialization.Serializable

interface Runtime {
    fun analyze(
        request: AnalysisRequest,
        cancellation: CancellationToken = CancellationToken.NONE,
    ): ContractResult<AnalysisResult>

    fun analyzeSource(
        source: SourceInput,
        request: AnalysisRequest = AnalysisRequest(),
        cancellation: CancellationToken = CancellationToken.NONE,
    ): ContractResult<AnalysisResult> =
        when (source) {
            is SourceInput.FilePath -> {
                analyze(
                    request.copy(
                        targetPaths = listOf(source.path),
                        language = request.language ?: source.languageHint,
                    ),
                    cancellation,
                )
            }

            is SourceInput.InMemory -> {
                ResultFactory.failure(
                    ContractError.PreconditionFailed("runtime does not support in-memory analysis"),
                )
            }
        }

    fun packs(cancellation: CancellationToken = CancellationToken.NONE): ContractResult<List<RuntimePack>> =
        ResultFactory.success(emptyList())

    fun suggestDefaults(
        languages: Set<String>? = null,
        cancellation: CancellationToken = CancellationToken.NONE,
    ): ContractResult<List<PackDefault>> = ResultFactory.success(emptyList())

    fun indexContentHash(
        path: String,
        cancellation: CancellationToken = CancellationToken.NONE,
    ): ContractResult<String?> = ResultFactory.success(null)

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
data class RuntimePack(
    val manifest: PackManifest,
    val playbook: Playbook,
)

@Serializable
data class RuntimeStatus(
    val isInitialized: Boolean,
    val indexedFiles: Int,
    val knowledgeEntries: Int,
    val lastAnalysis: Long? = null,
    val errors: List<String> = emptyList(),
    val indexedPaths: List<String> = emptyList(),
    val knowledgeMaterialized: Boolean = false,
    val isRuntimeOpen: Boolean = true,
)
