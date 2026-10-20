package dev.cint.host

import dev.cint.core.config.IntentConfig
import dev.cint.core.config.ProjectConfig
import dev.cint.core.config.parseProjectConfigTolerant
import dev.cint.core.facts.frontend.OracleOptions

data class HostSettings(
    val project: ProjectConfig,
    val lspTimeoutConfigured: Boolean = false,
) {
    fun oracleOptions(
        useLsp: Boolean = true,
        defaultTimeoutMs: Long? = null,
    ): OracleOptions {
        val lsp = project.core.lsp
        val timeoutMs =
            if (lspTimeoutConfigured || defaultTimeoutMs == null) {
                lsp.timeoutMs
            } else {
                defaultTimeoutMs
            }
        return OracleOptions(lsp.enabled && useLsp, timeoutMs)
    }

    fun intentConfig(language: String): IntentConfig =
        IntentConfig(
            language = language,
            pureAnnotations = project.core.purityAnnotations,
        )
}

object HostConfig {
    fun load(path: String?): HostSettings {
        if (path == null) return HostSettings(ProjectConfig())

        val parsed = parseProjectConfigTolerant(path)
        require(parsed.issues.isEmpty()) {
            "invalid config '$path': ${parsed.issues.joinToString("; ") { "${it.path}: ${it.message}" }}"
        }
        return HostSettings(parsed.config, parsed.lspTimeoutConfigured)
    }
}
