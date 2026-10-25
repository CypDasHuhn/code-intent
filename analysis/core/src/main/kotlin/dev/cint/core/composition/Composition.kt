package dev.cint.core.composition

import dev.cint.contracts.data.Annotation
import dev.cint.contracts.data.Confidence
import dev.cint.contracts.data.Contribution
import dev.cint.contracts.data.Intent
import dev.cint.contracts.data.Intents
import dev.cint.contracts.data.Provenance
import dev.cint.contracts.data.Role
import dev.cint.contracts.data.Span
import dev.cint.contracts.facts.FactBatch
import dev.cint.contracts.facts.SyntaxNode
import dev.cint.core.CoreContributors
import dev.cint.core.facts.FactsIndex
import dev.cint.core.store.AnnotationStore

private const val COMPOSITION_KEY_PREFIX = "\u0000composition:"
private const val RETURN_KEY = "${COMPOSITION_KEY_PREFIX}return"
private const val DERIVATIVE_KEY = "${COMPOSITION_KEY_PREFIX}derivative"
private const val SCAFFOLD_KEY = "${COMPOSITION_KEY_PREFIX}scaffold"
private const val CALL_CHAIN_KEY = "${COMPOSITION_KEY_PREFIX}call-chain"
private const val SOURCE_KEY_PREFIX = "${COMPOSITION_KEY_PREFIX}source:"

private const val RETURN_RULE = CoreContributors.RETURN_CATEGORY
private const val DERIVATIVE_RULE = CoreContributors.DERIVATIVE_USE
private const val SCAFFOLD_RULE = CoreContributors.SCAFFOLD

private val MUTATION_INTENTS =
    setOf(Intents.DATA_MODIFY, Intents.LOCAL_VALUE_MODIFY, Intents.BOOKKEEPING)
private val EFFECT_INTENTS =
    MUTATION_INTENTS + setOf(Intents.LOGGING, Intents.ERROR_HANDLING)
private const val COMPOSITION_SOURCE = "treesitter"

private const val DERIVATIVE_CONFIDENCE = 0.7
private const val DERIVATIVE_UNKNOWN_READ_ONLY_CONFIDENCE = 0.55

class NonConvergentComposition(
    val key: String,
    val iterations: Long,
    reason: String,
) : IllegalStateException("composition '$key' did not converge after $iterations iterations: $reason")

class Composition {
    fun compose(
        sourceIntents: Set<Intent>,
        facts: FactBatch,
        sources: List<Contribution>,
        contextSources: List<Contribution> = emptyList(),
        config: CompositionConfig = CompositionConfig(),
    ): List<Annotation> {
        if (facts.syntaxTree == null) return emptyList()

        val source =
            sources.filter { contribution ->
                contribution.role == Role.SOURCE_INTENT &&
                    contribution.intents.any { it in sourceIntents }
            }
        val contextContributions =
            source + contextSources.filter { it.role == Role.SOURCE_INTENT }

        val store = AnnotationStore()
        for ((rule, contributions) in contextContributions.groupBy { it.rule }) {
            store.replaceRule("$SOURCE_KEY_PREFIX$rule", contributions)
        }
        val context = CompositionContext(facts, store, config)

        val callChains = if (config.callChainsEnabled) callChainContributions(context) else emptyList()
        val returns = returnContributions(context)
        store.replaceRule(RETURN_KEY, returns)
        val limit = compositionLimit(facts)
        val derivatives =
            if (config.derivativeUseEnabled) {
                settleComposition(store, DERIVATIVE_KEY, limit) { derivativeContributions(context) }
            } else {
                emptyList()
            }
        val scaffolds =
            if (config.scaffoldsEnabled) {
                settleComposition(store, SCAFFOLD_KEY, limit) { scaffoldContributions(context) }
            } else {
                emptyList()
            }

        val output = AnnotationStore()
        listOf(
            RETURN_KEY to returns,
            DERIVATIVE_KEY to derivatives,
            SCAFFOLD_KEY to scaffolds,
            CALL_CHAIN_KEY to callChains,
        ).forEach { (key, contributions) ->
            if (contributions.isNotEmpty()) {
                output.replaceRule(key, contributions)
            }
        }
        return output.annotations()
    }
}

