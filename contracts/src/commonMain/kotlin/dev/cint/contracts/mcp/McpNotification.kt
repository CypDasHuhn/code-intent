package dev.cint.contracts.mcp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
