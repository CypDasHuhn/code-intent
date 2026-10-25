package dev.cint.contracts.mcp

import dev.cint.contracts.data.AnalysisResult
import dev.cint.contracts.knowledge.FunctionAnnotation
import dev.cint.contracts.knowledge.KnowledgeChange
import dev.cint.contracts.knowledge.KnowledgeSnapshot
import dev.cint.contracts.knowledge.PackDefault
import dev.cint.contracts.knowledge.PatternHypothesis
import dev.cint.contracts.knowledge.SpanOverride
import dev.cint.contracts.ports.RuntimeStatus
import dev.cint.contracts.util.currentTimeMillis
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
sealed interface McpRequest {
    @Serializable
    @SerialName("KnowledgeSnapshot")
    data class KnowledgeSnapshot(
        val requestId: String,
    ) : McpRequest

    @Serializable
    @SerialName("FunctionAnnotationsList")
    data class FunctionAnnotationsList(
        val requestId: String,
        val filter: FunctionAnnotationFilter? = null,
    ) : McpRequest

    @Serializable
    @SerialName("FunctionAnnotationsSet")
    data class FunctionAnnotationsSet(
        val requestId: String,
        val annotation: FunctionAnnotation,
    ) : McpRequest

    @Serializable
    @SerialName("FunctionAnnotationsRemove")
    data class FunctionAnnotationsRemove(
        val requestId: String,
        val selector: dev.cint.contracts.knowledge.FunctionSelector,
    ) : McpRequest

    @Serializable
    @SerialName("SpanOverridesList")
    data class SpanOverridesList(
        val requestId: String,
        val filter: SpanOverrideFilter? = null,
    ) : McpRequest

    @Serializable
    @SerialName("SpanOverridesSet")
    data class SpanOverridesSet(
        val requestId: String,
        val override: SpanOverride,
    ) : McpRequest

    @Serializable
    @SerialName("SpanOverridesRemove")
    data class SpanOverridesRemove(
        val requestId: String,
        val selector: dev.cint.contracts.knowledge.SpanSelector,
    ) : McpRequest

    @Serializable
    @SerialName("PatternsList")
    data class PatternsList(
        val requestId: String,
    ) : McpRequest

    @Serializable
    @SerialName("PatternsPropose")
    data class PatternsPropose(
        val requestId: String,
        val hypothesis: PatternHypothesis,
    ) : McpRequest

    @Serializable
    @SerialName("PatternsValidate")
    data class PatternsValidate(
        val requestId: String,
        val id: String,
        val accept: Boolean,
    ) : McpRequest

    @Serializable
    @SerialName("AnalysisRun")
    data class AnalysisRun(
        val requestId: String,
        val targetPaths: List<String> = emptyList(),
        val options: Map<String, String> = emptyMap(),
    ) : McpRequest

    @Serializable
    @SerialName("RepoStatus")
    data class RepoStatus(
        val requestId: String,
    ) : McpRequest

    @Serializable
    @SerialName("RepoIndex")
    data class RepoIndex(
        val requestId: String,
    ) : McpRequest

    @Serializable
    @SerialName("KnowledgeMaterializeDefaults")
    data class KnowledgeMaterializeDefaults(
        val requestId: String,
        val defaults: List<PackDefault>,
    ) : McpRequest

    @Serializable
    @SerialName("KnowledgeSuggestDefaults")
    data class KnowledgeSuggestDefaults(
        val requestId: String,
    ) : McpRequest

    @Serializable
    @SerialName("ChangesPropose")
    data class ChangesPropose(
        val requestId: String,
        val description: String,
        val changes: List<KnowledgeChange>,
    ) : McpRequest

    @Serializable
    @SerialName("ChangesApply")
    data class ChangesApply(
        val requestId: String,
        val proposalId: String,
    ) : McpRequest
}

@Serializable
sealed interface McpResponse {
    @Serializable
    @SerialName("KnowledgeSnapshotResponse")
    data class KnowledgeSnapshotResponse(
        val requestId: String,
        val snapshotData: KnowledgeSnapshot?,
        val error: String? = null,
    ) : McpResponse

    @Serializable
    @SerialName("FunctionAnnotationsList")
    data class FunctionAnnotationsList(
        val requestId: String,
        val annotations: List<FunctionAnnotation>,
        val error: String? = null,
    ) : McpResponse

