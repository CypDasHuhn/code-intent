package dev.cint.contracts.ports

import dev.cint.contracts.data.AnalysisResult
import dev.cint.contracts.facts.IndexedSource
import dev.cint.contracts.knowledge.KnowledgeSnapshot
import dev.cint.contracts.knowledge.Selector
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory
import kotlinx.serialization.Serializable

interface IntentPipeline {
    fun analyze(
        request: AnalysisRequest,
        source: IndexedSource,
        knowledge: KnowledgeSnapshot,
        cancellation: CancellationToken = CancellationToken.NONE,
    ): ContractResult<AnalysisResult>

    fun resolveSelector(
        selector: Selector,
        source: IndexedSource,
        cancellation: CancellationToken = CancellationToken.NONE,
    ): ContractResult<SelectorResolution> =
        ResultFactory.failure(
            ContractError.PreconditionFailed("selector resolution is not supported by this pipeline"),
        )
}

@Serializable
data class AnalysisRequest(
    val targetPaths: List<String> = emptyList(),
    val language: String? = null,
    val options: Map<String, String> = emptyMap(),
    val forceFullAnalysis: Boolean = false,
)
