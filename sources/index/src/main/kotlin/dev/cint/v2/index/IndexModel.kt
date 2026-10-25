package dev.cint.v2.index

import dev.cint.contracts.facts.IndexedSource
import dev.cint.contracts.facts.SemanticRequestKind
import dev.cint.contracts.facts.SourceInput
import dev.cint.contracts.ports.Index
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractResult

data class SemanticRequestSpec(
    val kind: SemanticRequestKind,
    val offset: Int = 0,
)

data class IndexPlan(
    val syntaxQueryIds: List<String>,
    val semanticRequests: List<SemanticRequestSpec>,
)

data class NodeChange(
    val added: Set<String>,
    val removed: Set<String>,
    val changed: Set<String>,
) {
    val isEmpty: Boolean
        get() = added.isEmpty() && removed.isEmpty() && changed.isEmpty()
}

interface IncrementalIndex : Index {
    fun snapshotSource(
        input: SourceInput,
        cancellation: CancellationToken = CancellationToken.NONE,
    ): ContractResult<IndexedSource?>

    fun nodeHashes(
        path: String,
        cancellation: CancellationToken = CancellationToken.NONE,
    ): ContractResult<Map<String, String>?>

    fun nodeHashesOf(
        input: SourceInput,
        cancellation: CancellationToken = CancellationToken.NONE,
    ): ContractResult<Map<String, String>?>

    fun lastChange(
        path: String,
        cancellation: CancellationToken = CancellationToken.NONE,
    ): ContractResult<NodeChange?>

    fun lastChangeOf(
        input: SourceInput,
        cancellation: CancellationToken = CancellationToken.NONE,
    ): ContractResult<NodeChange?>

    fun keyOf(input: SourceInput): ContractResult<String>
}
