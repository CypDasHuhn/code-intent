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
import cnames.structs.TSLanguage
import cnames.structs.TSTree
import dev.cint.v2.syntax.treesitter.cinterop.TSNode
import dev.cint.v2.syntax.treesitter.cinterop.TSInputEdit
import dev.cint.v2.syntax.treesitter.cinterop.TSRange
import dev.cint.v2.syntax.treesitter.cinterop.dlclose
import dev.cint.v2.syntax.treesitter.cinterop.dlopen
import dev.cint.v2.syntax.treesitter.cinterop.dlerror
import dev.cint.v2.syntax.treesitter.cinterop.dlsym
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
import dev.cint.v2.syntax.treesitter.cinterop.ts_tree_edit
import dev.cint.v2.syntax.treesitter.cinterop.ts_tree_get_changed_ranges
import dev.cint.v2.syntax.treesitter.cinterop.ts_tree_root_node
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CValue
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import platform.posix.free
import platform.posix.getenv
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_t
import platform.posix.pthread_mutex_unlock

@OptIn(ExperimentalForeignApi::class)
actual fun createSyntaxEngine(language: String): SyntaxEngine {
    val grammar = TreeSitterGrammars.find(language)
        ?: return UnavailableSyntaxEngine(
            language = language,
            reason = "no tree-sitter grammar is registered for '$language'",
        )
    if (language == "vue") return VueSyntaxEngine()
    return NativeTreeSitterSyntaxEngine(grammar)
}

@OptIn(ExperimentalForeignApi::class)
internal actual val treeSitterRuntimeVersion: String = "0.27.1"

@OptIn(ExperimentalForeignApi::class)
private class NativeTreeSitterSyntaxEngine(
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
        } catch (error: GrammarLoadException) {
            ResultFactory.failure(
                ContractError.DependencyUnavailable(grammar.grammarName, error.message ?: "grammar unavailable")
            )
        } catch (error: Throwable) {
            ResultFactory.failure(
                ContractError.InternalError("tree-sitter-${grammar.id} parse failed", error.message)
            )
        }
    }

    override fun diff(previous: SyntaxTree, next: SyntaxTree): ChangedRanges {
        if (previous.language != grammar.id || next.language != grammar.id) return ChangedRanges.WHOLE_FILE
        if (previous.contentHash == next.contentHash) return ChangedRanges.EMPTY
        val oldBytes = TreeAlgorithms.wholeTreeContent(previous) ?: return ChangedRanges.WHOLE_FILE
        val newBytes = TreeAlgorithms.wholeTreeContent(next) ?: return ChangedRanges.WHOLE_FILE
        return try {
            nativeDiff(oldBytes, newBytes)
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
        val parser = ts_parser_new() ?: error("ts_parser_new returned null")
        try {
            val tsLanguage = grammarLanguage(grammar)
            if (!ts_parser_set_language(parser, tsLanguage)) {
                throw GrammarLoadException(
                    "tree-sitter rejected ${grammar.grammarName} (ABI mismatch)"
                )
            }
            val previousBytes = previous?.let(TreeAlgorithms::wholeTreeContent)
            var oldTree: kotlinx.cinterop.CPointer<TSTree>? = null
            try {
                if (previousBytes != null) {
                    val oldSource = previousBytes.decodeToString()
                    oldTree = ts_parser_parse_string(parser, null, oldSource, previousBytes.size.toUInt())
                    val currentOldTree = oldTree
                    if (currentOldTree != null) {
                        val edit = TextEdit.between(previousBytes, content)
                        if (!edit.isNoOp) {
                            memScoped {
                                val input = alloc<TSInputEdit>()
                                input.start_byte = edit.startByte.toUInt()
                                input.old_end_byte = edit.oldEndByte.toUInt()
                                input.new_end_byte = edit.newEndByte.toUInt()
                                input.start_point.row = edit.startLine.toUInt()
                                input.start_point.column = edit.startColumn.toUInt()
                                input.old_end_point.row = edit.oldEndLine.toUInt()
                                input.old_end_point.column = edit.oldEndColumn.toUInt()
                                input.new_end_point.row = edit.newEndLine.toUInt()
                                input.new_end_point.column = edit.newEndColumn.toUInt()
                                ts_tree_edit(currentOldTree, input.ptr)
                            }
                        }
                    }
                }
                val source = content.decodeToString()
                val tree = ts_parser_parse_string(parser, oldTree, source, content.size.toUInt())
                    ?: error("ts_parser_parse_string returned null")
                try {
                    val root = ts_tree_root_node(tree)
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
                    ts_tree_delete(tree)
                }
            } finally {
                oldTree?.let { ts_tree_delete(it) }
            }
        } finally {
            ts_parser_delete(parser)
        }
    }

    private fun nativeDiff(oldBytes: ByteArray, newBytes: ByteArray): ChangedRanges {
        val parser = ts_parser_new() ?: return ChangedRanges.WHOLE_FILE
        try {
            if (!ts_parser_set_language(parser, grammarLanguage(grammar))) {
                throw GrammarLoadException(
                    "tree-sitter rejected ${grammar.grammarName} (ABI mismatch)"
                )
            }
            val oldSource = oldBytes.decodeToString()
            val oldTree = ts_parser_parse_string(parser, null, oldSource, oldBytes.size.toUInt())
                ?: return ChangedRanges.WHOLE_FILE
            try {
                val edit = TextEdit.between(oldBytes, newBytes)
                if (!edit.isNoOp) {
                    memScoped {
                        val input = alloc<TSInputEdit>()
                        input.start_byte = edit.startByte.toUInt()
                        input.old_end_byte = edit.oldEndByte.toUInt()
                        input.new_end_byte = edit.newEndByte.toUInt()
                        input.start_point.row = edit.startLine.toUInt()
                        input.start_point.column = edit.startColumn.toUInt()
                        input.old_end_point.row = edit.oldEndLine.toUInt()
                        input.old_end_point.column = edit.oldEndColumn.toUInt()
                        input.new_end_point.row = edit.newEndLine.toUInt()
                        input.new_end_point.column = edit.newEndColumn.toUInt()
                        ts_tree_edit(oldTree, input.ptr)
                    }
                }
                val newSource = newBytes.decodeToString()
                val newTree = ts_parser_parse_string(parser, oldTree, newSource, newBytes.size.toUInt())
                    ?: return ChangedRanges.WHOLE_FILE
                try {
                    return memScoped {
                        val length = alloc<UIntVar>()
                        val ranges: kotlinx.cinterop.CPointer<TSRange>? =
                            ts_tree_get_changed_ranges(oldTree, newTree, length.ptr)
                        if (ranges == null || length.value == 0u) {
                            ChangedRanges.EMPTY
                        } else {
                            try {
                                val spans = (0 until length.value.toInt()).mapNotNull { index ->
                                    val range = ranges[index]
                                    Span(
                                        startLine = range.start_point.row.toInt(),
                                        startCol = range.start_point.column.toInt(),
                                        endLine = range.end_point.row.toInt(),
                                        endCol = range.end_point.column.toInt(),
                                    )
                                }
                                if (spans.isEmpty()) ChangedRanges.EMPTY else ChangedRanges(spans)
                            } finally {
                                free(ranges)
                            }
                        }
                    }
                } finally {
                    ts_tree_delete(newTree)
                }
            } finally {
                ts_tree_delete(oldTree)
            }
        } finally {
            ts_parser_delete(parser)
        }
    }
}

