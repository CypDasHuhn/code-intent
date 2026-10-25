package dev.cint.contracts.ports

import dev.cint.contracts.facts.IndexedSource
import dev.cint.contracts.facts.SourceInput
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory

interface Index {
    fun ensure(
        input: SourceInput,
        support: LanguageSupport,
        cancellation: CancellationToken = CancellationToken.NONE,
    ): ContractResult<IndexedSource>

    fun fullRescan(
        inputs: List<IndexInput>,
        cancellation: CancellationToken = CancellationToken.NONE,
    ): ContractResult<RescanResult>

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

    fun contentHash(
        path: String,
        cancellation: CancellationToken = CancellationToken.NONE,
    ): ContractResult<String?> = ResultFactory.success(null)
}

data class IndexInput(
    val input: SourceInput,
    val support: LanguageSupport,
)

data class RescanResult(
    val sources: List<IndexedSource>,
)