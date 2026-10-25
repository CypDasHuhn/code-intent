package dev.cint.v2.syntax.treesitter

import dev.cint.contracts.facts.ChangedRanges
import dev.cint.contracts.facts.FactBatch
import dev.cint.contracts.facts.SyntaxTree
import dev.cint.contracts.ports.SyntaxEngine
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContractResult

actual fun createSyntaxEngine(): SyntaxEngine = UnavailableJvmSyntaxEngine()

private class UnavailableJvmSyntaxEngine : SyntaxEngine {
    override val language: String = "c"

    override fun parse(
        content: ByteArray,
        previous: SyntaxTree?,
        cancellation: CancellationToken,
    ): ContractResult<SyntaxTree> = contractFailure(unavailable())

    override fun diff(previous: SyntaxTree, next: SyntaxTree): ChangedRanges = ChangedRanges.WHOLE_FILE

    override fun nodeHash(tree: SyntaxTree, nodeId: String): ContractResult<String> =
        contractFailure(unavailable())

    override fun facts(
        tree: SyntaxTree,
        queryIds: List<String>,
        cancellation: CancellationToken,
    ): ContractResult<FactBatch> = contractFailure(unavailable())

    private fun unavailable(): ContractError =
        ContractError.DependencyUnavailable(
            dependency = "tree-sitter",
            reason = "tree-sitter C interop is native-only in this spike; run the native-spike binary"
        )
}
