package dev.cint.v2.testing.fakes

import dev.cint.contracts.ports.RuleEmission
import dev.cint.contracts.ports.RuleEvaluationView
import dev.cint.contracts.ports.RuleEvaluator
import dev.cint.contracts.ports.RuleEvaluatorRegistry
import dev.cint.contracts.ports.RuleSpec

class FakeRuleEvaluator(
    override val spec: RuleSpec,
    private val emissions: List<RuleEmission> = emptyList(),
    val recorder: FakeRecorder = FakeRecorder(),
) : RuleEvaluator {
    val views: MutableList<RuleEvaluationView> = mutableListOf()

    override fun evaluate(view: RuleEvaluationView): List<RuleEmission> {
        recorder.record("rule.evaluate", spec.id)
        views.add(view)
        return emissions
    }
}

class FakeRuleEvaluatorRegistry(
    private val evaluators: Map<String, RuleEvaluator> = emptyMap(),
    private val recorder: FakeRecorder = FakeRecorder(),
) : RuleEvaluatorRegistry {
    val requested: MutableList<String> = mutableListOf()

    override fun resolve(spec: RuleSpec): RuleEvaluator? {
        recorder.record("ruleRegistry.resolve", spec.id)
        requested.add(spec.id)
        return evaluators[spec.id]
    }
}
