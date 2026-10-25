package dev.cint.runtime

import dev.cint.contracts.facts.SourceInput
import dev.cint.contracts.ports.LanguageSupport
import dev.cint.contracts.util.ContractResult
import dev.cint.v2.languages.LanguageCatalog
import dev.cint.v2.languages.LanguageOptions
import dev.cint.v2.languages.LanguageSupportFactory

fun interface RuntimeLanguageComposition {
    val catalog: LanguageCatalog
        get() = LanguageCatalog.of(emptyList())

    fun resolve(
        input: SourceInput,
        options: LanguageOptions,
    ): ContractResult<LanguageSupport>
}

class DefaultRuntimeLanguageComposition(
    override val catalog: LanguageCatalog,
    private val factory: LanguageSupportFactory,
) : RuntimeLanguageComposition {
    override fun resolve(
        input: SourceInput,
        options: LanguageOptions,
    ): ContractResult<LanguageSupport> = factory.resolveForInput(input, options)
}
