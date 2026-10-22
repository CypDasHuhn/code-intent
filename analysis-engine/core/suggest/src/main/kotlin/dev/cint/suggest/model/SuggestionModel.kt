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
    val maxCandidates: Int = 5,
    val maxEnrichmentCandidates: Int = 5,
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
