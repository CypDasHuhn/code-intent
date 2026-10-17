package com.semanticpainter.treesitter

import com.semanticpainter.core.syntax.SyntaxNode
import com.semanticpainter.core.syntax.SyntaxTree

data class KotlinProperty(
    val name: String,
    val initCallPath: String?,
)

object KotlinSyntax {
    fun calls(tree: SyntaxTree): Sequence<SyntaxNode> = tree.root.descendants("call_expression")

    fun calleeName(call: SyntaxNode): String? {
        val navigation = call.children.firstOrNull { it.kind == "navigation_expression" }
        if (navigation != null) {
            val suffix = navigation.children.lastOrNull { it.kind == "navigation_suffix" }
            return suffix?.children?.firstOrNull { it.kind == "simple_identifier" }?.text
        }
        return call.children.firstOrNull { it.kind == "simple_identifier" }?.text
    }

    fun receiverNode(call: SyntaxNode): SyntaxNode? {
        val navigation = call.children.firstOrNull { it.kind == "navigation_expression" } ?: return null
        return navigation.children.firstOrNull { it.kind != "navigation_suffix" }
    }

    fun receiverOffset(receiver: SyntaxNode): Int =
        if (receiver.kind == "call_expression") (receiver.endByte - 1).coerceAtLeast(receiver.startByte)
        else receiver.startByte

    fun callPath(call: SyntaxNode): String? {
        val callee = calleeName(call) ?: return null
        val receiver = receiverNode(call)
        return if (receiver != null) "${receiver.text}.$callee" else callee
    }

    fun imports(tree: SyntaxTree): List<String> =
        tree.root.descendants("import_header")
            .map { it.text.removePrefix("import").trim() }
            .toList()

    fun properties(tree: SyntaxTree): Sequence<KotlinProperty> =
        tree.root.descendants("property_declaration").mapNotNull { declaration ->
            val variable = declaration.child("variable_declaration") ?: return@mapNotNull null
            val name = variable.child("simple_identifier")?.text ?: return@mapNotNull null
            val initializer = declaration.children.firstOrNull { it.kind == "call_expression" }
            KotlinProperty(name, initializer?.let(::callPath))
        }

    fun isConstant(node: SyntaxNode): Boolean =
        node.descendants().none {
            it.kind in setOf("simple_identifier", "navigation_expression", "call_expression", "user_type")
        }

    fun containsAny(node: SyntaxNode, kinds: Set<String>): Boolean =
        node.descendants().any { it.kind in kinds }
}
