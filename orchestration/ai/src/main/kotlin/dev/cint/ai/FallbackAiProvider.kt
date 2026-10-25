package dev.cint.ai

import dev.cint.contracts.data.Confidence
import dev.cint.contracts.data.Intent
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractResult

data class AiFunctionPrompt(
    val name: String,
    val text: String,
    val startLine: Int,
    val startColumn: Int,
)

data class AiProviderRequest(
    val path: String?,
    val language: String?,
    val contentHash: String,
    val source: String,
    val functions: List<AiFunctionPrompt>,
    val knownIntents: Set<Intent>,
)

sealed interface AiProposal {
    val name: String
    val intents: Set<Intent>
    val confidence: Confidence
    val rationale: String

    data class Function(
        override val name: String,
        override val intents: Set<Intent>,
        override val confidence: Confidence,
        override val rationale: String = "",
    ) : AiProposal

    data class Span(
        override val name: String,
        override val intents: Set<Intent>,
        override val confidence: Confidence,
        override val rationale: String = "",
    ) : AiProposal
}

interface FallbackAiProvider {
    val id: String

    fun propose(
        request: AiProviderRequest,
        cancellation: CancellationToken = CancellationToken.NONE,
    ): ContractResult<List<AiProposal>>
}