internal fun compositionLimit(facts: FactBatch): Long {
    val length =
        facts.syntaxTree
            ?.let { it.getNode(it.rootId)?.endByte }
            ?.toLong()
            ?: 0L
    return (length + 1L) * (length + 2L) / 2L + 1L
}

internal fun settleComposition(
    store: AnnotationStore,
    key: String,
    limit: Long,
    produce: () -> List<Contribution>,
): List<Contribution> {
    var output = produce()
    val seen = mutableSetOf<List<Contribution>>()
    var iterations = 0L
    while (true) {
        if (!store.replaceRule(key, output)) return output
        iterations++
        if (!seen.add(output)) {
            throw NonConvergentComposition(key, iterations, "repeated state")
        }
        if (iterations > limit) {
            throw NonConvergentComposition(key, iterations, "iteration limit $limit exceeded")
        }
        output = produce()
    }
}

internal class CompositionContext(
    val facts: FactBatch,
    val store: AnnotationStore,
    val config: CompositionConfig,
) {
    val index = FactsIndex(facts)
    val root: SyntaxNode? = index.root

    fun covering(node: SyntaxNode): List<Annotation> =
        store
            .annotations()
            .filter { it.startByte == node.startByte && it.endByte == node.endByte }

    fun containing(node: SyntaxNode): List<Annotation> =
        store
            .annotations()
            .filter { annotation ->
                annotation.startByte <= node.startByte && annotation.endByte >= node.endByte
            }

    fun intentsAt(node: SyntaxNode): Set<Intent> {
        val covering = containing(node)
        if (covering.isEmpty()) return setOf(Intents.UNTAGGED)
        val smallest = covering.minOf { it.endByte - it.startByte }
        return covering
            .filter { it.endByte - it.startByte == smallest }
            .flatMapTo(linkedSetOf()) { it.intents }
    }

    fun coverage(
        node: SyntaxNode,
        effect: Boolean = true,
    ): Coverage {
        val covering = covering(node)
        if (covering.isNotEmpty()) {
            return Coverage(covering.flatMap { it.intents }.toSet())
        }
        return if (effect) Coverage(setOf(Intents.UNTAGGED)) else Coverage(emptySet())
    }
}

internal data class Coverage(
    val intents: Set<Intent>,
)

internal data class ValueIntents(
    val intents: Set<Intent>,
    val confidence: Double,
)

