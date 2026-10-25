package dev.cint.v2.languages

import dev.cint.contracts.facts.LanguageDescriptor
import dev.cint.contracts.ports.ProviderMode
import dev.cint.contracts.ports.SemanticProviderConfig

fun interface LanguageProviderDefaults {
    fun configFor(descriptor: LanguageDescriptor): SemanticProviderConfig
}

object DefaultLanguageProviderDefaults : LanguageProviderDefaults {
    private val overrides: Map<String, SemanticProviderConfig> =
        mapOf(
            "kotlin" to SemanticProviderConfig(mode = ProviderMode.MANAGED, command = "kotlin-lsp --stdio"),
        )

    override fun configFor(descriptor: LanguageDescriptor): SemanticProviderConfig =
        overrides[descriptor.id] ?: SemanticProviderConfig(mode = descriptor.providerMode)
}
