package com.semanticpainter.treesitter

import com.semanticpainter.core.model.SourceFile
import com.semanticpainter.core.syntax.SyntaxNode
import com.semanticpainter.core.syntax.SyntaxTree
import org.treesitter.TSNode
import org.treesitter.TSParser
import org.treesitter.TSTree
import org.treesitter.TreeSitterKotlin

class KotlinParser {
    fun parse(file: SourceFile): SyntaxTree {
        val parser = TSParser()
        parser.setLanguage(TreeSitterKotlin())
        val tree = parser.parseString(null, file.text)
        return TsSyntaxTree(file, tree)
    }
}

class TsSyntaxTree(
    override val source: SourceFile,
    private val tree: TSTree,
) : SyntaxTree {
    private val bytes = source.text.toByteArray(Charsets.UTF_8)

    override val root: SyntaxNode by lazy { TsSyntaxNode(this, tree.rootNode) }

    internal fun text(start: Int, end: Int): String = String(bytes, start, end - start, Charsets.UTF_8)
}

class TsSyntaxNode(
    private val tree: TsSyntaxTree,
    private val node: TSNode,
) : SyntaxNode {
    override val kind: String get() = node.type
    override val startByte: Int get() = node.startByte
    override val endByte: Int get() = node.endByte
    override val startLine: Int get() = node.startPoint.row
    override val startCol: Int get() = node.startPoint.column
    override val endLine: Int get() = node.endPoint.row
    override val endCol: Int get() = node.endPoint.column
    override val text: String get() = tree.text(startByte, endByte)
    override val parent: SyntaxNode? get() = node.parent?.let { TsSyntaxNode(tree, it) }
    override val children: List<SyntaxNode> by lazy {
        (0 until node.childCount).map { TsSyntaxNode(tree, node.getChild(it)) }
    }
}
