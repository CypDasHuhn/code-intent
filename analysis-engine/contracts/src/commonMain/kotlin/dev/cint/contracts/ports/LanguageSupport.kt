package dev.cint.contracts.ports

import dev.cint.contracts.facts.LanguageDescriptor
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory

interface LanguageSupport {
    fun descriptor(): ContractResult<LanguageDescriptor>

    fun syntax(): ContractResult<SyntaxEngine>

    fun semanticProvider(): ContractResult<SemanticProvider>

    fun shutdown(): ContractResult<Unit> = ResultFactory.success(Unit)
}