    @Serializable
    @SerialName("FunctionAnnotationsSet")
    data class FunctionAnnotationsSet(
        val requestId: String,
        val annotation: FunctionAnnotation?,
        val error: String? = null,
    ) : McpResponse

    @Serializable
    @SerialName("FunctionAnnotationsRemove")
    data class FunctionAnnotationsRemove(
        val requestId: String,
        val success: Boolean,
        val error: String? = null,
    ) : McpResponse

    @Serializable
    @SerialName("SpanOverridesList")
    data class SpanOverridesList(
        val requestId: String,
        val overrides: List<SpanOverride>,
        val error: String? = null,
    ) : McpResponse

    @Serializable
    @SerialName("SpanOverridesSet")
    data class SpanOverridesSet(
        val requestId: String,
        val override: SpanOverride?,
        val error: String? = null,
    ) : McpResponse

    @Serializable
    @SerialName("SpanOverridesRemove")
    data class SpanOverridesRemove(
        val requestId: String,
        val success: Boolean,
        val error: String? = null,
    ) : McpResponse

    @Serializable
    @SerialName("PatternsList")
    data class PatternsList(
        val requestId: String,
        val hypotheses: List<PatternHypothesis>,
        val error: String? = null,
    ) : McpResponse

    @Serializable
    @SerialName("PatternsPropose")
    data class PatternsPropose(
        val requestId: String,
        val hypothesis: PatternHypothesis?,
        val error: String? = null,
    ) : McpResponse

    @Serializable
    @SerialName("PatternsValidate")
    data class PatternsValidate(
        val requestId: String,
        val success: Boolean,
        val error: String? = null,
    ) : McpResponse

    @Serializable
    @SerialName("AnalysisRun")
    data class AnalysisRun(
        val requestId: String,
        val result: AnalysisResult?,
        val error: String? = null,
    ) : McpResponse

    @Serializable
    @SerialName("RepoStatus")
    data class RepoStatus(
        val requestId: String,
        val status: RuntimeStatus?,
        val error: String? = null,
    ) : McpResponse

    @Serializable
    @SerialName("RepoIndex")
    data class RepoIndex(
        val requestId: String,
        val indexedFiles: Int,
        val error: String? = null,
    ) : McpResponse

    @Serializable
    @SerialName("KnowledgeMaterializeDefaults")
    data class KnowledgeMaterializeDefaults(
        val requestId: String,
        val success: Boolean,
        val error: String? = null,
    ) : McpResponse

    @Serializable
    @SerialName("KnowledgeSuggestDefaults")
    data class KnowledgeSuggestDefaults(
        val requestId: String,
        val defaults: List<PackDefault>,
        val error: String? = null,
    ) : McpResponse

    @Serializable
    @SerialName("ChangesPropose")
    data class ChangesPropose(
        val requestId: String,
        val proposalId: String?,
        val error: String? = null,
    ) : McpResponse

    @Serializable
    @SerialName("ChangesApply")
    data class ChangesApply(
        val requestId: String,
        val success: Boolean,
        val error: String? = null,
    ) : McpResponse

    @Serializable
    @SerialName("Error")
    data class Error(
        val requestId: String,
        val code: String,
        val message: String,
    ) : McpResponse
}

@Serializable
data class FunctionAnnotationFilter(
    val provenance: List<dev.cint.contracts.data.Provenance>? = null,
    val status: List<dev.cint.contracts.data.Status>? = null,
    val intents: List<String>? = null,
)

@Serializable
data class SpanOverrideFilter(
    val provenance: List<dev.cint.contracts.data.Provenance>? = null,
    val status: List<dev.cint.contracts.data.Status>? = null,
    val path: String? = null,
)

@Serializable
data class McpEnvelope(
    val request: McpRequest?,
    val response: McpResponse?,
    val notification: McpNotification?,
    val timestamp: Long = currentTimeMillis(),
)

@Serializable
sealed interface McpNotification {
    @Serializable
    @SerialName("AnalysisProgress")
    data class AnalysisProgress(
        val progress: Double,
        val message: String,
    ) : McpNotification

    @Serializable
    @SerialName("KnowledgeChanged")
    data class KnowledgeChanged(
        val changeType: String,
        val identifier: String,
    ) : McpNotification

    @Serializable
    @SerialName("IndexUpdated")
    data class IndexUpdated(
        val paths: List<String>,
    ) : McpNotification
}