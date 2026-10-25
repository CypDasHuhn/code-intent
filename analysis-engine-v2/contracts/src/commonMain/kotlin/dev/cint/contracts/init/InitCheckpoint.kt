package dev.cint.contracts.init

import dev.cint.contracts.knowledge.KnowledgeChange
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
enum class InitStatus {
    IN_PROGRESS,
    COMPLETE,
}

@Serializable
data class InitProposal(
    val id: String,
    val description: String,
    val changes: KnowledgeChange,
    val createdAt: Instant,
    val baseKnowledgeTimestamp: Long? = null,
    val appliedAt: Instant? = null,
    val appliedKnowledgeTimestamp: Long? = null,
) {
    init {
        require(id.isNotBlank()) { "InitProposal.id must not be blank" }
    }

    val isApplied: Boolean
        get() = appliedAt != null
}

@Serializable
data class InitCheckpoint(
    val workflowId: String,
    val status: InitStatus,
    val completedSteps: List<String> = emptyList(),
    val analyzedPaths: List<String> = emptyList(),
    val analyzedHashes: Map<String, String> = emptyMap(),
    val proposals: List<InitProposal> = emptyList(),
    val revision: Long = 0,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(workflowId.isNotBlank()) { "InitCheckpoint.workflowId must not be blank" }
        require(createdAt <= updatedAt) { "InitCheckpoint.createdAt must not be after updatedAt" }
    }

    fun isStepComplete(step: String): Boolean = step in completedSteps

    fun proposal(id: String): InitProposal? = proposals.firstOrNull { it.id == id }

    fun pendingProposals(): List<InitProposal> = proposals.filterNot { it.isApplied }
}
