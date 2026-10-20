package dev.cint.core.composition

import dev.cint.api.Annotation
import dev.cint.api.Contribution
import dev.cint.api.Role
import dev.cint.api.Span
import dev.cint.api.SyntaxNode
import dev.cint.api.UNTAGGED_INTENT
import dev.cint.core.CoreContributors
import dev.cint.core.config.IntentConfig
import dev.cint.core.engine.AnalysisContext
import dev.cint.core.engine.RuleContext
import dev.cint.core.flow.ControlFlow
import dev.cint.core.flow.Guard
import dev.cint.core.flow.ReturnSite
import dev.cint.core.model.SourceFile
import dev.cint.core.oracle.TypeOracle
import dev.cint.core.scheduler.TerminalNode
import dev.cint.core.store.AnnotationStore
import dev.cint.core.syntax.SyntaxModel
import dev.cint.core.syntax.SyntaxTree

private const val COMPOSITION_KEY_PREFIX = "\u0000composition:"
private const val RETURN_KEY = "${COMPOSITION_KEY_PREFIX}return"
private const val DERIVATIVE_KEY = "${COMPOSITION_KEY_PREFIX}derivative"
private const val SCAFFOLD_KEY = "${COMPOSITION_KEY_PREFIX}scaffold"
private const val SOURCE_KEY_PREFIX = "${COMPOSITION_KEY_PREFIX}source:"

private const val VALIDATION_INTENT = "VALIDATION"
private const val RETURN_RULE = CoreContributors.RETURN_CATEGORY
private const val DERIVATIVE_RULE = CoreContributors.DERIVATIVE_USE
private const val SCAFFOLD_RULE = CoreContributors.SCAFFOLD

private val rejectionSentinels =
    setOf("null", "undefined", "false", "0", "-1", "\"\"", "''", "[]", "{}")

private fun SyntaxNode.raw(
    role: Role,
    intents: Set<String>,
    rule: String,
    source: String,
    confidence: Double,
): Contribution =
    Contribution(
        rule = rule,
        role = role,
        intents = intents,
        evidence = text.replace("\n", " ").take(160),
        span = span(),
        source = source,
        confidence = confidence,
        startByte = startByte,
        endByte = endByte,
    )

private fun tagRange(
    start: SyntaxNode,
    end: SyntaxNode,
    intents: Set<String>,
    rule: String,
    source: String,
    confidence: Double,
    role: Role = Role.SCAFFOLD,
): Contribution =
    Contribution(
        rule = rule,
        role = role,
        intents = intents,
        evidence = "${start.text} ${end.text}".take(160),
        span = Span(start.startLine, start.startCol, end.endLine, end.endCol),
        source = source,
        confidence = confidence,
        startByte = start.startByte,
        endByte = end.endByte,
    )

class Composition {
    fun compose(
        terminal: TerminalNode,
        file: SourceFile,
        tree: SyntaxTree,
        syntax: SyntaxModel,
        flow: ControlFlow,
        oracle: TypeOracle,
        config: IntentConfig,
        sources: List<Contribution>,
    ): List<Annotation> {
        val source =
            sources.filter { contribution ->
                contribution.role == Role.SOURCE_INTENT &&
                    contribution.intents.any { it in terminal.sourceIntents }
            }

        val store = AnnotationStore()
        for ((rule, contributions) in source.groupBy { it.rule }) {
            store.replaceRule("$SOURCE_KEY_PREFIX$rule", contributions)
        }
        val context = AnalysisContext(file, tree, syntax, flow, oracle, config, store)

        val returns = returnContributions(context, config)
        store.replaceRule(RETURN_KEY, returns)
        val derivatives = settle(store, DERIVATIVE_KEY) {
            derivativeContributions(context, config.pureAnnotations)
        }
        val scaffolds = settle(store, SCAFFOLD_KEY) {
            scaffoldContributions(context)
        }

        val output = AnnotationStore()
        listOf(RETURN_KEY to returns, DERIVATIVE_KEY to derivatives, SCAFFOLD_KEY to scaffolds)
            .forEach { (key, contributions) ->
                if (contributions.isNotEmpty()) {
                    output.replaceRule(key, contributions)
                }
            }
        return output.annotations()
    }

    private fun settle(
        store: AnnotationStore,
        key: String,
        produce: () -> List<Contribution>,
    ): List<Contribution> {
        var output = produce()
        while (true) {
            if (!store.replaceRule(key, output)) return output
            output = produce()
        }
    }

