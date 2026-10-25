package dev.cint.rulepacks.packs.normalization

import dev.cint.contracts.facts.matchesCallee
import dev.cint.contracts.facts.matchesCalleePrefix
import dev.cint.contracts.facts.receiverQualifierMatches
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
private data class NormalizationData(
    val exactCalls: Set<String> = emptySet(),
    val instanceCalls: Set<String> = emptySet(),
    val candidateCalls: Set<String> = emptySet(),
    val candidatePrefixes: List<String> = emptyList(),
    val receiverQualifiers: Set<String> = emptySet(),
)

internal object NormalizationPack : PackRules {
    override val packId = "normalization"

    override fun create(
        spec: RuleSpec,
        data: JsonObject,
    ): RuleEvaluator = NormalizationRule(spec, data.decodeRule())
}

private class NormalizationRule(
    spec: RuleSpec,
    private val data: NormalizationData,
) : PackEvaluator(spec) {
    override fun evaluate(view: RuleEvaluationView): List<RuleEmission> {
        val result = mutableListOf<RuleEmission>()
        for (call in view.calls) {
            val callee = call.callee.orEmpty()
            val receiver = call.receiverPath.orEmpty()
            when {
                call.matchesCallee(data.exactCalls, ignoreCase = false) -> {
                    if (call.receiverQualifierMatches(data.receiverQualifiers) ||
                        receiver.isEmpty() ||
                        callee in data.instanceCalls
                    ) {
                        view.emitNode(view.node(call.nodeId), 0.9, SYNTAX_SOURCE)?.let(result::add)
                    } else {
                        view.candidateNode(view.node(call.nodeId))?.let { result += it }
                    }
                }

                callee in data.candidateCalls ||
                    call.matchesCalleePrefix(data.candidatePrefixes, ignoreCase = false) -> {
                    view.candidateNode(view.node(call.nodeId))?.let { result += it }
                }
            }
        }
        return result.distinctBy { Triple(it.startByte, it.endByte, it.intents) }
    }
}
