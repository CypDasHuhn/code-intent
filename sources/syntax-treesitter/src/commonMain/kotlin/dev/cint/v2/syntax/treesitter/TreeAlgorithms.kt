package dev.cint.v2.syntax.treesitter

import dev.cint.contracts.facts.SyntaxTree
import dev.cint.contracts.util.ContentHash
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.NodeHash
import dev.cint.contracts.util.ResultFactory

internal object TreeAlgorithms {
    fun nodeHash(
        tree: SyntaxTree,
        nodeId: String,
    ): ContractResult<String> {
        if (tree.getNode(nodeId) == null) {
            return ResultFactory.failure(ContractError.NotFound("SyntaxNode", nodeId))
        }
        return try {
            ResultFactory.success(
                subtreeHash(tree, nodeId, mutableMapOf(), mutableSetOf()).value,
            )
        } catch (error: Throwable) {
            ResultFactory.failure(
                ContractError.InternalError("tree-sitter node hash failed", error.message),
            )
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
        val childHashes =
            node.childIds.map { childId ->
                subtreeHash(tree, childId, memo, visiting)
            }
        val hash = NodeHash.compute(node.kind, childHashes, ContentHash.compute(node.text))
        visiting.remove(nodeId)
        memo[nodeId] = hash
        return hash
    }

    fun wholeTreeContent(tree: SyntaxTree): ByteArray? = tree.getNode(tree.rootId)?.text?.encodeToByteArray()
}
