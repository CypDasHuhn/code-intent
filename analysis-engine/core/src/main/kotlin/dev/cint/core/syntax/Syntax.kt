package dev.cint.core.syntax

import dev.cint.core.model.SourceFile
import dev.cint.core.model.Span

interface SyntaxTree {
    val source: SourceFile
    val root: SyntaxNode
}

interface SyntaxNode {
    val kind: String
    val startByte: Int
    val endByte: Int
    val startLine: Int
    val startCol: Int
    val endLine: Int
    val endCol: Int
    val text: String
    val parent: SyntaxNode?
    val children: List<SyntaxNode>

    fun span(): Span = Span(startLine, startCol, endLine, endCol)

    fun child(kind: String): SyntaxNode? = children.firstOrNull { it.kind == kind }

    fun childByField(name: String): SyntaxNode? = null

    fun childrenOfKind(kind: String): List<SyntaxNode> = children.filter { it.kind == kind }

    fun descendants(): Sequence<SyntaxNode> =
        sequence {
            yield(this@SyntaxNode)
            for (child in children) {
                yieldAll(child.descendants())
            }
        }

    fun descendants(kind: String): Sequence<SyntaxNode> = descendants().filter { it.kind == kind }
}
