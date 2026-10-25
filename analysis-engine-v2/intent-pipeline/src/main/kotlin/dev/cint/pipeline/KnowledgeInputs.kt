package dev.cint.pipeline

import dev.cint.contracts.data.AnalysisError
import dev.cint.contracts.data.Confidence
import dev.cint.contracts.data.Contribution
import dev.cint.contracts.data.ErrorSeverity
import dev.cint.contracts.data.Intent
import dev.cint.contracts.data.Provenance
import dev.cint.contracts.data.Role
import dev.cint.contracts.data.Status
import dev.cint.contracts.facts.CommentFact
import dev.cint.contracts.facts.DeclarationFact
import dev.cint.contracts.facts.FunctionFact
import dev.cint.contracts.facts.IndexedSource
import dev.cint.contracts.facts.SourceInput
import dev.cint.contracts.facts.SyntaxNode
import dev.cint.contracts.knowledge.FunctionAnnotation
import dev.cint.contracts.knowledge.FunctionSelector
import dev.cint.contracts.knowledge.KnowledgeSnapshot
import dev.cint.contracts.knowledge.PackDefault
import dev.cint.contracts.knowledge.SpanOverride
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory
import dev.cint.knowledge.KnowledgeStatusPolicy

data class KnowledgeInputResolution(
    val contributions: List<Contribution>,
    val errors: List<AnalysisError> = emptyList(),
)

fun interface KnowledgeInputResolver {
    fun resolve(
        source: IndexedSource,
        snapshot: KnowledgeSnapshot,
        packDefaults: List<PackDefault>,
        cancellation: CancellationToken,
    ): ContractResult<KnowledgeInputResolution>
}

class PrecedenceKnowledgeInputResolver : KnowledgeInputResolver {
    override fun resolve(
        source: IndexedSource,
        snapshot: KnowledgeSnapshot,
        packDefaults: List<PackDefault>,
        cancellation: CancellationToken,
    ): ContractResult<KnowledgeInputResolution> {
        cancellation.throwIfCancelled().let {
            if (it.isFailure) return ResultFactory.failure(it.errorOrNull!!)
        }

        val tree = source.facts.syntaxTree
        if (tree == null) return ResultFactory.success(KnowledgeInputResolution(emptyList()))

        val path = source.input.selectionPath()
        val nodes = tree.nodes.values.sortedWith(compareBy({ it.startByte }, { it.endByte }, { it.id }))
        val nodesBySpan = nodes.associateBy { it.span }
        val covered = mutableSetOf<String>()
        val contributions = mutableListOf<Contribution>()
        val errors = mutableListOf<AnalysisError>()

        val overrides =
            if (path == null) {
                emptyList()
            } else {
                snapshot.spanOverrides
                    .filter {
                        it.selector.path == path &&
                            it.selector.contentHash == source.contentHash &&
                            KnowledgeStatusPolicy.eligible(it.status)
                    }.sortedWith(overrideOrder)
            }
        for (override in overrides) {
            val node = nodesBySpan[override.selector.span] ?: continue
            if (!covered.add(node.id)) continue
            contributions += override.contribution(node)
        }

        val declarations = source.facts.declarations.associateBy { it.nodeId }
        val comments = source.facts.comments.associateBy { it.nodeId }
        val candidates =
            source.facts.functions
                .sortedBy { it.nodeId }
                .mapNotNull { function ->
                    tree.getNode(function.nodeId)?.let {
                        candidate(function, it, declarations[it.id], comments, tree)
                    }
                }

        val annotationCandidates =
            snapshot.functionAnnotations
                .filter { KnowledgeStatusPolicy.eligible(it.status) }
                .sortedWith(annotationOrder)
        for (candidate in candidates) {
            if (candidate.node.id in covered) continue
            val match = bestAnnotation(annotationCandidates, candidate, errors)
            if (match != null) {
                contributions +=
                    contribution(
                        candidate.node,
                        match.intents,
                        match.provenance,
                        match.confidence,
                        FUNCTION_RULE,
                    )
                continue
            }
            if (!snapshot.materialized) {
                val default = bestDefault(packDefaults, candidate, errors)
                if (default != null) {
                    contributions +=
                        contribution(
                            candidate.node,
                            default.intents,
                            default.provenance,
                            default.confidence,
                            DEFAULT_RULE,
                        )
                }
            }
        }

        return ResultFactory.success(
            KnowledgeInputResolution(
                contributions = contributions.sortedWith(
                    compareBy(
                        { it.startByte },
                        { it.endByte },
                        { it.rule },
                        { it.intents.sorted().joinToString("\u0000") },
                    ),
                ),
                errors = errors.distinctBy { it.code to it.message }.sortedWith(compareBy({ it.code }, { it.message })),
            ),
        )
    }

