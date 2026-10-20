package dev.cint.host

import dev.cint.api.RulePack
import dev.cint.api.RulePackConfigResolver
import dev.cint.api.UNTAGGED_INTENT
import dev.cint.core.config.IntentConfig
import dev.cint.core.config.ProjectConfig
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.TypeOracle
import dev.cint.core.runtime.rulepack.RulePackLoadFailure
import dev.cint.core.runtime.rulepack.RulePackLoadResult
import dev.cint.core.runtime.rulepack.RulePackLoader
import dev.cint.core.runtime.scheduler.ExecutionPlan
import dev.cint.core.runtime.scheduler.RuleEngine
import dev.cint.core.syntax.SyntaxModel
import dev.cint.core.syntax.SyntaxTree
import java.util.concurrent.ConcurrentHashMap

class HostPipeline(
    private val loader: RulePackLoader = defaultLoader(),
    project: ProjectConfig = ProjectConfig(),
    configResolver: RulePackConfigResolver = RulePackConfigResolver.NONE,
) {
    private val loaded = PackConfigLoader(loader).load(project, configResolver)
    private val discovered = RulePackLoadResult(loaded.packs, loaded.failures)
    private val engine = RuleEngine()
    private val snapshots = ConcurrentHashMap<String, RulePackLoadResult>()
    private val plans = ConcurrentHashMap<String, ExecutionPlan>()

    val failures: List<RulePackLoadFailure>
        get() = loaded.failures + snapshots.values.flatMap { it.failures }

    fun packs(language: String): List<RulePack> = snapshot(language).packs

    fun plan(language: String): ExecutionPlan =
        plans.computeIfAbsent(normalize(language)) {
            engine.plan(snapshot(it).packs)
        }

    fun analyze(
        source: SourceFile,
        tree: SyntaxTree,
        syntax: SyntaxModel,
        oracle: TypeOracle,
        config: IntentConfig,
    ) = engine.analyze(plan(config.language), source, tree, syntax, oracle, config)

    fun intents(languages: Collection<String>): List<String> {
        val intents = sortedSetOf<String>()
        for (language in languages) {
            intents += plan(language).terminal.sourceIntents
        }
        intents += UNTAGGED_INTENT
        return intents.toList()
    }

    private fun snapshot(language: String): RulePackLoadResult =
        snapshots.computeIfAbsent(normalize(language)) { normalized ->
            loader.validate(discovered.packs.filter { supports(it, normalized) })
        }

    private fun supports(
        pack: RulePack,
        language: String,
    ): Boolean =
        try {
            pack.languages.isEmpty() || pack.languages.any { it.equals(language, ignoreCase = true) }
        } catch (error: VirtualMachineError) {
            throw error
        } catch (_: Throwable) {
            true
        }

    private fun normalize(language: String): String = language.lowercase()
}

private fun defaultLoader(): RulePackLoader =
    RulePackLoader(
        onFailure = { System.err.println("cint rulepack: ${it.message}") },
    )
