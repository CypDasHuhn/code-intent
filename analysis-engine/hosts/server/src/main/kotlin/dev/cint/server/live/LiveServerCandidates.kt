package dev.cint.server.live

import dev.cint.api.CandidateEmit
import dev.cint.core.cache.AnchorFingerprint
import dev.cint.core.candidate.CandidateIdentity
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.restriction.AiRestrictionResolver
import dev.cint.core.restriction.AiRestrictionsFile
import dev.cint.core.support.hashing.SourceNormalizer
import dev.cint.core.syntax.UnitIdentity
import dev.cint.core.user.UserAnnotationResolver
import dev.cint.core.user.UserAnnotationsFile
import dev.cint.frontend.Frontends
import dev.cint.server.LiveServer
import dev.cint.suggest.model.CandidateDocument
import dev.cint.suggest.model.CandidateGroup
import dev.cint.suggest.model.CandidateProposal
import dev.cint.suggest.model.ProposalOperation
import dev.cint.suggest.model.ProposalOperationKind
import java.io.BufferedWriter
import java.util.concurrent.ConcurrentHashMap

internal fun LiveServer.publishCandidates(
    writer: BufferedWriter,
    doc: DocState,
    candidates: List<CandidateEmit>,
    proposals: List<CandidateProposal> = emptyList(),
    analysisRevision: Long,
    expectedStateRevision: Long? = null,
    commitSkip: String? = null,
    shortCircuited: Boolean = false,
): CandidatePublishResult? {
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
        val rejectedFingerprints =
            decisions
                .filter { it.decision == "reject" }
                .groupBy(
                    keySelector = { UnitIdentity.versionOf(it.anchorFingerprintVersion) },
                    valueTransform = { it.anchorFingerprint },
                ).mapValues { (_, fingerprints) -> fingerprints.filterNotNull().toSet() }
        val confirmed = decisions.filter { it.decision == "confirm" }.mapTo(linkedSetOf()) { it.candidateId }
        val confirmedFingerprints =
            decisions
                .filter { it.decision == "confirm" }
                .groupBy(
                    keySelector = { UnitIdentity.versionOf(it.anchorFingerprintVersion) },
                    valueTransform = { it.anchorFingerprint },
                ).mapValues { (_, fingerprints) -> fingerprints.filterNotNull().toSet() }
        val rejectedProposalAnchors =
            decisions
                .filter { it.decision == "reject" && it.reason == PROPOSAL_REASON }
                .mapNotNullTo(linkedSetOf()) { it.anchorId }
        val restrictionIds = AiRestrictionsFile.loadTolerant(root).mapTo(linkedSetOf()) { it.id }
        val restrictions = AiRestrictionsFile.loadTolerant(root)
        val currentAnnotations = analyses[key]?.output?.annotations.orEmpty()
        val currentUserAnnotations =
            currentAnnotations
                .filter { annotation -> annotation.provenance.any { it.rule == dev.cint.core.user.USER_CONTRIBUTOR_ID } }
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
        val fingerprintVersions = (rejectedFingerprints.keys + confirmedFingerprints.keys).sorted()
        val needsFingerprints = !shortCircuited && fingerprintVersions.isNotEmpty()
        val contextByVersion = linkedMapOf<Int, (Int, Int) -> String>()

        fun contextFor(version: Int): (Int, Int) -> String = contextByVersion.getOrPut(version) { anchorContexts(doc, version) }

        fun candidateFingerprints(candidate: CandidateEmit): Map<Int, String> =
            if (!needsFingerprints) {
                emptyMap()
            } else {
                fingerprintVersions.associateWith { version ->
                    val context = contextFor(version)
                    candidateFingerprint(path, doc.text, candidate, context(candidate.startByte, candidate.endByte))
                }
            }

        fun proposalFingerprints(proposal: CandidateProposal): Map<Int, String> =
            if (!needsFingerprints) {
                emptyMap()
            } else {
                fingerprintVersions.associateWith { version ->
                    val context = contextFor(version)
                    proposalFingerprint(path, doc.text, proposal, context(proposal.startByte, proposal.endByte))
                }
            }

        fun matches(
            fingerprints: Map<Int, String>,
            stored: Map<Int, Set<String>>,
        ): Boolean = fingerprints.any { (version, fingerprint) -> fingerprint in stored[version].orEmpty() }
        val includeOperations = (clientProtocols[writer] ?: 0) >= 4
        val groups = suggestionService.group(document, candidates)
        val publishedById = linkedMapOf<String, PublishedCandidate>()
        val groupPayloads = mutableListOf<Map<String, Any?>>()
        for (group in groups) {
            val groupResolved =
                group.anchorId in resolvedAnchors ||
                    group.candidates.any { candidate ->
                        val candidateId = candidateId(path, group.contentHash, candidate)
                        val fingerprints = candidateFingerprints(candidate)
                        val matchedVersion =
                            fingerprints.entries
                                .firstOrNull { (version, fingerprint) ->
                                    fingerprint in confirmedFingerprints[version].orEmpty()
                                }?.key
                        (
                            candidateId in confirmed &&
                                userAnnotations.any { annotation ->
                                    annotation.path == path &&
                                        annotation.span == candidate.span &&
                                        annotation.intents.any { it in candidate.intents }
                                }
                        ) ||
                            (
                                matchedVersion != null &&
                                    currentUserAnnotations.any { annotation ->
                                        annotationCoversCurrent(doc, candidate, annotation, contextFor(matchedVersion))
                                    }
                            )
                    }
            if (groupResolved) continue
            val kept =
                group.candidates.mapNotNull { candidate ->
                    val candidateId = candidateId(path, group.contentHash, candidate)
                    val fingerprints = candidateFingerprints(candidate)
                    if (
                        candidateId in rejected ||
                        matches(fingerprints, rejectedFingerprints) ||
                        candidateId in currentSessionSkips
                    ) {
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
            if (kept.isNotEmpty()) groupPayloads += candidateGroupPayload(group, kept, includeOperations)
        }
        val published = publishedById.values.toList()
        val publishedProposalList =
            if (!includeOperations) {
                emptyList()
            } else {
                proposals
                    .filter {
                        it.proposalId !in restrictionIds &&
                            it.proposalId !in rejected &&
                            (
                                shortCircuited ||
                                    !restrictionMatchesProposal(doc, it, restrictions, currentAnnotations)
                            ) &&
                            (
                                !needsFingerprints ||
                                    !matches(proposalFingerprints(it), rejectedFingerprints)
                            ) &&
                            it.proposalId !in currentSessionSkips &&
                            it.anchorId !in rejectedProposalAnchors
                    }.sortedWith(compareBy({ it.startByte }, { it.endByte }, { it.proposalId }))
                    .map { proposal ->
                        PublishedProposal(
                            proposalId = proposal.proposalId,
                            anchorId = proposal.anchorId,
                            path = path,
                            contentHash = proposal.contentHash,
                            version = doc.version,
                            analysisRevision = analysisRevision,
                            proposal = proposal,
                        )
                    }
            }
        groupPayloads += publishedProposalList.map { proposalGroupPayload(it, includeOperations) }
        publishedCandidates[key] = published
        publishedProposals[key] = publishedProposalList
        val revision = suggestionRevision.incrementAndGet()
        candidateStateRevisions[key] = revision
        val params =
            linkedMapOf<String, Any?>(
                "uri" to doc.uri,
                "version" to doc.version,
                "analysisRevision" to analysisRevision,
                "suggestionRevision" to revision,
                "groups" to groupPayloads,
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
        return CandidatePublishResult(published.size, publishedProposalList.size)
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
    includeOperations: Boolean,
): Map<String, Any?> =
    linkedMapOf<String, Any?>(
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
    ).apply {
        if (includeOperations) {
            put(
                "operations",
                kept.flatMap { published ->
                    published.candidate.intents.sorted().map { intent ->
                        operationPayload(
                            ProposalOperation(
                                kind = ProposalOperationKind.ADD,
                                intent = intent,
                                startByte = published.candidate.startByte,
                                endByte = published.candidate.endByte,
                                span = published.candidate.span,
                                confidence = published.candidate.confidence,
                                rationale = published.candidate.rationale,
                                source = published.candidate.source,
                            ),
                        )
                    }
                },
            )
        }
    }

private fun LiveServer.proposalGroupPayload(
    published: PublishedProposal,
    includeOperations: Boolean,
): Map<String, Any?> {
    val proposal = published.proposal
    return linkedMapOf<String, Any?>(
        "proposalId" to published.proposalId,
        "groupId" to published.proposalId,
        "anchorId" to published.anchorId,
        "path" to published.path,
        "contentHash" to published.contentHash,
        "unitId" to proposal.unitId,
        "startLine" to proposal.span.startLine,
        "startCol" to proposal.span.startCol,
        "endLine" to proposal.span.endLine,
        "endCol" to proposal.span.endCol,
        "startByte" to proposal.startByte,
        "endByte" to proposal.endByte,
        "candidates" to emptyList<Any?>(),
    ).apply {
        if (includeOperations) put("operations", proposal.operations.map(::operationPayload))
        proposal.provider?.let { put("provider", it) }
        proposal.model?.let { put("model", it) }
        proposal.promptVersion?.let { put("promptVersion", it) }
    }
}

private fun operationPayload(operation: ProposalOperation): Map<String, Any?> =
    linkedMapOf<String, Any?>(
        "kind" to operation.kind.name,
        "intent" to operation.intent,
        "startLine" to operation.span.startLine,
        "startCol" to operation.span.startCol,
        "endLine" to operation.span.endLine,
        "endCol" to operation.span.endCol,
        "startByte" to operation.startByte,
        "endByte" to operation.endByte,
        "confidence" to operation.confidence,
    ).apply {
        operation.rationale?.let { put("rationale", it) }
        operation.source?.let { put("source", it) }
    }

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

internal const val PROPOSAL_REASON: String = "PROPOSAL"

private fun candidateFingerprint(
    path: String,
    text: String,
    candidate: CandidateEmit,
    context: String,
): String =
    UserAnnotationResolver
        .selectionText(text, candidate.span)
        ?.let { anchor -> AnchorFingerprint.of(path, anchor, candidate.intents, candidate.reason.name, context) }
        .orEmpty()

private fun proposalFingerprint(
    path: String,
    text: String,
    proposal: CandidateProposal,
    context: String,
): String =
    UserAnnotationResolver
        .selectionText(text, proposal.span)
        ?.let { anchor ->
            AnchorFingerprint.of(
                path,
                anchor,
                proposal.operations.mapTo(linkedSetOf()) { it.intent },
                PROPOSAL_REASON,
                listOf(
                    context,
                    proposalOperationShape(text, proposal).joinToString(","),
                ).joinToString("|"),
            )
        }.orEmpty()

private fun LiveServer.restrictionMatchesProposal(
    doc: DocState,
    proposal: CandidateProposal,
    restrictions: List<dev.cint.core.restriction.AiRestrictionRecord>,
    annotations: List<dev.cint.api.Annotation>,
): Boolean {
    val path = relativePath(doc.uri)
    val frontend = Frontends.forLanguage(doc.language) ?: return false
    val context =
        dev.cint.frontend.SplitAnchorResolver.anchor(
            SourceFile(doc.uri, doc.text),
            frontend,
            proposal.startByte,
            proposal.endByte,
        )
    val proposalShape =
        proposalOperationShape(context.text, proposal, context.regionStartByte)
    val relevant =
        restrictions.filter { record ->
            record.unitFingerprint != null &&
                UserAnnotationResolver.pathMatches(record.path, path, root) &&
                when {
                    record.regionKey != null -> {
                        !context.regionAmbiguous &&
                            record.regionKey == context.regionKey &&
                            record.regionLanguage == context.language
                    }

                    record.regionOrdinal != null -> {
                        record.regionOrdinal == context.regionOrdinal &&
                            record.regionLanguage == context.language
                    }

                    else -> {
                        val range = UserAnnotationResolver.byteRange(doc.text, record.span)
                        val regionEnd = context.regionStartByte + context.text.toByteArray(Charsets.UTF_8).size
                        if (context.regionKey == null && context.regionOrdinal == null) {
                            true
                        } else {
                            range != null &&
                                range.first >= context.regionStartByte &&
                                range.second <= regionEnd &&
                                !context.regionAmbiguous
                        }
                    }
                } &&
                restrictionOperationShape(
                    context.text,
                    proposal,
                    record,
                    context.regionStartByte,
                ) == proposalShape
        }
    if (relevant.isEmpty()) return false
    val regionSource = SourceFile(doc.uri, context.text)
    val regionFrontend = Frontends.forLanguage(context.language) ?: frontend
    val tree = regionFrontend.parse(regionSource)
    val syntax = regionFrontend.syntax(tree)
    val region =
        dev.cint.core.facts.frontend.SplitRegion(
            language = context.language,
            text = context.text,
            startByte = context.regionStartByte,
            startLine = context.regionStartLine,
            startCol = context.regionStartCol,
        )
    return relevant.any { record ->
        val fingerprint = record.unitFingerprint ?: return@any false
        val resolved =
            AiRestrictionResolver
                .resolve(listOf(record), regionSource, tree, syntax, root)
                .map { it.shift(region) }
        if (resolved.isEmpty() || resolved.none { restrictionApplies(annotations, it) }) {
            return@any false
        }
        UnitIdentity.unitFingerprint(
            regionSource,
            tree,
            syntax,
            context.anchorStart,
            context.anchorEnd,
            record.fingerprintVersion,
        ) == fingerprint
    }
}

private fun annotationCoversCurrent(
    doc: DocState,
    candidate: CandidateEmit,
    annotation: dev.cint.api.Annotation,
    contextAt: (Int, Int) -> String,
): Boolean {
    if (annotation.intents.none { it in candidate.intents }) return false
    val candidateText = UserAnnotationResolver.selectionText(doc.text, candidate.span) ?: return false
    val annotationText = UserAnnotationResolver.selectionText(doc.text, annotation.span) ?: return false
    if (SourceNormalizer.normalize(candidateText) != SourceNormalizer.normalize(annotationText)) return false
    val candidateContext = contextAt(candidate.startByte, candidate.endByte)
    val annotationContext = contextAt(annotation.startByte, annotation.endByte)
    return candidateContext == annotationContext
}
