package dev.cint.suggest.model

import dev.cint.api.CandidateEmit
import dev.cint.api.Span

data class CandidateDocument(
    val path: String,
    val version: Int,
    val text: String,
    val knownIntents: Set<String>,
    val neighboringIntents: Set<String> = emptySet(),
    val language: String? = null,
)

data class SuggestionOptions(
    val providerId: String? = null,
    val model: String? = null,
    val maxCandidates: Int = 0,
    val maxEnrichmentCandidates: Int = 0,
    val promptVersion: String? = null,
    val excerptRadiusBytes: Int = SourceExcerpt.DEFAULT_RADIUS_BYTES,
    val allowGlobs: List<String> = emptyList(),
    val redactPatterns: List<String> = emptyList(),
)

data class CandidateGroup(
    val anchorId: String,
    val path: String,
    val contentHash: String,
    val span: Span,
    val startByte: Int,
    val endByte: Int,
    val candidates: List<CandidateEmit>,
)

enum class ProposalOperationKind {
    ADD,
    REMOVE,
}

data class ProposalOperation(
    val kind: ProposalOperationKind,
    val intent: String,
    val startByte: Int,
    val endByte: Int,
    val span: Span,
    val confidence: Double,
    val rationale: String? = null,
    val source: String? = null,
)

data class CandidateProposal(
    val proposalId: String,
    val anchorId: String,
    val path: String,
    val contentHash: String,
    val unitId: String,
    val span: Span,
    val startByte: Int,
    val endByte: Int,
    val operations: List<ProposalOperation>,
    val provider: String? = null,
    val model: String? = null,
    val promptVersion: String? = null,
) {
    val removes: List<ProposalOperation> get() = operations.filter { it.kind == ProposalOperationKind.REMOVE }
    val adds: List<ProposalOperation> get() = operations.filter { it.kind == ProposalOperationKind.ADD }
}