internal fun derivativeContributions(ctx: CompositionContext): List<Contribution> {
    val result = mutableListOf<Contribution>()
    val index = ctx.index
    val facts = ctx.facts
    val root = index.root
    val properties = derivativeProperties(ctx)

    for (property in properties) {
        val name = property.name ?: continue
        val nameNode = index.node(property.nameNodeId) ?: continue
        val initializer = index.node(property.initializerId) ?: continue
        val node = index.node(property.nodeId) ?: continue

        val scope = index.enclosingFunction(node)?.let { index.node(it.nodeId) } ?: root
        val references =
            facts.references
                .filter { it.name == name }
                .flatMap { it.nodeIds }
                .mapNotNull { index.node(it) }
        val uses =
            references
                .filter { reference ->
                    val start = scope?.startByte ?: Int.MIN_VALUE
                    val end = scope?.endByte ?: Int.MAX_VALUE
                    reference.startByte in start until end
                }.filter { it.startByte != nameNode.startByte || it.endByte != nameNode.endByte }
                .filterNot { it.startByte >= initializer.startByte && it.endByte <= initializer.endByte }
                .filterNot { isWriteTarget(ctx, it) }
        if (uses.isEmpty()) continue

        val useIntents = uses.map { ctx.intentsAt(it) }
        val allClassified =
            useIntents.all { intents ->
                Intents.UNTAGGED !in intents && intents.any { it != Intents.UNTAGGED }
            }
        val carriesReturnValue = useIntents.any { Intents.RETURN_VALUE in it }
        if (!allClassified && !carriesReturnValue) continue

        val confidence = readOnlyConfidence(ctx, initializer) ?: continue
        val intents =
            if (allClassified) {
                useIntents.flatten().filter { it != Intents.UNTAGGED }.toSet()
            } else {
                setOf(Intents.RETURN_VALUE)
            }
        if (intents.isEmpty()) continue

        result +=
            raw(
                node,
                Role.DERIVATIVE_USE,
                intents,
                DERIVATIVE_RULE,
                COMPOSITION_SOURCE,
                confidence,
            )
        for (write in facts.writes) {
            if (writeTargetName(ctx, write) != name) continue
            val writeNode = index.node(write.nodeId) ?: continue
            result +=
                raw(
                    writeNode,
                    Role.DERIVATIVE_USE,
                    intents,
                    DERIVATIVE_RULE,
                    COMPOSITION_SOURCE,
                    confidence,
                )
        }
    }

    for (property in properties) {
        val initializer = index.node(property.initializerId) ?: continue
        val node = index.node(property.nodeId) ?: continue
        val confidence = readOnlyConfidence(ctx, initializer) ?: continue
        val valueIntents = valueSourceIntents(ctx, initializer)
        val intents =
            valueIntents
                .flatMap { it.intents }
                .filter { it != Intents.UNTAGGED }
                .toSet()
        if (intents.isEmpty()) continue

        result +=
            raw(
                node,
                Role.DERIVATIVE_USE,
                intents,
                DERIVATIVE_RULE,
                COMPOSITION_SOURCE,
                valueIntents.minOf { it.confidence },
            )
    }

    for (write in facts.writes) {
        if (write.kind != dev.cint.contracts.facts.WriteKind.ASSIGNMENT) continue
        val node = index.node(write.nodeId) ?: continue
        val value = index.node(write.valueId) ?: continue
        val confidence = readOnlyConfidence(ctx, value) ?: continue
        val valueIntents = valueSourceIntents(ctx, value)
        val intents =
            valueIntents
                .flatMap { it.intents }
                .filter { it != Intents.UNTAGGED }
                .toSet()
        if (intents.isEmpty()) continue

        result +=
            raw(
                node,
                Role.DERIVATIVE_USE,
                intents,
                DERIVATIVE_RULE,
                COMPOSITION_SOURCE,
                valueIntents.minOf { it.confidence },
            )
    }

    for (function in facts.functions) {
        val node = index.node(function.nodeId) ?: continue
        val nameNode = index.node(function.nameNodeId) ?: continue
        val name = function.name ?: nameNode.text
        if (name.isEmpty()) continue
        if (!isAnnotatedPure(ctx, function.nodeId)) continue

        val functionReferences =
            facts.references
                .filter { it.name == name }
                .flatMap { it.nodeIds }
                .mapNotNull { index.node(it) }
                .filter { reference ->
                    (reference.startByte != nameNode.startByte || reference.endByte != nameNode.endByte) &&
                        (reference.startByte < node.startByte || reference.endByte > node.endByte) &&
                        !isDeclarationName(ctx, reference)
                }
        if (functionReferences.isEmpty()) continue

        val places = functionReferences.mapNotNull { derivativePlaceIntents(ctx, it) }
        if (places.size != functionReferences.size) continue

        val intents = places.flatten().toSet()
        if (intents.none { it != Intents.UNTAGGED }) continue

        result +=
            raw(
                node,
                Role.DERIVATIVE_USE,
                intents,
                DERIVATIVE_RULE,
                COMPOSITION_SOURCE,
                DERIVATIVE_CONFIDENCE,
            )
    }

    return merge(result)
}

internal fun scaffoldContributions(ctx: CompositionContext): List<Contribution> {
    val result = mutableListOf<Contribution>()
    result += ifScaffolds(ctx)
    result += catchScaffolds(ctx)
    result += tryScaffolds(ctx)
    result += functionScaffolds(ctx)
    return result
}

internal fun returnContributions(ctx: CompositionContext): List<Contribution> {
    val result = mutableListOf<Contribution>()
    for (site in returnSites(ctx)) {
        val guard = site.guards.asReversed().firstOrNull { guardIntents(ctx, it.nodeId).isNotEmpty() }
        val intents =
            if (guard != null && (guard.onTrue || isRejection(ctx, site.expressionId))) {
                guardIntents(ctx, guard.nodeId)
            } else {
                setOf(Intents.UNTAGGED)
            }
        result +=
            raw(
                site.node,
                Role.SCAFFOLD,
                intents,
                RETURN_RULE,
                "cfg",
                if (intents == setOf(Intents.UNTAGGED)) 1.0 else 0.8,
            )
    }
    return result
}

private data class CompositionGuard(
    val nodeId: String,
    val onTrue: Boolean,
)

private data class CompositionReturnSite(
    val node: SyntaxNode,
    val expressionId: String?,
    val guards: List<CompositionGuard>,
)