private class GrammarLoadException(message: String) : RuntimeException(message)

@OptIn(ExperimentalForeignApi::class)
private val grammarLock = nativeHeap.alloc<pthread_mutex_t>().also {
    kotlinx.cinterop.memScoped {
        check(platform.posix.pthread_mutex_init(it.ptr, null) == 0) { "cannot initialise grammar lock" }
    }
}

@OptIn(ExperimentalForeignApi::class)
private inline fun <T> withGrammarLock(block: () -> T): T {
    pthread_mutex_lock(grammarLock.ptr)
    try {
        return block()
    } finally {
        pthread_mutex_unlock(grammarLock.ptr)
    }
}

@OptIn(ExperimentalForeignApi::class)
private val grammarHandles = mutableMapOf<String, kotlinx.cinterop.CPointer<out kotlinx.cinterop.CPointed>>()

@OptIn(ExperimentalForeignApi::class)
private val grammarLanguages = mutableMapOf<String, kotlinx.cinterop.CPointer<TSLanguage>>()

@OptIn(ExperimentalForeignApi::class)
private fun grammarLanguage(grammar: TreeSitterGrammar): kotlinx.cinterop.CPointer<TSLanguage> = withGrammarLock {
    grammarLanguages[grammar.id]?.let { return@withGrammarLock it }
    val handle = loadGrammarLibrary(grammar)
    try {
        val symbol = dlsym(handle, grammar.nativeSymbol)
            ?: throw GrammarLoadException(
                "grammar library '${grammar.nativeLibrary}' does not export '${grammar.nativeSymbol}'"
            )
        val factory = symbol.reinterpret<CFunction<() -> kotlinx.cinterop.CPointer<TSLanguage>?>>()
        val language = factory.invoke()
            ?: throw GrammarLoadException("'${grammar.nativeSymbol}' returned a null language")
        grammarHandles[grammar.id] = handle
        grammarLanguages[grammar.id] = language
        language
    } catch (error: Throwable) {
        dlclose(handle)
        throw error
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun loadGrammarLibrary(grammar: TreeSitterGrammar): kotlinx.cinterop.CPointer<out kotlinx.cinterop.CPointed> {
    val candidates = buildList {
        val path = getenv("CINT_TREE_SITTER_GRAMMAR_PATH")?.toKString()
        if (!path.isNullOrBlank()) {
            for (directory in path.split(':')) {
                if (directory.isNotBlank()) add("$directory/${grammar.nativeLibrary}")
            }
        }
        add(grammar.nativeLibrary)
    }
    var lastError: String? = null
    for (candidate in candidates) {
        val handle = dlopen(candidate, RTLD_NOW)
        if (handle != null) return handle
        lastError = dlerror()?.toKString()
    }
    throw GrammarLoadException(
        "cannot load ${grammar.nativeLibrary} for '${grammar.id}': ${lastError ?: "library not found"}"
    )
}

private const val RTLD_NOW = 2

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
            isNamed = ts_node_is_named(node),
        )
        return id
    }
}
