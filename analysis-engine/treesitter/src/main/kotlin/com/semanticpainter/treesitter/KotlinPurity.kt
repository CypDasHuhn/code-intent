package com.semanticpainter.treesitter

import com.semanticpainter.core.syntax.SyntaxNode
import com.semanticpainter.core.syntax.SyntaxTree

object KotlinPurity {
    fun annotationTexts(declaration: SyntaxNode): List<String> {
        val modifiers = declaration.child("modifiers") ?: return emptyList()
        return modifiers.children
            .filter { it.kind == "annotation" }
            .map { normalize(it.text) }
    }

    fun isAnnotatedPure(
        declaration: SyntaxNode,
        patterns: List<String>,
    ): Boolean =
        annotationTexts(declaration).any { annotation ->
            patterns.any { pattern -> matches(annotation, pattern) }
        }

    fun findFunction(
        tree: SyntaxTree,
        name: String,
    ): SyntaxNode? =
        tree.root.descendants("function_declaration").firstOrNull { declaration ->
            declaration.children.firstOrNull { it.kind == "simple_identifier" }?.text == name
        }

    fun isAnnotatedPureCall(
        tree: SyntaxTree,
        call: SyntaxNode,
        patterns: List<String>,
    ): Boolean {
        if (KotlinSyntax.receiverNode(call) != null) return false
        val callee = KotlinSyntax.calleeName(call) ?: return false
        val function = findFunction(tree, callee) ?: return false
        return isAnnotatedPure(function, patterns)
    }

    private fun normalize(text: String): String =
        text
            .removePrefix("@")
            .replace(Regex("""^(field|property|get|set|receiver|param|setparam|delegate)\s*:\s*"""), "")
            .replace(Regex("""\s+"""), "")
            .removePrefix("[")
            .removeSuffix("]")

    private fun matches(
        annotation: String,
        pattern: String,
    ): Boolean {
        val expected = normalize(pattern)
        if (annotation.contains('(') || expected.contains('(')) {
            return annotation == expected
        }
        return annotation == expected || annotation.endsWith(".$expected")
    }
}
