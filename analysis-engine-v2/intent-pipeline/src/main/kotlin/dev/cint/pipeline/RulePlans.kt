package dev.cint.pipeline

import dev.cint.contracts.ports.CompositionSpec
import dev.cint.contracts.ports.RuleEvaluator
import dev.cint.contracts.ports.RuleEvaluatorRegistry
import dev.cint.contracts.ports.RuleSpec
import dev.cint.contracts.ports.bindRulePlan
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory

class RuleEvaluatorCatalog(
    evaluators: Iterable<RuleEvaluator> = emptyList(),
) : RuleEvaluatorRegistry {
    private val byId: Map<String, RuleEvaluator> =
        evaluators.sortedBy { it.spec.id }.associateBy { it.spec.id }

    override fun resolve(spec: RuleSpec): RuleEvaluator? = byId[spec.id]

    fun evaluators(): List<RuleEvaluator> = byId.values.sortedBy { it.spec.id }

    companion object {
        fun bundled(evaluators: Iterable<RuleEvaluator> = emptyList()): RuleEvaluatorCatalog =
            RuleEvaluatorCatalog(evaluators)
    }
}

class MapRuleEvaluatorRegistry(
    evaluators: Iterable<RuleEvaluator> = emptyList(),
) : RuleEvaluatorRegistry {
    private val delegate = RuleEvaluatorCatalog(evaluators)

    override fun resolve(spec: RuleSpec): RuleEvaluator? = delegate.resolve(spec)
}

data class BoundPacks(
    val rules: List<RuleEvaluator>,
    val composition: List<CompositionSpec>,
)

fun bindPackRules(
    selection: PackSelection,
    registry: RuleEvaluatorRegistry,
): ContractResult<BoundPacks> {
    val rules = mutableListOf<RuleEvaluator>()
    val composition = mutableListOf<CompositionSpec>()
    for (pack in selection.packs) {
        val plan = bindRulePlan(pack.playbook, registry)
        if (plan.isFailure) return ResultFactory.failure(plan.errorOrNull!!)
        rules += plan.getOrThrow().rules
        pack.playbook.composition?.let(composition::add)
    }
    return ResultFactory.success(BoundPacks(rules, composition))
}
