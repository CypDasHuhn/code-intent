package dev.cint.server.suggestions

import com.fasterxml.jackson.databind.JsonNode
import dev.cint.core.cache.AnchorFingerprint
import dev.cint.core.candidate.CandidateIdentity
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.restriction.AiRestrictionRecord
import dev.cint.core.restriction.AiRestrictionResolver
import dev.cint.core.restriction.AiRestrictionsFile
import dev.cint.core.restriction.ResolvedRestrictionOperation
import dev.cint.core.restriction.RestrictionOperation
import dev.cint.core.syntax.UnitIdentity
import dev.cint.core.user.UserAnnotation
import dev.cint.core.user.UserAnnotationResolver
import dev.cint.core.user.UserAnnotationsFile
import dev.cint.frontend.Frontends
import dev.cint.frontend.SplitAnchorResolver
import dev.cint.server.LiveServer
import dev.cint.server.SuggestionDecision
import dev.cint.server.live.DocumentKey
import dev.cint.server.live.PROPOSAL_REASON
import dev.cint.server.live.PublishedProposal
import dev.cint.server.live.additionsAvailable
import dev.cint.server.live.anchorContext
import dev.cint.server.live.docsFor
import dev.cint.server.live.nextAnalysisRevision
import dev.cint.server.live.proposalOperationShape
import dev.cint.server.live.publishCandidates
import dev.cint.server.live.relativePath
import dev.cint.server.live.removalsApply
import dev.cint.server.live.republishUri
import java.io.BufferedWriter
import java.time.Instant

internal fun LiveServer.handleCandidateDecision(
    writer: BufferedWriter,
    params: JsonNode?,
    id: JsonNode?,
) {
    val uri = params?.get("uri")?.asText() ?: return sendError(writer, id, "uri is required")
    val candidateId = params.get("candidateId")?.asText()
    val proposalId = params.get("proposalId")?.asText() ?: params.get("groupId")?.asText()
    val targetId = proposalId ?: candidateId ?: return sendError(writer, id, "candidateId or proposalId is required")
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
    return synchronized(proposalDecisionLock) {
        val proposal =
            publishedProposals[key]
                .orEmpty()
                .firstOrNull { it.proposalId == targetId && it.version == doc.version }
        if (proposal != null) {
            return handleProposalDecision(writer, id, doc, uri, key, proposal, decision, baseStateRevision)
        }
        val candidate =
            publishedCandidates[key]
                .orEmpty()
                .firstOrNull { it.candidateId == targetId && it.version == doc.version }
                ?: return respond(writer, id, mapOf("accepted" to false, "missing" to true))
        val currentHash = suggestionService.anchorHash(doc.text, candidate.candidate)
        if (currentHash != candidate.contentHash) {
            return respond(writer, id, mapOf("accepted" to false, "stale" to true))
        }
        when (decision) {
            "skip" -> {
                val current = publishedCandidates[key].orEmpty()
                val proposals = publishedProposals[key].orEmpty().map { it.proposal }
                val analysisRevision = current.maxOfOrNull { it.analysisRevision } ?: nextAnalysisRevision(key)
                candidateDecisionBarrierForTest?.invoke()
                val published =
                    publishCandidates(
                        writer,
                        doc,
                        current.map { it.candidate },
                        proposals,
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
                        candidateId = candidate.candidateId,
                        path = candidate.path,
                        contentHash = candidate.contentHash,
                        intents = candidate.candidate.intents,
                        reason = candidate.candidate.reason.name,
                        decision = "reject",
                        createdAt = Instant.now().toString(),
                        anchorId = candidate.anchorId,
                        anchorFingerprint =
                            anchorFingerprint(
                                doc,
                                candidate.candidate.span,
                                candidate.candidate.intents,
                                candidate.candidate.reason.name,
                                candidate.candidate.startByte,
                                candidate.candidate.endByte,
                            ),
                        anchorFingerprintVersion = UnitIdentity.CURRENT_FINGERPRINT_VERSION,
                        startLine = candidate.candidate.span.startLine,
                        startCol = candidate.candidate.span.startCol,
                        endLine = candidate.candidate.span.endLine,
                        endCol = candidate.candidate.span.endCol,
                    ),
                )
                republishUri(
                    uri,
                    key,
                    publishedProposals[key].orEmpty().map { it.proposal },
                    publishedCandidates[key].orEmpty().map { it.candidate },
                )
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
                                userAnnotation(doc, candidate),
                            )
                            suggestionStore.record(
                                SuggestionDecision(
                                    candidateId = candidate.candidateId,
                                    path = candidate.path,
                                    contentHash = candidate.contentHash,
                                    intents = candidate.candidate.intents,
                                    reason = candidate.candidate.reason.name,
                                    decision = "confirm",
                                    createdAt = Instant.now().toString(),
                                    anchorId = candidate.anchorId,
                                    anchorFingerprint =
                                        anchorFingerprint(
                                            doc,
                                            candidate.candidate.span,
                                            candidate.candidate.intents,
                                            candidate.candidate.reason.name,
                                            candidate.candidate.startByte,
                                            candidate.candidate.endByte,
                                        ),
                                    anchorFingerprintVersion = UnitIdentity.CURRENT_FINGERPRINT_VERSION,
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
                republishUri(
                    uri,
                    key,
                    publishedProposals[key].orEmpty().map { it.proposal },
                    publishedCandidates[key].orEmpty().map { it.candidate },
                )
            }
        }
        respond(writer, id, mapOf("accepted" to true, "stale" to false, "decision" to decision))
    }
}

