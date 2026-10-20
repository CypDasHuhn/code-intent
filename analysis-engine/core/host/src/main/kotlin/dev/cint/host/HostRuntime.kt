package dev.cint.host

import dev.cint.api.RulePackConfigResolver
import dev.cint.core.config.ProjectConfig

object HostRuntime {
    fun pipeline(
        project: ProjectConfig = ProjectConfig(),
        configResolver: RulePackConfigResolver = RulePackConfigResolver.NONE,
    ): HostPipeline = HostPipeline(project = project, configResolver = configResolver)

    fun pipeline(
        settings: HostSettings,
        configResolver: RulePackConfigResolver = RulePackConfigResolver.NONE,
    ): HostPipeline = pipeline(settings.project, configResolver)
}
