package dev.cint.server.live

import dev.cint.api.CandidateEmit
import dev.cint.core.candidate.CandidateIdentity
import dev.cint.core.user.UserAnnotationsFile
import dev.cint.server.LiveServer
import dev.cint.suggest.model.CandidateDocument
import dev.cint.suggest.model.CandidateGroup
import java.io.BufferedWriter
import java.util.concurrent.ConcurrentHashMap

internal fun LiveServer.publishCandidates(
    writer: BufferedWriter,
    doc: DocState,
    candidates: List<CandidateEmit>,
    analysisRevision: Long,
    expectedStateRevision: Long? = null,
    commitSkip: String? = null,
): Int? {
    val key = DocumentKey(writer, doc.uri)
    val lock = candidateLocks.computeIfAbsent(key) { Any() }
    synchronized(lock) {
        val currentAnalysis = analyses[key]?.analysisRevision ?: 0L
        val currentPublished = publishedCandidates[key]?.maxOfOrNull { it.analysisRevision } ?: 0L
        val currentState = candidateStateRevisions[key] ?: 0L
        if (
            analysisRevision < currentAnalysis ||
            analysisRevision < currentPublished ||
            (expectedStateRevision != null && expectedStateRevision != currentState)
        ) {
            return null
        }
        if (commitSkip != null) {
            sessionSkipped.computeIfAbsent(key) { ConcurrentHashMap.newKeySet() } += commitSkip
            candidatePublishBarrierForTest?.invoke()
        }
        val path = relativePath(doc.uri)
        val currentSessionSkips = sessionSkipped[key].orEmpty()
        val decisions = suggestionStore.load()
        val rejected = decisions.filter { it.decision == "reject" }.mapTo(linkedSetOf()) { it.candidateId }
        val confirmed = decisions.filter { it.decision == "confirm" }.mapTo(linkedSetOf()) { it.candidateId }
        val userAnnotations =
            if (confirmed.isEmpty()) {
                emptyList()
            } else {
                UserAnnotationsFile.loadTolerant(root) { error ->
                    System.err.println("cint user annotations: ${error.message ?: error.javaClass.name}")
                }
            }
        val resolvedAnchors =
            decisions
                .filter { it.decision == "confirm" && it.anchorId != null }
                .filter { decision ->
                    userAnnotations.any { annotation ->
                        annotation.path == decision.path &&
                            annotation.span ==
                            dev.cint.api.Span(
                                decision.startLine,
                                decision.startCol,
                                decision.endLine,
                                decision.endCol,
                            ) &&
                            annotation.intents.any { it in decision.intents }
                    }
                }.mapNotNullTo(linkedSetOf()) { it.anchorId }
        val document = CandidateDocument(path, doc.version, doc.text, emptySet())
        val groups = suggestionService.group(document, candidates)
        val publishedById = linkedMapOf<String, PublishedCandidate>()
        val publishedGroups = mutableListOf<Pair<CandidateGroup, List<PublishedCandidate>>>()
        for (group in groups) {
            val groupResolved =
                group.anchorId in resolvedAnchors ||
                    group.candidates.any { candidate ->
                        val candidateId = candidateId(path, group.contentHash, candidate)
                        candidateId in confirmed &&
                            userAnnotations.any { annotation ->
                                annotation.path == path &&
                                    annotation.span == candidate.span &&
                                    annotation.intents.any { it in candidate.intents }
                            }
                    }
            if (groupResolved) continue
            val kept =
                group.candidates.mapNotNull { candidate ->
                    val candidateId = candidateId(path, group.contentHash, candidate)
                    if (candidateId in rejected || candidateId in currentSessionSkips) {
                        null
                    } else {
                        PublishedCandidate(
                            candidateId = candidateId,
                            anchorId = group.anchorId,
                            path = path,
                            contentHash = group.contentHash,
                            version = doc.version,
                            analysisRevision = analysisRevision,
                            candidate = candidate,
                        ).also { publishedById[candidateId] = it }
                    }
                }
            if (kept.isNotEmpty()) publishedGroups += group to kept
        }
        val published = publishedById.values.toList()
        publishedCandidates[key] = published
        val revision = suggestionRevision.incrementAndGet()
        candidateStateRevisions[key] = revision
        val params =
            linkedMapOf<String, Any?>(
                "uri" to doc.uri,
                "version" to doc.version,
                "analysisRevision" to analysisRevision,
                "suggestionRevision" to revision,
                "groups" to publishedGroups.map { (group, kept) -> candidateGroupPayload(group, kept) },
                "candidates" to published.map(::candidatePayload),
            )
        write(
            writer,
            mapOf(
                "jsonrpc" to "2.0",
                "method" to "cint/publishCandidates",
                "params" to params,
            ),
        )
        return published.size
    }
}

private fun LiveServer.candidateId(
    path: String,
    contentHash: String,
    candidate: CandidateEmit,
): String =
    CandidateIdentity.of(
        path,
        contentHash,
        candidate.startByte,
        candidate.endByte,
        candidate.intents,
        candidate.reason.name,
    )

private fun LiveServer.candidateGroupPayload(
    group: CandidateGroup,
    kept: List<PublishedCandidate>,
): Map<String, Any?> =
    linkedMapOf(
        "anchorId" to group.anchorId,
        "path" to group.path,
        "contentHash" to group.contentHash,
        "startLine" to group.span.startLine,
        "startCol" to group.span.startCol,
        "endLine" to group.span.endLine,
        "endCol" to group.span.endCol,
        "startByte" to group.startByte,
        "endByte" to group.endByte,
        "candidates" to kept.map(::candidatePayload),
    )

private fun LiveServer.candidatePayload(candidate: PublishedCandidate): Map<String, Any?> {
    val emit = candidate.candidate
    return linkedMapOf<String, Any?>(
        "candidateId" to candidate.candidateId,
        "path" to candidate.path,
        "contentHash" to candidate.contentHash,
        "intents" to emit.intents.toList(),
        "source" to emit.source,
        "confidence" to emit.confidence,
        "startLine" to emit.span.startLine,
        "startCol" to emit.span.startCol,
        "endLine" to emit.span.endLine,
        "endCol" to emit.span.endCol,
        "startByte" to emit.startByte,
        "endByte" to emit.endByte,
        "reason" to emit.reason.name,
        "aiEligible" to emit.aiEligible,
    ).apply {
        emit.rationale?.let { put("rationale", it) }
        emit.promptVersion?.let { put("promptVersion", it) }
        emit.provider?.let { put("provider", it) }
        emit.model?.let { put("model", it) }
    }
}
