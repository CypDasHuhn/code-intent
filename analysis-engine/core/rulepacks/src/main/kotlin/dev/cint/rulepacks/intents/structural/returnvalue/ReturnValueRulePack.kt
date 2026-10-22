package dev.cint.rulepacks.intents.structural.returnvalue

import dev.cint.api.Emit
import dev.cint.api.Intent
import dev.cint.api.Matcher
import dev.cint.api.ReturnFact
import dev.cint.api.RuleEmit
import dev.cint.api.SingleIntentPack
import dev.cint.api.SyntaxNode
import dev.cint.api.View

const val RETURN_VALUE_INTENT: Intent = "RETURN_VALUE"
const val RETURN_VALUE_PACK_ID: String = "return-value"
const val RETURN_VALUE_RULE_ID: String = "$RETURN_VALUE_PACK_ID:return-value"

private const val ESTABLISHED_CONFIDENCE: Double = 0.9
private val BARE_LABELED_RETURN = Regex("""^return@[A-Za-z_][A-Za-z0-9_]*$""")

class ReturnValueRulePack(
    config: ReturnValueConfig = ReturnValueConfig(),
) : SingleIntentPack<ReturnValueConfig>(
        id = RETURN_VALUE_PACK_ID,
        intent = RETURN_VALUE_INTENT,
        languages = config.languages.keys.toSet(),
        config = config,
        schema = ReturnValueConfig.SCHEMA,
        ruleId = RETURN_VALUE_RULE_ID,
    ) {
    override val matchers: List<Matcher> =
        listOf(Matcher { view -> ReturnValueRule(config.forLanguage(view.language())).match(view) })

    override fun withConfig(config: ReturnValueConfig): SingleIntentPack<ReturnValueConfig> = ReturnValueRulePack(config)
}

private class ReturnValueRule(
    private val config: ReturnValueLanguageConfig,
) : Matcher {
    override fun match(view: View): List<RuleEmit> {
        val selected = linkedMapOf<Pair<Int, Int>, SyntaxNode>()
        for (site in view.returnSites()) {
            val expression = returnExpression(site) ?: continue
            if (!config.includeLiterals && isLiteral(view, expression)) continue
            selected.putIfAbsent(expression.startByte to expression.endByte, expression)
        }
        return selected.values.map { expression ->
            Emit.of(RETURN_VALUE_INTENT, expression, confidence = ESTABLISHED_CONFIDENCE)
        }
    }

    private fun returnExpression(site: ReturnFact): SyntaxNode? {
        if (BARE_LABELED_RETURN.matches(site.node.text.trim())) return null
        val expression = site.expression
        if (expression != null && isValue(expression)) return expression
        return site.node.children.lastOrNull { child ->
            isValue(child) && child.text.trim() != "return"
        }
    }

    private fun isValue(node: SyntaxNode): Boolean {
        val text = node.text.trim()
        return text.isNotEmpty() &&
            text != ";" &&
            text != "return" &&
            !text.startsWith("@") &&
            !node.kind.contains("label") &&
            !node.kind.contains("comment")
    }

    private fun isLiteral(
        view: View,
        expression: SyntaxNode,
    ): Boolean = view.isConstant(expression)
}