    private fun returnContributions(
        ctx: RuleContext,
        config: IntentConfig,
    ): List<Contribution> =
        ctx.flow.returns
            .map { site ->
                val validation = isValidationReturn(ctx, site, config)
                val intents = setOf(if (validation) VALIDATION_INTENT else UNTAGGED_INTENT)
                site.node.raw(
                    Role.SCAFFOLD,
                    intents,
                    RETURN_RULE,
                    "cfg",
                    if (validation) 0.8 else 1.0,
                )
            }

    private fun isValidationReturn(
        ctx: RuleContext,
        site: ReturnSite,
        config: IntentConfig,
    ): Boolean {
        val guard = site.guards.lastOrNull { isValidationGuard(ctx, it, config) } ?: return false
        if (guard.onTrue) return true
        return isRejection(site)
    }

    private fun isValidationGuard(
        ctx: RuleContext,
        guard: Guard,
        config: IntentConfig,
    ): Boolean {
        val certified =
            ctx.containingAnnotations(guard.condition).any { VALIDATION_INTENT in it.intents } ||
                ctx.containingAnnotations(guard.node).any { VALIDATION_INTENT in it.intents }
        if (!certified) return false
        val explicit = hasValidationComment(ctx, guard.node)
        val predicate = isValidationPredicate(ctx, guard.condition, config)
        val pure = ctx.syntax.isSideEffectFree(guard.condition)
        if (ctx.syntax.isConstant(guard.condition) && !explicit) return false
        return explicit || predicate || pure
    }

    private fun isValidationPredicate(
        ctx: RuleContext,
        condition: SyntaxNode,
        config: IntentConfig,
    ): Boolean {
        val definition = config.intents[VALIDATION_INTENT] ?: return false
        val predicates = definition.list("predicates").toSet()
        val prefixes = definition.list("predicatePrefixes")
        return ctx.syntax.callsIn(condition).any { call ->
            val name = call.callee ?: return@any false
            name in predicates || prefixes.any { name.startsWith(it) }
        }
    }

    private fun hasValidationComment(
        ctx: RuleContext,
        ifNode: SyntaxNode,
    ): Boolean {
        val scope =
            ctx.syntax
                .enclosingFunction(ifNode)
                ?.let { ctx.syntax.bodyOf(it) }
                ?: ctx.tree.root
        val comment = ctx.syntax.closestCommentBefore(scope, ifNode) ?: return false
        if (ifNode.startLine - comment.endLine > 1) return false
        return Regex("""cint\s*:\s*validation""").containsMatchIn(comment.text.lowercase())
    }

    private fun isRejection(site: ReturnSite): Boolean {
        val expression = site.expression ?: return true
        return expression.text.trim() in rejectionSentinels
    }

    private fun derivativeContributions(
        ctx: RuleContext,
        pureAnnotations: List<String>,
    ): List<Contribution> {
        val result = mutableListOf<Contribution>()
        val references = ctx.references

        for (declaration in ctx.properties) {
            val name = declaration.name ?: continue
            val nameNode = declaration.nameNode ?: continue
            val initializer = declaration.initializer ?: continue

            val scope = ctx.syntax.enclosingFunction(declaration.node) ?: ctx.tree.root
            val uses =
                references[name]
                    .orEmpty()
                    .filter { it.startByte in scope.startByte until scope.endByte }
                    .filter { it.startByte != nameNode.startByte }
                    .filterNot { it.startByte >= initializer.startByte && it.endByte <= initializer.endByte }
            if (uses.isEmpty()) continue

            val useIntents = uses.map { ctx.intentsAt(it) }
            val allClassified =
                useIntents.all { intents ->
                    UNTAGGED_INTENT !in intents && intents.any { it != UNTAGGED_INTENT }
                }
            if (!allClassified) continue
            if (!isPure(ctx, initializer, pureAnnotations)) continue

            val intents = useIntents.flatten().toSet()
            if (intents.none { it != UNTAGGED_INTENT }) continue

            result +=
                declaration.node.raw(
                    Role.DERIVATIVE_USE,
                    intents,
                    DERIVATIVE_RULE,
                    "treesitter",
                    0.7,
                )
        }

        for (declaration in ctx.functions) {
            val nameNode = declaration.nameNode ?: continue
            val name = nameNode.text
            if (name.isEmpty()) continue
            if (!ctx.syntax.isAnnotatedPure(declaration.node, pureAnnotations)) continue

            val functionReferences =
                references[name].orEmpty().filter { reference ->
                    (reference.startByte != nameNode.startByte || reference.endByte != nameNode.endByte) &&
                        reference.startByte !in declaration.node.startByte until declaration.node.endByte &&
                        !ctx.syntax.isDeclarationName(reference)
                }
            if (functionReferences.isEmpty()) continue

            val places = functionReferences.mapNotNull { derivativePlaceIntents(ctx, it) }
            if (places.size != functionReferences.size) continue

            val intents = places.flatten().toSet()
            if (intents.none { it != UNTAGGED_INTENT }) continue

            result +=
                declaration.node.raw(
                    Role.DERIVATIVE_USE,
                    intents,
                    DERIVATIVE_RULE,
                    "treesitter",
                    0.7,
                )
        }

        return result
    }