private fun returnSites(ctx: CompositionContext): List<CompositionReturnSite> {
    val result = mutableListOf<CompositionReturnSite>()
    for (function in ctx.facts.functions) {
        val body = ctx.index.node(function.bodyId) ?: continue
        walkBody(ctx, body, emptyList(), result)
    }
    if (ctx.facts.functions.isEmpty()) {
        for (fact in ctx.facts.returns) {
            val node = ctx.index.node(fact.nodeId) ?: continue
            if (!isReturnStatement(node)) continue
            val guards =
                fact.guardIds.map { guardId ->
                    val ifFact =
                        ctx.facts.ifs.firstOrNull {
                            it.nodeId == guardId || it.conditionId == guardId
                        }
                    val onTrue =
                        ifFact
                            ?.thenBodyId
                            ?.let(ctx.index::node)
                            ?.let { ctx.index.contains(it, node) }
                            ?: true
                    CompositionGuard(guardId, onTrue)
                }
            result += CompositionReturnSite(node, fact.expressionId, guards)
        }
    }
    return result
}

private fun walkBody(
    ctx: CompositionContext,
    container: SyntaxNode,
    guards: List<CompositionGuard>,
    out: MutableList<CompositionReturnSite>,
) {
    val statements = ctx.index.statements(container)
    if (statements.isNotEmpty()) {
        statements.forEach { walkStatement(ctx, it, guards, out) }
    } else {
        walkStatement(ctx, container, guards, out)
    }
}

private fun walkStatement(
    ctx: CompositionContext,
    statement: SyntaxNode,
    guards: List<CompositionGuard>,
    out: MutableList<CompositionReturnSite>,
) {
    val ifFact = ctx.index.ifFact(statement)
    if (ifFact != null) {
        val hasCondition = ctx.index.node(ifFact.conditionId) != null
        val thenBody = ctx.index.node(ifFact.thenBodyId)
        if (thenBody != null) {
            val thenGuards =
                if (hasCondition) guards + CompositionGuard(ifFact.nodeId, true) else guards
            walkBody(ctx, thenBody, thenGuards, out)
        }
        val elseBody = ctx.index.node(ifFact.elseBodyId)
        if (elseBody != null) {
            val elseGuards =
                if (hasCondition) guards + CompositionGuard(ifFact.nodeId, false) else guards
            walkBody(ctx, elseBody, elseGuards, out)
        }
        return
    }

    val loop = ctx.facts.guards.firstOrNull { it.nodeId == statement.id && it.kind == dev.cint.contracts.facts.GuardKind.LOOP }
    if (loop != null) {
        val body = ctx.index.node(loop.thenBodyId)
        if (body != null) walkBody(ctx, body, guards, out)
        return
    }

    val tryFact = ctx.facts.tries.firstOrNull { it.nodeId == statement.id }
    if (tryFact != null) {
        ctx.index.node(tryFact.bodyId)?.let { walkBody(ctx, it, guards, out) }
        for (catchId in tryFact.catchIds) {
            ctx.index.node(catchId)?.let { walkBody(ctx, it, guards, out) }
        }
        ctx.index.node(tryFact.finallyBlockId)?.let { walkBody(ctx, it, guards, out) }
        return
    }

    val returnFact = ctx.index.returnFact(statement)
    if (returnFact != null && isReturnStatement(statement)) {
        out +=
            CompositionReturnSite(
                node = statement,
                expressionId = returnFact.expressionId ?: exitExpression(ctx, statement)?.id,
                guards = guards,
            )
        return
    }

    if (ctx.index.statements(statement).isNotEmpty()) {
        walkBody(ctx, statement, guards, out)
    }
}