private fun LiveServer.handleProposalDecision(
    writer: BufferedWriter,
    id: JsonNode?,
    doc: dev.cint.server.live.DocState,
    uri: String,
    key: DocumentKey,
    proposal: PublishedProposal,
    decision: String,
    baseStateRevision: Long,
) {
    if (!suggestionSettings.restrictionsEnabled) {
        return respond(writer, id, mapOf("accepted" to false, "disabled" to true))
    }
    val currentHash =
        UserAnnotationResolver
            .selectionText(doc.text, proposal.proposal.span)
            ?.let(CandidateIdentity::contentHash)
    if (currentHash != proposal.contentHash) {
        return respond(writer, id, mapOf("accepted" to false, "stale" to true))
    }
    if (baseStateRevision != (candidateStateRevisions[key] ?: 0L)) {
        return respond(writer, id, mapOf("accepted" to false, "stale" to true))
    }
    if (decision == "confirm") {
        val cached = analyses[key] ?: return respond(writer, id, mapOf("accepted" to false, "stale" to true))
        val removals =
            proposal.proposal.removes.map { removal ->
                ResolvedRestrictionOperation(
                    intent = removal.intent,
                    startByte = removal.startByte,
                    endByte = removal.endByte,
                    span = removal.span,
                    confidence = removal.confidence,
                    rationale = removal.rationale,
                )
            }
        val additions =
            proposal.proposal.adds.map { addition ->
                ResolvedRestrictionOperation(
                    intent = addition.intent,
                    startByte = addition.startByte,
                    endByte = addition.endByte,
                    span = addition.span,
                    confidence = addition.confidence,
                    rationale = addition.rationale,
                )
            }
        if (!removalsApply(cached.output.annotations, removals) || !additionsAvailable(cached.output.annotations, additions)) {
            return respond(writer, id, mapOf("accepted" to false, "stale" to true))
        }
    }
    when (decision) {
        "skip" -> {
            val current = publishedCandidates[key].orEmpty()
            val proposals = publishedProposals[key].orEmpty().map { it.proposal }
            val analysisRevision = current.maxOfOrNull { it.analysisRevision } ?: nextAnalysisRevision(key)
            candidateDecisionBarrierForTest?.invoke()
            val published =
                publishCandidates(
                    writer,
                    doc,
                    current.map { it.candidate },
                    proposals,
                    analysisRevision,
                    expectedStateRevision = baseStateRevision,
                    commitSkip = proposal.proposalId,
                )
            if (published == null) {
                return respond(writer, id, mapOf("accepted" to false, "stale" to true))
            }
        }

        "reject" -> {
            suggestionStore.record(
                SuggestionDecision(
                    candidateId = proposal.proposalId,
                    path = proposal.path,
                    contentHash = proposal.contentHash,
                    intents = proposal.proposal.operations.mapTo(linkedSetOf()) { it.intent },
                    reason = PROPOSAL_REASON,
                    decision = "reject",
                    createdAt = Instant.now().toString(),
                    anchorId = proposal.anchorId,
                    anchorFingerprint =
                        anchorFingerprint(
                            doc,
                            proposal.proposal.span,
                            proposal.proposal.operations.mapTo(linkedSetOf()) { it.intent },
                            PROPOSAL_REASON,
                            proposal.proposal.startByte,
                            proposal.proposal.endByte,
                            proposalOperationShape(doc.text, proposal.proposal),
                        ),
                    anchorFingerprintVersion = UnitIdentity.CURRENT_FINGERPRINT_VERSION,
                    startLine = proposal.proposal.span.startLine,
                    startCol = proposal.proposal.span.startCol,
                    endLine = proposal.proposal.span.endLine,
                    endCol = proposal.proposal.span.endCol,
                ),
            )
            republishUri(
                uri,
                key,
                remainingProposals(key, proposal.proposalId),
                publishedCandidates[key].orEmpty().map { it.candidate },
            )
        }

        "confirm" -> {
            val anchor = anchorIdentity(doc, proposal)
            val removes = relativeOperations(doc, proposal, remove = true)
            val adds = relativeOperations(doc, proposal, remove = false)
            val anchorLength = proposal.proposal.endByte - proposal.proposal.startByte
            val valid =
                removes.all { it.startOffset < anchorLength && it.endOffset > 0 && it.endOffset > it.startOffset } &&
                    adds.all { it.startOffset >= 0 && it.endOffset <= anchorLength && it.endOffset > it.startOffset }
            if (!valid) {
                return respond(writer, id, mapOf("accepted" to false, "stale" to true))
            }
            val persisted =
                runCatching {
                    AiRestrictionsFile.add(
                        root,
                        AiRestrictionRecord(
                            id = proposal.proposalId,
                            path = proposal.path,
                            span = proposal.proposal.span,
                            contentHash = proposal.contentHash,
                            remove = removes,
                            add = adds,
                            symbol = anchor.symbol,
                            unitFingerprint = anchor.fingerprint,
                            regionOrdinal = anchor.regionOrdinal,
                            regionLanguage = anchor.regionLanguage,
                            regionKey = anchor.regionKey,
                            provider = proposal.proposal.provider,
                            model = proposal.proposal.model,
                            promptVersion = proposal.proposal.promptVersion,
                            createdAt = Instant.now().toString(),
                        ),
                    )
                }
            if (persisted.isFailure) {
                val error = persisted.exceptionOrNull()
                return sendError(writer, id, error?.message ?: "failed to persist restriction")
            }
            republishUri(
                uri,
                key,
                remainingProposals(key, proposal.proposalId),
                publishedCandidates[key].orEmpty().map { it.candidate },
            )
        }
    }
    respond(writer, id, mapOf("accepted" to true, "stale" to false, "decision" to decision))
}

