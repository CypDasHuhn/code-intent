package dev.cint.contracts.ports

import dev.cint.contracts.facts.IndexedSource
import dev.cint.contracts.facts.SourceInput
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractResult

interface Index {
    fun ensure(
        input: SourceInput,
        support: LanguageSupport,
        cancellation: CancellationToken = CancellationToken.NONE,
    ): ContractResult<IndexedSource>

    fun invalidate(
        paths: List<String>,
        cancellation: CancellationToken = CancellationToken.NONE,
    ): ContractResult<Unit>

    fun snapshot(
        path: String,
        cancellation: CancellationToken = CancellationToken.NONE,
    ): ContractResult<IndexedSource?>

    fun dependentsOf(
        path: String,
        cancellation: CancellationToken = CancellationToken.NONE,
    ): ContractResult<List<String>>
}