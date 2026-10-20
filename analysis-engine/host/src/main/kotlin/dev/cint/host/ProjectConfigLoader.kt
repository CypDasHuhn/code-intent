package dev.cint.host

import dev.cint.api.RulePack
import dev.cint.api.RulePackConfigResolver
import dev.cint.core.config.CorePolicy
import dev.cint.core.config.ProjectConfig
import dev.cint.core.config.ProjectConfigIssue
import dev.cint.core.config.parseProjectConfig
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
    private val loader: RulePackLoader,
) {
    fun load(
        path: String?,
        configResolver: RulePackConfigResolver = RulePackConfigResolver.NONE,
    ): LoadedProjectConfig {
        val parsed = parseProjectConfig(path)
        val loaded = load(parsed.config, configResolver)
        return loaded.copy(issues = parsed.issues)
    }

    fun load(
        config: ProjectConfig,
        configResolver: RulePackConfigResolver = RulePackConfigResolver.NONE,
    ): LoadedProjectConfig {
        val result = loader.load(config.packs, configResolver)
        return LoadedProjectConfig(result.packs, config.core, result.failures, emptyList())
    }
}
