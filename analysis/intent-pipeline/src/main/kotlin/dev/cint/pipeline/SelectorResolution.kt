package dev.cint.pipeline

import dev.cint.contracts.facts.IndexedSource
import dev.cint.contracts.facts.SourceInput
import dev.cint.contracts.knowledge.FunctionSelector
import dev.cint.contracts.knowledge.PatternSelector
import dev.cint.contracts.knowledge.Selector
import dev.cint.contracts.knowledge.SpanSelector
import dev.cint.contracts.ports.ResolvedSelectorTarget
import dev.cint.contracts.ports.SelectorResolution
import dev.cint.contracts.ports.SelectorUnsupportedReason
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory

internal fun resolveSelectorInSource(
    selector: Selector,
    source: IndexedSource,
    cancellation: CancellationToken,
): ContractResult<SelectorResolution> {
    val checked = cancellation.throwIfCancelled()
    if (checked.isFailure) return ResultFactory.failure(checked.errorOrNull!!)
    return ResultFactory.success(
        when (selector) {
            is FunctionSelector -> {
                resolveFunctionSelector(selector, source)
            }

            is SpanSelector -> {
                resolveSpanSelector(selector, source)
            }

            is PatternSelector -> {
                SelectorResolution(
                    selector = selector.identityKey,
                    unsupported =
                        listOf(
                            SelectorUnsupportedReason(
                                reason =
                                    "selector '${selector.identityKey}' is a pattern selector; " +
                                        "knowledge.resolveSelector supports function and span selectors",
                                path = source.pathLabel(),
                            ),
                        ),
                    scannedPaths = listOfNotNull(source.pathLabel()),
                )
            }
        },
    )
}

private fun resolveFunctionSelector(
    selector: FunctionSelector,
    source: IndexedSource,
): SelectorResolution {
    val language = source.facts.syntaxTree?.language
    val path = source.pathLabel()
    val matched = mutableListOf<ResolvedSelectorTarget>()
    val unsupported = linkedMapOf<String, SelectorUnsupportedReason>()
    for (target in selectorTargets(source)) {
        when (val match = selectorMatch(selector, target)) {
            SelectorMatch.Matched -> {
                matched += target.resolved(path, language)
            }

            is SelectorMatch.Unsupported -> {
                val reason =
                    SelectorUnsupportedReason(
                        reason = match.reason,
                        path = path,
                        kind = target.kind.name,
                        nodeId = target.node.id,
                    )
                unsupported.putIfAbsent("${match.reason}|${target.node.id}", reason)
            }

            SelectorMatch.NoMatch -> {
                Unit
            }
        }
    }
    return SelectorResolution(
        selector = selector.identityKey,
        matched = matched.sortedWith(targetOrder),
        unsupported = unsupported.values.sortedWith(unsupportedOrder),
        scannedPaths = listOfNotNull(path),
    )
}

private fun resolveSpanSelector(
    selector: SpanSelector,
    source: IndexedSource,
): SelectorResolution {
    val path = source.pathLabel()
    val tree = source.facts.syntaxTree
    if (path != selector.path || tree == null) {
        return SelectorResolution(selector = selector.identityKey, scannedPaths = listOfNotNull(path))
    }
    if (source.contentHash != selector.contentHash) {
        return SelectorResolution(
            selector = selector.identityKey,
            unsupported =
                listOf(
                    SelectorUnsupportedReason(
                        reason =
                            "selector '${selector.identityKey}' uses contentHash '${selector.contentHash}', " +
                                "but indexed content hash is '${source.contentHash}'",
                        path = path,
                    ),
                ),
            scannedPaths = listOfNotNull(path),
        )
    }
    val node =
        tree.nodes.values
            .filter { it.span == selector.span }
            .sortedWith(compareBy({ it.startByte }, { it.endByte }, { it.id }))
            .firstOrNull()
    return SelectorResolution(
        selector = selector.identityKey,
        matched =
            node
                ?.let {
                    listOf(
                        ResolvedSelectorTarget(
                            path = path,
                            language = tree.language,
                            kind = it.kind,
                            span = it.span,
                            startByte = it.startByte,
                            endByte = it.endByte,
                            nodeId = it.id,
                        ),
                    )
                }.orEmpty(),
        scannedPaths = listOfNotNull(path),
    )
}

private fun selectorTargets(source: IndexedSource): List<KnowledgeTarget> {
    val tree = source.facts.syntaxTree ?: return emptyList()
    val declarations = source.facts.declarations.associateBy { it.nodeId }
    val comments = source.facts.comments.associateBy { it.nodeId }
    val functions =
        source.facts.functions
            .sortedBy { it.nodeId }
            .mapNotNull { function ->
                tree.getNode(function.nodeId)?.let {
                    functionTarget(function, it, declarations[it.id], comments, tree)
                }
            }
    val graph = CallGraph.of(source.facts)
    val calls =
        if (graph == null) {
            emptyList()
        } else {
            graph.calls
                .mapNotNull { call ->
                    val node = tree.getNode(call.nodeId) ?: tree.getNode(call.siteId) ?: return@mapNotNull null
                    callTarget(
                        call,
                        node,
                        tree,
                        source.facts.imports,
                        source.semanticFacts,
                        graph.isInChain(call),
                    )
                }.sortedWith(compareBy({ it.node.startByte }, { it.node.endByte }, { it.node.id }))
        }
    return functions + calls
}

private fun KnowledgeTarget.resolved(
    path: String?,
    language: String?,
): ResolvedSelectorTarget =
    ResolvedSelectorTarget(
        path = path,
        language = language,
        kind = kind.name,
        name = name,
        names = names.sorted(),
        span = node.span,
        startByte = node.startByte,
        endByte = node.endByte,
        nodeId = node.id,
        receiverTypes = receiverTypes.sorted(),
        declaringTypes = declaringTypes.sorted(),
        importPaths = importPaths.sorted(),
        signatures = signatures.sorted(),
    )

private fun IndexedSource.pathLabel(): String? =
    when (val input = input) {
        is SourceInput.FilePath -> input.path
        is SourceInput.InMemory -> input.path ?: input.documentId
    }

private val targetOrder =
    compareBy<ResolvedSelectorTarget>(
        { it.path.orEmpty() },
        { it.startByte },
        { it.endByte },
        { it.nodeId },
        { it.kind },
    )

private val unsupportedOrder =
    compareBy<SelectorUnsupportedReason>(
        { it.path.orEmpty() },
        { it.reason },
        { it.nodeId.orEmpty() },
    )
