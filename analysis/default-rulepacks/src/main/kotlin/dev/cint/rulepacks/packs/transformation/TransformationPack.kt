package dev.cint.rulepacks.packs.transformation

import dev.cint.contracts.data.CandidateReason
import dev.cint.contracts.data.Intents
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
private data class TransformationData(
    val exactCalls: Set<String> = emptySet(),
    val candidateCalls: Set<String> = emptySet(),
    val candidatePrefixes: List<String> = emptyList(),
    val receiverQualifiers: Set<String> = emptySet(),
)

internal object TransformationPack : PackRules {
    override val packId = "data-transformation"

    override fun create(
        spec: RuleSpec,
        data: JsonObject,
    ): RuleEvaluator = TransformationRule(spec, data.decodeRule())
}

private class TransformationRule(
    spec: RuleSpec,
    private val data: TransformationData,
) : PackEvaluator(spec) {
    override fun evaluate(view: RuleEvaluationView): List<RuleEmission> {
        val result = mutableListOf<RuleEmission>()
        for (call in view.calls) {
            val callee = call.callee.orEmpty()
            val receiver = call.receiverPath.orEmpty()
            val receiverNode = view.node(call.receiverId)
            val receiverHasSearch =
                receiverNode != null &&
                    view.annotations(Intents.SEARCH).any {
                        it.startByte >= receiverNode.startByte && it.endByte <= receiverNode.endByte
                    }
            val established =
                call.matchesCallee(data.exactCalls, ignoreCase = false) &&
                    (
                        receiver.isNotEmpty() &&
                            (
                                call.receiverQualifierMatches(data.receiverQualifiers) ||
                                    receiverHasSearch ||
                                    view.intentsAt(receiverNode ?: continue).any {
                                        it == Intents.SEARCH || it == Intents.DATA_TRANSFORMATION
                                    }
                            )
                    )
            when {
                established -> {
                    view.emitNode(view.node(call.nodeId), 0.9, SYNTAX_SOURCE)?.let(result::add)
                }

                callee in data.candidateCalls ||
                    call.matchesCalleePrefix(data.candidatePrefixes, ignoreCase = false) -> {
                    view
                        .candidateNode(view.node(call.nodeId), reason = CandidateReason.LOW_CONFIDENCE)
                        ?.let { result += it }
                }
            }
        }
        return result.distinctBy { Triple(it.startByte, it.endByte, it.intents) }
    }
}
