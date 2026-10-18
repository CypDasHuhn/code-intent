package dev.cint.treesitter

import dev.cint.core.model.SourceFile
import dev.cint.core.syntax.SyntaxNode
import dev.cint.core.syntax.SyntaxTree
import org.treesitter.TSLanguage
import org.treesitter.TSNode
import org.treesitter.TSParser
import org.treesitter.TSTree
import org.treesitter.TreeSitterCSharp
import org.treesitter.TreeSitterJavascript
import org.treesitter.TreeSitterKotlin
import org.treesitter.TreeSitterTypescript
import org.treesitter.TreeSitterVue

open class TreeSitterParser(
    private val languageFactory: () -> TSLanguage,
) {
    fun parse(file: SourceFile): SyntaxTree {
        val parser = TSParser()
        parser.setLanguage(languageFactory())
        val tree = parser.parseString(null, file.text)
        return TsSyntaxTree(file, tree)
    }
}

class KotlinParser : TreeSitterParser({ TreeSitterKotlin() })

class JavascriptParser : TreeSitterParser({ TreeSitterJavascript() })

class TypescriptParser : TreeSitterParser({ TreeSitterTypescript() })

class VueParser : TreeSitterParser({ TreeSitterVue() })

class CsharpParser : TreeSitterParser({ TreeSitterCSharp() })

class TsSyntaxTree(
    override val source: SourceFile,
    private val tree: TSTree,
) : SyntaxTree {
    private val bytes = source.text.toByteArray(Charsets.UTF_8)

    override val root: SyntaxNode by lazy {
        val rootNode = tree.rootNode
        check(!rootNode.isNull) { "empty syntax tree" }
        TsSyntaxNode(this, rootNode)
    }

    internal fun text(
        start: Int,
        end: Int,
    ): String = String(bytes, start, end - start, Charsets.UTF_8)
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
    override val parent: SyntaxNode? get() {
        val parentNode = node.parent
        return if (parentNode == null || parentNode.isNull) null else TsSyntaxNode(tree, parentNode)
    }

    override fun childByField(name: String): SyntaxNode? {
        val child = node.getChildByFieldName(name) ?: return null
        return if (child.isNull) null else TsSyntaxNode(tree, child)
    }

    override val children: List<SyntaxNode> by lazy {
        (0 until node.childCount).mapNotNull { index ->
            val child = node.getChild(index)
            if (child == null || child.isNull) null else TsSyntaxNode(tree, child)
        }
    }
}
