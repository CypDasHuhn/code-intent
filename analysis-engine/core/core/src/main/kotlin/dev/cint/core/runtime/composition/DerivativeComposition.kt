package dev.cint.core.runtime.composition

import dev.cint.api.Annotation
import dev.cint.api.Contribution
import dev.cint.api.Role
import dev.cint.api.SyntaxNode
import dev.cint.api.UNTAGGED_INTENT
import dev.cint.core.runtime.context.RuleContext

private const val DERIVATIVE_CONFIDENCE: Double = 0.7
private const val DERIVATIVE_UNKNOWN_READ_ONLY_CONFIDENCE: Double = 0.55
private const val INITIALIZER_CONFIDENCE: Double = 0.7

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

        val confidence = readOnlyConfidence(ctx, initializer, pureAnnotations) ?: continue
        val intents = useIntents.flatten().toSet()
        if (intents.none { it != UNTAGGED_INTENT }) continue

        result +=
            node.raw(
                Role.DERIVATIVE_USE,
                intents,
                DERIVATIVE_RULE,
                "treesitter",
                confidence,
            )
    }

    for ((node, _, _, initializer1) in ctx.properties) {
        val initializer = initializer1 ?: continue
        val annotations = initializerSourceAnnotations(ctx, initializer)
        val intentsAt =
            if (annotations.isEmpty()) {
                emptySet()
            } else {
                ctx.intentsAt(initializer).filter { it != UNTAGGED_INTENT }
            }
        val intents = (annotations.flatMap { it.intents } + intentsAt).toSet()
        if (intents.isEmpty()) continue

        val confidence =
            annotations.maxOfOrNull { it.confidence }
                ?: INITIALIZER_CONFIDENCE
        result +=
            node.raw(
                Role.DERIVATIVE_USE,
                intents,
                DERIVATIVE_RULE,
                "treesitter",
                confidence,
            )
    }

    for ((node, _, nameNode1) in ctx.functions) {
        val nameNode = nameNode1 ?: continue
        val name = nameNode.text
        if (name.isEmpty()) continue
        if (!ctx.syntax.isAnnotatedPure(node, pureAnnotations) && !ctx.isUserPure(node)) continue

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
                DERIVATIVE_CONFIDENCE,
            )
    }

    return merge(result)
}

private fun merge(contributions: List<Contribution>): List<Contribution> {
    val merged = linkedMapOf<Triple<Role, Int, Int>, Contribution>()
    for (contribution in contributions) {
        val key = Triple(contribution.role, contribution.startByte, contribution.endByte)
        val previous = merged[key]
        merged[key] =
            if (previous == null) {
                contribution
            } else {
                previous.copy(
                    intents = previous.intents + contribution.intents,
                    confidence = maxOf(previous.confidence, contribution.confidence),
                )
            }
    }
    return merged.values.toList()
}

private fun initializerSourceAnnotations(
    ctx: RuleContext,
    initializer: SyntaxNode,
): List<Annotation> {
    val annotations = mutableListOf<Annotation>()
    annotations += ctx.covering(initializer).filter { it.role == Role.SOURCE_INTENT }
    for (descendant in initializer.descendants()) {
        annotations += ctx.covering(descendant).filter { it.role == Role.SOURCE_INTENT }
    }
    return annotations
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

private fun readOnlyConfidence(
    ctx: RuleContext,
    initializer: SyntaxNode,
    pureAnnotations: List<String>,
): Double? {
    if (hasKnownWrite(ctx, initializer)) return null
    return when {
        ctx.syntax.isConstant(initializer) ||
            ctx.syntax.isIdentifier(initializer) ||
            ctx.syntax.isSideEffectFree(initializer) ||
            ctx.syntax.isAnnotatedPureCall(ctx.tree, initializer, pureAnnotations) ||
            isUserPureCall(ctx, initializer) -> DERIVATIVE_CONFIDENCE

        else -> DERIVATIVE_UNKNOWN_READ_ONLY_CONFIDENCE
    }
}

private fun hasKnownWrite(
    ctx: RuleContext,
    initializer: SyntaxNode,
): Boolean =
    ctx.writes.any { write ->
        write.node.startByte >= initializer.startByte && write.node.endByte <= initializer.endByte
    }

private fun isUserPureCall(
    ctx: RuleContext,
    call: SyntaxNode,
): Boolean {
    if (ctx.syntax.receiverNode(call) != null) return false
    val callee = ctx.syntax.calleeName(call) ?: return false
    val function = ctx.syntax.findFunction(ctx.tree, callee) ?: return false
    return ctx.isUserPure(function)
}
