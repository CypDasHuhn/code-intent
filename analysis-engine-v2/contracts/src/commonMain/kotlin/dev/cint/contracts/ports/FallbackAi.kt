package dev.cint.contracts.ports

import dev.cint.contracts.facts.IndexedSource
import dev.cint.contracts.knowledge.KnowledgeChange
import dev.cint.contracts.knowledge.KnowledgeSnapshot
import dev.cint.contracts.knowledge.PackDefault
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractResult

data class FallbackAiRequest(
    val language: String?,
    val source: IndexedSource,
    val knowledge: KnowledgeSnapshot,
    val packDefaults: List<PackDefault>,
    val dirty: Boolean = false,
    val changedPaths: Set<String> = emptySet(),
)

fun interface FallbackAiPolicy {
    fun shouldRun(request: FallbackAiRequest): Boolean

    companion object {
        val DISABLED: FallbackAiPolicy = FallbackAiPolicy { false }

        val SELECTIVE: FallbackAiPolicy = FallbackAiPolicy { request ->
            !request.knowledge.materialized ||
                request.dirty ||
                request.changedPaths.isNotEmpty()
        }

        fun enabled(): FallbackAiPolicy = FallbackAiPolicy { true }

        fun selective(): FallbackAiPolicy = SELECTIVE
    }
}

fun interface FallbackAi {
    fun propose(
        request: FallbackAiRequest,
        cancellation: CancellationToken,
    ): ContractResult<KnowledgeChange>
}
