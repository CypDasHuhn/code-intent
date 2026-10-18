package com.semanticpainter.core.syntax

data class CallFact(
    val node: SyntaxNode,
    val site: SyntaxNode,
    val callee: String?,
    val receiver: SyntaxNode?,
)

data class PropertyFact(
    val node: SyntaxNode,
    val name: String?,
    val nameNode: SyntaxNode?,
    val initializer: SyntaxNode?,
)

data class FunctionFact(
    val node: SyntaxNode,
    val name: String?,
    val nameNode: SyntaxNode?,
    val body: SyntaxNode?,
)

data class IfFact(
    val node: SyntaxNode,
    val condition: SyntaxNode?,
    val thenBody: SyntaxNode?,
    val elseBody: SyntaxNode?,
    val elseKeyword: SyntaxNode?,
)

data class TryFact(
    val node: SyntaxNode,
    val body: SyntaxNode?,
    val catches: List<SyntaxNode>,
    val finallyBlock: SyntaxNode?,
    val headerStart: SyntaxNode?,
    val headerEnd: SyntaxNode?,
)

data class LoopFact(
    val node: SyntaxNode,
    val condition: SyntaxNode?,
    val body: SyntaxNode?,
)

interface SyntaxModel {
    fun calls(tree: SyntaxTree): Sequence<CallFact>

    fun callsIn(node: SyntaxNode): Sequence<CallFact>

    fun properties(tree: SyntaxTree): Sequence<PropertyFact>

    fun functions(tree: SyntaxTree): Sequence<FunctionFact>

    fun ifs(tree: SyntaxTree): Sequence<IfFact>

    fun loops(tree: SyntaxTree): Sequence<LoopFact>

    fun loopBody(node: SyntaxNode): SyntaxNode?

    fun tries(tree: SyntaxTree): Sequence<TryFact>

    fun catches(tree: SyntaxTree): Sequence<SyntaxNode>

    fun guardConditions(tree: SyntaxTree): Sequence<SyntaxNode>

    fun imports(tree: SyntaxTree): List<String>

    fun referencesByName(tree: SyntaxTree): Map<String, List<SyntaxNode>>

    fun variableName(declaration: SyntaxNode): String?

    fun nameNode(declaration: SyntaxNode): SyntaxNode?

    fun functionNameNode(declaration: SyntaxNode): SyntaxNode?

    fun initializerOf(declaration: SyntaxNode): SyntaxNode?

    fun bodyOf(declaration: SyntaxNode): SyntaxNode?

    fun statementList(container: SyntaxNode): SyntaxNode?

    fun statements(container: SyntaxNode): List<SyntaxNode>?

    fun statementExpression(node: SyntaxNode): SyntaxNode?

    fun enclosingFunction(node: SyntaxNode): SyntaxNode?

    fun enclosingDeclaration(node: SyntaxNode): SyntaxNode?

    fun declarationHolder(node: SyntaxNode): SyntaxNode?

    fun enclosingStatement(node: SyntaxNode): SyntaxNode

    fun writes(reference: SyntaxNode): SyntaxNode?

    fun annotationTexts(declaration: SyntaxNode): List<String>

    fun precedingComments(node: SyntaxNode): Sequence<SyntaxNode>

    fun closestCommentBefore(
        scope: SyntaxNode,
        node: SyntaxNode,
    ): SyntaxNode?

    fun isConstant(node: SyntaxNode): Boolean

    fun isSideEffectFree(node: SyntaxNode): Boolean

    fun isThrow(node: SyntaxNode): Boolean

    fun isExit(node: SyntaxNode): Boolean

    fun isReturn(node: SyntaxNode): Boolean

    fun exitExpression(node: SyntaxNode): SyntaxNode?

    fun isDeclarationName(node: SyntaxNode): Boolean

    fun isIdentifier(node: SyntaxNode): Boolean

    fun isBlockClose(node: SyntaxNode): Boolean

    fun previousBlockClose(node: SyntaxNode): SyntaxNode?

    fun previousSibling(node: SyntaxNode): SyntaxNode?

    fun ifCondition(node: SyntaxNode): SyntaxNode?

    fun loopCondition(node: SyntaxNode): SyntaxNode?

    fun elseBodyOf(node: SyntaxNode): SyntaxNode?

    fun callPath(call: SyntaxNode): String?

    fun calleeName(call: SyntaxNode): String?

    fun receiverNode(call: SyntaxNode): SyntaxNode?

    fun receiverOffset(receiver: SyntaxNode): Int

    fun findFunction(
        tree: SyntaxTree,
        name: String,
    ): SyntaxNode?

    fun isAnnotatedPure(
        declaration: SyntaxNode,
        patterns: List<String>,
    ): Boolean

    fun isAnnotatedPureCall(
        tree: SyntaxTree,
        call: SyntaxNode,
        patterns: List<String>,
    ): Boolean
}
