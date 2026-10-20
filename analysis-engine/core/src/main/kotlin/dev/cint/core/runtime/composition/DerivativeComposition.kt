package dev.cint.core.runtime.composition

import dev.cint.api.Contribution
import dev.cint.api.Role
import dev.cint.api.SyntaxNode
import dev.cint.api.UNTAGGED_INTENT
import dev.cint.core.runtime.engine.RuleContext

internal fun derivativeContributions(
    ctx: RuleContext,
    pureAnnotations: List<String>,
): List<Contribution> {
    val result = mutableListOf<Contribution>()
    val references = ctx.references

    for ((node, name1, nameNode1, initializer1) in ctx.properties) {
        val name = name1 ?: continue
        val nameNode = nameNode1 ?: continue
        val initializer = initializer1 ?: continue

        val scope = ctx.syntax.enclosingFunction(node) ?: ctx.tree.root
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
            node.raw(
                Role.DERIVATIVE_USE,
                intents,
                DERIVATIVE_RULE,
                "treesitter",
                0.7,
            )
    }

    for ((node, _, nameNode1) in ctx.functions) {
        val nameNode = nameNode1 ?: continue
        val name = nameNode.text
        if (name.isEmpty()) continue
        if (!ctx.syntax.isAnnotatedPure(node, pureAnnotations)) continue

        val functionReferences =
            references[name].orEmpty().filter { reference ->
                (reference.startByte != nameNode.startByte || reference.endByte != nameNode.endByte) &&
                    reference.startByte !in node.startByte until node.endByte &&
                    !ctx.syntax.isDeclarationName(reference)
            }
        if (functionReferences.isEmpty()) continue

        val places = functionReferences.mapNotNull { derivativePlaceIntents(ctx, it) }
        if (places.size != functionReferences.size) continue

        val intents = places.flatten().toSet()
        if (intents.none { it != UNTAGGED_INTENT }) continue

        result +=
            node.raw(
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