    private fun bestAnnotation(
        annotations: List<FunctionAnnotation>,
        candidate: FunctionCandidate,
        errors: MutableList<AnalysisError>,
    ): FunctionAnnotation? {
        var selected: FunctionAnnotation? = null
        for (annotation in annotations) {
            when (val match = selectorMatch(annotation.selector, candidate)) {
                MatchResult.Matched -> {
                    if (selected == null || annotationOrder.compare(annotation, selected) < 0) {
                        selected = annotation
                    }
                }
                is MatchResult.Unsupported -> errors += unsupported(match.reason)
                MatchResult.NoMatch -> Unit
            }
        }
        return selected
    }

    private fun bestDefault(
        defaults: List<PackDefault>,
        candidate: FunctionCandidate,
        errors: MutableList<AnalysisError>,
    ): PackDefault? {
        var selected: PackDefault? = null
        for (default in defaults) {
            if (!KnowledgeStatusPolicy.eligible(default.status)) continue
            when (val match = selectorMatch(default.selector, candidate)) {
                MatchResult.Matched -> {
                    if (selected == null || defaultOrder.compare(default, selected) < 0) {
                        selected = default
                    }
                }
                is MatchResult.Unsupported -> errors += unsupported(match.reason)
                MatchResult.NoMatch -> Unit
            }
        }
        return selected
    }

    private fun unsupported(reason: String): AnalysisError =
        AnalysisError(
            code = "KNOWLEDGE_SELECTOR_UNSUPPORTED",
            message = reason,
            severity = ErrorSeverity.WARNING,
        )

    private fun SpanOverride.contribution(node: SyntaxNode): Contribution =
        contribution(node, intents, provenance, confidence, OVERRIDE_RULE)

    private companion object {
        const val OVERRIDE_RULE = "knowledge:span-override"
        const val FUNCTION_RULE = "knowledge:function-annotation"
        const val DEFAULT_RULE = "knowledge:pack-default"
    }
}

private data class FunctionCandidate(
    val function: FunctionFact,
    val node: SyntaxNode,
    val selectors: List<FunctionSelector>,
    val name: String,
    val receiverTypes: Set<String>,
    val annotations: Set<String>,
    val comments: Set<String>,
)

