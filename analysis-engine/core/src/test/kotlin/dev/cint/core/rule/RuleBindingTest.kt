package dev.cint.core.rule

import dev.cint.contracts.data.Intents
import dev.cint.contracts.ports.Playbook
import dev.cint.contracts.ports.RuleEvaluator
import dev.cint.contracts.ports.RuleEvaluatorRegistry
import dev.cint.contracts.ports.RuleSpec
import dev.cint.contracts.ports.bindRulePlan
import dev.cint.contracts.util.ContractError
import dev.cint.core.engine.RuleEngine
import dev.cint.core.support.TestRule
import dev.cint.core.support.emit
import dev.cint.core.support.facts
import dev.cint.core.support.node
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuleBindingTest {
    private val node = node("root", "program", 0, 10)

    @Test
    fun playbookSpecsBindToContractEvaluatorsWithoutCoreInPacks() {
        val playbook =
            Playbook(
                packId = "example",
                rules = listOf(RuleSpec("example:logging", Intents.LOGGING)),
            )
        val evaluators =
            mapOf(
                "example:logging" to
                    TestRule("example:logging", Intents.LOGGING) {
                        listOf(emit(Intents.LOGGING, node))
                    },
            )
        val plan = bindRulePlan(playbook, registry(evaluators)).getOrThrow()

        val output =
            RuleEngine().analyze(
                facts("root", listOf(node)),
                plan.rules,
            )

        assertEquals(plan.rules.map { it.spec.id }, output.annotations.map { it.rule })
        assertEquals(setOf(Intents.LOGGING), output.annotations.flatMap { it.intents }.toSet())
    }

    @Test
    fun binderFailsInsteadOfSilentlyDroppingAnUnboundPackRule() {
        val playbook =
            Playbook(
                packId = "example",
                rules =
                    listOf(
                        RuleSpec("example:logging", Intents.LOGGING),
                        RuleSpec("example:lookup", Intents.LOOKUP),
                    ),
            )
        val evaluators =
            mapOf(
                "example:logging" to
                    TestRule("example:logging", Intents.LOGGING) {
                        listOf(emit(Intents.LOGGING, node))
                    },
            )

        val result = bindRulePlan(playbook, registry(evaluators))

        assertTrue(result.isFailure)
        assertEquals(
            ContractError.NotFound("rule-evaluator", "example:lookup"),
            result.errorOrNull,
        )
    }

    private fun registry(evaluators: Map<String, RuleEvaluator>): RuleEvaluatorRegistry =
        object : RuleEvaluatorRegistry {
            override fun resolve(spec: RuleSpec): RuleEvaluator? = evaluators[spec.id]
        }
}
