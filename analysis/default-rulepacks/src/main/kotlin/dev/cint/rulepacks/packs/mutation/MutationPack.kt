package dev.cint.rulepacks.packs.mutation

import dev.cint.contracts.data.Intents
import dev.cint.contracts.facts.CallFact
import dev.cint.contracts.facts.DeclarationKind
import dev.cint.contracts.facts.WriteKind
import dev.cint.contracts.facts.matchesCallee
import dev.cint.contracts.facts.matchesCalleePrefix
import dev.cint.contracts.facts.matchesLocalCandidate
import dev.cint.contracts.facts.pathMatches
import dev.cint.contracts.facts.receiverQualifierMatches
import dev.cint.contracts.facts.receiverSuffixMatches
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
private data class MutationData(
    val persistenceCalls: Set<String> = emptySet(),
    val persistencePaths: Set<String> = emptySet(),
    val persistencePrefixes: List<String> = emptyList(),
    val persistenceQualifiers: Set<String> = emptySet(),
    val persistenceSuffixes: Set<String> = emptySet(),
    val mutableOperations: Set<String> = emptySet(),
    val mutableReceiverNames: Set<String> = emptySet(),
    val localCandidateExact: Set<String> = emptySet(),
    val localCandidatePrefixes: List<String> = emptyList(),
    val readPrefixes: List<String> = emptyList(),
    val compoundMutationCalls: Set<String> = emptySet(),
)

internal object MutationPack : PackRules {
    override val packId = "mutation"

    override fun create(
        spec: RuleSpec,
        data: JsonObject,
    ): RuleEvaluator {
        val config = data.decodeRule<MutationData>()
        return when (spec.owns) {
            Intents.DATA_MODIFY -> DataModifyRule(spec, config)
            else -> LocalValueModifyRule(spec, config)
        }
    }
}

private class DataModifyRule(
    spec: RuleSpec,
    private val data: MutationData,
) : PackEvaluator(spec) {
    override fun evaluate(view: RuleEvaluationView): List<RuleEmission> {
        val result = mutableListOf<RuleEmission>()
        for (call in view.calls) {
            val persistent = persistentReceiver(call)
            val exactPath = call.pathMatches(data.persistencePaths)
            val configured = configuredPersistence(call)
            when {
                durableCallback(view, call) || exactPath || (configured && persistent) -> {
                    view.emitNode(view.node(call.nodeId), 0.9, SYNTAX_SOURCE)?.let(result::add)
                }

                persistenceCandidate(view, call) -> {
                    view.candidateNode(view.node(call.nodeId))?.let { result += it }
                }
            }
        }
        return result.distinctBy { Triple(it.startByte, it.endByte, it.intents) }
    }

    private fun persistentReceiver(call: CallFact): Boolean =
        !call.receiverPath.isNullOrBlank() &&
            (
                call.receiverQualifierMatches(data.persistenceQualifiers) ||
                    call.receiverSuffixMatches(data.persistenceSuffixes)
            )

    private fun configuredPersistence(call: CallFact): Boolean =
        call.matchesCallee(data.persistenceCalls, ignoreCase = false) ||
            call.matchesCalleePrefix(data.persistencePrefixes, ignoreCase = true) ||
            call.pathMatches(data.persistencePaths)

    private fun persistenceEstablished(
        view: RuleEvaluationView,
        call: CallFact,
    ): Boolean =
        durableCallback(view, call) ||
            call.pathMatches(data.persistencePaths) ||
            (configuredPersistence(call) && persistentReceiver(call))

    private fun persistenceCandidate(
        view: RuleEvaluationView,
        call: CallFact,
    ): Boolean {
        val callee = call.callee.orEmpty()
        if (callee.isEmpty()) return false
        if (persistenceEstablished(view, call)) return false
        val receiver = call.receiverPath.orEmpty()
        val receiverName = call.receiverName.orEmpty()
        val configured = configuredPersistence(call)
        if (receiver.isEmpty()) return configured
        if (persistentReceiver(call)) {
            return configured ||
                data.mutableOperations.any { it == callee } ||
                call.matchesLocalCandidate(data.localCandidateExact, data.localCandidatePrefixes)
        }
        val mutableReceiver = data.mutableReceiverNames.any { it.equals(receiverName, ignoreCase = true) }
        if (mutableReceiver && !(configured && isReadCall(call))) return false
        if (data.mutableOperations.any { it == callee }) return configured
        if (!configured) return false
        if (call.matchesLocalCandidate(data.localCandidateExact, data.localCandidatePrefixes) &&
            data.persistenceCalls.none { it == callee }
        ) {
            return false
        }
        return true
    }

    private fun isReadCall(call: CallFact): Boolean = call.matchesCalleePrefix(data.readPrefixes, ignoreCase = true)

    private fun durableCallback(
        view: RuleEvaluationView,
        call: CallFact,
    ): Boolean {
        val callee = call.callee.orEmpty()
        if (callee.isEmpty()) return false
        val callNode = view.node(call.nodeId) ?: return false
        val function = view.enclosingFunction(callNode) ?: return false
        val targets = data.persistencePaths + data.persistenceCalls
        return function.parameters.any { parameter ->
            parameter.name == callee &&
                parameter.functionTyped &&
                parameter.referencedPaths.any { referenced -> referenced in targets }
        }
    }
}

