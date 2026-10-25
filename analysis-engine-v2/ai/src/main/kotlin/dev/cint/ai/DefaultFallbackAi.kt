package dev.cint.ai

import dev.cint.contracts.data.Confidence
import dev.cint.contracts.data.Intents
import dev.cint.contracts.data.Provenance
import dev.cint.contracts.data.Status
import dev.cint.contracts.facts.FactBatch
import dev.cint.contracts.facts.IndexedSource
import dev.cint.contracts.facts.SourceInput
import dev.cint.contracts.facts.SyntaxNode
import dev.cint.contracts.knowledge.AnnotationScope
import dev.cint.contracts.knowledge.FunctionAnnotation
import dev.cint.contracts.knowledge.FunctionSelector
import dev.cint.contracts.knowledge.KnowledgeChange
import dev.cint.contracts.knowledge.SpanOverride
import dev.cint.contracts.knowledge.SpanSelector
import dev.cint.contracts.ports.FallbackAi
import dev.cint.contracts.ports.FallbackAiPolicy
import dev.cint.contracts.ports.FallbackAiRequest
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

class DefaultFallbackAi(
    private val provider: FallbackAiProvider,
    private val policy: FallbackAiPolicy = FallbackAiPolicy.selective(),
    private val budget: FallbackAiBudget = FallbackAiBudget(),
    private val redaction: FallbackAiRedaction = FallbackAiRedaction.NONE,
    private val clock: () -> Instant = { Clock.System.now() },
) : FallbackAi {
    private var remainingCalls = budget.maxCalls

    fun propose(request: FallbackAiRequest): ContractResult<KnowledgeChange> =
        propose(request, CancellationToken.NONE)

    override fun propose(
        request: FallbackAiRequest,
        cancellation: CancellationToken,
    ): ContractResult<KnowledgeChange> {
        val cancelled = cancellation.throwIfCancelled()
        if (cancelled.isFailure) return ResultFactory.failure(cancelled.errorOrNull!!)
        if (!policy.shouldRun(request)) return noop()
        val path = request.source.selectionPath()
        if (!redaction.allows(path)) return noop()

        val candidates = candidates(request.source.facts)
        if (candidates.isEmpty()) return noop()
        val sourceText = redaction
            .redact(request.source.sourceText())
            .take(minOf(budget.maxSourceChars, budget.maxPromptChars))
        val functions = mutableListOf<AiFunctionPrompt>()
        var usedChars = sourceText.length
        for (candidate in candidates.take(budget.maxFunctions)) {
            val text = redaction.redact(candidate.node.text).take(budget.maxFunctionChars)
            val cost = candidate.name.length + text.length
            if (usedChars + cost > budget.maxPromptChars) break
            usedChars += cost
            functions += AiFunctionPrompt(
                name = candidate.name,
                text = text,
                startLine = candidate.node.startLine,
                startColumn = candidate.node.startCol,
            )
        }
        if (functions.isEmpty()) return noop()
        if (!consumeCall()) return noop()
        val promptedNames = functions.mapTo(HashSet()) { it.name }
        val promptedCandidates = candidates.filter { it.name in promptedNames }
        val prompt = AiProviderRequest(
            path = path,
            language = request.language,
            contentHash = request.source.contentHash,
            source = sourceText,
            functions = functions,
            knownIntents = Intents.allKnown,
        )

        val proposals = provider.propose(prompt, cancellation)
        if (proposals.isFailure) return ResultFactory.failure(proposals.errorOrNull!!)
        val afterCancel = cancellation.throwIfCancelled()
        if (afterCancel.isFailure) return ResultFactory.failure(afterCancel.errorOrNull!!)

        val now = clock()
        val changes = mergeChanges(
            proposals.getOrThrow()
                .take(budget.maxProposals)
                .mapNotNull { proposal ->
                    when (proposal) {
                        is AiProposal.Function -> functionChange(proposal, request, promptedCandidates, now)
                        is AiProposal.Span -> spanChange(proposal, request, promptedCandidates, path, now)
                    }
                },
        )
        if (changes.isEmpty()) return noop()
        return ResultFactory.success(
            KnowledgeChange.Batch("fallback-ai:${provider.id}", changes),
        )
    }

    private fun functionChange(
        proposal: AiProposal.Function,
        request: FallbackAiRequest,
        candidates: List<Candidate>,
        now: Instant,
    ): KnowledgeChange? {
        val candidate = candidates.firstOrNull { it.matches(proposal.name) } ?: return null
        if (hasProtectedFunction(request, candidate)) return null
        val intents = validIntents(proposal.intents) ?: return null
        val annotation = FunctionAnnotation.create(
            selector = FunctionSelector(qualifiedName = candidate.name),
            intents = intents,
            scope = AnnotationScope.GLOBAL,
            provenance = Provenance.AI_FALLBACK,
            confidence = capped(proposal.confidence),
            status = Status.SUGGESTED,
            createdAt = now,
        )
        return KnowledgeChange.SetFunctionAnnotation(annotation)
    }

    private fun spanChange(
        proposal: AiProposal.Span,
        request: FallbackAiRequest,
        candidates: List<Candidate>,
        path: String?,
        now: Instant,
    ): KnowledgeChange? {
        if (path == null) return null
        val candidate = candidates.firstOrNull { it.matches(proposal.name) } ?: return null
        val intents = validIntents(proposal.intents) ?: return null
        val selector = SpanSelector(path, candidate.node.span, request.source.contentHash)
        val existing = request.knowledge.spanOverrides.find { it.selector.identityKey == selector.identityKey }
        if (existing != null && !replaceable(existing.provenance, existing.status)) return null
        val override = SpanOverride.create(
            selector = selector,
            intents = intents,
            provenance = Provenance.AI_FALLBACK,
            confidence = capped(proposal.confidence),
            status = Status.SUGGESTED,
            rationale = proposal.rationale,
            createdAt = now,
        )
        return KnowledgeChange.SetSpanOverride(override)
    }

    private fun hasProtectedFunction(request: FallbackAiRequest, candidate: Candidate): Boolean =
        request.knowledge.functionAnnotations.any {
            (it.selector.qualifiedName == candidate.name || it.selector.qualifiedName == candidate.localName) &&
                !replaceable(it.provenance, it.status)
        }

    private fun replaceable(provenance: Provenance, status: Status): Boolean =
        provenance == Provenance.AI_FALLBACK && status == Status.SUGGESTED

    private fun validIntents(intents: Set<String>): Set<String>? {
        val valid = intents.filter(Intents::isKnown).toSet()
        return valid.takeIf { it.isNotEmpty() }
    }

    private fun capped(confidence: Confidence): Confidence =
        Confidence.fromDouble(minOf(confidence.value, budget.maxConfidence))

    private fun mergeChanges(changes: List<KnowledgeChange>): List<KnowledgeChange> {
        val functions = LinkedHashMap<String, KnowledgeChange.SetFunctionAnnotation>()
        val spans = LinkedHashMap<String, KnowledgeChange.SetSpanOverride>()
        val others = mutableListOf<KnowledgeChange>()
        for (change in changes) {
            when (change) {
                is KnowledgeChange.SetFunctionAnnotation -> {
                    val key = change.annotation.selector.identityKey
                    val existing = functions[key]
                    functions[key] = if (existing == null) {
                        change
                    } else {
                        val merged = existing.annotation
                        KnowledgeChange.SetFunctionAnnotation(
                            merged.copy(
                                intents = merged.intents + change.annotation.intents,
                                confidence = merged.confidence.max(change.annotation.confidence),
                                updatedAt = maxOf(merged.updatedAt, change.annotation.updatedAt),
                            ),
                        )
                    }
                }
                is KnowledgeChange.SetSpanOverride -> {
                    val key = change.override.selector.identityKey
                    val existing = spans[key]
                    spans[key] = if (existing == null) {
                        change
                    } else {
                        val merged = existing.override
                        KnowledgeChange.SetSpanOverride(
                            merged.copy(
                                intents = merged.intents + change.override.intents,
                                confidence = merged.confidence.max(change.override.confidence),
                                rationale = listOf(merged.rationale, change.override.rationale)
                                    .filter(String::isNotBlank)
                                    .distinct()
                                    .joinToString("; "),
                                updatedAt = maxOf(merged.updatedAt, change.override.updatedAt),
                            ),
                        )
                    }
                }
                else -> others += change
            }
        }
        return functions.values + spans.values + others
    }

    @Synchronized
    private fun consumeCall(): Boolean {
        if (remainingCalls <= 0) return false
        remainingCalls -= 1
        return true
    }

    private fun noop(): ContractResult<KnowledgeChange> =
        ResultFactory.success(KnowledgeChange.Batch("fallback-ai:noop", emptyList()))

    private fun IndexedSource.selectionPath(): String? = when (val input = input) {
        is SourceInput.FilePath -> input.path
        is SourceInput.InMemory -> input.path ?: input.documentId
    }

    private fun IndexedSource.sourceText(): String {
        val input = input
        if (input is SourceInput.InMemory && input.text.isNotBlank()) return input.text
        val nodes = facts.syntaxTree?.nodes?.values.orEmpty()
        val root = facts.syntaxTree?.rootId?.let { facts.syntaxTree?.getNode(it) }
        if (root != null && root.text.isNotBlank()) return root.text
        return nodes.sortedWith(compareBy({ it.startByte }, { it.id }))
            .map { it.text }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n")
    }

    private fun candidates(facts: FactBatch): List<Candidate> {
        val tree = facts.syntaxTree ?: return emptyList()
        return facts.functions.sortedBy { it.nodeId }.mapNotNull { function ->
            val node = tree.getNode(function.nodeId) ?: return@mapNotNull null
            val name = function.name?.takeIf { it.isNotBlank() }
                ?: node.text.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            Candidate(name, name.substringAfterLast('.'), node)
        }
    }

    private data class Candidate(
        val name: String,
        val localName: String,
        val node: SyntaxNode,
    ) {
        fun matches(candidate: String): Boolean =
            candidate == name || candidate == localName ||
                candidate.substringAfterLast('.') == localName
    }
}