private fun ifScaffolds(ctx: CompositionContext): List<Contribution> {
    val result = mutableListOf<Contribution>()
    for (guard in ctx.facts.ifs) {
        val node = ctx.index.node(guard.nodeId) ?: continue
        val condition = ctx.index.node(guard.conditionId) ?: continue
        val thenBody = ctx.index.node(guard.thenBodyId) ?: continue
        val thenIntents = mutableSetOf<Intent>()
        thenIntents += conditionIntents(ctx, condition)
        thenIntents += bodyIntents(ctx, thenBody)

        val elseBody = ctx.index.node(guard.elseBodyId)
        if (elseBody == null) {
            if (!hasRealIntent(thenIntents)) continue
            result += raw(node, Role.SCAFFOLD, thenIntents, SCAFFOLD_RULE, COMPOSITION_SOURCE, 0.9)
            continue
        }

        val elseIntents = bodyIntents(ctx, elseBody).toMutableSet()
        if (thenIntents == elseIntents) {
            if (!hasRealIntent(thenIntents)) continue
            result += raw(node, Role.SCAFFOLD, thenIntents, SCAFFOLD_RULE, COMPOSITION_SOURCE, 0.9)
            continue
        }

        if (!hasRealIntent(thenIntents) && !hasRealIntent(elseIntents)) continue
        if (thenIntents.isNotEmpty()) {
            result +=
                tagRange(node, thenBody, thenIntents, SCAFFOLD_RULE, COMPOSITION_SOURCE, 0.9)
        }
        if (elseIntents.isNotEmpty()) {
            val start = ctx.index.node(guard.elseKeywordId) ?: elseBody
            result +=
                tagRange(start, elseBody, elseIntents, SCAFFOLD_RULE, COMPOSITION_SOURCE, 0.9)
        }
    }
    return result
}

private fun catchScaffolds(ctx: CompositionContext): List<Contribution> {
    val result = mutableListOf<Contribution>()
    for (tryFact in ctx.facts.tries) {
        for (catchId in tryFact.catchIds) {
            val catchNode = ctx.index.node(catchId) ?: continue
            val intents = bodyIntents(ctx, catchNode)
            if (!hasRealIntent(intents)) continue
            val previous = previousBlockClose(ctx, catchNode)
            result +=
                if (previous != null) {
                    tagRange(previous, catchNode, intents, SCAFFOLD_RULE, COMPOSITION_SOURCE, 0.9)
                } else {
                    raw(catchNode, Role.SCAFFOLD, intents, SCAFFOLD_RULE, COMPOSITION_SOURCE, 0.9)
                }
        }
    }
    return result
}

private fun previousBlockClose(
    ctx: CompositionContext,
    node: SyntaxNode,
): SyntaxNode? {
    val parent = ctx.index.node(node.parentId) ?: return null
    val index = parent.childIds.indexOf(node.id)
    if (index <= 0) return null
    val previous = ctx.index.node(parent.childIds[index - 1]) ?: return null
    if (isBlockClose(previous)) return previous
    return previous.childIds.mapNotNull(ctx.index::node).lastOrNull(::isBlockClose)
}

private fun isBlockClose(node: SyntaxNode): Boolean = node.text.trim() == "}"

private fun tryScaffolds(ctx: CompositionContext): List<Contribution> {
    val result = mutableListOf<Contribution>()
    for (tryFact in ctx.facts.tries) {
        val node = ctx.index.node(tryFact.nodeId) ?: continue
        val catches = tryFact.catchIds.mapNotNull { ctx.index.node(it) }
        if (catches.isEmpty()) continue

        val tryBody = ctx.index.node(tryFact.bodyId) ?: node
        val tryBodyIntents = bodyIntents(ctx, tryBody)
        val catchIntents = catches.map { bodyIntents(ctx, it) }
        val finallyIntents =
            ctx.index.node(tryFact.finallyBlockId)?.let { bodyIntents(ctx, it) } ?: emptySet()

        val catchesCovered =
            catchIntents.all { it.isNotEmpty() && Intents.UNTAGGED !in it }
        val catchesHaveRealIntent = catchIntents.any { hasRealIntent(it) }
        val wholeCovered =
            tryBodyIntents.isNotEmpty() &&
                Intents.UNTAGGED !in tryBodyIntents &&
                catchesCovered &&
                (
                    tryFact.finallyBlockId == null ||
                        (finallyIntents.isNotEmpty() && Intents.UNTAGGED !in finallyIntents)
                )

        if (wholeCovered) {
            val intents =
                buildSet {
                    addAll(tryBodyIntents)
                    addAll(catchIntents.flatten())
                    addAll(finallyIntents)
                }
            result += raw(node, Role.SCAFFOLD, intents, SCAFFOLD_RULE, COMPOSITION_SOURCE, 0.9)
        } else if (catchesCovered && catchesHaveRealIntent) {
            val start = ctx.index.node(tryFact.headerStartId) ?: node
            val end =
                ctx.index.node(tryFact.headerEndId)
                    ?: start.copy(
                        endByte = start.startByte + 5,
                        endLine = start.startLine,
                        endCol = start.startCol + 5,
                    )
            val intents = catchIntents.flatten().toSet()
            result += tagRange(start, end, intents, SCAFFOLD_RULE, COMPOSITION_SOURCE, 0.8)
        }
    }
    return result
}

