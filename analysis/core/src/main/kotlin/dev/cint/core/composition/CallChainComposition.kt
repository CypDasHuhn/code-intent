package dev.cint.core.composition

import dev.cint.contracts.data.Confidence
import dev.cint.contracts.data.Contribution
import dev.cint.contracts.data.Intents
import dev.cint.contracts.data.Provenance
import dev.cint.contracts.data.Role
import dev.cint.contracts.facts.CallFact
import dev.cint.contracts.facts.SyntaxNode
import dev.cint.contracts.knowledge.KnowledgeRules
import dev.cint.core.CoreContributors

private const val CALL_CHAIN_RULE = CoreContributors.CALL_CHAIN

internal fun callChainContributions(ctx: CompositionContext): List<Contribution> {
    val byNode = ctx.facts.calls.groupBy { it.nodeId }
    val calls =
        byNode.values
            .map { group -> group.minWithOrNull(compareBy({ it.callee ?: "" }, { it.siteId }))!! }
            .sortedWith(compareBy({ it.nodeId.length }, { it.nodeId }, { it.siteId }))
    val receiverByCall = linkedMapOf<String, CallFact>()
    val parentByCall = linkedMapOf<String, CallFact>()
    for (call in calls) {
        val receiverId = call.receiverId ?: continue
        val receiver =
            calls
                .filter {
                    it.nodeId != call.nodeId &&
                        (it.nodeId == receiverId || it.siteId == receiverId)
                }.sortedWith(compareBy({ it.nodeId.length }, { it.nodeId }))
                .lastOrNull()
                ?: continue
        receiverByCall[call.nodeId] = receiver
        parentByCall[receiver.nodeId] = call
    }

    val direct = linkedMapOf<String, CallIntents>()
    val overridden = mutableSetOf<String>()
    for (call in calls) {
        val node = ctx.index.node(call.nodeId) ?: continue
        val annotations =
            ctx.covering(node).filter {
                it.role == Role.SOURCE_INTENT && it.intents.any { intent -> intent != Intents.UNTAGGED }
            }
        if (annotations.isEmpty()) continue
        if (annotations.any { annotation -> annotation.provenance.any { it.rule == KnowledgeRules.SPAN_OVERRIDE } }) {
            overridden += call.nodeId
        }
        direct[call.nodeId] =
            CallIntents(
                intents = annotations.flatMapTo(linkedSetOf()) { it.intents } - Intents.UNTAGGED,
                provenance = annotations.maxByOrNull { it.confidence.value }!!.primaryProvenance,
                confidence = Confidence.fromDouble(annotations.minOf { it.confidence.value }),
            )
    }

    val memo = mutableMapOf<String, CallIntents?>()

    fun effective(
        call: CallFact,
        visiting: MutableSet<String>,
    ): CallIntents? {
        memo[call.nodeId]?.let { return it }
        if (!visiting.add(call.nodeId)) return null
        val base = direct[call.nodeId]
        val receiver = receiverByCall[call.nodeId]
        val inherited = if (call.nodeId in overridden) null else receiver?.let { effective(it, visiting) }
        visiting.remove(call.nodeId)
        if (base == null && inherited == null) {
            memo[call.nodeId] = null
            return null
        }
        val intents = linkedSetOf<String>()
        base?.intents?.let(intents::addAll)
        inherited?.intents?.let(intents::addAll)
        if (inherited != null && inherited.intents.any { it == Intents.SEARCH || it == Intents.DATA_TRANSFORMATION }) {
            intents += Intents.DATA_TRANSFORMATION
        }
        val result =
            if (intents.isEmpty()) {
                null
            } else {
                val provenance = base?.provenance ?: inherited?.provenance ?: Provenance.DERIVED
                val confidence =
                    listOfNotNull(base?.confidence?.value, inherited?.confidence?.value)
                        .minOrNull()
                        ?.let(Confidence::fromDouble)
                        ?: Confidence.MEDIUM
                CallIntents(intents, provenance, confidence)
            }
        memo[call.nodeId] = result
        return result
    }

    val contributions = mutableListOf<Contribution>()
    for (call in calls) {
        val receiver = receiverByCall[call.nodeId] ?: continue
        val node = ctx.index.node(call.nodeId) ?: continue
        if (call.nodeId !in overridden) {
            val inherited = effective(receiver, mutableSetOf()) ?: continue
            val intents = linkedSetOf<String>()
            intents += inherited.intents
            if (inherited.intents.any { it == Intents.SEARCH || it == Intents.DATA_TRANSFORMATION }) {
                intents += Intents.DATA_TRANSFORMATION
            }
            if (intents.isNotEmpty()) {
                contributions +=
                    contribution(
                        node = node,
                        intents = intents,
                        provenance = inherited.provenance,
                        confidence = inherited.confidence,
                        rule = CALL_CHAIN_RULE,
                    )
            }
        }
        if (call.nodeId in receiverByCall && call.nodeId !in parentByCall) {
            val root = effective(call, mutableSetOf()) ?: continue
            contributions +=
                contribution(
                    node = node,
                    intents = setOf(Intents.CHAINING),
                    provenance = Provenance.DERIVED,
                    confidence = Confidence.fromDouble(minOf(root.confidence.value, Confidence.HIGH.value)),
                    rule = CALL_CHAIN_RULE,
                )
        }
    }
    return contributions
}

private data class CallIntents(
    val intents: Set<String>,
    val provenance: Provenance,
    val confidence: Confidence,
)

private fun contribution(
    node: SyntaxNode,
    intents: Set<String>,
    provenance: Provenance,
    confidence: Confidence,
    rule: String,
): Contribution =
    Contribution(
        rule = rule,
        role = Role.SOURCE_INTENT,
        intents = intents.toSet(),
        span = node.span,
        source = "composition",
        confidence = confidence,
        startByte = node.startByte,
        endByte = node.endByte,
        provenance = provenance,
    )
