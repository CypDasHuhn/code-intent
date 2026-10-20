package dev.cint.host

import dev.cint.api.RulePackConfigResolver
import dev.cint.core.config.ProjectConfig
import java.nio.file.Path

object HostRuntime {
    fun pipeline(
        project: ProjectConfig = ProjectConfig(),
        configResolver: RulePackConfigResolver = RulePackConfigResolver.NONE,
        root: Path? = null,
    ): HostPipeline = HostPipeline(project = project, configResolver = configResolver, root = root)

    fun pipeline(
        settings: HostSettings,
        configResolver: RulePackConfigResolver = RulePackConfigResolver.NONE,
        root: Path? = null,
    ): HostPipeline = pipeline(settings.project, configResolver, root)
}