    private fun derivativePlaceIntents(
        ctx: RuleContext,
        reference: SyntaxNode,
    ): Set<String>? {
        val place = ctx.syntax.enclosingDeclaration(reference) ?: return null
        val annotations = ctx.covering(place).filter { it.role == Role.DERIVATIVE_USE }
        if (annotations.isEmpty()) return null
        return annotations.flatMap { it.intents }.toSet()
    }

    private fun isPure(
        ctx: RuleContext,
        initializer: SyntaxNode,
        pureAnnotations: List<String>,
    ): Boolean =
        ctx.syntax.isConstant(initializer) ||
            ctx.syntax.isIdentifier(initializer) ||
            ctx.syntax.isAnnotatedPureCall(ctx.tree, initializer, pureAnnotations)

    private fun scaffoldContributions(ctx: RuleContext): List<Contribution> {
        val result = mutableListOf<Contribution>()
        result += ifScaffolds(ctx)
        result += catchScaffolds(ctx)
        result += tryScaffolds(ctx)
        result += functionScaffolds(ctx)
        return result
    }

    private fun ifScaffolds(ctx: RuleContext): List<Contribution> =
        ctx.ifs
            .flatMap { guard ->
                val condition = guard.condition ?: return@flatMap emptyList()
                val thenBody = guard.thenBody ?: return@flatMap emptyList()
                val thenIntents = mutableSetOf<String>()
                thenIntents += conditionIntents(ctx, condition)
                thenIntents += bodyIntents(ctx, thenBody)

                val elseBody = guard.elseBody
                if (elseBody == null) {
                    if (thenIntents.none { it != UNTAGGED_INTENT }) return@flatMap emptyList()
                    return@flatMap listOf(
                        guard.node.raw(
                            Role.SCAFFOLD,
                            thenIntents,
                            SCAFFOLD_RULE,
                            "treesitter",
                            0.9,
                        ),
                    )
                }

                val elseIntents = bodyIntents(ctx, elseBody)
                if (thenIntents == elseIntents) {
                    if (thenIntents.none { it != UNTAGGED_INTENT }) return@flatMap emptyList()
                    return@flatMap listOf(
                        guard.node.raw(
                            Role.SCAFFOLD,
                            thenIntents,
                            SCAFFOLD_RULE,
                            "treesitter",
                            0.9,
                        ),
                    )
                }

                if (
                    thenIntents.none { it != UNTAGGED_INTENT } &&
                    elseIntents.none { it != UNTAGGED_INTENT }
                ) {
                    return@flatMap emptyList()
                }

                val result = mutableListOf<Contribution>()
                if (thenIntents.isNotEmpty()) {
                    result +=
                        tagRange(
                            guard.node,
                            thenBody,
                            thenIntents,
                            SCAFFOLD_RULE,
                            "treesitter",
                            0.9,
                        )
                }
                if (elseIntents.isNotEmpty()) {
                    result +=
                        tagRange(
                            guard.elseKeyword ?: elseBody,
                            elseBody,
                            elseIntents,
                            SCAFFOLD_RULE,
                            "treesitter",
                            0.9,
                        )
                }
                result
            }.toList()

    private fun catchScaffolds(ctx: RuleContext): List<Contribution> =
        ctx.catches
            .mapNotNull { catchNode ->
                val intents = bodyIntents(ctx, catchNode)
                if (intents.none { it != UNTAGGED_INTENT }) return@mapNotNull null

                val previous = ctx.syntax.previousBlockClose(catchNode)
                if (previous != null) {
                    tagRange(previous, catchNode, intents, SCAFFOLD_RULE, "treesitter", 0.9)
                } else {
                    catchNode.raw(Role.SCAFFOLD, intents, SCAFFOLD_RULE, "treesitter", 0.9)
                }
            }.toList()

