package dev.cint.contracts.ports

import dev.cint.contracts.facts.ChangedRanges
import dev.cint.contracts.facts.FactBatch
import dev.cint.contracts.facts.SyntaxTree
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractResult

interface SyntaxEngine {
    val language: String

    fun parse(
        content: ByteArray,
        previous: SyntaxTree?,
        cancellation: CancellationToken = CancellationToken.NONE,
    ): ContractResult<SyntaxTree>

    fun diff(
        previous: SyntaxTree,
        next: SyntaxTree,
    ): ChangedRanges

    /**
     * Returns the structural hash of [nodeId]'s complete subtree.
     *
     * Implementations must combine the node kind, its ordered child subtree hashes, and its own
     * content, so any descendant change changes every ancestor hash.
     */
    fun nodeHash(tree: SyntaxTree, nodeId: String): ContractResult<String>

    fun facts(
        tree: SyntaxTree,
        queryIds: List<String>,
        cancellation: CancellationToken = CancellationToken.NONE,
    ): ContractResult<FactBatch>
}