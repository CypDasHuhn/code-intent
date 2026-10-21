package dev.cint.rulepacks.intents.structural

import dev.cint.api.CallFact
import dev.cint.api.CandidateEmit
import dev.cint.api.CandidateReason
import dev.cint.api.Emit
import dev.cint.api.Intent
import dev.cint.api.Matcher
import dev.cint.api.RuleEmit
import dev.cint.api.SingleIntentPack
import dev.cint.api.SyntaxNode
import dev.cint.api.View

const val CHAINING_INTENT: Intent = "CHAINING"
const val CHAINING_PACK_ID: String = "chaining"
const val CHAINING_RULE_ID: String = "$CHAINING_PACK_ID:chaining"

private const val ESTABLISHED_CONFIDENCE: Double = 0.85
private const val CANDIDATE_CONFIDENCE: Double = 0.4

class ChainingRulePack(
    config: ChainingConfig = ChainingConfig(),
) : SingleIntentPack<ChainingConfig>(
        id = CHAINING_PACK_ID,
        intent = CHAINING_INTENT,
        languages = config.languages.keys.toSet(),
        config = config,
        schema = ChainingConfig.SCHEMA,
        ruleId = CHAINING_RULE_ID,
    ) {
    override val matchers: List<Matcher> =
        listOf(Matcher { view -> ChainingRule(config.forLanguage(view.language())).match(view) })

    override fun withConfig(config: ChainingConfig): SingleIntentPack<ChainingConfig> = ChainingRulePack(config)
}

private data class Chain(
    val root: CallFact,
    val members: List<CallFact>,
    val direct: Boolean,
) {
    val memberKeys: Set<Pair<Int, Int>> = members.mapTo(mutableSetOf()) { it.node.startByte to it.node.endByte }
}

private class ChainingRule(
    private val config: ChainingLanguageConfig,
) : Matcher {
    override fun match(view: View): List<RuleEmit> {
        val calls = view.calls()
        val chains = calls.mapNotNull { call -> chainFor(call, calls) }
        val selected = linkedMapOf<Pair<Int, Int>, Chain>()

        for (chain in chains) {
            if (chains.any { other -> other !== chain && chain.root.node.key() in other.memberKeys }) continue
            val key = chain.root.node.key()
            val previous = selected[key]
            if (previous == null || (!previous.direct && chain.direct)) {
                selected[key] = chain
            }
        }

        return selected.values.map { chain ->
            if (chain.direct) {
                Emit.of(CHAINING_INTENT, chain.root.node, confidence = ESTABLISHED_CONFIDENCE)
            } else {
                CandidateEmit.of(
                    CHAINING_INTENT,
                    chain.root.node,
                    CandidateReason.LOW_CONFIDENCE,
                    source = "rule:$CHAINING_RULE_ID",
                    confidence = CANDIDATE_CONFIDENCE,
                    aiEligible = true,
                )
            }
        }
    }

    private fun chainFor(
        root: CallFact,
        calls: List<CallFact>,
    ): Chain? {
        if (root.receiver == null) return null

        val members = mutableListOf<CallFact>()
        val seen = mutableSetOf<Pair<Int, Int>>()
        var current: CallFact? = root
        while (current != null && seen.add(current.node.key())) {
            members += current
            val receiver = current.receiver ?: break
            if (config.excludeBooleanChains && "||" in receiver.text) return null
            val next =
                calls
                    .filter { it !== current && within(it.node, receiver) }
                    .maxByOrNull { it.node.endByte - it.node.startByte } ?: break
            current = next
        }
        if (members.size < config.minimumLinks) return null

        val direct =
            members.zipWithNext().all { (member, next) ->
                isDirectLink(member, next)
            }
        return Chain(root, members, direct)
    }

    private fun isDirectLink(
        member: CallFact,
        next: CallFact,
    ): Boolean {
        val receiver = member.receiver ?: return false
        val offset = next.node.endByte - receiver.startByte
        if (offset <= 0 || offset > receiver.text.length) return false
        return receiver.text.substring(offset).all { it == ')' || it == ']' || it == ';' || it.isWhitespace() }
    }
}

private fun SyntaxNode.key(): Pair<Int, Int> = startByte to endByte

private fun within(
    node: SyntaxNode,
    container: SyntaxNode,
): Boolean = node.startByte >= container.startByte && node.endByte <= container.endByte
