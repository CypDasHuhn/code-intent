package dev.cint.contracts.facts

import dev.cint.contracts.data.Span
import kotlinx.serialization.Serializable

@Serializable
data class SyntaxNode(
    val id: String,
    val kind: String,
    val startByte: Int,
    val endByte: Int,
    val startLine: Int,
    val startCol: Int,
    val endLine: Int,
    val endCol: Int,
    val text: String,
    val parentId: String? = null,
    val childIds: List<String> = emptyList(),
    val isNamed: Boolean = true,
) {
    val span: Span
        get() = Span(startLine, startCol, endLine, endCol)
}

@Serializable
data class SyntaxTree(
    val rootId: String,
    val nodes: Map<String, SyntaxNode>,
    val language: String,
    val version: String,
    val contentHash: String,
) {
    fun getNode(id: String): SyntaxNode? = nodes[id]

    fun getChildren(parentId: String): List<SyntaxNode> = nodes.values.filter { it.parentId == parentId }

    fun getDescendants(rootId: String): Sequence<SyntaxNode> =
        sequence {
            val root = nodes[rootId] ?: return@sequence
            yield(root)
            for (childId in root.childIds) {
                yieldAll(getDescendants(childId))
            }
        }
}
