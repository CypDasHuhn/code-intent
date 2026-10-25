package dev.cint.contracts.ports

import dev.cint.contracts.data.AnalysisResult
import dev.cint.contracts.facts.IndexedSource
import dev.cint.contracts.knowledge.KnowledgeSnapshot
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractResult
import kotlinx.serialization.Serializable

interface IntentPipeline {
    fun analyze(
        request: AnalysisRequest,
        source: IndexedSource,
        knowledge: KnowledgeSnapshot,
        cancellation: CancellationToken = CancellationToken.NONE,
    ): ContractResult<AnalysisResult>
}

@Serializable
data class AnalysisRequest(
    val targetPaths: List<String> = emptyList(),
    val language: String? = null,
    val options: Map<String, String> = emptyMap(),
    val forceFullAnalysis: Boolean = false,
)