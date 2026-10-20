package dev.cint.core.runtime.composition

import dev.cint.api.Contribution
import dev.cint.api.Role
import dev.cint.api.SyntaxNode
import dev.cint.api.UNTAGGED_INTENT
import dev.cint.core.facts.flow.Guard
import dev.cint.core.facts.flow.ReturnSite
import dev.cint.core.runtime.context.RuleContext

internal fun returnContributions(ctx: RuleContext): List<Contribution> =
    ctx.flow.returns
        .map { site ->
            val guard = site.guards.lastOrNull { guardIntents(ctx, it).isNotEmpty() }
            val intents =
                if (guard != null && (guard.onTrue || isRejection(site))) {
                    guardIntents(ctx, guard)
                } else {
                    setOf(UNTAGGED_INTENT)
                }
            site.node.raw(
                Role.SCAFFOLD,
                intents,
                RETURN_RULE,
                "cfg",
                if (intents == setOf(UNTAGGED_INTENT)) 1.0 else 0.8,
            )
        }

private fun guardIntents(
    ctx: RuleContext,
    guard: Guard,
): Set<String> {
    val condition = annotatedIntents(ctx, guard.condition)
    return condition.ifEmpty { annotatedIntents(ctx, guard.node) }
}

private fun annotatedIntents(
    ctx: RuleContext,
    node: SyntaxNode,
): Set<String> =
    ctx
        .containingAnnotations(node)
        .filter { annotation -> annotation.provenance.any { it.certifiesReturn } }
        .flatMap { it.intents }
        .filterTo(linkedSetOf()) { it != UNTAGGED_INTENT }

private fun isRejection(site: ReturnSite): Boolean {
    val expression = site.expression ?: return true
    return expression.text.trim() in rejectionSentinels
}