private class LocalValueModifyRule(
    spec: RuleSpec,
    private val data: MutationData,
) : PackEvaluator(spec) {
    override fun evaluate(view: RuleEvaluationView): List<RuleEmission> {
        val result = mutableListOf<RuleEmission>()
        val localNames =
            view.declarations
                .filter { it.kind == DeclarationKind.VARIABLE }
                .mapNotNull { it.name }
                .toSet()
        val bookkeepingSpans = view.annotations(Intents.BOOKKEEPING).map { it.startByte to it.endByte }.toSet()
        for (write in view.writes) {
            val node = view.node(write.nodeId) ?: continue
            if (write.kind == WriteKind.INCREMENT && write.loopIncrement) continue
            if (bookkeepingSpans.any { node.startByte >= it.first && node.endByte <= it.second }) continue
            if (Intents.LOCAL_COPY in view.intentsAt(node)) continue
            if (write.targetName != null && write.targetName in localNames) {
                view.emitNode(node, 0.8, SYNTAX_SOURCE)?.let(result::add)
            } else if (write.kind == WriteKind.ASSIGNMENT && write.targetName != null) {
                view.candidateNode(node)?.let { result += it }
            }
        }
        for (call in view.calls) {
            val callee = call.callee.orEmpty()
            val receiver = call.receiverPath.orEmpty()
            val receiverName = call.receiverName.orEmpty()
            val mutableReceiver = data.mutableReceiverNames.any { it.equals(receiverName, ignoreCase = true) }
            if (persistenceEstablished(view, call) || persistentReceiver(call)) continue
            if (persistenceCandidate(view, call)) continue
            when {
                data.mutableOperations.any { it == callee } && mutableReceiver -> {
                    view.emitNode(view.node(call.nodeId), 0.8, SYNTAX_SOURCE)?.let(result::add)
                }

                data.mutableOperations.any { it == callee } ||
                    call.matchesLocalCandidate(data.localCandidateExact, data.localCandidatePrefixes) -> {
                    view.candidateNode(view.node(call.nodeId))?.let { result += it }
                }
            }
        }
        return result.distinctBy { Triple(it.startByte, it.endByte, it.intents) }
    }

    private fun persistentReceiver(call: CallFact): Boolean =
        !call.receiverPath.isNullOrBlank() &&
            (
                call.receiverQualifierMatches(data.persistenceQualifiers) ||
                    call.receiverSuffixMatches(data.persistenceSuffixes)
            )

    private fun configuredPersistence(call: CallFact): Boolean =
        call.matchesCallee(data.persistenceCalls, ignoreCase = false) ||
            call.matchesCalleePrefix(data.persistencePrefixes, ignoreCase = true) ||
            call.pathMatches(data.persistencePaths)

    private fun persistenceEstablished(
        view: RuleEvaluationView,
        call: CallFact,
    ): Boolean =
        durableCallback(view, call) ||
            call.pathMatches(data.persistencePaths) ||
            (configuredPersistence(call) && persistentReceiver(call))

    private fun persistenceCandidate(
        view: RuleEvaluationView,
        call: CallFact,
    ): Boolean {
        val callee = call.callee.orEmpty()
        if (callee.isEmpty()) return false
        if (persistenceEstablished(view, call)) return false
        val receiver = call.receiverPath.orEmpty()
        val receiverName = call.receiverName.orEmpty()
        val configured = configuredPersistence(call)
        if (receiver.isEmpty()) return configured
        if (persistentReceiver(call)) {
            return configured ||
                data.mutableOperations.any { it == callee } ||
                call.matchesLocalCandidate(data.localCandidateExact, data.localCandidatePrefixes)
        }
        val mutableReceiver = data.mutableReceiverNames.any { it.equals(receiverName, ignoreCase = true) }
        if (mutableReceiver && !(configured && isReadCall(call))) return false
        if (data.mutableOperations.any { it == callee }) return configured
        if (!configured) return false
        if (call.matchesLocalCandidate(data.localCandidateExact, data.localCandidatePrefixes) &&
            data.persistenceCalls.none { it == callee }
        ) {
            return false
        }
        return true
    }

    private fun isReadCall(call: CallFact): Boolean = call.matchesCalleePrefix(data.readPrefixes, ignoreCase = true)

    private fun durableCallback(
        view: RuleEvaluationView,
        call: CallFact,
    ): Boolean {
        val callee = call.callee.orEmpty()
        if (callee.isEmpty()) return false
        val callNode = view.node(call.nodeId) ?: return false
        val function = view.enclosingFunction(callNode) ?: return false
        val targets = data.persistencePaths + data.persistenceCalls
        return function.parameters.any { parameter ->
            parameter.name == callee &&
                parameter.functionTyped &&
                parameter.referencedPaths.any { referenced -> referenced in targets }
        }
    }
}
