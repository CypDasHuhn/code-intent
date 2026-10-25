package dev.cint.rulepacks.packs.validation

import dev.cint.contracts.data.CandidateReason
import dev.cint.contracts.data.Intents
import dev.cint.contracts.facts.CallFact
import dev.cint.contracts.facts.GuardFact
import dev.cint.contracts.facts.GuardKind
import dev.cint.contracts.facts.JumpKind
import dev.cint.contracts.facts.SyntaxNode
import dev.cint.contracts.facts.matchesCallee
import dev.cint.contracts.facts.matchesCalleePrefix
import dev.cint.contracts.facts.matchesDirective
import dev.cint.contracts.ports.RuleEmission
import dev.cint.contracts.ports.RuleEvaluationView
import dev.cint.contracts.ports.RuleEvaluator
import dev.cint.contracts.ports.RuleSpec
import dev.cint.rulepacks.COMMENT_SOURCE
import dev.cint.rulepacks.PackEvaluator
import dev.cint.rulepacks.PackRules
import dev.cint.rulepacks.SYNTAX_SOURCE
import dev.cint.rulepacks.decodeRule
import dev.cint.rulepacks.within
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
private data class ValidationData(
    val predicateCalls: Set<String> = emptySet(),
    val predicatePrefixes: List<String> = emptyList(),
    val directive: String? = null,
)

internal object ValidationPack : PackRules {
    override val packId = "validation"

    override fun create(
        spec: RuleSpec,
        data: JsonObject,
    ): RuleEvaluator = ValidationRule(spec, data.decodeRule())
}

private class ValidationRule(
    spec: RuleSpec,
    private val data: ValidationData,
) : PackEvaluator(spec) {
    override fun evaluate(view: RuleEvaluationView): List<RuleEmission> {
        val result = mutableListOf<RuleEmission>()
        for (guard in view.guards) {
            if (guard.kind != GuardKind.CONDITIONAL) continue
            val condition = view.node(guard.conditionId) ?: continue
            val body = view.node(guard.thenBodyId) ?: continue
            val explicit = hasValidationComment(view, guard, 1)
            val bookkeeping =
                view.annotations(Intents.BOOKKEEPING).any {
                    it.startByte >= body.startByte && it.endByte <= body.endByte
                }
            val predicate = callsWithin(view, condition).any { call -> isPredicate(call) }
            val rejection =
                hasRejectingExit(view, body) ||
                    view.node(guard.elseBodyId)?.let { hasRejectingExit(view, it) } == true
            val pure = rejection && callsWithin(view, condition).isEmpty() && !hasWriteWithin(view, condition)
            val readOnly = rejection && !hasWriteWithin(view, condition)
            val established =
                (explicit || bookkeeping || (predicate && rejection) || pure || readOnly) &&
                    !(guard.conditionConstant && !explicit && !bookkeeping)
            val span = guard.conditionSpan ?: condition.span
            val startByte = guard.conditionStartByte ?: condition.startByte
            val endByte = guard.conditionEndByte ?: condition.endByte
            if (established) {
                val confidence =
                    when {
                        explicit -> 1.0
                        bookkeeping -> 0.9
                        predicate -> 0.7
                        pure -> 0.6
                        else -> 0.5
                    }
                val source = if (explicit) COMMENT_SOURCE else SYNTAX_SOURCE
                view.emitSpan(span, startByte, endByte, confidence, source, certifiesReturn = true)?.let(result::add)
            } else if (hasValidationComment(view, guard, 3)) {
                view.candidateSpan(span, startByte, endByte, CandidateReason.GAP)?.let { result += it }
            }
        }
        for (coalesce in view.coalesces) {
            val node = view.node(coalesce.nodeId) ?: continue
            val right = view.node(coalesce.rightId) ?: continue
            if (!hasRejectingExit(view, right)) continue
            if (hasKnownWrites(view, node)) continue
            view.emitNode(node, 0.6, SYNTAX_SOURCE)?.let(result::add)
        }
        return result.distinctBy { Triple(it.startByte, it.endByte, it.intents) }
    }

    private fun hasKnownWrites(
        view: RuleEvaluationView,
        node: SyntaxNode,
    ): Boolean =
        view.writes.any { write -> view.node(write.nodeId)?.let { within(it, node) } == true } ||
            setOf(Intents.BOOKKEEPING, Intents.DATA_MODIFY, Intents.LOCAL_VALUE_MODIFY).any { intent ->
                view.annotations(intent).any { it.startByte >= node.startByte && it.endByte <= node.endByte }
            }

    private fun hasValidationComment(
        view: RuleEvaluationView,
        guard: GuardFact,
        maxDistance: Int,
    ): Boolean {
        val directive = data.directive ?: return false
        val guardNode = view.node(guard.nodeId) ?: return false
        val comment =
            view.comments
                .mapNotNull { view.node(it.nodeId) }
                .filter { it.endByte <= guardNode.startByte }
                .filter { guardNode.startLine - it.endLine <= maxDistance }
                .maxByOrNull { it.startByte } ?: return false
        val fact = view.comments.firstOrNull { it.nodeId == comment.id } ?: return false
        return fact.matchesDirective(directive)
    }

    private fun isPredicate(call: CallFact): Boolean =
        call.matchesCallee(data.predicateCalls) || call.matchesCalleePrefix(data.predicatePrefixes)

    private fun callsWithin(
        view: RuleEvaluationView,
        node: SyntaxNode,
    ): List<CallFact> = view.calls.filter { call -> view.node(call.nodeId)?.let { within(it, node) } == true }

    private fun hasWriteWithin(
        view: RuleEvaluationView,
        node: SyntaxNode,
    ): Boolean = view.writes.any { write -> view.node(write.nodeId)?.let { within(it, node) } == true }

    private fun hasRejectingExit(
        view: RuleEvaluationView,
        body: SyntaxNode,
    ): Boolean =
        view.exits.any { exit ->
            exit.kind != JumpKind.THROW &&
                exit.rejection &&
                view.node(exit.nodeId)?.let { within(it, body) } == true
        }
}
