package dev.cint.v2.syntax.treesitter

import dev.cint.contracts.facts.ChangedRanges
import dev.cint.contracts.facts.FactBatch
import dev.cint.contracts.facts.SyntaxTree
import dev.cint.contracts.ports.SyntaxEngine
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContentHash
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.NodeHash
import dev.cint.contracts.util.ResultFactory

actual fun createSyntaxEngine(): SyntaxEngine = UnavailableJvmSyntaxEngine()

private class UnavailableJvmSyntaxEngine : SyntaxEngine {
    override val language: String = "c"

    override fun parse(
        content: ByteArray,
        previous: SyntaxTree?,
        cancellation: CancellationToken,
    ): ContractResult<SyntaxTree> = contractFailure(unavailable())

    override fun diff(previous: SyntaxTree, next: SyntaxTree): ChangedRanges = ChangedRanges.WHOLE_FILE

    override fun nodeHash(tree: SyntaxTree, nodeId: String): ContractResult<String> {
        if (tree.getNode(nodeId) == null) {
            return contractFailure(ContractError.NotFound("SyntaxNode", nodeId))
        }
        return try {
            ResultFactory.success(
                subtreeHash(tree, nodeId, mutableMapOf(), mutableSetOf()).value,
            )
        } catch (t: Throwable) {
            contractFailure(ContractError.InternalError("tree-sitter node hash failed", t.message))
        }
    }

    private fun subtreeHash(
        tree: SyntaxTree,
        nodeId: String,
        memo: MutableMap<String, NodeHash>,
        visiting: MutableSet<String>,
    ): NodeHash {
        memo[nodeId]?.let { return it }
        check(visiting.add(nodeId)) { "syntax tree contains a cycle at $nodeId" }
        val node = checkNotNull(tree.getNode(nodeId)) { "syntax node $nodeId is missing" }
        val childHashes = node.childIds.map { childId ->
            subtreeHash(tree, childId, memo, visiting)
        }
        val hash = NodeHash.compute(node.kind, childHashes, ContentHash.compute(node.text))
        visiting.remove(nodeId)
        memo[nodeId] = hash
        return hash
    }

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
