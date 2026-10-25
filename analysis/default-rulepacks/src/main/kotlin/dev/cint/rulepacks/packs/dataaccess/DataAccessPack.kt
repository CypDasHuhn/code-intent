package dev.cint.rulepacks.packs.dataaccess

import dev.cint.contracts.facts.CallFact
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
private data class DataAccessData(
    val searchCalls: Set<String> = emptySet(),
    val searchPrefixes: List<String> = emptyList(),
    val searchQualifiers: Set<String> = emptySet(),
    val lookupCalls: Set<String> = emptySet(),
    val lookupQualifiers: Set<String> = emptySet(),
    val ambiguousCalls: Set<String> = emptySet(),
    val ambiguousPrefixes: List<String> = emptyList(),
    val compoundMutationCalls: Set<String> = emptySet(),
)

internal object DataAccessPack : PackRules {
    override val packId = "data-access"

    override fun create(
        spec: RuleSpec,
        data: JsonObject,
    ): RuleEvaluator {
        val config = data.decodeRule<DataAccessData>()
        return when (spec.owns) {
            "SEARCH" -> SearchRule(spec, config)
            else -> LookupRule(spec, config)
        }
    }
}

private class SearchRule(
    spec: RuleSpec,
    private val data: DataAccessData,
) : PackEvaluator(spec) {
    override fun evaluate(view: RuleEvaluationView): List<RuleEmission> {
        val result = mutableListOf<RuleEmission>()
        for (call in view.calls) {
            if (isLookupOnly(call)) continue
            val receiver = call.receiverPath.orEmpty()
            val configured = call.matchesCallee(data.searchCalls) || call.matchesCalleePrefix(data.searchPrefixes)
            val established = configured && receiver.isNotEmpty() && call.receiverQualifierMatches(data.searchQualifiers)
            when {
                established -> view.emitNode(view.node(call.nodeId), 1.0, SYNTAX_SOURCE)?.let(result::add)
                isAmbiguous(call) -> view.candidateNode(view.node(call.nodeId))?.let { result += it }
                configured -> view.candidateNode(view.node(call.nodeId))?.let { result += it }
            }
        }
        return result.distinctBy { Triple(it.startByte, it.endByte, it.intents) }
    }

    private fun isLookupOnly(call: CallFact): Boolean = call.matchesCallee(data.lookupCalls) && !call.matchesCallee(data.searchCalls)

    private fun isAmbiguous(call: CallFact): Boolean {
        if (call.matchesCallee(data.compoundMutationCalls)) return false
        if (call.matchesCallee(data.lookupCalls)) return false
        if (call.matchesCallee(data.searchCalls)) return false
        if (call.matchesCalleePrefix(data.searchPrefixes)) return false
        return call.matchesCallee(data.ambiguousCalls) || call.matchesCalleePrefix(data.ambiguousPrefixes)
    }
}

private class LookupRule(
    spec: RuleSpec,
    private val data: DataAccessData,
) : PackEvaluator(spec) {
    override fun evaluate(view: RuleEvaluationView): List<RuleEmission> {
        val result = mutableListOf<RuleEmission>()
        for (call in view.calls) {
            val receiver = call.receiverPath.orEmpty()
            val configured = call.matchesCallee(data.lookupCalls) || isAmbiguous(call)
            val knownReceiver = receiver.isNotEmpty() && call.receiverQualifierMatches(data.lookupQualifiers)
            when {
                configured && knownReceiver -> {
                    view.emitNode(view.node(call.nodeId), 1.0, SYNTAX_SOURCE)?.let(result::add)
                }

                isAmbiguous(call) || configured -> {
                    view.candidateNode(view.node(call.nodeId))?.let { result += it }
                }
            }
        }
        return result.distinctBy { Triple(it.startByte, it.endByte, it.intents) }
    }

    private fun isAmbiguous(call: CallFact): Boolean {
        if (call.matchesCallee(data.compoundMutationCalls)) return false
        if (call.matchesCallee(data.lookupCalls)) return false
        if (call.matchesCallee(data.searchCalls)) return false
        if (call.matchesCalleePrefix(data.searchPrefixes)) return false
        return call.matchesCallee(data.ambiguousCalls) || call.matchesCalleePrefix(data.ambiguousPrefixes)
    }
}
