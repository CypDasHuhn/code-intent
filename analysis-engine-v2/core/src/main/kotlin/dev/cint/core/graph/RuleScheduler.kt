package dev.cint.core.graph

import dev.cint.contracts.data.Intent
import dev.cint.contracts.data.Intents
import dev.cint.core.CoreContributors
import dev.cint.core.rule.Rule

enum class SchedulerError {
    DUPLICATE_INTENT_OWNER,
    DUPLICATE_RULE_ID,
    RESERVED_INTENT_OWNER,
    RESERVED_CONTRIBUTOR_ID,
    UNKNOWN_DEPENDENCY,
    DEPENDENCY_CYCLE,
}

sealed class SchedulerException(
    val error: SchedulerError,
    message: String,
) : IllegalArgumentException(message)

class DuplicateIntentOwner(
    val intent: Intent,
    val owners: List<Rule>,
) : SchedulerException(
        SchedulerError.DUPLICATE_INTENT_OWNER,
        "duplicate owner for intent '$intent': ${owners.joinToString { it.id }}",
    )

class DuplicateRuleId(
    val ruleId: String,
    val rules: List<Rule>,
) : SchedulerException(
        SchedulerError.DUPLICATE_RULE_ID,
        "duplicate rule id '$ruleId': ${rules.joinToString { "${it.id} owns ${it.owns}" }}",
    )

class ReservedIntentOwner(
    val rule: Rule,
    val intent: Intent = Intents.UNTAGGED,
) : SchedulerException(
        SchedulerError.RESERVED_INTENT_OWNER,
        "rule '${rule.id}' owns reserved intent '$intent'",
    )

class ReservedContributorId(
    val rule: Rule,
    prefix: String = CoreContributors.PREFIX,
) : SchedulerException(
        SchedulerError.RESERVED_CONTRIBUTOR_ID,
        "rule '${rule.id}' uses the reserved contributor prefix '$prefix'",
    )

class UnknownDependency(
    val intent: Intent,
    val reader: Rule,
) : SchedulerException(
        SchedulerError.UNKNOWN_DEPENDENCY,
        "unknown dependency '$intent' read by ${reader.id}",
    )

class DependencyCycle(
    val rules: List<Rule>,
) : SchedulerException(
        SchedulerError.DEPENDENCY_CYCLE,
        "dependency cycle: ${rules.joinToString(" -> ") { it.id }}",
    )

data class RuleRelation(
    val reader: Rule,
    val intent: Intent,
    val owner: Rule,
)

data class TerminalNode(
    val sourceIntents: Set<Intent>,
)

data class ExecutionPlan(
    val strata: List<List<Rule>>,
    val relations: List<RuleRelation>,
    val terminal: TerminalNode,
)

private val Rule.id: String get() = spec.id
private val Rule.owns: Intent get() = spec.owns
private val Rule.reads: Set<Intent> get() = spec.reads.toSet()
private val Rule.optionalReads: Set<Intent> get() = spec.optionalReads.toSet()

private val ruleOrder =
    compareBy<Rule>(
        { it.id },
        { it.owns },
        { it.reads.sorted().joinToString("\u0000") },
        { it.optionalReads.sorted().joinToString("\u0000") },
    )

class RuleScheduler {
    fun plan(rules: List<Rule>): ExecutionPlan {
        val sorted = sortedRules(rules)
        val owners = ownersOf(sorted)
        val relations = relationsOf(sorted, owners)
        return ExecutionPlan(
            strata = strataOf(sorted, relations),
            relations = relations,
            terminal = TerminalNode(owners.keys.toSortedSet()),
        )
    }

    private fun sortedRules(rules: List<Rule>): List<Rule> {
        val sorted = rules.sortedWith(ruleOrder)
        var previous: Rule? = null
        for (rule in sorted) {
            if (rule.id.startsWith(CoreContributors.PREFIX)) {
                throw ReservedContributorId(rule)
            }
            if (previous?.id == rule.id) {
                throw DuplicateRuleId(rule.id, listOf(previous, rule))
            }
            previous = rule
        }
        return sorted
    }

    private fun ownersOf(rules: List<Rule>): Map<Intent, Rule> {
        val owners = sortedMapOf<Intent, Rule>()
        for (rule in rules) {
            if (rule.owns == Intents.UNTAGGED) {
                throw ReservedIntentOwner(rule, rule.owns)
            }
            val existing = owners[rule.owns]
            if (existing != null) {
                throw DuplicateIntentOwner(rule.owns, listOf(existing, rule).sortedWith(ruleOrder))
            }
            owners[rule.owns] = rule
        }
        return owners
    }

    private fun relationsOf(
        rules: List<Rule>,
        owners: Map<Intent, Rule>,
    ): List<RuleRelation> =
        rules.flatMap { reader ->
            val required =
                reader.reads.sorted().map { intent ->
                    val owner = owners[intent] ?: throw UnknownDependency(intent, reader)
                    RuleRelation(reader, intent, owner)
                }
            val optional =
                reader.optionalReads.sorted().mapNotNull { intent ->
                    owners[intent]?.let { RuleRelation(reader, intent, it) }
                }
            required + optional
        }

    private fun strataOf(
        rules: List<Rule>,
        relations: List<RuleRelation>,
    ): List<List<Rule>> {
        val dependencies = mutableMapOf<Rule, MutableSet<Rule>>()
        val dependents = mutableMapOf<Rule, MutableSet<Rule>>()
        for (relation in relations) {
            if (relation.owner == relation.reader) continue
            dependencies.getOrPut(relation.reader) { mutableSetOf() } += relation.owner
            dependents.getOrPut(relation.owner) { mutableSetOf() } += relation.reader
        }

        val remaining = rules.toMutableSet()
        val indegree = rules.associateWith { dependencies[it].orEmpty().size }.toMutableMap()
        val strata = mutableListOf<List<Rule>>()
        var ready = rules.filter { indegree.getValue(it) == 0 }.sortedWith(ruleOrder)

        while (ready.isNotEmpty()) {
            strata += ready
            val next = mutableListOf<Rule>()
            for (rule in ready) {
                remaining.remove(rule)
                for (dependent in dependents[rule].orEmpty()) {
                    val degree = indegree.getValue(dependent) - 1
                    indegree[dependent] = degree
                    if (degree == 0) next += dependent
                }
            }
            ready = next.sortedWith(ruleOrder)
        }

        if (remaining.isNotEmpty()) {
            throw DependencyCycle(findCycle(rules, dependencies))
        }
        return strata
    }

    private fun findCycle(
        rules: List<Rule>,
        dependencies: Map<Rule, Set<Rule>>,
    ): List<Rule> {
        val state = mutableMapOf<Rule, Int>()
        val stack = mutableListOf<Rule>()

        fun visit(rule: Rule): List<Rule>? {
            when (state[rule]) {
                1 -> {
                    val start = stack.indexOf(rule)
                    return stack.subList(start, stack.size) + rule
                }

                2 -> {
                    return null
                }
            }
            state[rule] = 1
            stack += rule
            for (dependency in dependencies[rule].orEmpty().sortedWith(ruleOrder)) {
                val cycle = visit(dependency)
                if (cycle != null) return cycle
            }
            stack.removeAt(stack.lastIndex)
            state[rule] = 2
            return null
        }

        for (rule in rules.sortedWith(ruleOrder)) {
            val cycle = visit(rule) ?: continue
            return cycle
        }
        return emptyList()
    }
}
