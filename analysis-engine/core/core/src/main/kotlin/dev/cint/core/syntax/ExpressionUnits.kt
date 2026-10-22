package dev.cint.core.syntax

import dev.cint.api.Span
import dev.cint.api.SyntaxNode

internal class ExpressionNodes(
    private val kinds: ProfileSyntaxKinds,
) {
    private val excluded =
        kinds.commentKinds +
            kinds.importKinds +
            kinds.annotationKinds +
            kinds.modifierKinds +
            kinds.propertyKinds +
            kinds.functionKinds +
            kinds.classKinds +
            kinds.classBodyKinds +
            kinds.parameterListKinds +
            kinds.typeReferenceKinds +
            kinds.declarationWrapperKinds +
            kinds.expressionExcludedKinds

    private val skippedSubtrees =
        kinds.commentKinds +
            kinds.importKinds +
            kinds.expressionExcludedKinds

    fun expressions(tree: SyntaxTree): Sequence<SyntaxNode> =
        buildList {
            fun visit(node: SyntaxNode) {
                if (shouldSkipSubtree(node)) return
                if (isExpression(node)) add(node)
                for (child in node.children) visit(child)
            }
            visit(tree.root)
        }.asSequence()

    fun isExpression(node: SyntaxNode): Boolean {
        if (node.parent == null || !node.isNamed) return false
        if (kinds.expressionKinds.isNotEmpty()) return node.kind in kinds.expressionKinds
        return node.kind !in excluded
    }

    fun shouldSkipSubtree(node: SyntaxNode): Boolean {
        if (node.kind in skippedSubtrees) return true
        return isDeclarationName(node)
    }

    private fun isDeclarationName(node: SyntaxNode): Boolean {
        val parent = node.parent ?: return false
        if (parent.kind in kinds.annotationKinds) {
            if (node.kind in kinds.identifierKinds || node.kind in kinds.typeReferenceKinds) return true
            if (node.kind in kinds.expressionExcludedKinds) return true
        }
        if (parent.kind in kinds.functionKinds || parent.kind in kinds.classKinds) {
            if (node.kind in kinds.identifierKinds) return true
            if (node.kind in kinds.typeReferenceKinds || node.kind in kinds.expressionExcludedKinds) return true
        }
        if (parent.kind in kinds.parameterKinds) {
            val equals = equalsChild(parent)
            if (equals != null && node.endByte > equals.startByte) return false
            if (node.kind in kinds.identifierKinds || node.kind in kinds.typeReferenceKinds) return true
            if (node.kind in kinds.expressionExcludedKinds) return true
        }
        if (parent.kind in kinds.variableKinds || parent.kind in kinds.bindingKinds) {
            if (node.kind in kinds.typeReferenceKinds || node.kind in kinds.expressionExcludedKinds) return true
            if (node.kind in kinds.identifierKinds) {
                val equals = equalsChild(parent)
                return equals == null || node.endByte <= equals.startByte
            }
        }
        if (parent.kind in kinds.propertyKinds) {
            val equals = equalsChild(parent)
            if (node.kind in kinds.identifierKinds && (equals == null || node.endByte <= equals.startByte)) return true
            if (node.kind in kinds.typeReferenceKinds || node.kind in kinds.expressionExcludedKinds) return true
        }
        return false
    }

    private fun equalsChild(parent: SyntaxNode): SyntaxNode? = parent.children.firstOrNull { it.kind in kinds.equalsKinds }
}

data class ExpressionUnit(
    val unitId: String,
    val node: SyntaxNode,
    val parent: ExpressionUnit?,
    val enclosingStatement: SyntaxNode,
    val enclosingFunction: SyntaxNode?,
) {
    val startByte: Int get() = node.startByte
    val endByte: Int get() = node.endByte
    val text: String get() = node.text
    val span: Span get() = node.span()
}

fun SyntaxModel.expressionUnits(tree: SyntaxTree): List<ExpressionUnit> {
    val result = mutableListOf<ExpressionUnit>()

    fun visit(
        node: SyntaxNode,
        parent: ExpressionUnit?,
    ) {
        if (skipExpressionSubtree(node)) return
        val unit =
            if (isExpression(node)) {
                ExpressionUnit(
                    unitId = "u${result.size}",
                    node = node,
                    parent = parent,
                    enclosingStatement = enclosingStatement(node),
                    enclosingFunction = enclosingFunction(node),
                ).also(result::add)
            } else {
                null
            }
        val nextParent = unit ?: parent
        for (child in node.children) {
            visit(child, nextParent)
        }
    }
    visit(tree.root, null)
    return result
}
