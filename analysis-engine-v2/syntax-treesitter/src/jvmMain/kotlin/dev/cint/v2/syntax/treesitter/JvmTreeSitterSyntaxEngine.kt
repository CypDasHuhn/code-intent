package dev.cint.v2.syntax.treesitter

import dev.cint.contracts.data.Span
import dev.cint.contracts.facts.ChangedRanges
import dev.cint.contracts.facts.FactBatch
import dev.cint.contracts.facts.SyntaxNode
import dev.cint.contracts.facts.SyntaxTree
import dev.cint.contracts.ports.SyntaxEngine
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContentHash
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory
import org.treesitter.TSInputEdit
import org.treesitter.TSLanguage
import org.treesitter.TSNode
import org.treesitter.TSParser
import org.treesitter.TSPoint
import org.treesitter.TSTree

actual fun createSyntaxEngine(language: String): SyntaxEngine {
    val grammar = TreeSitterGrammars.find(language)
        ?: return UnavailableSyntaxEngine(
            language = language,
            reason = "no tree-sitter grammar is registered for '$language'",
        )
    return JvmTreeSitterSyntaxEngine(grammar)
}

internal actual val treeSitterRuntimeVersion: String = "0.26.6"

private class JvmTreeSitterSyntaxEngine(
    private val grammar: TreeSitterGrammar,
) : SyntaxEngine {
    override val language: String = grammar.id

    override fun parse(
        content: ByteArray,
        previous: SyntaxTree?,
        cancellation: CancellationToken,
    ): ContractResult<SyntaxTree> {
        if (cancellation.isCancelled) {
            return ResultFactory.failure(ContractError.Cancelled("SyntaxEngine.parse"))
        }
        if (previous != null && previous.language != grammar.id) {
            return ResultFactory.failure(
                ContractError.InvalidInput(
                    "previous tree language '${previous.language}' does not match '${grammar.id}'",
                    previous.language,
                )
            )
        }
        return try {
            ResultFactory.success(parseWithPrevious(content, previous))
        } catch (error: Throwable) {
            ResultFactory.failure(mapFailure("tree-sitter-${grammar.id} parse failed", error))
        }
    }

    override fun diff(previous: SyntaxTree, next: SyntaxTree): ChangedRanges {
        if (previous.language != grammar.id || next.language != grammar.id) return ChangedRanges.WHOLE_FILE
        if (previous.contentHash == next.contentHash) return ChangedRanges.EMPTY
        val oldBytes = TreeAlgorithms.wholeTreeContent(previous) ?: return ChangedRanges.WHOLE_FILE
        val newBytes = TreeAlgorithms.wholeTreeContent(next) ?: return ChangedRanges.WHOLE_FILE
        return try {
            val parser = TSParser()
            try {
                check(parser.setLanguage(languageFor(grammar))) {
                    "tree-sitter rejected ${grammar.grammarName} (ABI mismatch)"
                }
                val oldTree = parser.parseString(null, oldBytes.decodeToString())
                    ?: return ChangedRanges.WHOLE_FILE
                try {
                    val edit = TextEdit.between(oldBytes, newBytes)
                    if (!edit.isNoOp) oldTree.edit(edit.toInputEdit())
                    val newTree = parser.parseString(oldTree, newBytes.decodeToString())
                        ?: return ChangedRanges.WHOLE_FILE
                    try {
                        val ranges = TSTree.getChangedRanges(oldTree, newTree)
                            ?: return ChangedRanges.WHOLE_FILE
                        val spans = ranges.mapNotNull { range ->
                            val start = range.startPoint
                            val end = range.endPoint
                            Span(
                                startLine = start.row,
                                startCol = start.column,
                                endLine = end.row,
                                endCol = end.column,
                            )
                        }
                        if (spans.isEmpty()) ChangedRanges.EMPTY else ChangedRanges(spans)
                    } finally {
                        newTree.close()
                    }
                } finally {
                    oldTree.close()
                }
            } finally {
                parser.close()
            }
        } catch (_: Throwable) {
            ChangedRanges.WHOLE_FILE
        }
    }

    override fun nodeHash(tree: SyntaxTree, nodeId: String): ContractResult<String> =
        if (tree.language != grammar.id) {
            ResultFactory.failure(
                ContractError.InvalidInput(
                    "tree language '${tree.language}' does not match engine '$language'",
                    tree.language,
                )
            )
        } else {
            TreeAlgorithms.nodeHash(tree, nodeId)
        }

    override fun facts(
        tree: SyntaxTree,
        queryIds: List<String>,
        cancellation: CancellationToken,
    ): ContractResult<FactBatch> {
        if (cancellation.isCancelled) {
            return ResultFactory.failure(ContractError.Cancelled("SyntaxEngine.facts"))
        }
        if (tree.language != grammar.id) {
            return ResultFactory.failure(
                ContractError.InvalidInput(
                    "tree language '${tree.language}' does not match engine '$language'",
                    tree.language,
                )
            )
        }
        return SyntaxFactExtractor.extract(tree, queryIds)
    }

    private fun parseWithPrevious(content: ByteArray, previous: SyntaxTree?): SyntaxTree {
        val parser = TSParser()
        try {
            check(parser.setLanguage(languageFor(grammar))) {
                "tree-sitter rejected ${grammar.grammarName} (ABI mismatch)"
            }
            val previousBytes = previous?.let(TreeAlgorithms::wholeTreeContent)
            var oldTree: TSTree? = null
            try {
                if (previousBytes != null && previous != null) {
                    oldTree = parser.parseString(null, previousBytes.decodeToString())
                    if (oldTree != null) {
                        val edit = TextEdit.between(previousBytes, content)
                        if (!edit.isNoOp) oldTree.edit(edit.toInputEdit())
                    }
                }
                val tree = parser.parseString(oldTree, content.decodeToString())
                    ?: error("tree-sitter returned no tree")
                try {
                    val root = tree.rootNode
                    check(!root.isNull) { "tree-sitter returned a null root" }
                    val builder = TreeBuilder(content)
                    val rootId = builder.add(root, parentId = null)
                    return SyntaxTree(
                        rootId = rootId,
                        nodes = builder.nodes,
                        language = grammar.id,
                        version = "${grammar.grammarName}-${grammar.version}/runtime-$treeSitterRuntimeVersion",
                        contentHash = ContentHash.compute(content).value,
                    )
                } finally {
                    tree.close()
                }
            } finally {
                oldTree?.close()
            }
        } finally {
            parser.close()
        }
    }

    private fun mapFailure(message: String, error: Throwable): ContractError = when (error) {
        is UnsatisfiedLinkError, is NoClassDefFoundError, is ExceptionInInitializerError ->
            ContractError.DependencyUnavailable(
                dependency = grammar.grammarName,
                reason = "$message: ${error.message ?: error::class.simpleName}",
            )
        else -> ContractError.InternalError(message, error.message)
    }
}

