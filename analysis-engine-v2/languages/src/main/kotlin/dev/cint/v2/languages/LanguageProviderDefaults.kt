package dev.cint.v2.languages

import dev.cint.contracts.facts.LanguageDescriptor
import dev.cint.contracts.ports.SemanticProviderConfig

fun interface LanguageProviderDefaults {
    fun configFor(descriptor: LanguageDescriptor): SemanticProviderConfig
}

internal object DescriptorModeProviderDefaults : LanguageProviderDefaults {
    override fun configFor(descriptor: LanguageDescriptor): SemanticProviderConfig =
        SemanticProviderConfig(mode = descriptor.providerMode)
}
