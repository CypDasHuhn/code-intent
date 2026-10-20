package dev.cint.host

import dev.cint.core.config.IntentConfig
import dev.cint.core.config.ProjectConfig
import dev.cint.core.config.ProjectConfigIssue
import dev.cint.core.config.parseProjectConfig
import dev.cint.core.facts.frontend.OracleOptions
import java.io.File

data class HostSettings(
    val project: ProjectConfig,
    val issues: List<ProjectConfigIssue> = emptyList(),
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
            intents = emptyMap(),
            pureAnnotations = project.core.purityAnnotations,
        )
}

object HostConfig {
    fun load(
        path: String?,
        @Suppress("UNUSED_PARAMETER") language: String,
    ): HostSettings = load(path)

    fun load(path: String?): HostSettings {
        if (path == null) return HostSettings(ProjectConfig())

        val file = File(path)
        require(file.isFile) { "config not found: '$path'" }
        val parsed = parseProjectConfig(path)
        if (parsed.issues.isNotEmpty()) {
            val message = parsed.issues.joinToString("; ") { "${it.path}: ${it.message}" }
            error("invalid config '$path': $message")
        }
        return HostSettings(parsed.config, parsed.issues, parsed.lspTimeoutConfigured)
    }
}
