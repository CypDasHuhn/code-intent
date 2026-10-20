package dev.cint.core.host

import dev.cint.api.Intent
import dev.cint.api.RulePack
import dev.cint.api.UNTAGGED_INTENT
import dev.cint.core.config.IntentConfig
import dev.cint.core.model.SourceFile
import dev.cint.core.oracle.TypeOracle
import dev.cint.core.rulepack.RulePackLoadResult
import dev.cint.core.rulepack.RulePackLoader
import dev.cint.core.scheduler.ExecutionPlan
import dev.cint.core.scheduler.RuleEngine
import dev.cint.core.syntax.SyntaxModel
import dev.cint.core.syntax.SyntaxTree
import java.util.concurrent.ConcurrentHashMap

class HostPipeline(
    private val loader: RulePackLoader = RulePackLoader(),
    private val constructedPacks: (String) -> List<RulePack> = { emptyList() },
) {
    private val discovered = loader.load()
    private val engine = RuleEngine()
    private val snapshots = ConcurrentHashMap<String, RulePackLoadResult>()
    private val plans = ConcurrentHashMap<String, ExecutionPlan>()

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
            val selected = discovered.packs.filter { supports(it, normalized) }
            val owned = selected.flatMapTo(linkedSetOf()) { ownedIntents(it).orEmpty() }
            val selectedIds = selected.mapNotNull { safeId(it) }.toSet()
            val additions = mutableListOf<RulePack>()

            for (candidate in constructedPacks(normalized).filter { supports(it, normalized) }) {
                val id = safeId(candidate)
                if (id != null && id in selectedIds) continue
                val intents = ownedIntents(candidate)
                if (intents == null) {
                    additions += candidate
                    continue
                }
                if (intents.none { it in owned }) {
                    additions += candidate
                    owned += intents
                }
            }

            loader.validate(selected + additions)
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

    private fun ownedIntents(pack: RulePack): Set<Intent>? =
        try {
            pack.rules().mapTo(linkedSetOf()) { it.owns }
        } catch (error: VirtualMachineError) {
            throw error
        } catch (_: Throwable) {
            null
        }

    private fun safeId(pack: RulePack): String? =
        try {
            pack.id
        } catch (error: VirtualMachineError) {
            throw error
        } catch (_: Throwable) {
            null
        }

    private fun normalize(language: String): String = language.lowercase()
}
