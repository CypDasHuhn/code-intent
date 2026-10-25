package dev.cint.rulepacks.packs.errorhandling

import dev.cint.contracts.facts.JumpKind
import dev.cint.contracts.facts.matchesCallee
import dev.cint.contracts.facts.receiverQualifierMatches
import dev.cint.contracts.facts.tokenizedCallee
import dev.cint.contracts.ports.RuleEmission
import dev.cint.contracts.ports.RuleEvaluationView
import dev.cint.contracts.ports.RuleEvaluator
import dev.cint.contracts.ports.RuleSpec
import dev.cint.rulepacks.PackEvaluator
import dev.cint.rulepacks.PackRules
import dev.cint.rulepacks.SYNTAX_SOURCE
import dev.cint.rulepacks.decodeRule
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
private data class ErrorHandlingData(
    val exactCalls: Set<String> = emptySet(),
    val excludedCalls: Set<String> = emptySet(),
    val methods: Set<String> = emptySet(),
    val receiverNames: Set<String> = emptySet(),
    val receiverQualifiers: Set<String> = emptySet(),
)

internal object ErrorHandlingPack : PackRules {
    override val packId = "error-handling"

    override fun create(
        spec: RuleSpec,
        data: JsonObject,
    ): RuleEvaluator = ErrorHandlingRule(spec, data.decodeRule())
}

private class ErrorHandlingRule(
    spec: RuleSpec,
    private val data: ErrorHandlingData,
) : PackEvaluator(spec) {
    override fun evaluate(view: RuleEvaluationView): List<RuleEmission> {
        val result = mutableListOf<RuleEmission>()
        for (call in view.calls) {
            val callee = call.callee.orEmpty()
            if (data.excludedCalls.any { it.equals(callee, ignoreCase = false) }) continue
            val receiverName = call.receiverName.orEmpty()
            val loggingCall =
                call.matchesCallee(data.methods) &&
                    (
                        data.receiverNames.any { it.equals(receiverName, ignoreCase = true) } ||
                            call.receiverQualifierMatches(data.receiverQualifiers)
                    )
            if (loggingCall) continue
            if (call.tokenizedCallee().any { it in data.exactCalls }) {
                view.emitNode(view.node(call.nodeId), 1.0, SYNTAX_SOURCE)?.let(result::add)
            }
        }
        for (exit in view.exits) {
            if (exit.kind != JumpKind.THROW) continue
            view.emitNode(view.node(exit.nodeId), 1.0, SYNTAX_SOURCE)?.let(result::add)
        }
        return result.distinctBy { Triple(it.startByte, it.endByte, it.intents) }
    }
}