private fun candidate(
    function: FunctionFact,
    node: SyntaxNode,
    declaration: DeclarationFact?,
    comments: Map<String, CommentFact>,
    tree: dev.cint.contracts.facts.SyntaxTree,
): FunctionCandidate {
    val name = function.name?.takeIf { it.isNotBlank() }
        ?: declaration?.name?.takeIf { it.isNotBlank() }
        ?: node.text.takeIf { it.isNotBlank() }
        ?: ""
    val localName = name.substringAfterLast('.')
    val receiverTypes = linkedSetOf<String>()
    if ('.' in name) receiverTypes += name.substringBeforeLast('.')
    val nameNode = function.nameNodeId?.let(tree::getNode)
    val nameStart = nameNode?.startByte ?: node.startByte
    tree.getDescendants(node.id)
        .filter {
            it.id != node.id &&
                it.endByte <= nameStart &&
                it.text.isNotBlank() &&
                it.kind.contains("type", ignoreCase = true)
        }
        .sortedBy { it.startByte }
        .forEach { receiverTypes += it.text }
    val selectors = linkedSetOf<FunctionSelector>()
    if (name.isNotBlank()) {
        selectors += FunctionSelector(qualifiedName = name)
        if (localName != name) {
            selectors += FunctionSelector(qualifiedName = localName)
        }
        for (receiver in receiverTypes) {
            selectors += FunctionSelector(qualifiedName = localName, receiverType = receiver)
        }
        selectors += FunctionSelector(qualifiedName = name, signature = node.text)
    }
    val annotationText =
        buildSet {
            declaration?.annotations?.forEach { add(normalizeAnnotation(it)) }
            declaration?.annotationNodeIds
                ?.mapNotNull { comments[it]?.magicComment }
                ?.forEach { add(normalizeAnnotation(it)) }
        }
    val commentText =
        buildSet {
            declaration?.precedingCommentIds?.forEach { id ->
                comments[id]?.let { comment ->
                    comment.magicComment?.takeIf { it.isNotBlank() }?.let(::add)
                    comment.nodeId.takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }
    return FunctionCandidate(
        function = function,
        node = node,
        selectors = selectors.toList(),
        name = localName,
        receiverTypes = receiverTypes,
        annotations = annotationText,
        comments = commentText,
    )
}

private sealed interface MatchResult {
    data object Matched : MatchResult
    data object NoMatch : MatchResult
    data class Unsupported(val reason: String) : MatchResult
}

private fun selectorMatch(
    selector: FunctionSelector,
    candidate: FunctionCandidate,
): MatchResult {
    val structural = selector.structuralPattern
    if (structural != null) {
        if (selector.qualifiedName.isBlank() && selector.importPath == null &&
            selector.receiverType == null && selector.declaringType == null && selector.signature == null
        ) {
            return structuralMatch(structural, candidate)
        }
        when (val result = structuralMatch(structural, candidate)) {
            MatchResult.Matched -> Unit
            MatchResult.NoMatch -> return MatchResult.NoMatch
            is MatchResult.Unsupported -> return result
        }
    }
    if (selector.importPath != null) {
        return MatchResult.Unsupported(
            "selector '${selector.identityKey}' uses importPath, which requires call resolution; deferred to ticket 076",
        )
    }
    if (selector.declaringType != null) {
        return MatchResult.Unsupported(
            "selector '${selector.identityKey}' uses declaringType, which is not resolvable from current facts",
        )
    }
    if (selector.receiverType != null && candidate.receiverTypes.isEmpty()) {
        return MatchResult.Unsupported(
            "selector '${selector.identityKey}' uses receiverType but no receiver is resolvable for declaration '${candidate.function.nodeId}'; deferred to ticket 076",
        )
    }
    return if (candidate.selectors.any { selector.matches(it) }) {
        MatchResult.Matched
    } else {
        MatchResult.NoMatch
    }
}

private fun structuralMatch(
    pattern: String,
    candidate: FunctionCandidate,
): MatchResult {
    if (pattern.startsWith("token-prefix:")) {
        val parts = pattern.removePrefix("token-prefix:").split(';')
        val prefix = parts.firstOrNull()?.takeIf { it.isNotBlank() } ?: return MatchResult.NoMatch
        val exceptions =
            parts.drop(1)
                .filter { it.startsWith("except:") }
                .flatMap { it.removePrefix("except:").split(',') }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        val matches =
            candidate.name.startsWith(prefix) &&
                candidate.name !in exceptions &&
                (candidate.name.length == prefix.length || !candidate.name[prefix.length].isLowerCase())
        return if (matches) MatchResult.Matched else MatchResult.NoMatch
    }
    if (pattern.startsWith("@")) {
        return if (normalizeAnnotation(pattern) in candidate.annotations) {
            MatchResult.Matched
        } else {
            MatchResult.NoMatch
        }
    }
    if (pattern.startsWith("cint:")) {
        val marker = pattern.removePrefix("cint:")
        return if (candidate.comments.any { it == marker }) MatchResult.Matched else MatchResult.NoMatch
    }
    return MatchResult.Unsupported(
        "structural selector '$pattern' is not applicable to function knowledge; deferred to ticket 076",
    )
}

private fun normalizeAnnotation(text: String): String =
    text.removePrefix("@").replace(Regex("""\s+"""), "")

private val annotationOrder =
    compareByDescending<FunctionAnnotation> { KnowledgeStatusPolicy.rank(it.status) }
        .thenByDescending { it.confidence.value }
        .thenBy { it.selector.identityKey }

private val defaultOrder =
    compareByDescending<PackDefault> { KnowledgeStatusPolicy.rank(it.status) }
        .thenByDescending { it.confidence.value }
        .thenBy { it.packId }
        .thenBy { it.packVersion }
        .thenBy { it.intents.sorted().joinToString("\u0000") }

private val overrideOrder =
    compareByDescending<SpanOverride> { KnowledgeStatusPolicy.rank(it.status) }
        .thenByDescending { it.confidence.value }
        .thenBy { it.selector.identityKey }

private fun SourceInput.selectionPath(): String? = when (this) {
    is SourceInput.FilePath -> path
    is SourceInput.InMemory -> path ?: documentId
}

private fun contribution(
    node: SyntaxNode,
    intents: Set<Intent>,
    provenance: Provenance,
    confidence: Confidence,
    rule: String,
): Contribution =
    Contribution(
        rule = rule,
        role = Role.SOURCE_INTENT,
        intents = intents.toSet(),
        span = node.span,
        source = KNOWLEDGE_SOURCE,
        confidence = confidence,
        startByte = node.startByte,
        endByte = node.endByte,
        provenance = provenance,
    )

private const val KNOWLEDGE_SOURCE = "knowledge"
