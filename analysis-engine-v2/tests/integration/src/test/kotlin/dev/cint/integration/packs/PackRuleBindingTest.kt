package dev.cint.integration.packs

import dev.cint.contracts.data.Confidence
import dev.cint.contracts.data.Intents
import dev.cint.contracts.facts.FactBatch
import dev.cint.contracts.facts.SyntaxNode
import dev.cint.contracts.facts.SyntaxTree
import dev.cint.contracts.ports.EstablishedEmission
import dev.cint.contracts.ports.RuleEmission
import dev.cint.contracts.ports.RuleEvaluationView
import dev.cint.contracts.ports.RuleEvaluator
import dev.cint.contracts.ports.RuleEvaluatorRegistry
import dev.cint.contracts.ports.RuleSpec
import dev.cint.contracts.ports.bindRulePlan
import dev.cint.contracts.util.ContractError
import dev.cint.core.engine.RuleEngine
import dev.cint.packs.BundledRulePackSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PackRuleBindingTest {
    private val source = BundledRulePackSource()

    @Test
    fun bindsARealBundledPlaybookAndRunsItThroughCore() {
        val playbook = source.playbook("logging").getOrThrow()
        val loggingSpec = playbook.rules.single { it.owns == Intents.LOGGING }
        val evaluator = evaluatorFor(loggingSpec)
        val plan = bindRulePlan(playbook, registry(loggingSpec.id, evaluator)).getOrThrow()

        assertEquals(playbook.rules, plan.rules.map { it.spec })

        val output = RuleEngine().analyze(facts(), plan.rules)

        assertEquals(setOf(Intents.LOGGING), output.annotations.flatMap { it.intents }.toSet())
        assertEquals(setOf(loggingSpec.id), output.annotations.map { it.rule }.toSet())
    }

    @Test
    fun unboundBundledRuleFailsInsteadOfBeingDropped() {
        val playbook = source.playbook("logging").getOrThrow()

        val result = bindRulePlan(playbook, registry("missing", evaluatorFor(playbook.rules.first())))

        assertTrue(result.isFailure)
        assertEquals(
            ContractError.NotFound("rule-evaluator", playbook.rules.first().id),
            result.errorOrNull,
        )
    }

    @Test
    fun mismatchedBundledRuleFailsValidation() {
        val playbook = source.playbook("logging").getOrThrow()
        val expected = playbook.rules.first()
        val mismatched = evaluatorFor(expected.copy(owns = Intents.LOOKUP))

        val result = bindRulePlan(playbook, registry(expected.id, mismatched))

        assertTrue(result.isFailure)
        assertEquals(
            ContractError.ValidationError(
                "evaluator '${expected.id}' does not match its playbook spec",
                "RuleSpec",
            ),
            result.errorOrNull,
        )
    }

    private fun evaluatorFor(spec: RuleSpec): RuleEvaluator =
        object : RuleEvaluator {
            override val spec: RuleSpec = spec

            override fun evaluate(view: RuleEvaluationView): List<RuleEmission> {
                val root = view.node("root") ?: return emptyList()
                return listOf(
                    EstablishedEmission(
                        intents = setOf(this.spec.owns),
                        span = root.span,
                        startByte = root.startByte,
                        endByte = root.endByte,
                        source = "integration",
                        confidence = Confidence.MAX,
                    ),
                )
            }
        }

    private fun registry(
        id: String,
        evaluator: RuleEvaluator,
    ): RuleEvaluatorRegistry =
        object : RuleEvaluatorRegistry {
            override fun resolve(spec: RuleSpec): RuleEvaluator? =
                evaluator.takeIf { spec.id == id }
        }

    private fun facts(): FactBatch {
        val root =
            SyntaxNode(
                id = "root",
                kind = "program",
                startByte = 0,
                endByte = 4,
                startLine = 0,
                startCol = 0,
                endLine = 0,
                endCol = 4,
                text = "test",
            )
        return FactBatch(
            syntaxTree =
                SyntaxTree(
                    rootId = "root",
                    nodes = mapOf("root" to root),
                    language = "synthetic",
                    version = "1",
                    contentHash = "integration-hash",
                ),
        )
    }
}
