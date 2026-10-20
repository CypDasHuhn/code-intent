package dev.cint.core.scheduler

import dev.cint.api.Annotation
import dev.cint.api.Contribution
import dev.cint.api.Emit
import dev.cint.api.Intent
import dev.cint.api.Role
import dev.cint.api.Rule
import dev.cint.api.RulePack
import dev.cint.api.toContribution
import dev.cint.core.composition.Composition
import dev.cint.core.config.IntentConfig
import dev.cint.core.engine.AnalysisContext
import dev.cint.core.flow.ControlFlow
import dev.cint.core.flow.ControlFlowBuilder
import dev.cint.core.model.SourceFile
import dev.cint.core.oracle.TypeOracle
import dev.cint.core.store.AnnotationStore
import dev.cint.core.syntax.SyntaxModel
import dev.cint.core.syntax.SyntaxTree

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
    ): List<Annotation> = analyze(plan(packs), file, tree, syntax, oracle, config)

    fun analyze(
        plan: ExecutionPlan,
        file: SourceFile,
        tree: SyntaxTree,
        syntax: SyntaxModel,
        oracle: TypeOracle,
        config: IntentConfig,
    ): List<Annotation> {
        val flow = ControlFlowBuilder(tree, syntax).build()
        val settled = linkedMapOf<String, List<Contribution>>()

        for (stratum in plan.strata) {
            val settledStratum = mutableListOf<Pair<String, List<Contribution>>>()
            for (rule in stratum) {
                val contributions = settle(rule, settled, file, tree, syntax, flow, oracle, config)
                settledStratum += rule.id to contributions
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
                plan.terminal,
                file,
                tree,
                syntax,
                flow,
                oracle,
                config,
                sources,
            )
        return (store.annotations() + composed).sortedWith(
            compareBy<Annotation>({ it.span.startLine }, { it.span.startCol }, { -it.endByte }),
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
    ): List<Contribution> {
        val store = AnnotationStore()
        for ((id, contributions) in base) {
            store.replaceRule(id, contributions)
        }
        val context = AnalysisContext(file, tree, syntax, flow, oracle, config, store)
        val view = context.view(rule.owns, rule.reads)

        fun run(): List<Contribution> = contributionsOf(rule, rule.run(view))

        var output = run()
        if (rule.owns !in rule.reads) return output

        val configuredLimit = maxIterationsPerRule?.toLong()
        val limit = configuredLimit ?: fixpointLimit(file)
        var identities = emptySet<AnnotationIdentity>()
        var iterations = 0L
        while (true) {
            if (!store.replaceRule(rule.id, output)) return output
            iterations++
            val next = identitiesOf(output)
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

    private fun contributionsOf(
        rule: Rule,
        emits: List<Emit>,
    ): List<Contribution> =
        emits.map { emit ->
            require(emit.intents == setOf(rule.owns)) {
                "rule '${rule.id}' emitted intents ${emit.intents.sorted()} but owns '${rule.owns}'"
            }
            rule.toContribution(emit)
        }
}
