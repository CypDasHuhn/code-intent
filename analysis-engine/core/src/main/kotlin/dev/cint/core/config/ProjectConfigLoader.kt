package dev.cint.core.config

import dev.cint.api.RulePack
import dev.cint.core.runtime.rulepack.RulePackLoadFailure
import dev.cint.core.runtime.rulepack.RulePackLoader

data class LoadedProjectConfig(
    val packs: List<RulePack>,
    val core: CorePolicy,
    val failures: List<RulePackLoadFailure>,
    val issues: List<ProjectConfigIssue>,
) {
    val isDegraded: Boolean get() = failures.isNotEmpty() || issues.isNotEmpty()
}

class ProjectConfigLoader(
    private val loader: RulePackLoader = RulePackLoader(),
) {
    fun load(
        path: String?,
        knownPackIds: Set<String> = emptySet(),
    ): LoadedProjectConfig {
        val parsed = parseProjectConfig(path)
        val loaded = load(parsed.config, knownPackIds)
        return loaded.copy(issues = parsed.issues)
    }

    fun load(
        config: ProjectConfig,
        knownPackIds: Set<String> = emptySet(),
    ): LoadedProjectConfig {
        val result = loader.load(config.packs, knownPackIds)
        return LoadedProjectConfig(result.packs, config.core, result.failures, emptyList())
    }
}
