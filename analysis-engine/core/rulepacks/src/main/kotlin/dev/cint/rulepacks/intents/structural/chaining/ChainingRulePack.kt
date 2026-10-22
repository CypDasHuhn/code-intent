package dev.cint.rulepacks.intents.structural.chaining

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
private val AMBIGUOUS_RECEIVER_KINDS = listOf("conditional", "ternary", "if_expression")
private val OPERAND_BOUNDARIES = listOf("argument", "lambda", "statement", "program", "block")

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

        return selected.values
            .filterNot { config.excludeBooleanChains && isBooleanOrOperand(it.root.node) }
            .map { chain ->
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
        var ambiguous = false
        var current: CallFact? = root
        while (current != null && seen.add(current.node.key())) {
            members += current
            val receiver = current.receiver ?: break
            if (config.excludeBooleanChains && isBooleanOrExpression(receiver)) return null
            val contained =
                calls.filter { call ->
                    call !== current && (within(call.node, receiver) || within(call.site, receiver))
                }
            val exact =
                contained.filter { call ->
                    sameSpan(call.node, receiver) || sameSpan(call.site, receiver)
                }
            if (exact.isEmpty()) {
                if (isAmbiguousReceiver(receiver)) ambiguous = true
                break
            }
            current = exact.maxByOrNull { it.node.endByte - it.node.startByte } ?: break
        }
        if (members.size < config.minimumLinks) return null

        val direct =
            !ambiguous &&
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
        return sameSpan(next.node, receiver) || sameSpan(next.site, receiver)
    }

    private fun isBooleanOrOperand(node: SyntaxNode): Boolean {
        var current = node
        while (true) {
            val parent = current.parent ?: return false
            if (parent.children.any { it.text.trim() == "||" }) return true
            if (OPERAND_BOUNDARIES.any { parent.kind.contains(it) }) return false
            current = parent
        }
    }

    private fun isAmbiguousReceiver(node: SyntaxNode): Boolean {
        if (AMBIGUOUS_RECEIVER_KINDS.any { node.kind.contains(it) }) return true
        val text = node.text.trim()
        if (!text.startsWith("(") || !text.endsWith(")")) return false
        val inner =
            node.children.firstOrNull { child ->
                val childText = child.text.trim()
                childText.isNotEmpty() && childText != "(" && childText != ")" && childText.length < text.length
            } ?: return false
        return isAmbiguousReceiver(inner)
    }

    private fun isBooleanOrExpression(node: SyntaxNode): Boolean {
        if (node.children.any { it.text.trim() == "||" }) return true
        val text = node.text.trim()
        if (!text.startsWith("(") || !text.endsWith(")")) return false
        val inner =
            node.children.firstOrNull { child ->
                val childText = child.text.trim()
                childText.isNotEmpty() && childText != "(" && childText != ")" && childText.length < text.length
            } ?: return false
        return isBooleanOrExpression(inner)
    }
}

private fun SyntaxNode.key(): Pair<Int, Int> = startByte to endByte

private fun sameSpan(
    first: SyntaxNode,
    second: SyntaxNode,
): Boolean = first.startByte == second.startByte && first.endByte == second.endByte

private fun within(
    node: SyntaxNode,
    container: SyntaxNode,
): Boolean = node.startByte >= container.startByte && node.endByte <= container.endByte