private fun functionScaffolds(ctx: CompositionContext): List<Contribution> {
    val result = mutableListOf<Contribution>()
    for (function in ctx.facts.functions) {
        val node = ctx.index.node(function.nodeId) ?: continue
        val body = ctx.index.node(function.bodyId) ?: continue
        val intents = bodyIntents(ctx, body)
        if (intents.isEmpty()) continue
        if (Intents.UNTAGGED in intents) continue
        if (!hasRealIntent(intents)) continue
        result += raw(node, Role.SCAFFOLD, intents, SCAFFOLD_RULE, COMPOSITION_SOURCE, 0.9)
    }
    return result
}

private fun conditionIntents(
    ctx: CompositionContext,
    condition: SyntaxNode,
): Set<Intent> {
    val effective = unwrapParenthesized(ctx, condition)
    val covered = ctx.coverage(effective).intents
    if (hasRealIntent(covered)) return covered
    val value = ctx.index.value(effective)
    if (value?.constant == true || value?.sideEffectFree == true) return emptySet()
    return covered
}

private fun unwrapParenthesized(
    ctx: CompositionContext,
    node: SyntaxNode,
): SyntaxNode {
    val text = node.text.trim()
    if (!text.startsWith("(") || !text.endsWith(")")) return node
    return node.childIds
        .mapNotNull(ctx.index::node)
        .firstOrNull { child -> child.text.trim() != "(" && child.text.trim() != ")" }
        ?: node
}

private fun bodyIntents(
    ctx: CompositionContext,
    body: SyntaxNode,
): Set<Intent> {
    val statements = ctx.index.statements(body)
    if (statements.isNotEmpty()) {
        return statements.flatMap { statementIntents(ctx, it) }.toSet()
    }

    exitIntents(ctx, body)?.let { return it }

    val direct = ctx.coverage(body).intents
    if (hasRealIntent(direct)) return direct
    val expression = expressionOf(ctx, body) ?: return direct
    val inner = ctx.coverage(expression).intents
    return if (hasRealIntent(inner)) inner else direct
}

private fun statementIntents(
    ctx: CompositionContext,
    statement: SyntaxNode,
): Set<Intent> {
    exitIntents(ctx, statement)?.let { return it }

    val direct = ctx.coverage(statement).intents
    if (hasRealIntent(direct)) return direct
    val expression = expressionOf(ctx, statement) ?: return direct
    val inner = ctx.coverage(expression).intents
    return if (hasRealIntent(inner)) inner else direct
}

private fun expressionOf(
    ctx: CompositionContext,
    node: SyntaxNode,
): SyntaxNode? =
    ctx.index
        .statement(node)
        ?.expressionId
        ?.let(ctx.index::node)

private fun exitIntents(
    ctx: CompositionContext,
    node: SyntaxNode,
): Set<Intent>? {
    val statement = ctx.index.statement(node)
    val returnFact = ctx.index.returnFact(node)
    if (statement == null && returnFact == null) return null
    if (statement?.kind == dev.cint.contracts.facts.StatementKind.CONTINUE ||
        statement?.kind == dev.cint.contracts.facts.StatementKind.BREAK
    ) {
        return emptySet()
    }
    val isReturn = returnFact != null && isReturnStatement(node)
    if (!isReturn && statement?.kind != dev.cint.contracts.facts.StatementKind.RETURN) return null

    val covered = ctx.coverage(node).intents - Intents.RETURN_VALUE
    if (hasRealIntent(covered)) return covered

    val expression = exitExpression(ctx, node) ?: return emptySet()
    val value = ctx.index.value(expression)
    if (expression.text.trim() == "false" || value?.booleanValue == false) return emptySet()
    if (value?.constant == true || value?.sideEffectFree == true) return setOf(Intents.UNTAGGED)
    val intents = ctx.intentsAt(expression) - Intents.RETURN_VALUE
    return if (hasRealIntent(intents)) intents else setOf(Intents.UNTAGGED)
}

private fun isReturnStatement(node: SyntaxNode): Boolean =
    node.kind == "return" ||
        node.kind.endsWith("return_statement") ||
        node.text.trimStart().startsWith("return")

