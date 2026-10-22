package dev.cint.core.runtime.scheduler

import dev.cint.api.Annotation
import dev.cint.api.CandidateEmit
import dev.cint.api.Contribution
import dev.cint.api.Emit
import dev.cint.api.Intent
import dev.cint.api.Role
import dev.cint.api.Rule
import dev.cint.api.RuleEmit
import dev.cint.api.RulePack
import dev.cint.api.toContribution
import dev.cint.core.config.IntentConfig
import dev.cint.core.facts.flow.ControlFlow
import dev.cint.core.facts.flow.ControlFlowBuilder
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.NullOracle
import dev.cint.core.facts.oracle.OracleBudget
import dev.cint.core.facts.oracle.OracleSession
import dev.cint.core.facts.oracle.OracleStats
import dev.cint.core.facts.oracle.TypeOracle
import dev.cint.core.facts.store.AnnotationStore
import dev.cint.core.runtime.composition.Composition
import dev.cint.core.runtime.engine.AnalysisContext
import dev.cint.core.syntax.SyntaxModel
import dev.cint.core.syntax.SyntaxTree
import dev.cint.core.user.USER_CONTRIBUTOR_ID

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

private data class AnnotationIdentity(
    val role: Role,
    val startByte: Int,
    val endByte: Int,
    val intents: Set<Intent>,
)

class RuleEngine(
    private val scheduler: RuleScheduler = RuleScheduler(),
    private val maxIterationsPerRule: Int? = null,
    private val oracleBudget: OracleBudget = OracleBudget(),
) {
    init {
        require(maxIterationsPerRule == null || maxIterationsPerRule > 0) {
            "maxIterationsPerRule must be positive"
        }
    }

    fun plan(packs: List<RulePack>): ExecutionPlan = scheduler.plan(packs)

    fun analyze(
        file: SourceFile,
        tree: SyntaxTree,
        syntax: SyntaxModel,
        oracle: TypeOracle,
        config: IntentConfig,
        packs: List<RulePack>,
        userContributions: List<Contribution> = emptyList(),
    ): AnalysisOutput = analyze(plan(packs), file, tree, syntax, oracle, config, userContributions)

    fun analyze(
        plan: ExecutionPlan,
        file: SourceFile,
        tree: SyntaxTree,
        syntax: SyntaxModel,
        oracle: TypeOracle,
        config: IntentConfig,
        userContributions: List<Contribution> = emptyList(),
    ): AnalysisOutput {
        val flow = ControlFlowBuilder(tree, syntax).build()
        val source = (oracle as? OracleSession)?.source() ?: oracle
        val session =
            when {
                source === NullOracle -> null
                oracle is OracleSession -> oracle
                else -> OracleSession(source, oracleBudget)
            }
        session?.beginAnalysis(file)
        val effective = session ?: source
        val settled = linkedMapOf<String, List<Contribution>>()
        val candidates = mutableListOf<CandidateEmit>()
        if (userContributions.isNotEmpty()) {
            settled[USER_CONTRIBUTOR_ID] = userContributions
        }

        for (stratum in plan.strata) {
            val settledStratum = mutableListOf<Pair<String, List<Contribution>>>()
            for (rule in stratum) {
                val settlement = settle(rule, settled, file, tree, syntax, flow, effective, config)
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

        val store = AnnotationStore()
        for ((id, contributions) in settled) {
            store.replaceRule(id, contributions)
        }
        val sources = settled.values.flatten()
        val composed =
            Composition().compose(
                plan.terminal.sourceIntents,
                file,
                tree,
                syntax,
                flow,
                effective,
                config,
                sources,
                userContributions,
            )
        val annotations =
            (store.annotations() + composed).sortedWith(
                compareBy<Annotation>({ it.span.startLine }, { it.span.startCol }, { -it.endByte }),
            )
        return AnalysisOutput(
            annotations,
            candidates.sortedWith(
                compareBy<CandidateEmit>(
                    { it.startByte },
                    { it.endByte },
                    { it.intents.sorted().joinToString(",") },
                    { it.reason.name },
                    { it.source },
                ),
            ),
            session?.oracleStats() ?: OracleStats.EMPTY,
        )
    }

    private fun settle(
        rule: Rule,
        base: Map<String, List<Contribution>>,
        file: SourceFile,
        tree: SyntaxTree,
        syntax: SyntaxModel,
        flow: ControlFlow,
        oracle: TypeOracle,
        config: IntentConfig,
    ): RuleSettlement {
        val store = AnnotationStore()
        for ((id, contributions) in base) {
            store.replaceRule(id, contributions)
        }
        val context = AnalysisContext(file, tree, syntax, flow, oracle, config, store)
        val view = context.view(rule.owns, rule.reads)

        fun run(): RuleOutcome = outcomeOf(rule, rule.run(view))

        var output = run()
        if (rule.owns !in rule.reads) return settleOutput(rule, output)

        val configuredLimit = maxIterationsPerRule?.toLong()
        val limit = configuredLimit ?: fixpointLimit(file)
        var identities = emptySet<AnnotationIdentity>()
        var iterations = 0L
        while (true) {
            if (!store.replaceRule(rule.id, output.established.map(rule::toContribution))) return settleOutput(rule, output)
            iterations++
            val next = identitiesOf(output.established.map(rule::toContribution))
            if (!next.containsAll(identities)) {
                throw NonMonotoneRule(rule.id, iterations, "annotation identity set shrank")
            }
            if (iterations > limit) {
                val kind = if (configuredLimit != null) "configured" else "derived"
                throw NonConvergentRule(rule.id, iterations, "$kind iteration limit $limit exceeded")
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

    private fun fixpointLimit(file: SourceFile): Long {
        val length =
            file.text
                .toByteArray(Charsets.UTF_8)
                .size
                .toLong()
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
        return RuleOutcome(established, candidates)
    }

    private data class RuleSettlement(
        val contributions: List<Contribution>,
        val candidates: List<CandidateEmit>,
    )
}
