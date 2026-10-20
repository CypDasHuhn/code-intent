package dev.cint.core.runtime.composition

import dev.cint.api.Contribution
import dev.cint.api.Role
import dev.cint.api.SyntaxNode
import dev.cint.api.UNTAGGED_INTENT
import dev.cint.core.config.IntentConfig
import dev.cint.core.facts.flow.Guard
import dev.cint.core.facts.flow.ReturnSite
import dev.cint.core.runtime.engine.RuleContext

internal fun returnContributions(
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
    val explicit = hasValidationComment(ctx, guard.node, config)
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
    config: IntentConfig,
): Boolean {
    val scope =
        ctx.syntax
            .enclosingFunction(ifNode)
            ?.let { ctx.syntax.bodyOf(it) }
            ?: ctx.tree.root
    val comment = ctx.syntax.closestCommentBefore(scope, ifNode) ?: return false
    if (ifNode.startLine - comment.endLine > 1) return false
    val magicComment =
        config.intents[VALIDATION_INTENT]
            ?.list("magicComment")
            ?.firstOrNull()
            ?: return false
    return Regex("""cint\s*:\s*${Regex.escape(magicComment)}""", RegexOption.IGNORE_CASE)
        .containsMatchIn(comment.text)
}

private fun isRejection(site: ReturnSite): Boolean {
    val expression = site.expression ?: return true
    return expression.text.trim() in rejectionSentinels
}
