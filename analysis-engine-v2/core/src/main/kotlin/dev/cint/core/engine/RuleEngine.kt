package dev.cint.core.engine

import dev.cint.contracts.data.Annotation
import dev.cint.contracts.data.Candidate
import dev.cint.contracts.data.Contribution
import dev.cint.contracts.data.Intent
import dev.cint.contracts.data.Role
import dev.cint.contracts.facts.FactBatch
import dev.cint.core.composition.Composition
import dev.cint.core.composition.CompositionConfig
import dev.cint.core.graph.ExecutionPlan
import dev.cint.core.graph.RuleScheduler
import dev.cint.core.rule.CandidateEmit
import dev.cint.core.rule.Emit
import dev.cint.core.rule.Rule
import dev.cint.core.rule.RuleEmit
import dev.cint.core.rule.toCandidate
import dev.cint.core.rule.toContribution
import dev.cint.core.store.AnnotationStore
import dev.cint.core.view.RuleView

const val USER_CONTRIBUTOR_ID: String = "user:annotations"

private val Rule.id: String get() = spec.id
private val Rule.owns: Intent get() = spec.owns
private val Rule.reads: Set<Intent> get() = spec.reads.toSet()
private val Rule.optionalReads: Set<Intent> get() = spec.optionalReads.toSet()

class NonConvergentRule(
    val ruleId: String,
    val iterations: Long,
    reason: String,
) : IllegalStateException("rule '$ruleId' did not converge after $iterations iterations: $reason")

class NonMonotoneRule(
    val ruleId: String,
    val iterations: Long,
    reason: String,
) : IllegalStateException("rule '$ruleId' is not monotone at iteration $iterations: $reason")

data class AnalysisOutput(
    val annotations: List<Annotation>,
    val candidates: List<Candidate>,
)

private data class AnnotationIdentity(
    val role: Role,
    val startByte: Int,
    val endByte: Int,
    val intents: Set<Intent>,
)

class RuleEngine(
    private val scheduler: RuleScheduler = RuleScheduler(),
    private val maxIterationsPerRule: Int? = null,
    private val composition: Composition = Composition(),
) {
    init {
        require(maxIterationsPerRule == null || maxIterationsPerRule > 0) {
            "maxIterationsPerRule must be positive"
        }
    }

    fun plan(rules: List<Rule>): ExecutionPlan = scheduler.plan(rules)

    fun analyze(
        facts: FactBatch,
        rules: List<Rule>,
        userContributions: List<Contribution> = emptyList(),
        compositionConfig: CompositionConfig = CompositionConfig(),
    ): AnalysisOutput = analyze(plan(rules), facts, userContributions, compositionConfig)

    fun analyze(
        plan: ExecutionPlan,
        facts: FactBatch,
        userContributions: List<Contribution> = emptyList(),
        compositionConfig: CompositionConfig = CompositionConfig(),
    ): AnalysisOutput {
        val settled = linkedMapOf<String, List<Contribution>>()
        val candidates = mutableListOf<Candidate>()
        if (userContributions.isNotEmpty()) {
            settled[USER_CONTRIBUTOR_ID] = userContributions
        }

        for (stratum in plan.strata) {
            val settledStratum = mutableListOf<Pair<String, List<Contribution>>>()
            for (rule in stratum) {
                val settlement = settle(rule, settled, facts)
                settledStratum += rule.id to settlement.contributions
                candidates += settlement.candidates
            }
            for ((id, contributions) in settledStratum) {
                if (contributions.isEmpty()) {
                    settled.remove(id)
                } else {
                    settled[id] = contributions
                }
            }
        }

        val sources = settled.values.flatten()
        val composed =
            composition.compose(
                plan.terminal.sourceIntents,
                facts,
                sources,
                userContributions,
                compositionConfig,
            )

        val store = AnnotationStore()
        for ((id, contributions) in settled) {
            store.replaceRule(id, contributions)
        }
        val annotations =
            (store.annotations() + composed).sortedWith(
                compareBy<Annotation>(
                    { it.span.startLine },
                    { it.span.startCol },
                    { -it.endByte },
                ),
            )
        return AnalysisOutput(
            annotations = annotations,
            candidates =
                candidates.sortedWith(
                    compareBy<Candidate>(
                        { it.startByte },
                        { it.endByte },
                        { it.intents.sorted().joinToString(",") },
                        { it.reason.name },
                        { it.source },
                    ),
                ),
        )
    }

    private fun settle(
        rule: Rule,
        base: Map<String, List<Contribution>>,
        facts: FactBatch,
    ): RuleSettlement {
        val store = AnnotationStore()
        for ((id, contributions) in base) {
            store.replaceRule(id, contributions)
        }
        val view = RuleView(rule.owns, rule.reads, rule.optionalReads, facts, store)

        fun run(): RuleOutcome = outcomeOf(rule, rule.evaluate(view))

        var output = run()
        if (rule.owns !in rule.reads) {
            return settleOutput(rule, output)
        }

        val configuredLimit = maxIterationsPerRule?.toLong()
        val limit = configuredLimit ?: fixpointLimit(facts)
        var identities = emptySet<AnnotationIdentity>()
        var iterations = 0L
        while (true) {
            if (!store.replaceRule(rule.id, output.established.map(rule::toContribution))) {
                return settleOutput(rule, output)
            }
            iterations++
            val next = identitiesOf(output.established.map(rule::toContribution))
            if (!next.containsAll(identities)) {
                throw NonMonotoneRule(rule.id, iterations, "annotation identity set shrank")
            }
            if (iterations > limit) {
                val kind = if (configuredLimit != null) "configured" else "derived"
                throw NonConvergentRule(
                    rule.id,
                    iterations,
                    "$kind iteration limit $limit exceeded",
                )
            }
            identities = next
            output = run()
        }
    }

    private fun settleOutput(
        rule: Rule,
        outcome: RuleOutcome,
    ): RuleSettlement =
        RuleSettlement(
            contributions = outcome.established.map(rule::toContribution),
            candidates = outcome.candidates,
        )

    private fun identitiesOf(contributions: List<Contribution>): Set<AnnotationIdentity> =
        contributions.mapTo(mutableSetOf()) {
            AnnotationIdentity(it.role, it.startByte, it.endByte, it.intents)
        }

    private fun fixpointLimit(facts: FactBatch): Long {
        val length =
            facts.syntaxTree
                ?.let { it.getNode(it.rootId)?.endByte }
                ?.toLong()
                ?: 0L
        return (length + 1L) * (length + 2L) / 2L + 1L
    }

    private fun outcomeOf(
        rule: Rule,
        emits: List<RuleEmit>,
    ): RuleOutcome {
        val established = mutableListOf<Emit>()
        val candidates = mutableListOf<CandidateEmit>()
        for (emit in emits) {
            require(emit.intents == setOf(rule.owns)) {
                "rule '${rule.id}' emitted intents ${emit.intents.sorted()} but owns '${rule.owns}'"
            }
            when (emit) {
                is Emit -> established += emit
                is CandidateEmit -> candidates += emit
            }
        }
        return RuleOutcome(
            established = established,
            candidates = candidates.map { it.toCandidate() },
        )
    }

    private data class RuleSettlement(
        val contributions: List<Contribution>,
        val candidates: List<Candidate>,
    )

    private data class RuleOutcome(
        val established: List<Emit>,
        val candidates: List<Candidate>,
    )
}