private val languageCache = mutableMapOf<String, TSLanguage>()

private fun languageFor(grammar: TreeSitterGrammar): TSLanguage = synchronized(languageCache) {
    languageCache.getOrPut(grammar.id) {
        val type = Class.forName(grammar.jvmFactory)
        val instance = type.getDeclaredConstructor().newInstance()
        instance as? TSLanguage ?: error("'${grammar.jvmFactory}' is not a TSLanguage")
    }
}

private fun TextEdit.toInputEdit(): TSInputEdit = TSInputEdit(
    startByte,
    oldEndByte,
    newEndByte,
    TSPoint(startLine, startColumn),
    TSPoint(oldEndLine, oldEndColumn),
    TSPoint(newEndLine, newEndColumn),
)

private class TreeBuilder(private val content: ByteArray) {
    val nodes = LinkedHashMap<String, SyntaxNode>()
    private var nextId = 0

    fun add(node: TSNode, parentId: String?): String {
        val id = "n${nextId++}"
        val startByte = node.startByte
        val endByte = node.endByte
        val start = node.startPoint
        val end = node.endPoint
        val childCount = node.childCount
        val childIds = ArrayList<String>(childCount)
        for (index in 0 until childCount) {
            childIds += add(node.getChild(index), id)
        }
        val text = if (startByte in 0..endByte && endByte <= content.size) {
            content.copyOfRange(startByte, endByte).decodeToString()
        } else {
            ""
        }
        nodes[id] = SyntaxNode(
            id = id,
            kind = node.type ?: "",
            startByte = startByte,
            endByte = endByte,
            startLine = start.row,
            startCol = start.column,
            endLine = end.row,
            endCol = end.column,
            text = text,
            parentId = parentId,
            childIds = childIds,
            isNamed = node.isNamed,
        )
        return id
    }
}
