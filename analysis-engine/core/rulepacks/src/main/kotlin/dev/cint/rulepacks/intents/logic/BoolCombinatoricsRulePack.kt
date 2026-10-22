package dev.cint.rulepacks.intents.logic

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

const val BOOL_COMBINATORICS_INTENT: Intent = "BOOL_COMBINATORICS"
const val BOOL_COMBINATORICS_PACK_ID: String = "bool-combinatorics"
const val BOOL_COMBINATORICS_RULE_ID: String = "$BOOL_COMBINATORICS_PACK_ID:bool-combinatorics"

private const val ESTABLISHED_CONFIDENCE: Double = 0.8
private const val CANDIDATE_CONFIDENCE: Double = 0.4

private val BOOLEAN_TOKENS = setOf("&&", "||")
private const val NEGATION_TOKEN = "!"
private val PARENTHESIZED_KINDS = setOf("parenthesized_expression")

class BoolCombinatoricsRulePack(
    config: BoolCombinatoricsConfig = BoolCombinatoricsConfig(),
) : SingleIntentPack<BoolCombinatoricsConfig>(
        id = BOOL_COMBINATORICS_PACK_ID,
        intent = BOOL_COMBINATORICS_INTENT,
        languages = config.languages.keys.toSet(),
        config = config,
        schema = BoolCombinatoricsConfig.SCHEMA,
        ruleId = BOOL_COMBINATORICS_RULE_ID,
    ) {
    override val matchers: List<Matcher> =
        listOf(Matcher { view -> BoolCombinatoricsRule(config.forLanguage(view.language())).match(view) })

    override fun withConfig(config: BoolCombinatoricsConfig): SingleIntentPack<BoolCombinatoricsConfig> = BoolCombinatoricsRulePack(config)
}

private enum class Match {
    ESTABLISHED,
    CANDIDATE,
}

private data class Shape(
    val operators: Int,
    val negations: Int,
    val mixed: Boolean,
    val negatedCompound: Boolean,
    val quantifiers: Int,
    val distinctPredicates: Int,
)

private class BoolCombinatoricsRule(
    private val config: BoolCombinatoricsLanguageConfig,
) : Matcher {
    override fun match(view: View): List<RuleEmit> {
        val sources =
            buildList {
                view.guardConditions().forEach { add(it) }
                view.returnSites().mapNotNullTo(this) { it.expression }
                view.properties().mapNotNullTo(this) { it.initializer }
            }
        val selected = linkedMapOf<Pair<Int, Int>, Pair<SyntaxNode, Match>>()

        for (source in sources) {
            val root = unwrapParenthesized(source)
            val calls = view.calls().filter { within(it.node, root) }
            if (!eligibleRoot(root, calls)) continue
            val shape = shape(root, calls)
            val match =
                when {
                    established(shape) -> Match.ESTABLISHED
                    borderline(shape) -> Match.CANDIDATE
                    else -> continue
                }
            val key = root.startByte to root.endByte
            val previous = selected[key]
            if (previous == null || (previous.second == Match.CANDIDATE && match == Match.ESTABLISHED)) {
                selected[key] = root to match
            }
        }

        return selected.values.map { (node, match) ->
            when (match) {
                Match.ESTABLISHED -> {
                    Emit.of(BOOL_COMBINATORICS_INTENT, node, confidence = ESTABLISHED_CONFIDENCE)
                }

                Match.CANDIDATE -> {
                    CandidateEmit.of(
                        BOOL_COMBINATORICS_INTENT,
                        node,
                        CandidateReason.LOW_CONFIDENCE,
                        source = "rule:$BOOL_COMBINATORICS_RULE_ID",
                        confidence = CANDIDATE_CONFIDENCE,
                        aiEligible = true,
                    )
                }
            }
        }
    }

    private fun eligibleRoot(
        root: SyntaxNode,
        calls: List<CallFact>,
    ): Boolean {
        val direct = root.children.any { it.text in BOOLEAN_TOKENS || it.text == NEGATION_TOKEN }
        if (direct) return true
        return calls.any { call -> call.callee?.let { it in config.quantifierCalls } == true }
    }

    private fun established(shape: Shape): Boolean =
        shape.operators >= config.minimumOperators ||
            (shape.operators > 0 && shape.mixed) ||
            shape.negatedCompound ||
            shape.quantifiers > 0 ||
            shape.distinctPredicates >= config.minimumPredicates

    private fun borderline(shape: Shape): Boolean =
        shape.operators > 0 ||
            shape.quantifiers > 0 ||
            (shape.negations > 0 && shape.distinctPredicates > 0)

    private fun shape(
        root: SyntaxNode,
        calls: List<CallFact>,
    ): Shape {
        val tokens =
            root
                .descendants()
                .filter { it.children.isEmpty() }
                .map { it.text }
                .toList()
        val operators = tokens.count { it in BOOLEAN_TOKENS }
        val negations = tokens.count { it == NEGATION_TOKEN }
        val hasAnd = tokens.any { it == "&&" }
        val hasOr = tokens.any { it == "||" }
        val quantifiers =
            calls.count { call ->
                call.callee?.let { it in config.quantifierCalls } == true
            }
        val distinctPredicates =
            calls
                .mapNotNull { it.callee }
                .filter(::classifiedPredicate)
                .distinct()
                .size
        val negatedCompound =
            negations > 0 &&
                root.descendants().any { node ->
                    node.kind in PARENTHESIZED_KINDS && node.descendants().any { it.text in BOOLEAN_TOKENS }
                }
        return Shape(
            operators = operators,
            negations = negations,
            mixed = hasAnd && hasOr,
            negatedCompound = negatedCompound,
            quantifiers = quantifiers,
            distinctPredicates = distinctPredicates,
        )
    }

    private fun classifiedPredicate(callee: String): Boolean =
        callee in config.predicateCalls ||
            config.predicatePrefixes.any { prefixMatches(callee, it) }

    private fun prefixMatches(
        callee: String,
        prefix: String,
    ): Boolean {
        if (prefix.isEmpty() || !callee.startsWith(prefix) || callee.length == prefix.length) return false
        val next = callee[prefix.length]
        return next.isUpperCase() || next.isDigit() || next == '_'
    }

    private fun unwrapParenthesized(node: SyntaxNode): SyntaxNode {
        var current = node
        while (current.kind in PARENTHESIZED_KINDS) {
            val inner = current.children.firstOrNull { it.kind != "(" && it.kind != ")" } ?: return current
            if (inner.startByte == current.startByte && inner.endByte == current.endByte) return current
            current = inner
        }
        return current
    }

    private fun within(
        node: SyntaxNode,
        container: SyntaxNode,
    ): Boolean = node.startByte >= container.startByte && node.endByte <= container.endByte
}
