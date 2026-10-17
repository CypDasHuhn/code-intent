package com.semanticpainter.treesitter

import com.semanticpainter.core.syntax.SyntaxNode
import com.semanticpainter.core.syntax.SyntaxTree

data class KotlinProperty(
    val name: String,
    val initCallPath: String?,
)

private val COMMENT_KINDS = setOf("comment", "line_comment", "multiline_comment")

object KotlinSyntax {
    fun calls(tree: SyntaxTree): Sequence<SyntaxNode> = tree.root.descendants("call_expression")

    fun callWithTrailingLambda(call: SyntaxNode): SyntaxNode {
        var current = call
        var parent = current.parent
        while (parent != null && parent.kind == "call_expression") {
            val suffix = parent.children.firstOrNull { it.kind == "call_suffix" }
            val hasLambda = suffix?.children?.any {
                it.kind == "annotated_lambda" || it.kind == "lambda_literal"
            } == true
            if (!hasLambda) break
            current = parent
            parent = current.parent
        }
        return current
    }

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
            val name = variableName(declaration) ?: return@mapNotNull null
            val initializer = initializerOf(declaration)
            KotlinProperty(name, initializer?.let(::callPath))
        }

    fun variableName(declaration: SyntaxNode): String? =
        declaration.child("variable_declaration")?.child("simple_identifier")?.text

    fun initializerOf(declaration: SyntaxNode): SyntaxNode? {
        val equals = declaration.children.firstOrNull { it.kind == "=" } ?: return null
        return declaration.children.firstOrNull { it.startByte >= equals.endByte }
    }

    fun enclosingFunction(node: SyntaxNode): SyntaxNode? {
        var current = node.parent
        while (current != null) {
            if (current.kind == "function_declaration") return current
            current = current.parent
        }
        return null
    }

    fun enclosingDeclaration(node: SyntaxNode): SyntaxNode? {
        var current = node.parent
        while (current != null) {
            if (current.kind == "property_declaration" || current.kind == "function_declaration") {
                return current
            }
            current = current.parent
        }
        return null
    }

    fun enclosingStatement(node: SyntaxNode): SyntaxNode {
        var current = node
        var parent = current.parent
        while (parent != null) {
            if (parent.kind == "statements") return current
            if (parent.kind in setOf(
                    "function_body",
                    "control_structure_body",
                    "catch_block",
                    "finally_block",
                    "try_expression",
                )
            ) {
                return current
            }
            current = parent
            parent = parent.parent
        }
        return current
    }

    fun statementsOf(container: SyntaxNode): List<SyntaxNode> =
        container.child("statements")?.children?.filterNot { it.kind in COMMENT_KINDS }.orEmpty()

    fun ifCondition(ifNode: SyntaxNode): SyntaxNode? =
        ifNode.children.firstOrNull {
            it.kind !in setOf("if", "(", ")", "else", "control_structure_body")
        }

    fun elseBodyOf(ifNode: SyntaxNode): SyntaxNode? {
        val elseIndex = ifNode.children.indexOfFirst { it.kind == "else" }
        if (elseIndex < 0) return null
        return ifNode.children.drop(elseIndex + 1)
            .firstOrNull { it.kind in setOf("control_structure_body", "if_expression") }
    }

    fun isThrow(node: SyntaxNode): Boolean =
        node.kind == "jump_expression" && node.text.trimStart().startsWith("throw")

    fun isConstant(node: SyntaxNode): Boolean =
        node.descendants().none {
            it.kind in setOf("simple_identifier", "navigation_expression", "call_expression", "user_type")
        }

    fun isSideEffectFree(node: SyntaxNode): Boolean =
        node.descendants().none { child ->
            child.kind in setOf("call_expression", "assignment", "jump_expression") ||
                child.kind.contains("assignment") ||
                child.kind.contains("lambda") ||
                child.kind.contains("object") ||
                child.kind.contains("increment") ||
                child.kind.contains("decrement") ||
                (
                    (child.kind == "postfix_expression" || child.kind == "prefix_expression") &&
                        (child.text.contains("++") || child.text.contains("--"))
                )
        }

    fun containsAny(node: SyntaxNode, kinds: Set<String>): Boolean =
        node.descendants().any { it.kind in kinds }
}
