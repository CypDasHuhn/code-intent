package dev.cint.runtime

import dev.cint.contracts.facts.LanguageDescriptor
import dev.cint.contracts.ports.ProviderMode
import dev.cint.contracts.ports.SemanticProviderConfig
import dev.cint.v2.index.IndexPlan
import dev.cint.v2.index.SemanticRequestSpec
import dev.cint.v2.languages.LanguageOptions
import dev.cint.v2.languages.LanguageProviderDefaults

data class RuntimeConfig(
    val rootPath: String? = null,
    val knowledgePath: String? = null,
    val initializeLanguages: Set<String> = emptySet(),
    val detectLanguages: Boolean = true,
    val indexPlan: IndexPlan = defaultIndexPlan(),
    val semanticTimeoutMs: Long = 5_000,
    val providerCommands: Map<String, String> = DEFAULT_PROVIDER_COMMANDS,
    val providerConfigs: Map<String, SemanticProviderConfig> = emptyMap(),
    val languageOptions: LanguageOptions = LanguageOptions(rootPath = rootPath),
    val publishOnAnalyze: Boolean = false,
    val materializeDefaultsOnOpen: Boolean = true,
    val applyUnmaterializedDefaults: Boolean = true,
    val enableCallChains: Boolean = true,
) {
    companion object {
        val DEFAULT_PROVIDER_COMMANDS: Map<String, String> =
            mapOf("kotlin" to "kotlin-lsp --stdio")

        fun defaultIndexPlan(): IndexPlan =
            IndexPlan(
                syntaxQueryIds = emptyList(),
                semanticRequests =
                    listOf(SemanticRequestSpec(dev.cint.contracts.facts.SemanticRequestKind.TYPE_AT, 0)),
            )
    }
}

internal fun RuntimeConfig.providerDefaults(): LanguageProviderDefaults =
    LanguageProviderDefaults { descriptor -> providerConfigFor(descriptor) }

internal fun RuntimeConfig.providerConfigFor(descriptor: LanguageDescriptor): SemanticProviderConfig {
    val configured = providerConfigs[descriptor.id]
    val commandOverride = providerCommands[descriptor.id]
    val base =
        configured
            ?: SemanticProviderConfig(
                mode = if (commandOverride != null) ProviderMode.MANAGED else descriptor.providerMode,
                command = commandOverride,
            )
    val command = base.command ?: commandOverride
    return if (base.mode == ProviderMode.MANAGED && !command.isNullOrBlank()) {
        base.copy(command = command)
    } else {
        base
    }
}
