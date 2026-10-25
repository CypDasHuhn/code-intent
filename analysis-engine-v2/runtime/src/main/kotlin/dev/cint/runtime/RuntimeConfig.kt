package dev.cint.runtime

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
    val languageOptions: LanguageOptions = LanguageOptions(),
    val publishOnAnalyze: Boolean = false,
    val materializeDefaultsOnOpen: Boolean = true,
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
    LanguageProviderDefaults { descriptor ->
        providerConfigs[descriptor.id]
            ?: run {
                val command = providerCommands[descriptor.id]
                SemanticProviderConfig(
                    mode = if (command != null) ProviderMode.MANAGED else descriptor.providerMode,
                    command = command,
                )
            }
    }
