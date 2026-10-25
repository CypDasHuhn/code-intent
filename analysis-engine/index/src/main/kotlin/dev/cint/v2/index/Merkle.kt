package dev.cint.v2.index

import dev.cint.contracts.facts.SyntaxTree
import dev.cint.contracts.ports.SyntaxEngine
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContentHash
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.Failure
import dev.cint.contracts.util.NodeHash
import dev.cint.contracts.util.ResultFactory
import dev.cint.contracts.util.Success

internal data class MerkleState(
    val rootHash: NodeHash,
    val nodeHashes: Map<String, NodeHash>,
)

internal fun buildMerkle(
    tree: SyntaxTree,
    expectedContentHash: ContentHash,
    expectedLanguage: String,
    syntax: SyntaxEngine,
    cancellation: CancellationToken,
): ContractResult<MerkleState> {
    validateTree(tree, expectedContentHash, expectedLanguage)?.let {
        return ResultFactory.failure(it)
    }
    val hashes = LinkedHashMap<String, NodeHash>(tree.nodes.size)
    for (nodeId in tree.nodes.keys) {
        if (cancellation.isCancelled) {
            return ResultFactory.failure(ContractError.Cancelled("Index.nodeHash"))
        }
        val hash =
            when (val result = syntax.nodeHash(tree, nodeId)) {
                is Success -> result.contents
                is Failure -> return ResultFactory.failure(result.error)
            }
        if (!hash.isSha256()) {
            return ResultFactory.failure(
                ContractError.ValidationError(
                    "Syntax engine returned a malformed node hash",
                    field = "nodeHash.$nodeId",
                ),
            )
        }
        hashes[nodeId] = NodeHash(hash)
    }
    val rootHash =
        hashes[tree.rootId]
            ?: return ResultFactory.failure(
                ContractError.ValidationError("Syntax tree root hash is missing", field = "rootId"),
            )
    return ResultFactory.success(MerkleState(rootHash, hashes))
}

internal fun changeBetween(
    previous: Map<String, NodeHash>,
    next: Map<String, NodeHash>,
): NodeChange {
    val added = next.keys - previous.keys
    val removed = previous.keys - next.keys
    val changed =
        (next.keys intersect previous.keys)
            .filterTo(linkedSetOf()) { previous[it] != next[it] }
    return NodeChange(added = added, removed = removed, changed = changed)
}

private fun validateTree(
    tree: SyntaxTree,
    expectedContentHash: ContentHash,
    expectedLanguage: String,
): ContractError? {
    if (!tree.contentHash.isSha256()) {
        return ContractError.ValidationError(
            "Syntax tree content hash is not a SHA-256 hex digest",
            field = "contentHash",
        )
    }
    if (tree.contentHash != expectedContentHash.value) {
        return ContractError.ValidationError(
            "Syntax tree content hash does not match the source content",
            field = "contentHash",
        )
    }
    if (tree.language != expectedLanguage) {
        return ContractError.ValidationError(
            "Syntax tree language does not match the resolved syntax engine",
            field = "language",
        )
    }
    if (tree.rootId.isBlank()) {
        return ContractError.ValidationError("Syntax tree root id is blank", field = "rootId")
    }
    if (!tree.nodes.containsKey(tree.rootId)) {
        return ContractError.ValidationError(
            "Syntax tree root is missing from its node map",
            field = "rootId",
        )
    }
    val root = tree.nodes.getValue(tree.rootId)
    if (root.parentId != null) {
        return ContractError.ValidationError("Syntax tree root must not have a parent", field = "rootId")
    }

    for ((key, node) in tree.nodes) {
        if (node.id != key) {
            return ContractError.ValidationError(
                "Syntax node map key does not match node id",
                field = "nodes.$key",
            )
        }
        if (node.id.isBlank()) {
            return ContractError.ValidationError("Syntax node id is blank", field = "nodes")
        }
        if (node.childIds.size != node.childIds.toSet().size) {
            return ContractError.ValidationError(
                "Syntax node has duplicate child ids",
                field = "nodes.$key.childIds",
            )
        }
        for (childId in node.childIds) {
            val child =
                tree.nodes[childId]
                    ?: return ContractError.ValidationError(
                        "Syntax node references a missing child",
                        field = "nodes.$key.childIds",
                    )
            if (child.parentId != null && child.parentId != key) {
                return ContractError.ValidationError(
                    "Syntax node parent does not match its parent's child list",
                    field = "nodes.$childId.parentId",
                )
            }
        }
        val parentId = node.parentId ?: continue
        val parent =
            tree.nodes[parentId]
                ?: return ContractError.ValidationError(
                    "Syntax node references a missing parent",
                    field = "nodes.$key.parentId",
                )
        if (key !in parent.childIds) {
            return ContractError.ValidationError(
                "Syntax node is absent from its parent's child list",
                field = "nodes.$key.parentId",
            )
        }
    }

    val visiting = mutableSetOf<String>()
    val visited = mutableSetOf<String>()
    val error = visit(tree, tree.rootId, visiting, visited)
    if (error != null) return error
    if (visited.size != tree.nodes.size) {
        return ContractError.ValidationError(
            "Syntax tree contains nodes unreachable from the root",
            field = "nodes",
        )
    }
    return null
}

private fun visit(
    tree: SyntaxTree,
    nodeId: String,
    visiting: MutableSet<String>,
    visited: MutableSet<String>,
): ContractError? {
    if (nodeId in visited) return null
    if (!visiting.add(nodeId)) {
        return ContractError.ValidationError("Syntax tree contains a cycle", field = "nodes.$nodeId")
    }
    val node =
        tree.nodes[nodeId]
            ?: return ContractError.ValidationError("Syntax node is missing", field = "nodes.$nodeId")
    for (childId in node.childIds) {
        val error = visit(tree, childId, visiting, visited)
        if (error != null) return error
    }
    visiting.remove(nodeId)
    visited += nodeId
    return null
}

private fun String.isSha256(): Boolean {
    if (length != 64) return false
    return all { it in '0'..'9' || it in 'a'..'f' }
}
