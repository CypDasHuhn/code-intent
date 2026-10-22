package dev.cint.core.runtime.composition

import dev.cint.api.Annotation
import dev.cint.api.Contribution
import dev.cint.api.Role
import dev.cint.api.SyntaxNode
import dev.cint.api.UNTAGGED_INTENT
import dev.cint.api.WriteFact
import dev.cint.api.WriteKind
import dev.cint.core.runtime.context.RuleContext

private val MUTATION_INTENTS = setOf("DATA_MODIFY", "LOCAL_VALUE_MODIFY", "BOOKKEEPING")
private val EFFECT_INTENTS = MUTATION_INTENTS + setOf("LOGGING", "ERROR_HANDLING")
private val ASSIGNMENT_OPERATORS = setOf("=", "+=", "-=", "*=", "/=", "%=", "?:=", "||=", "&&=")
private val COMMENT_KINDS = setOf("comment", "line_comment", "multiline_comment")

private data class ValueIntents(
    val intents: Set<String>,
    val confidence: Double,
)

private const val DERIVATIVE_CONFIDENCE: Double = 0.7
private const val DERIVATIVE_UNKNOWN_READ_ONLY_CONFIDENCE: Double = 0.55

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
                .filterNot { reference -> isWriteTarget(ctx, reference) }
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
        for (write in ctx.writes) {
            if (writeTargetName(write) != name) continue
            result +=
                write.node.raw(
                    Role.DERIVATIVE_USE,
                    intents,
                    DERIVATIVE_RULE,
                    "treesitter",
                    confidence,
                )
        }
    }

    for ((node, _, _, initializer1) in ctx.properties) {
        val initializer = initializer1 ?: continue
        readOnlyConfidence(ctx, initializer, pureAnnotations) ?: continue
        val valueIntents = valueSourceIntents(ctx, initializer)
        val intents = valueIntents.flatMap { it.intents }.filter { it != UNTAGGED_INTENT }.toSet()
        if (intents.isEmpty()) continue

        result +=
            node.raw(
                Role.DERIVATIVE_USE,
                intents,
                DERIVATIVE_RULE,
                "treesitter",
                valueIntents.minOf { it.confidence },
            )
    }

    for (write in ctx.writes) {
        if (write.kind != WriteKind.ASSIGNMENT) continue
        val value = assignmentValue(write.node) ?: continue
        readOnlyConfidence(ctx, value, pureAnnotations) ?: continue
        val valueIntents = valueSourceIntents(ctx, value)
        val intents = valueIntents.flatMap { it.intents }.filter { it != UNTAGGED_INTENT }.toSet()
        if (intents.isEmpty()) continue

        result +=
            write.node.raw(
                Role.DERIVATIVE_USE,
                intents,
                DERIVATIVE_RULE,
                "treesitter",
                valueIntents.minOf { it.confidence },
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
            previous?.copy(
                intents = previous.intents + contribution.intents,
                confidence = minOf(previous.confidence, contribution.confidence),
            )
                ?: contribution
    }
    return merged.values.toList()
}

private fun valueSourceIntents(
    ctx: RuleContext,
    node: SyntaxNode,
): List<ValueIntents> =
    node
        .descendants()
        .flatMap { descendant ->
            ctx
                .covering(descendant)
                .filter { annotation -> annotation.role == Role.SOURCE_INTENT }
                .mapNotNull { annotation ->
                    val intents = annotation.intents - EFFECT_INTENTS
                    if (intents.isEmpty()) null else ValueIntents(intents, annotation.confidence)
                }
        }.distinct()
        .toList()

private fun allSourceAnnotations(
    ctx: RuleContext,
    node: SyntaxNode,
): List<Annotation> =
    node
        .descendants()
        .flatMap { ctx.covering(it).filter { annotation -> annotation.role == Role.SOURCE_INTENT } }
        .distinct()
        .toList()

private fun assignmentValue(node: SyntaxNode): SyntaxNode? {
    node.childByField("right")?.let { return it }
    val operator = node.children.indexOfFirst { it.kind in ASSIGNMENT_OPERATORS || it.text in ASSIGNMENT_OPERATORS }
    if (operator < 0) return null
    return node.children
        .drop(operator + 1)
        .firstOrNull { it.kind != ";" && it.kind !in COMMENT_KINDS }
}

private fun isWriteTarget(
    ctx: RuleContext,
    reference: SyntaxNode,
): Boolean =
    ctx.writes.any { write ->
        val target = write.reference
        if (target != null) {
            target.startByte == reference.startByte && target.endByte == reference.endByte
        } else {
            reference.startByte >= write.node.startByte && reference.endByte <= write.node.endByte
        }
    }

private fun writeTargetName(write: WriteFact): String? {
    write.reference?.text?.let { return it.substringBefore('[').substringBefore('.').trim() }
    val text = write.node.text.trim()
    val target =
        text
            .substringBefore('=')
            .substringBefore('+')
            .substringBefore('-')
            .trim()
    if (target.isEmpty()) return null
    return target.substringBefore('[').substringBefore('.').trim()
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
    node: SyntaxNode,
    pureAnnotations: List<String>,
): Double? {
    if (hasKnownWrite(ctx, node)) return null
    return when {
        ctx.syntax.isConstant(node) ||
            ctx.syntax.isIdentifier(node) ||
            ctx.syntax.isSideEffectFree(node) ||
            ctx.syntax.isAnnotatedPureCall(ctx.tree, node, pureAnnotations) ||
            ctx.isUserPureCall(node) ||
            ctx.isUserReadOnlyCall(node) -> DERIVATIVE_CONFIDENCE

        else -> DERIVATIVE_UNKNOWN_READ_ONLY_CONFIDENCE
    }
}

private fun hasKnownWrite(
    ctx: RuleContext,
    node: SyntaxNode,
): Boolean =
    ctx.writes.any { write ->
        write.node.startByte >= node.startByte && write.node.endByte <= node.endByte
    } ||
        allSourceAnnotations(ctx, node).any { annotation ->
            annotation.intents.any { it in MUTATION_INTENTS }
        }
