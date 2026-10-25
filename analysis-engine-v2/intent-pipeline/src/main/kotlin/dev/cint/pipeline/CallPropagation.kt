package dev.cint.pipeline

import dev.cint.contracts.facts.CallFact
import dev.cint.contracts.facts.FactBatch

internal class CallGraph private constructor(
    val calls: List<CallFact>,
    private val receiverByCall: Map<String, CallFact>,
    private val parentByCall: Map<String, CallFact>,
) {
    fun chain(call: CallFact): List<CallFact> {
        val result = mutableListOf<CallFact>()
        val seen = mutableSetOf<String>()
        var current: CallFact? = call
        while (current != null && seen.add(current.nodeId)) {
            result += current
            current = receiverByCall[current.nodeId]
        }
        return result
    }

    fun isInChain(call: CallFact): Boolean =
        call.nodeId in receiverByCall || call.nodeId in parentByCall

    fun roots(): List<CallFact> =
        calls
            .filter { it.nodeId in receiverByCall && it.nodeId !in parentByCall }
            .sortedWith(callOrder)

    companion object {
        fun of(facts: FactBatch): CallGraph? {
            facts.syntaxTree ?: return null
            val byNode = facts.calls.groupBy { it.nodeId }
            val calls =
                byNode
                    .values
                    .map { group -> group.sortedWith(callOrder).first() }
                    .sortedWith(callOrder)
            val receiverByCall = linkedMapOf<String, CallFact>()
            val parentByCall = linkedMapOf<String, CallFact>()
            for (call in calls) {
                val receiver = receiverCall(call, calls) ?: continue
                receiverByCall[call.nodeId] = receiver
                parentByCall[receiver.nodeId] = call
            }
            return CallGraph(calls, receiverByCall, parentByCall)
        }

        private fun receiverCall(
            call: CallFact,
            calls: List<CallFact>,
        ): CallFact? {
            val receiverId = call.receiverId ?: return null
            return calls
                .filter { candidate ->
                    candidate.nodeId != call.nodeId &&
                        (
                            candidate.nodeId == receiverId ||
                                candidate.siteId == receiverId
                        )
                }.sortedWith(callOrder)
                .lastOrNull()
        }
    }
}

/**
 * Same-file declaration propagation only.
 * Cross-file semantic callee identity is deferred to ticket 078 (runtime/index/provider resolved targets).
 */
internal class DeclarationContracts {
    private val byName = linkedMapOf<String, MutableList<DeclarationEntry>>()

    fun add(
        target: KnowledgeTarget,
        contract: IntentContract,
    ) {
        val entry = DeclarationEntry(target, contract)
        for (name in target.literalNames) {
            byName.getOrPut(name) { mutableListOf() } += entry
        }
    }

    fun resolve(target: KnowledgeTarget): IntentContract? {
        val candidates =
            target.literalNames
                .sortedWith(compareByDescending<String> { it.length }.thenBy { it })
                .flatMap { byName[it].orEmpty() }
                .distinctBy { it.target.node.id }
                .mapNotNull { entry -> score(entry.target, target)?.let { entry to it } }
        return candidates
            .sortedWith(
                compareByDescending<Pair<DeclarationEntry, Int>> { it.second }
                    .thenBy { it.first.target.node.id }
                    .thenBy { it.first.contract.rule }
                    .thenBy { it.first.contract.provenance.ordinal },
            )
            .firstOrNull()
            ?.first
            ?.contract
    }

    private fun score(
        declaration: KnowledgeTarget,
        call: KnowledgeTarget,
    ): Int? {
        var score = 1
        if (declaration.declaringTypes.isNotEmpty()) {
            if (call.declaringTypes.none { actual ->
                    declaration.declaringTypes.any { expected -> typeMatches(expected, actual) }
                }
            ) {
                return null
            }
            score += 10
        }
        val declarationArity = declaration.arity
        val callArity = call.arity
        if (declarationArity != null && callArity != null) {
            if (declarationArity != callArity) return null
            score += 5
        }
        if (declaration.signatures.any { it in call.signatures }) score += 3
        return score
    }
}

private fun typeMatches(
    expected: String,
    actual: String,
): Boolean {
    if (expected == actual) return true
    val expectedSimple = expected.substringAfterLast('.')
    val actualSimple = actual.substringAfterLast('.')
    if (expectedSimple.equals(actualSimple, ignoreCase = true)) return true
    return actual.endsWith(".$expected") || expected.endsWith(".$actual")
}

private data class DeclarationEntry(
    val target: KnowledgeTarget,
    val contract: IntentContract,
)

private val callOrder =
    compareBy<CallFact>({ it.nodeId.length }, { it.nodeId }, { it.siteId }, { it.callee ?: "" }, { it.path ?: "" })
