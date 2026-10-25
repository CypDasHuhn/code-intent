package dev.cint.pipeline

import dev.cint.contracts.data.Completeness
import dev.cint.contracts.facts.FactBatch
import dev.cint.contracts.facts.FunctionFact
import dev.cint.contracts.facts.IndexedSource
import dev.cint.contracts.facts.SourceInput
import dev.cint.contracts.facts.SyntaxNode
import dev.cint.contracts.facts.SyntaxTree

internal const val HASH = "hash"

internal fun sourceWithFunctions(vararg names: String): IndexedSource {
    val nodes = linkedMapOf<String, SyntaxNode>()
    val functions = mutableListOf<FunctionFact>()
    val root = SyntaxNode(
        id = "root",
        kind = "file",
        startByte = 0,
        endByte = 1000,
        startLine = 0,
        startCol = 0,
        endLine = 99,
        endCol = 0,
        text = "",
        childIds = names.mapIndexed { index, _ -> "fn-$index" },
    )
    nodes[root.id] = root
    names.forEachIndexed { index, name ->
        val node = SyntaxNode(
            id = "fn-$index",
            kind = "function_declaration",
            startByte = index * 100,
            endByte = index * 100 + 20,
            startLine = index,
            startCol = 0,
            endLine = index,
            endCol = 20,
            text = "fun $name",
            parentId = root.id,
        )
        nodes[node.id] = node
        functions += FunctionFact(nodeId = node.id, name = name, nameNodeId = null, bodyId = null)
    }
    val tree = SyntaxTree(
        rootId = root.id,
        nodes = nodes,
        language = "fake",
        version = "1",
        contentHash = HASH,
    )
    return IndexedSource(
        input = SourceInput.InMemory("", "fake", 1, "Sample.fake"),
        facts = FactBatch(syntaxTree = tree, functions = functions),
        sourceEpoch = 7,
        completeness = Completeness.COMPLETE,
        contentHash = HASH,
    )
}

internal fun IndexedSource.node(id: String): SyntaxNode =
    requireNotNull(facts.syntaxTree?.getNode(id))