private fun exitExpression(
    ctx: CompositionContext,
    node: SyntaxNode,
): SyntaxNode? =
    node.childIds
        .mapNotNull(ctx.index::node)
        .filterNot { child ->
            child.text.trim() in setOf("return", "continue", "break") ||
                child.kind.contains("comment")
        }.lastOrNull()

private fun guardIntents(
    ctx: CompositionContext,
    guardId: String,
): Set<Intent> {
    val guard = ctx.index.node(guardId) ?: return emptySet()
    val annotations = ctx.store.annotations()
    return annotations
        .filter { annotation ->
            annotation.role == Role.SOURCE_INTENT &&
                (
                    (annotation.startByte >= guard.startByte && annotation.endByte <= guard.endByte) ||
                        (annotation.startByte <= guard.startByte && annotation.endByte >= guard.endByte)
                ) &&
                annotation.provenance.any { it.certifiesReturn }
        }.flatMapTo(linkedSetOf()) { it.intents }
        .filterTo(linkedSetOf()) { it != Intents.UNTAGGED }
}

private fun isRejection(
    ctx: CompositionContext,
    expressionId: String?,
): Boolean {
    val expression = ctx.index.node(expressionId) ?: return true
    return ctx.index.value(expression)?.rejection == true
}

private fun valueSourceIntents(
    ctx: CompositionContext,
    node: SyntaxNode,
): List<ValueIntents> =
    ctx.index
        .descendants(node)
        .flatMap { descendant ->
            ctx
                .covering(descendant)
                .filter { it.role == Role.SOURCE_INTENT }
                .mapNotNull { annotation ->
                    val intents = annotation.intents - EFFECT_INTENTS
                    if (intents.isEmpty()) {
                        null
                    } else {
                        ValueIntents(intents, annotation.confidence.value)
                    }
                }
        }.distinct()
        .toList()

private fun allSourceAnnotations(
    ctx: CompositionContext,
    node: SyntaxNode,
): List<Annotation> =
    ctx.index
        .descendants(node)
        .flatMap { ctx.covering(it).filter { annotation -> annotation.role == Role.SOURCE_INTENT } }
        .distinct()
        .toList()

private fun isWriteTarget(
    ctx: CompositionContext,
    reference: SyntaxNode,
): Boolean =
    ctx.facts.writes.any { write ->
        val target = ctx.index.node(write.referenceId)
        if (target != null) {
            target.startByte <= reference.startByte && target.endByte >= reference.endByte
        } else {
            val node = ctx.index.node(write.nodeId)
            node != null &&
                reference.startByte >= node.startByte &&
                reference.endByte <= node.endByte
        }
    }

private fun writeTargetName(
    ctx: CompositionContext,
    write: dev.cint.contracts.facts.WriteFact,
): String? =
    write.targetName
        ?: ctx.index.node(write.referenceId)?.text
        ?: return null

private fun derivativePlaceIntents(
    ctx: CompositionContext,
    reference: SyntaxNode,
): Set<Intent>? {
    val place = ctx.index.enclosingDeclaration(reference) ?: return null
    val placeNode = ctx.index.node(place.nodeId) ?: return null
    val annotations = ctx.covering(placeNode).filter { it.role == Role.DERIVATIVE_USE }
    if (annotations.isEmpty()) return null
    return annotations.flatMap { it.intents }.toSet()
}

private fun readOnlyConfidence(
    ctx: CompositionContext,
    node: SyntaxNode,
): Double? {
    if (hasKnownWrite(ctx, node)) return null
    if (hasMutationIntent(ctx, node)) return null
    val value = ctx.index.value(node)
    val confidence =
        when {
            value?.constant == true || value?.sideEffectFree == true -> DERIVATIVE_CONFIDENCE
            value?.kind == dev.cint.contracts.facts.ValueKind.IDENTIFIER -> DERIVATIVE_CONFIDENCE
            isAnnotatedPureCall(ctx, node) -> DERIVATIVE_CONFIDENCE
            else -> DERIVATIVE_UNKNOWN_READ_ONLY_CONFIDENCE
        }
    return if (confidence < ctx.config.derivativeMinConfidence) null else confidence
}

