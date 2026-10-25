package dev.cint.v2.syntax.treesitter

import dev.cint.contracts.facts.ChangedRanges
import dev.cint.contracts.facts.FactBatch
import dev.cint.contracts.facts.SyntaxNode
import dev.cint.contracts.facts.SyntaxTree
import dev.cint.contracts.ports.SyntaxEngine
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContentHash
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.NodeHash
import dev.cint.contracts.util.ResultFactory
import dev.cint.v2.syntax.treesitter.cinterop.TSNode
import dev.cint.v2.syntax.treesitter.cinterop.ts_node_child
import dev.cint.v2.syntax.treesitter.cinterop.ts_node_child_count
import dev.cint.v2.syntax.treesitter.cinterop.ts_node_end_byte
import dev.cint.v2.syntax.treesitter.cinterop.ts_node_end_point
import dev.cint.v2.syntax.treesitter.cinterop.ts_node_is_named
import dev.cint.v2.syntax.treesitter.cinterop.ts_node_start_byte
import dev.cint.v2.syntax.treesitter.cinterop.ts_node_start_point
import dev.cint.v2.syntax.treesitter.cinterop.ts_node_type
import dev.cint.v2.syntax.treesitter.cinterop.ts_parser_delete
import dev.cint.v2.syntax.treesitter.cinterop.ts_parser_new
import dev.cint.v2.syntax.treesitter.cinterop.ts_parser_parse_string
import dev.cint.v2.syntax.treesitter.cinterop.ts_parser_set_language
import dev.cint.v2.syntax.treesitter.cinterop.ts_tree_delete
import dev.cint.v2.syntax.treesitter.cinterop.ts_tree_root_node
import dev.cint.v2.syntax.treesitter.cinterop.tree_sitter_c
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.cinterop.useContents

@OptIn(ExperimentalForeignApi::class)
actual fun createSyntaxEngine(): SyntaxEngine = NativeTreeSitterSyntaxEngine()

@OptIn(ExperimentalForeignApi::class)
private class NativeTreeSitterSyntaxEngine : SyntaxEngine {
    override val language: String = "c"

    override fun parse(
        content: ByteArray,
        previous: SyntaxTree?,
        cancellation: CancellationToken,
    ): ContractResult<SyntaxTree> {
        if (cancellation.isCancelled) {
            return contractFailure(ContractError.Cancelled("SyntaxEngine.parse"))
        }
        return try {
            ResultFactory.success(parseTree(content))
        } catch (t: Throwable) {
            contractFailure(ContractError.InternalError("tree-sitter-c parse failed", t.message))
        }
    }

    override fun diff(previous: SyntaxTree, next: SyntaxTree): ChangedRanges {
        return if (previous.contentHash == next.contentHash) ChangedRanges.EMPTY else ChangedRanges.WHOLE_FILE
    }

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
    ): ContractResult<FactBatch> {
        if (cancellation.isCancelled) {
            return contractFailure(ContractError.Cancelled("SyntaxEngine.facts"))
        }
        return ResultFactory.success(FactBatch(syntaxTree = tree))
    }

    private fun parseTree(content: ByteArray): SyntaxTree {
        val parser = ts_parser_new() ?: error("ts_parser_new returned null")
        try {
            val tsLanguage = tree_sitter_c() ?: error("tree_sitter_c returned null")
            check(ts_parser_set_language(parser, tsLanguage)) { "ts_parser_set_language rejected tree-sitter-c" }
            val source = content.decodeToString()
            val sourceLength = source.encodeToByteArray().size.toUInt()
            val tree = ts_parser_parse_string(parser, null, source, sourceLength)
                ?: error("ts_parser_parse_string returned null")
            try {
                val root = ts_tree_root_node(tree)
                val builder = TreeBuilder(content)
                val rootId = builder.add(root, parentId = null)
                return SyntaxTree(
                    rootId = rootId,
                    nodes = builder.nodes,
                    language = language,
                    version = "tree-sitter-c-0.24.1",
                    contentHash = ContentHash.compute(content).value
                )
            } finally {
                ts_tree_delete(tree)
            }
        } finally {
            ts_parser_delete(parser)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private class TreeBuilder(private val content: ByteArray) {
    val nodes = LinkedHashMap<String, SyntaxNode>()
    private var nextId = 0

    fun add(node: CValue<TSNode>, parentId: String?): String {
        val id = "n${nextId++}"
        val startByte = ts_node_start_byte(node).toInt()
        val endByte = ts_node_end_byte(node).toInt()
        val start = ts_node_start_point(node)
        val end = ts_node_end_point(node)
        val startRow = start.useContents { row.toInt() }
        val startCol = start.useContents { column.toInt() }
        val endRow = end.useContents { row.toInt() }
        val endCol = end.useContents { column.toInt() }

        val childCount = ts_node_child_count(node).toInt()
        val childIds = ArrayList<String>(childCount)
        for (index in 0 until childCount) {
            childIds += add(ts_node_child(node, index.toUInt()), id)
        }

        val text = if (startByte in 0..endByte && endByte <= content.size) {
            content.copyOfRange(startByte, endByte).decodeToString()
        } else {
            ""
        }
        nodes[id] = SyntaxNode(
            id = id,
            kind = ts_node_type(node)?.toKString() ?: "",
            startByte = startByte,
            endByte = endByte,
            startLine = startRow,
            startCol = startCol,
            endLine = endRow,
            endCol = endCol,
            text = text,
            parentId = parentId,
            childIds = childIds,
            isNamed = ts_node_is_named(node)
        )
        return id
    }
}
