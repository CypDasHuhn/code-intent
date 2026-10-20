package dev.cint.core.runtime.composition

import dev.cint.api.Annotation
import dev.cint.api.Contribution
import dev.cint.api.Intent
import dev.cint.api.Role
import dev.cint.core.config.IntentConfig
import dev.cint.core.facts.flow.ControlFlow
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.TypeOracle
import dev.cint.core.facts.store.AnnotationStore
import dev.cint.core.runtime.engine.AnalysisContext
import dev.cint.core.syntax.SyntaxModel
import dev.cint.core.syntax.SyntaxTree

class Composition {
    fun compose(
        sourceIntents: Set<Intent>,
        file: SourceFile,
        tree: SyntaxTree,
        syntax: SyntaxModel,
        flow: ControlFlow,
        oracle: TypeOracle,
        config: IntentConfig,
        sources: List<Contribution>,
        contextSources: List<Contribution> = emptyList(),
    ): List<Annotation> {
        val source =
            sources.filter { contribution ->
                contribution.role == Role.SOURCE_INTENT &&
                    contribution.intents.any { it in sourceIntents }
            }

        val store = AnnotationStore()
        val contextContributions = source + contextSources.filter { it.role == Role.SOURCE_INTENT }
        for ((rule, contributions) in contextContributions.groupBy { it.rule }) {
            store.replaceRule("$SOURCE_KEY_PREFIX$rule", contributions)
        }
        val context = AnalysisContext(file, tree, syntax, flow, oracle, config, store)

        val returns = returnContributions(context)
        store.replaceRule(RETURN_KEY, returns)
        val derivatives =
            settle(store, DERIVATIVE_KEY) {
                derivativeContributions(context, config.pureAnnotations)
            }
        val scaffolds =
            settle(store, SCAFFOLD_KEY) {
                scaffoldContributions(context)
            }

        val output = AnnotationStore()
        listOf(RETURN_KEY to returns, DERIVATIVE_KEY to derivatives, SCAFFOLD_KEY to scaffolds)
            .forEach { (key, contributions) ->
                if (contributions.isNotEmpty()) {
                    output.replaceRule(key, contributions)
                }
            }
        return output.annotations()
    }

    private fun settle(
        store: AnnotationStore,
        key: String,
        produce: () -> List<Contribution>,
    ): List<Contribution> {
        var output = produce()
        while (true) {
            if (!store.replaceRule(key, output)) return output
            output = produce()
        }
    }
}
