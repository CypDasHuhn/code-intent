package dev.cint.server.review

import com.fasterxml.jackson.databind.JsonNode
import dev.cint.core.user.UserAnnotation
import dev.cint.core.user.UserAnnotationsFile
import dev.cint.server.LiveServer
import dev.cint.server.SuggestionDecision
import dev.cint.server.live.DocumentKey
import dev.cint.server.live.docsFor
import dev.cint.server.live.nextAnalysisRevision
import dev.cint.server.live.relativePath
import dev.cint.server.live.republishUri
import java.io.BufferedWriter
import java.time.Instant

internal fun LiveServer.handleCandidateDecision(
    writer: BufferedWriter,
    params: JsonNode?,
    id: JsonNode?,
) {
    val uri = params?.get("uri")?.asText() ?: return sendError(writer, id, "uri is required")
    val candidateId = params.get("candidateId")?.asText() ?: return sendError(writer, id, "candidateId is required")
    val decision = params.get("decision")?.asText()?.lowercase() ?: return sendError(writer, id, "decision is required")
    if (decision !in setOf("confirm", "reject", "skip")) {
        return sendError(writer, id, "unknown decision: $decision")
    }
    val doc = docsFor(writer)[uri] ?: return sendError(writer, id, "document is not open: $uri")
    val version = params.get("version")?.asInt() ?: doc.version
    if (version != doc.version) {
        return respond(writer, id, mapOf("accepted" to false, "stale" to true))
    }
    val key = DocumentKey(writer, uri)
    val baseStateRevision = candidateStateRevisions[key] ?: 0L
    val candidate =
        publishedCandidates[key]
            .orEmpty()
            .firstOrNull { it.candidateId == candidateId }
            ?: return respond(writer, id, mapOf("accepted" to false, "missing" to true))
    val currentHash = suggestionService.anchorHash(doc.text, candidate.candidate)
    if (currentHash != candidate.contentHash) {
        return respond(writer, id, mapOf("accepted" to false, "stale" to true))
    }
    when (decision) {
        "skip" -> {
            val current = publishedCandidates[key].orEmpty()
            val analysisRevision = current.maxOfOrNull { it.analysisRevision } ?: nextAnalysisRevision(key)
            candidateDecisionBarrierForTest?.invoke()
            val published =
                publishCandidates(
                    writer,
                    doc,
                    current.map { it.candidate },
                    analysisRevision,
                    expectedStateRevision = baseStateRevision,
                    commitSkip = candidateId,
                )
            if (published == null) {
                return respond(writer, id, mapOf("accepted" to false, "stale" to true))
            }
        }

        "reject" -> {
            suggestionStore.record(
                SuggestionDecision(
                    candidateId = candidateId,
                    path = candidate.path,
                    contentHash = candidate.contentHash,
                    intents = candidate.candidate.intents,
                    reason = candidate.candidate.reason.name,
                    decision = "reject",
                    createdAt = Instant.now().toString(),
                    anchorId = candidate.anchorId,
                    startLine = candidate.candidate.span.startLine,
                    startCol = candidate.candidate.span.startCol,
                    endLine = candidate.candidate.span.endLine,
                    endCol = candidate.candidate.span.endCol,
                ),
            )
            republishUri(uri)
        }

        "confirm" -> {
            val anchorLock = anchorLocks.computeIfAbsent(candidate.anchorId) { Any() }
            val confirmed =
                synchronized(anchorLock) {
                    if (anchorResolvedGlobally(candidate.anchorId)) {
                        false
                    } else {
                        UserAnnotationsFile.add(
                            root,
                            UserAnnotation(
                                path = relativePath(uri),
                                span = candidate.candidate.span,
                                intents = candidate.candidate.intents,
                                contentHash = candidate.contentHash,
                                createdAt = Instant.now().toString(),
                            ),
                        )
                        suggestionStore.record(
                            SuggestionDecision(
                                candidateId = candidateId,
                                path = candidate.path,
                                contentHash = candidate.contentHash,
                                intents = candidate.candidate.intents,
                                reason = candidate.candidate.reason.name,
                                decision = "confirm",
                                createdAt = Instant.now().toString(),
                                anchorId = candidate.anchorId,
                                startLine = candidate.candidate.span.startLine,
                                startCol = candidate.candidate.span.startCol,
                                endLine = candidate.candidate.span.endLine,
                                endCol = candidate.candidate.span.endCol,
                            ),
                        )
                        true
                    }
                }
            if (!confirmed) {
                return respond(writer, id, mapOf("accepted" to false, "missing" to true))
            }
            republishUri(uri)
        }
    }
    respond(writer, id, mapOf("accepted" to true, "stale" to false, "decision" to decision))
}

private fun LiveServer.anchorResolvedGlobally(anchorId: String): Boolean {
    val decisions = suggestionStore.load()
    val confirmations = decisions.filter { it.decision == "confirm" && it.anchorId == anchorId }
    if (confirmations.isEmpty()) return false
    val userAnnotations = UserAnnotationsFile.loadTolerant(root)
    return confirmations.any { decision ->
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
    }
}