private fun hasKnownWrite(
    ctx: CompositionContext,
    node: SyntaxNode,
): Boolean =
    ctx.facts.writes.any { write ->
        val writeNode = ctx.index.node(write.nodeId) ?: return@any false
        writeNode.startByte >= node.startByte && writeNode.endByte <= node.endByte
    }

private fun hasMutationIntent(
    ctx: CompositionContext,
    node: SyntaxNode,
): Boolean =
    allSourceAnnotations(ctx, node).any { annotation ->
        annotation.intents.any { it in MUTATION_INTENTS }
    }

private fun derivativeProperties(ctx: CompositionContext): List<dev.cint.contracts.facts.PropertyFact> {
    val properties = ctx.facts.properties
    return properties.filter { property ->
        val node = ctx.index.node(property.nodeId) ?: return@filter false
        properties.none { other ->
            other.nodeId != property.nodeId &&
                ctx.index.node(other.nodeId)?.let { ctx.index.contains(node, it) } == true
        }
    }
}

private fun isAnnotatedPureCall(
    ctx: CompositionContext,
    node: SyntaxNode,
): Boolean {
    val call =
        ctx.facts.calls.firstOrNull { it.nodeId == node.id || it.siteId == node.id }
            ?: return false
    if (call.receiverId != null) return false
    val name = call.callee ?: return false
    val function = ctx.facts.functions.firstOrNull { it.name == name } ?: return false
    return isAnnotatedPure(ctx, function.nodeId)
}

private fun isAnnotatedPure(
    ctx: CompositionContext,
    nodeId: String,
): Boolean {
    if (ctx.config.pureAnnotations.isEmpty()) return false
    val declaration = ctx.facts.declarations.firstOrNull { it.nodeId == nodeId } ?: return false
    return declaration.annotations.any { annotation ->
        ctx.config.pureAnnotations.any { pureAnnotationMatches(annotation, it) }
    }
}

internal fun pureAnnotationMatches(
    annotation: String,
    pattern: String,
): Boolean {
    val normalized = normalizeAnnotationText(annotation)
    val expected = normalizeAnnotationText(pattern)
    if (normalized.contains('(') || expected.contains('(')) {
        return normalized == expected
    }
    return normalized == expected || normalized.endsWith(".$expected")
}

/**
 * Language adapters normalize annotation text before emitting DeclarationFact
 * annotations. Language-specific use-site targets are owned by ticket 072.
 */
private fun normalizeAnnotationText(text: String): String =
    text
        .removePrefix("@")
        .replace(Regex("""\s+"""), "")
        .removePrefix("[")
        .removeSuffix("]")

private fun isDeclarationName(
    ctx: CompositionContext,
    reference: SyntaxNode,
): Boolean =
    ctx.facts.declarations.any { declaration ->
        declaration.nameNodeId == reference.id
    }

private fun hasRealIntent(intents: Set<Intent>): Boolean = intents.any { it != Intents.UNTAGGED }

private fun raw(
    node: SyntaxNode,
    role: Role,
    intents: Set<Intent>,
    rule: String,
    source: String,
    confidence: Double,
): Contribution =
    Contribution(
        rule = rule,
        role = role,
        intents = intents,
        span = node.span,
        source = source,
        confidence = Confidence.fromDouble(confidence),
        startByte = node.startByte,
        endByte = node.endByte,
        provenance = Provenance.DERIVED,
    )

private fun tagRange(
    start: SyntaxNode,
    end: SyntaxNode,
    intents: Set<Intent>,
    rule: String,
    source: String,
    confidence: Double,
    role: Role = Role.SCAFFOLD,
): Contribution =
    Contribution(
        rule = rule,
        role = role,
        intents = intents,
        span = Span(start.startLine, start.startCol, end.endLine, end.endCol),
        source = source,
        confidence = Confidence.fromDouble(confidence),
        startByte = start.startByte,
        endByte = end.endByte,
        provenance = Provenance.DERIVED,
    )

private fun merge(contributions: List<Contribution>): List<Contribution> {
    val merged = linkedMapOf<Triple<Role, Int, Int>, Contribution>()
    for (contribution in contributions) {
        val key = Triple(contribution.role, contribution.startByte, contribution.endByte)
        val previous = merged[key]
        merged[key] =
            previous?.copy(
                intents = previous.intents + contribution.intents,
                confidence = previous.confidence.min(contribution.confidence),
            )
                ?: contribution
    }
    return merged.values.toList()
}
