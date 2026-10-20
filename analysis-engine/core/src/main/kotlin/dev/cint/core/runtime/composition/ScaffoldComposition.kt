package dev.cint.core.runtime.composition

import dev.cint.api.Contribution
import dev.cint.api.Role
import dev.cint.api.SyntaxNode
import dev.cint.api.UNTAGGED_INTENT
import dev.cint.core.runtime.engine.RuleContext

internal fun scaffoldContributions(ctx: RuleContext): List<Contribution> {
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
    for ((node, _, catches, finallyBlock, start, end) in ctx.tries) {
        if (catches.isEmpty()) continue

        val tryBodyIntents = bodyIntents(ctx, node)
        val catchIntents = catches.map { bodyIntents(ctx, it) }
        val finallyIntents = finallyBlock?.let { bodyIntents(ctx, it) } ?: emptySet()

        val catchesCovered = catchIntents.all { it.isNotEmpty() && UNTAGGED_INTENT !in it }
        val catchesHaveRealIntent =
            catchIntents.any { intents ->
                intents.any { it != UNTAGGED_INTENT }
            }

        val wholeCovered =
            tryBodyIntents.isNotEmpty() &&
                UNTAGGED_INTENT !in tryBodyIntents &&
                catchesCovered &&
                (finallyBlock == null || (finallyIntents.isNotEmpty() && UNTAGGED_INTENT !in finallyIntents))

        if (wholeCovered) {
            val intents =
                buildSet {
                    addAll(tryBodyIntents)
                    addAll(catchIntents.flatten().toSet())
                    addAll(finallyIntents)
                }
            result += node.raw(Role.SCAFFOLD, intents, SCAFFOLD_RULE, "treesitter", 0.9)
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
