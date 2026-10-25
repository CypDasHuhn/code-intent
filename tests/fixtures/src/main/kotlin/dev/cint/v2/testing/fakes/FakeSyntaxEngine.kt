package dev.cint.v2.testing.fakes

import dev.cint.contracts.data.Span
import dev.cint.contracts.facts.CallFact
import dev.cint.contracts.facts.ChangedRanges
import dev.cint.contracts.facts.DeclarationFact
import dev.cint.contracts.facts.DeclarationKind
import dev.cint.contracts.facts.FactBatch
import dev.cint.contracts.facts.FunctionFact
import dev.cint.contracts.facts.SyntaxNode
import dev.cint.contracts.facts.SyntaxTree
import dev.cint.contracts.ports.SyntaxEngine
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContentHash
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.NodeHash
import dev.cint.contracts.util.ResultFactory

class FakeSyntaxEngine(
    override val language: String = "fake",
    val recorder: FakeRecorder = FakeRecorder(),
    private val treeFactory: (ByteArray) -> SyntaxTree = { defaultSyntaxTree(language, it) },
    private val factFactory: (SyntaxTree, List<String>) -> FactBatch = { tree, _ -> defaultFacts(tree) },
) : SyntaxEngine {
    var closed: Boolean = false
        private set

    override fun parse(
        content: ByteArray,
        previous: SyntaxTree?,
        cancellation: CancellationToken,
    ): ContractResult<SyntaxTree> {
        recorder.record("syntax.parse", "language=$language,bytes=${content.size},previous=${previous?.rootId}")
        val checked = cancellation.throwIfCancelled()
        if (checked.isFailure) return ResultFactory.failure(checked.errorOrNull!!)
        if (closed) {
            return ResultFactory.failure(ContractError.PreconditionFailed("syntax engine is closed"))
        }
        return ResultFactory.success(treeFactory(content))
    }

    override fun diff(
        previous: SyntaxTree,
        next: SyntaxTree,
    ): ChangedRanges {
        recorder.record("syntax.diff", "previous=${previous.contentHash},next=${next.contentHash}")
        return if (previous.contentHash == next.contentHash) {
            ChangedRanges.EMPTY
        } else {
            ChangedRanges.WHOLE_FILE
        }
    }

    override fun nodeHash(
        tree: SyntaxTree,
        nodeId: String,
    ): ContractResult<String> {
        recorder.record("syntax.nodeHash", "node=$nodeId")
        if (tree.getNode(nodeId) == null) {
            return ResultFactory.failure(ContractError.NotFound("syntax-node", nodeId))
        }
        return try {
            ResultFactory.success(subtreeHash(tree, nodeId, mutableMapOf(), mutableSetOf()).value)
        } catch (error: IllegalStateException) {
            ResultFactory.failure(ContractError.InternalError("node hash failed", error.message))
        }
    }

    private fun subtreeHash(
        tree: SyntaxTree,
        nodeId: String,
        memo: MutableMap<String, NodeHash>,
        visiting: MutableSet<String>,
    ): NodeHash {
        memo[nodeId]?.let { return it }
        check(visiting.add(nodeId)) { "cycle at $nodeId" }
        val node = tree.getNode(nodeId) ?: error("missing node $nodeId")
        val children = node.childIds.map { subtreeHash(tree, it, memo, visiting) }
        visiting.remove(nodeId)
        val hash = NodeHash.compute(node.kind, children, ContentHash.compute(node.text))
        memo[nodeId] = hash
        return hash
    }

    override fun facts(
        tree: SyntaxTree,
        queryIds: List<String>,
        cancellation: CancellationToken,
    ): ContractResult<FactBatch> {
        recorder.record("syntax.facts", queryIds.joinToString(","))
        val checked = cancellation.throwIfCancelled()
        if (checked.isFailure) return ResultFactory.failure(checked.errorOrNull!!)
        if (closed) {
            return ResultFactory.failure(ContractError.PreconditionFailed("syntax engine is closed"))
        }
        return ResultFactory.success(factFactory(tree, queryIds))
    }

    fun shutdown() {
        closed = true
        recorder.record("syntax.shutdown")
    }
}

