package dev.cint.contracts.ports

import dev.cint.contracts.facts.LanguageDescriptor
import dev.cint.contracts.util.ContractResult

interface LanguageSupport {
    fun descriptor(): ContractResult<LanguageDescriptor>
    fun syntax(): ContractResult<SyntaxEngine>
    fun semanticProvider(): ContractResult<SemanticProvider>
}