private fun LiveServer.remainingProposals(
    key: DocumentKey,
    decided: String,
): List<dev.cint.suggest.model.CandidateProposal> =
    publishedProposals[key]
        .orEmpty()
        .filterNot { it.proposalId == decided }
        .map { it.proposal }

private fun LiveServer.relativeOperations(
    doc: dev.cint.server.live.DocState,
    published: PublishedProposal,
    remove: Boolean,
): List<RestrictionOperation> {
    val operations = if (remove) published.proposal.removes else published.proposal.adds
    val frontend = Frontends.forLanguage(doc.language) ?: return emptyList()
    val context =
        SplitAnchorResolver.anchor(
            SourceFile(doc.uri, doc.text),
            frontend,
            published.proposal.startByte,
            published.proposal.endByte,
        )
    return operations.map { operation ->
        val tokens =
            AiRestrictionResolver.tokenOffsetsFor(
                text = context.text,
                anchorStart = context.anchorStart,
                start = operation.startByte - context.regionStartByte,
                end = operation.endByte - context.regionStartByte,
            )
        RestrictionOperation(
            intent = operation.intent,
            startOffset = operation.startByte - published.proposal.startByte,
            endOffset = operation.endByte - published.proposal.startByte,
            confidence = operation.confidence,
            rationale = operation.rationale,
            startToken = tokens?.first,
            endToken = tokens?.second,
        )
    }
}

private data class AnchorIdentity(
    val symbol: String?,
    val fingerprint: String?,
    val regionOrdinal: Int? = null,
    val regionLanguage: String? = null,
    val regionKey: String? = null,
)

private fun LiveServer.anchorIdentity(
    doc: dev.cint.server.live.DocState,
    published: PublishedProposal,
): AnchorIdentity {
    val frontend = Frontends.forLanguage(doc.language) ?: return AnchorIdentity(null, null)
    return runCatching {
        val context =
            SplitAnchorResolver.anchor(
                SourceFile(doc.uri, doc.text),
                frontend,
                published.proposal.startByte,
                published.proposal.endByte,
            )
        AnchorIdentity(
            symbol = context.symbol,
            fingerprint = context.unitFingerprint,
            regionOrdinal = context.regionOrdinal,
            regionLanguage = if (context.regionOrdinal != null) context.language else null,
            regionKey = context.regionKey,
        )
    }.getOrDefault(AnchorIdentity(null, null))
}

