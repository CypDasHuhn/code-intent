package dev.cint.rulepacks.packs.returnvalue

import dev.cint.contracts.facts.JumpKind
import dev.cint.contracts.ports.RuleEmission
import dev.cint.contracts.ports.RuleEvaluationView
import dev.cint.contracts.ports.RuleEvaluator
import dev.cint.contracts.ports.RuleSpec
import dev.cint.rulepacks.PackEvaluator
import dev.cint.rulepacks.PackRules
import dev.cint.rulepacks.SYNTAX_SOURCE
import kotlinx.serialization.json.JsonObject

internal object ReturnValuePack : PackRules {
    override val packId = "return-value"

    override fun create(
        spec: RuleSpec,
        @Suppress("UNUSED_PARAMETER") data: JsonObject,
    ): RuleEvaluator = ReturnValueRule(spec)
}

private class ReturnValueRule(
    spec: RuleSpec,
) : PackEvaluator(spec) {
    override fun evaluate(view: RuleEvaluationView): List<RuleEmission> {
        val result = mutableListOf<RuleEmission>()
        for (ret in view.returns) {
            if (ret.jumpKind != JumpKind.RETURN || ret.labeled) continue
            val statement = view.node(ret.nodeId) ?: continue
            val expression = view.node(ret.expressionId) ?: continue
            if (view.value(expression.id)?.returnLiteral == true) continue
            val span = ret.expressionSpan ?: expression.span
            view.emitSpan(span, statement.startByte, expression.endByte, 0.9, SYNTAX_SOURCE)?.let(result::add)
        }
        return result.distinctBy { Triple(it.startByte, it.endByte, it.intents) }
    }
}
