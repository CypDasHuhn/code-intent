package dev.cint.runtime

import dev.cint.contracts.facts.LanguageDescriptor
import dev.cint.contracts.ports.ProviderMode
import dev.cint.contracts.ports.RuntimeLanguage
import dev.cint.v2.languages.LanguageOptions

internal fun RuntimeConfig.languageStatus(
    descriptor: LanguageDescriptor,
    options: LanguageOptions,
): RuntimeLanguage {
    val provider = options.providerConfig ?: providerConfigFor(descriptor)
    val hostChannelConfigured = options.hostChannel != null || provider.hostChannelConfig != null
    val hostChannelReady =
        options.hostChannel != null ||
            (options.hostChannelProvider != null && provider.hostChannelConfig != null)
    val configurationMissing =
        when (provider.mode) {
            ProviderMode.MANAGED -> if (provider.command.isNullOrBlank()) listOf("command") else emptyList()
            ProviderMode.ATTACHED -> if (provider.endpoint.isNullOrBlank()) listOf("endpoint") else emptyList()
            ProviderMode.EDITOR -> if (!hostChannelConfigured) listOf("hostChannelConfig") else emptyList()
        }
    val runtimeMissing =
        when (provider.mode) {
            ProviderMode.EDITOR -> if (!hostChannelReady) listOf("hostSemanticChannelProvider") else emptyList()
            else -> emptyList()
        }
    val missing = (configurationMissing + runtimeMissing).distinct()
    val configured = configurationMissing.isEmpty()
    return RuntimeLanguage(
        language = descriptor.id,
        name = descriptor.name,
        extensions = descriptor.extensions,
        aliases = descriptor.aliases,
        providerMode = provider.mode,
        providerConfigured = configured,
        providerAvailable = configured && runtimeMissing.isEmpty(),
        command = provider.command,
        endpoint = provider.endpoint,
        hostChannelType = provider.hostChannelConfig?.type ?: if (options.hostChannel != null) "injected" else null,
        missingConfiguration = missing,
    )
}