private fun LiveServer.anchorFingerprint(
    doc: dev.cint.server.live.DocState,
    span: dev.cint.api.Span,
    intents: Collection<String>,
    reason: String,
    startByte: Int,
    endByte: Int,
    operations: Collection<String> = emptyList(),
): String? =
    UserAnnotationResolver
        .selectionText(doc.text, span)
        ?.let { text ->
            val context = anchorContext(doc, startByte, endByte)
            val operationContext =
                if (operations.isEmpty()) {
                    context
                } else {
                    listOf(context, operations.sorted().joinToString(",")).joinToString("|")
                }
            AnchorFingerprint.of(
                relativePath(doc.uri),
                text,
                intents,
                reason,
                operationContext,
            )
        }

private fun LiveServer.userAnnotation(
    doc: dev.cint.server.live.DocState,
    candidate: dev.cint.server.live.PublishedCandidate,
): UserAnnotation {
    val frontend = Frontends.forLanguage(doc.language)
    val anchor =
        frontend?.let {
            userAnchor(
                doc,
                it,
                candidate.candidate.startByte,
                candidate.candidate.endByte,
                candidate.candidate.intents,
            )
        }
    return UserAnnotation(
        path = relativePath(doc.uri),
        span = candidate.candidate.span,
        intents = candidate.candidate.intents,
        symbol = anchor?.symbol,
        contentHash = candidate.contentHash,
        createdAt = Instant.now().toString(),
        anchorFingerprint = anchor?.fingerprint,
        unitFingerprint = anchor?.unitFingerprint,
        unitFingerprintVersion = anchor?.unitFingerprintVersion,
        regionOrdinal = anchor?.regionOrdinal,
        regionLanguage = anchor?.regionLanguage,
        regionKey = anchor?.regionKey,
        startToken = anchor?.startToken,
        endToken = anchor?.endToken,
        startTokenOffset = anchor?.startTokenOffset,
        endTokenOffset = anchor?.endTokenOffset,
        startNormalized = anchor?.startNormalized,
        endNormalized = anchor?.endNormalized,
    )
}

private data class UserAnchor(
    val symbol: String?,
    val fingerprint: String?,
    val unitFingerprint: String?,
    val unitFingerprintVersion: Int?,
    val regionOrdinal: Int?,
    val regionLanguage: String?,
    val regionKey: String?,
    val startToken: Int?,
    val endToken: Int?,
    val startTokenOffset: Int?,
    val endTokenOffset: Int?,
    val startNormalized: Int?,
    val endNormalized: Int?,
)

private fun LiveServer.userAnchor(
    doc: dev.cint.server.live.DocState,
    frontend: dev.cint.core.facts.frontend.LanguageFrontend,
    startByte: Int,
    endByte: Int,
    intents: Set<String>,
): UserAnchor? =
    runCatching {
        val context = SplitAnchorResolver.anchor(SourceFile(doc.uri, doc.text), frontend, startByte, endByte)
        val unitFingerprint = context.unitFingerprint
        if (unitFingerprint == null) {
            return@runCatching UserAnchor(null, null, null, null, null, null, null, null, null, null, null, null, null)
        }
        val selected = UserAnnotationResolver.selectionText(doc.text, candidateSpan(doc, startByte, endByte))
        val fingerprint =
            selected?.let {
                AnchorFingerprint.of(relativePath(doc.uri), it, intents, context = unitFingerprint)
            }
        UserAnchor(
            symbol = context.symbol,
            fingerprint = fingerprint,
            unitFingerprint = unitFingerprint,
            unitFingerprintVersion = context.fingerprintVersion,
            regionOrdinal = context.regionOrdinal,
            regionLanguage = if (context.regionOrdinal != null) context.language else null,
            regionKey = context.regionKey,
            startToken = context.startToken,
            endToken = context.endToken,
            startTokenOffset = context.startTokenOffset,
            endTokenOffset = context.endTokenOffset,
            startNormalized = context.startNormalized,
            endNormalized = context.endNormalized,
        )
    }.getOrNull()

private fun candidateSpan(
    doc: dev.cint.server.live.DocState,
    startByte: Int,
    endByte: Int,
): dev.cint.api.Span = UserAnnotationResolver.spanFor(doc.text, startByte, endByte) ?: dev.cint.api.Span(0, 0, 0, 0)

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
