package dev.cint.ai

import dev.cint.contracts.facts.IndexedSource
import dev.cint.contracts.knowledge.KnowledgeChange
import dev.cint.contracts.knowledge.KnowledgeSnapshot
import dev.cint.contracts.knowledge.PackDefault
import dev.cint.contracts.util.ContractResult

data class FallbackAiRequest(
    val language: String?,
    val source: IndexedSource,
    val knowledge: KnowledgeSnapshot,
    val packDefaults: List<PackDefault>,
)

fun interface FallbackAiPolicy {
    fun shouldRun(request: FallbackAiRequest): Boolean

    companion object {
        val DISABLED: FallbackAiPolicy = FallbackAiPolicy { false }

        fun enabled(): FallbackAiPolicy = FallbackAiPolicy { true }
    }
}

fun interface FallbackAiHook {
    fun propose(request: FallbackAiRequest): ContractResult<KnowledgeChange>
}
