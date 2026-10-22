package dev.cint.server.live

import dev.cint.api.CandidateEmit
import dev.cint.api.ConfigFieldDescriptor
import dev.cint.core.runtime.scheduler.AnalysisOutput
import java.io.BufferedWriter
import java.util.concurrent.atomic.AtomicBoolean

internal data class ResolvedConfigField(
    val packId: String,
    val language: String,
    val field: ConfigFieldDescriptor,
)

internal data class DocState(
    val uri: String,
    val text: String,
    val version: Int,
    val language: String,
)

internal data class PublishedCandidate(
    val candidateId: String,
    val anchorId: String,
    val path: String,
    val contentHash: String,
    val version: Int,
    val analysisRevision: Long,
    val candidate: CandidateEmit,
)

internal data class DocumentKey(
    val owner: BufferedWriter,
    val uri: String,
)

internal data class CachedAnalysis(
    val output: AnalysisOutput,
    val version: Int,
    val textHash: String,
    val configRevision: Long,
    val analysisRevision: Long,
)

internal data class SuggestPass(
    val requestId: String?,
    val version: Int,
    val baseStateRevision: Long,
    val cancelled: AtomicBoolean = AtomicBoolean(false),
)