fun defaultSyntaxTree(
    language: String,
    content: ByteArray,
): SyntaxTree {
    val text = content.decodeToString()
    val contentHash = ContentHash.compute(content).value
    val lines = sourceLines(text)

    val children = mutableListOf<String>()
    val nodes = linkedMapOf<String, SyntaxNode>()
    val functionPattern = Regex("""\bfun\s+([A-Za-z_][A-Za-z0-9_]*)""")
    val callPattern = Regex("""\bcall\s+([A-Za-z_][A-Za-z0-9_]*)""")

    lines.forEach { line ->
        functionPattern.findAll(line.text).forEachIndexed { matchIndex, match ->
            val id = "fn-${line.index}-$matchIndex"
            children.add(id)
            nodes[id] =
                node(
                    id = id,
                    kind = "function_declaration",
                    text = match.value,
                    startByte =
                        line.startByte +
                            line.text
                                .substring(0, match.range.first)
                                .encodeToByteArray()
                                .size,
                    endByte =
                        line.startByte +
                            line.text
                                .substring(0, match.range.last + 1)
                                .encodeToByteArray()
                                .size,
                    line = line.index,
                    startCol = match.range.first,
                    endCol = match.range.last + 1,
                )
        }
        callPattern.findAll(line.text).forEachIndexed { matchIndex, match ->
            val id = "call-${line.index}-$matchIndex"
            children.add(id)
            nodes[id] =
                node(
                    id = id,
                    kind = "call_expression",
                    text = match.value,
                    startByte =
                        line.startByte +
                            line.text
                                .substring(0, match.range.first)
                                .encodeToByteArray()
                                .size,
                    endByte =
                        line.startByte +
                            line.text
                                .substring(0, match.range.last + 1)
                                .encodeToByteArray()
                                .size,
                    line = line.index,
                    startCol = match.range.first,
                    endCol = match.range.last + 1,
                )
        }
    }

    val rootEndLine = (lines.size - 1).coerceAtLeast(0)
    val rootEndCol = lines.lastOrNull()?.text?.length ?: 0
    nodes["root"] =
        node(
            id = "root",
            kind = "source_file",
            text = text,
            startByte = 0,
            endByte = content.size,
            line = 0,
            startCol = 0,
            endLine = rootEndLine,
            endCol = rootEndCol,
            children = children,
        )

    return SyntaxTree(
        rootId = "root",
        nodes = nodes,
        language = language,
        version = "fake-1",
        contentHash = contentHash,
    )
}

fun defaultFacts(tree: SyntaxTree): FactBatch {
    val functions =
        tree.nodes.values
            .filter { it.kind == "function_declaration" }
            .sortedWith(compareBy({ it.startByte }, { it.id }))
    val calls =
        tree.nodes.values
            .filter { it.kind == "call_expression" }
            .sortedWith(compareBy({ it.startByte }, { it.id }))

    return FactBatch(
        syntaxTree = tree,
        calls =
            calls.map {
                CallFact(
                    nodeId = it.id,
                    siteId = it.id,
                    callee = it.text.substringAfter("call ").trim(),
                    receiverId = null,
                    path = it.text.substringAfter("call ").trim(),
                )
            },
        declarations =
            functions.map {
                DeclarationFact(
                    nodeId = it.id,
                    kind = DeclarationKind.FUNCTION,
                    name = it.text.substringAfter("fun ").trim(),
                    nameNodeId = null,
                )
            },
        functions =
            functions.map {
                FunctionFact(
                    nodeId = it.id,
                    name = it.text.substringAfter("fun ").trim(),
                    nameNodeId = null,
                    bodyId = null,
                )
            },
    )
}

private data class SourceLine(
    val index: Int,
    val text: String,
    val startByte: Int,
)

private fun sourceLines(text: String): List<SourceLine> {
    val lines = mutableListOf<SourceLine>()
    var lineStart = 0
    var byteOffset = 0
    var index = 0
    while (index < text.length) {
        val ch = text[index]
        if (ch == '\n' || ch == '\r') {
            val rawLine = text.substring(lineStart, index)
            lines.add(SourceLine(lines.size, rawLine, byteOffset))
            val newlineBytes = if (ch == '\r' && index + 1 < text.length && text[index + 1] == '\n') 2 else 1
            byteOffset += rawLine.encodeToByteArray().size + newlineBytes
            index += newlineBytes
            lineStart = index
        } else {
            index += 1
        }
    }
    val finalLine = text.substring(lineStart)
    lines.add(SourceLine(lines.size, finalLine, byteOffset))
    return lines
}

private fun node(
    id: String,
    kind: String,
    text: String,
    startByte: Int,
    endByte: Int,
    line: Int,
    startCol: Int,
    endLine: Int = line,
    endCol: Int,
    children: List<String> = emptyList(),
): SyntaxNode =
    SyntaxNode(
        id = id,
        kind = kind,
        startByte = startByte,
        endByte = endByte,
        startLine = line,
        startCol = startCol,
        endLine = endLine,
        endCol = endCol,
        text = text,
        parentId = if (id == "root") null else "root",
        childIds = children,
    )

fun SyntaxTree.spanOf(nodeId: String): Span? = getNode(nodeId)?.span