    private fun tryScaffolds(ctx: RuleContext): List<Contribution> {
        val result = mutableListOf<Contribution>()
        for (tryFact in ctx.tries) {
            val catches = tryFact.catches
            if (catches.isEmpty()) continue

            val tryBodyIntents = bodyIntents(ctx, tryFact.node)
            val catchIntents = catches.map { bodyIntents(ctx, it) }
            val finallyIntents = tryFact.finallyBlock?.let { bodyIntents(ctx, it) } ?: emptySet()

            val catchesCovered = catchIntents.all { it.isNotEmpty() && UNTAGGED_INTENT !in it }
            val catchesHaveRealIntent =
                catchIntents.any { intents ->
                    intents.any { it != UNTAGGED_INTENT }
                }

            val wholeCovered =
                tryBodyIntents.isNotEmpty() &&
                    UNTAGGED_INTENT !in tryBodyIntents &&
                    catchesCovered &&
                    (tryFact.finallyBlock == null || (finallyIntents.isNotEmpty() && UNTAGGED_INTENT !in finallyIntents))

            val start = tryFact.headerStart
            val end = tryFact.headerEnd

            if (wholeCovered) {
                val intents = tryBodyIntents + catchIntents.flatten().toSet() + finallyIntents
                result += tryFact.node.raw(Role.SCAFFOLD, intents, SCAFFOLD_RULE, "treesitter", 0.9)
            } else if (catchesCovered && catchesHaveRealIntent && start != null && end != null) {
                val intents = catchIntents.flatten().toSet()
                result +=
                    tagRange(
                        start,
                        end,
                        intents,
                        SCAFFOLD_RULE,
                        "treesitter",
                        0.8,
                    )
            }
        }
        return result
    }

    private fun functionScaffolds(ctx: RuleContext): List<Contribution> =
        ctx.syntax
            .functions(ctx.tree)
            .mapNotNull { function ->
                val body = function.body ?: return@mapNotNull null
                val intents = bodyIntents(ctx, body)
                if (intents.isEmpty()) return@mapNotNull null
                if (UNTAGGED_INTENT in intents) return@mapNotNull null
                if (intents.none { it != UNTAGGED_INTENT }) return@mapNotNull null
                function.node.raw(Role.SCAFFOLD, intents, SCAFFOLD_RULE, "treesitter", 0.9)
            }.toList()

    private fun conditionIntents(
        ctx: RuleContext,
        condition: SyntaxNode,
    ): Set<String> {
        val covered = ctx.coverage(condition).intents
        if (covered.any { it != UNTAGGED_INTENT }) return covered
        if (ctx.syntax.isConstant(condition) || ctx.syntax.isSideEffectFree(condition)) {
            return emptySet()
        }
        return covered
    }

    private fun bodyIntents(
        ctx: RuleContext,
        body: SyntaxNode?,
    ): Set<String> {
        if (body == null) return emptySet()
        val statements = ctx.syntax.statements(body)
        if (statements != null) {
            return statements.flatMap { statementIntents(ctx, it) }.toSet()
        }
        val exit = exitIntents(ctx, body)
        if (exit != null) return exit
        val direct = ctx.coverage(body).intents
        if (direct.any { it != UNTAGGED_INTENT }) return direct
        val expression = ctx.syntax.statementExpression(body) ?: return direct
        val inner = ctx.coverage(expression).intents
        return if (inner.any { it != UNTAGGED_INTENT }) inner else direct
    }

    private fun statementIntents(
        ctx: RuleContext,
        statement: SyntaxNode,
    ): Set<String> {
        exitIntents(ctx, statement)?.let { return it }
        val direct = ctx.coverage(statement).intents
        if (direct.any { it != UNTAGGED_INTENT }) return direct
        val expression = ctx.syntax.statementExpression(statement) ?: return direct
        val inner = ctx.coverage(expression).intents
        return if (inner.any { it != UNTAGGED_INTENT }) inner else direct
    }

    private fun exitIntents(
        ctx: RuleContext,
        node: SyntaxNode,
    ): Set<String>? {
        if (!ctx.syntax.isExit(node)) return null
        if (ctx.syntax.isReturn(node)) {
            val covered = ctx.coverage(node)
            if (covered.hasRealIntent) {
                return covered.intents
            }
            val expression = ctx.syntax.exitExpression(node) ?: return emptySet()
            if (expression.text.trim() == "false") return emptySet()
            if (ctx.syntax.isConstant(expression) || ctx.syntax.isSideEffectFree(expression)) {
                return setOf(UNTAGGED_INTENT)
            }
            val intents = ctx.intentsAt(expression)
            return if (intents.any { it != UNTAGGED_INTENT }) intents else setOf(UNTAGGED_INTENT)
        }
        val expression = ctx.syntax.exitExpression(node) ?: return emptySet()
        if (ctx.syntax.isConstant(expression) || ctx.syntax.isSideEffectFree(expression)) {
            return emptySet()
        }
        return ctx.intentsAt(expression)
    }
}
