package dev.cint.pipeline

import dev.cint.contracts.data.AnalysisError
import dev.cint.contracts.data.Contribution
import dev.cint.contracts.data.ErrorSeverity
import dev.cint.contracts.data.Role
import dev.cint.contracts.facts.IndexedSource
import dev.cint.contracts.facts.SourceInput
import dev.cint.contracts.facts.SyntaxNode
import dev.cint.contracts.knowledge.AnnotationScope
import dev.cint.contracts.knowledge.FunctionAnnotation
import dev.cint.contracts.knowledge.KnowledgeRules
import dev.cint.contracts.knowledge.KnowledgeSnapshot
import dev.cint.contracts.knowledge.PackDefault
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory
import dev.cint.knowledge.KnowledgePrecedence
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
        val unsupported = linkedMapOf<String, String>()
        val matched = mutableSetOf<String>()
        val unsupportedScopes = mutableSetOf<String>()

        val overrides =
            if (path == null) {
                emptyList()
            } else {
                snapshot.spanOverrides
                    .filter {
                        it.selector.path == path &&
                            it.selector.contentHash == source.contentHash &&
                            KnowledgeStatusPolicy.derivable(it.status, it.provenance)
                    }.sortedWith(KnowledgePrecedence.spanOverrides())
            }
        for (override in overrides) {
            val node = nodesBySpan[override.selector.span] ?: continue
            if (!covered.add(node.id)) continue
            contributions +=
                IntentContract(
                    intents = override.intents,
                    provenance = override.provenance,
                    confidence = override.confidence,
                    rule = OVERRIDE_RULE,
                ).contribution(node)
        }

        val declarations = source.facts.declarations.associateBy { it.nodeId }
        val comments = source.facts.comments.associateBy { it.nodeId }
        val functionTargets =
            source.facts.functions
                .sortedBy { it.nodeId }
                .mapNotNull { function ->
                    tree.getNode(function.nodeId)?.let {
                        functionTarget(function, it, declarations[it.id], comments, tree)
                    }
                }

        val graph = CallGraph.of(source.facts)
            ?: return ResultFactory.success(KnowledgeInputResolution(emptyList()))
        val callTargets =
            graph.calls
                .mapNotNull { call ->
                    val node = tree.getNode(call.nodeId) ?: tree.getNode(call.siteId) ?: return@mapNotNull null
                    callTarget(call, node, tree, source.facts.imports, source.semanticFacts, graph.isInChain(call))
                }.sortedWith(compareBy({ it.node.startByte }, { it.node.endByte }, { it.node.id }))

        val annotations =
            snapshot.functionAnnotations
                .filter { KnowledgeStatusPolicy.derivable(it.status, it.provenance) }
                .filter { annotation ->
                    val applies = scopeApplies(annotation, path)
                    if (annotation.scope == AnnotationScope.FILE &&
                        annotation.path.isNullOrBlank() &&
                        unsupportedScopes.add(annotation.selector.identityKey)
                    ) {
                        errors += unsupportedScope(annotation)
                    }
                    applies
                }.sortedWith(annotationOrder)
        val defaults =
            if (snapshot.materialized) {
                emptyList()
            } else {
                packDefaults
                    .filter { KnowledgeStatusPolicy.eligible(it.status) }
                    .sortedWith(defaultOrder)
            }
        val declarationContracts = DeclarationContracts()

        for (target in functionTargets) {
            if (target.node.id in covered) continue
            val match = bestMatch(target, annotations, defaults, unsupported, matched) ?: continue
            contributions += match.contract.contribution(target.node)
            declarationContracts.add(target, match.contract)
        }

        for (target in callTargets) {
            if (target.node.id in covered) continue
            val match = bestMatch(target, annotations, defaults, unsupported, matched)
            val contract =
                match?.contract
                    ?: declarationContracts.resolve(target)?.withRule(FUNCTION_CALL_RULE)
            if (contract != null && contract.intents.isNotEmpty()) {
                contributions += contract.contribution(target.node)
            }
        }

        for ((selector, reason) in unsupported) {
            if (selector in matched) continue
            errors +=
                unsupported(
                    "selector '$selector' is unsupported: $reason",
                )
        }

        return ResultFactory.success(
            KnowledgeInputResolution(
                contributions = contributions.distinct().sortedWith(contributionOrder),
                errors = errors.distinctBy { it.code to it.message }.sortedWith(compareBy({ it.code }, { it.message })),
            ),
        )
    }

    private fun bestMatch(
        target: KnowledgeTarget,
        annotations: List<FunctionAnnotation>,
        defaults: List<PackDefault>,
        unsupported: MutableMap<String, String>,
        matched: MutableSet<String>,
    ): RecordMatch? {
        var selectedAnnotation: FunctionAnnotation? = null
        for (annotation in annotations) {
            when (val match = selectorMatch(annotation.selector, target)) {
                SelectorMatch.Matched -> {
                    matched += annotation.selector.identityKey
                    if (selectedAnnotation == null ||
                        annotationOrder.compare(annotation, selectedAnnotation) < 0
                    ) {
                        selectedAnnotation = annotation
                    }
                }
                is SelectorMatch.Unsupported -> {
                    if (annotation.selector.identityKey !in matched) {
                        unsupported.putIfAbsent(annotation.selector.identityKey, match.reason)
                    }
                }
                SelectorMatch.NoMatch -> Unit
            }
        }
        selectedAnnotation?.let {
            return RecordMatch(
                IntentContract(
                    intents = it.intents,
                    provenance = it.provenance,
                    confidence = it.confidence,
                    rule = FUNCTION_RULE,
                ),
            )
        }

        var selectedDefault: PackDefault? = null
        for (default in defaults) {
            when (val match = selectorMatch(default.selector, target)) {
                SelectorMatch.Matched -> {
                    matched += default.selector.identityKey
                    if (selectedDefault == null ||
                        defaultOrder.compare(default, selectedDefault) < 0
                    ) {
                        selectedDefault = default
                    }
                }
                is SelectorMatch.Unsupported -> {
                    if (default.selector.identityKey !in matched) {
                        unsupported.putIfAbsent(default.selector.identityKey, match.reason)
                    }
                }
                SelectorMatch.NoMatch -> Unit
            }
        }
        return selectedDefault?.let {
            RecordMatch(
                IntentContract(
                    intents = it.intents,
                    provenance = it.provenance,
                    confidence = it.confidence,
                    rule = DEFAULT_RULE,
                ),
            )
        }
    }

    private fun scopeApplies(
        annotation: FunctionAnnotation,
        path: String?,
    ): Boolean =
        when (annotation.scope) {
            AnnotationScope.GLOBAL, AnnotationScope.PROJECT -> true
            AnnotationScope.FILE -> path != null && annotation.path == path
        }

    private fun unsupported(reason: String): AnalysisError =
        AnalysisError(
            code = "KNOWLEDGE_SELECTOR_UNSUPPORTED",
            message = reason,
            severity = ErrorSeverity.WARNING,
        )

    private fun unsupportedScope(annotation: FunctionAnnotation): AnalysisError =
        AnalysisError(
            code = "KNOWLEDGE_SCOPE_UNSUPPORTED",
            message = "FILE-scoped annotation '${annotation.selector.identityKey}' has no path",
            severity = ErrorSeverity.WARNING,
        )

    private data class RecordMatch(val contract: IntentContract)

    private companion object {
        const val OVERRIDE_RULE = KnowledgeRules.SPAN_OVERRIDE
        const val FUNCTION_RULE = KnowledgeRules.FUNCTION_ANNOTATION
        const val DEFAULT_RULE = KnowledgeRules.PACK_DEFAULT
        const val FUNCTION_CALL_RULE = KnowledgeRules.FUNCTION_CALL

        val annotationOrder = KnowledgePrecedence.functionAnnotations(::selectorSpecificity)
        val defaultOrder = KnowledgePrecedence.packDefaults(::selectorSpecificity)

        val contributionOrder =
            compareBy<Contribution>(
                { it.startByte },
                { it.endByte },
                { it.rule },
                { it.provenance.ordinal },
                { it.confidence.value },
                { it.intents.sorted().joinToString("\u0000") },
            )
    }
}

private fun IntentContract.contribution(node: SyntaxNode): Contribution =
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

private fun SourceInput.selectionPath(): String? = when (this) {
    is SourceInput.FilePath -> path
    is SourceInput.InMemory -> path ?: documentId
}

private const val KNOWLEDGE_SOURCE = "knowledge"
