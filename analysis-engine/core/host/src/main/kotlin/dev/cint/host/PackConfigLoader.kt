package dev.cint.host

import dev.cint.api.RulePack
import dev.cint.api.RulePackConfigResolver
import dev.cint.core.config.CorePolicy
import dev.cint.core.config.ProjectConfig
import dev.cint.core.runtime.rulepack.RulePackLoadFailure
import dev.cint.core.runtime.rulepack.RulePackLoader

internal data class LoadedPackConfig(
    val packs: List<RulePack>,
    val core: CorePolicy,
    val failures: List<RulePackLoadFailure>,
)

internal class PackConfigLoader(
    private val loader: RulePackLoader,
) {
    fun load(
        config: ProjectConfig,
        configResolver: RulePackConfigResolver = RulePackConfigResolver.NONE,
        overlay: Map<String, Map<String, Any?>> = emptyMap(),
    ): LoadedPackConfig {
        val result = loader.load(config.packs, configResolver, overlay)
        return LoadedPackConfig(result.packs, config.core, result.failures)
    }
}
