package dev.cint.core.syntax

import dev.cint.api.CallFact
import dev.cint.api.FunctionFact
import dev.cint.api.IfFact
import dev.cint.api.PropertyFact
import dev.cint.api.SyntaxNode
import dev.cint.api.TryFact

internal class ProfileSyntaxKinds(
    private val profile: SyntaxProfile,
) {
    val callKinds = profile.kinds(SyntaxGroups.CALL)
    val navigationKinds = profile.kinds(SyntaxGroups.NAVIGATION)
    val calleeSuffixKinds = profile.kinds(SyntaxGroups.CALLEE_SUFFIX)
    val callSuffixKinds = profile.kinds(SyntaxGroups.CALL_SUFFIX)
    val identifierKinds = profile.kinds(SyntaxGroups.IDENTIFIER)
    val importKinds = profile.kinds(SyntaxGroups.IMPORT)
    val propertyKinds = profile.kinds(SyntaxGroups.PROPERTY)
    val variableKinds = profile.kinds(SyntaxGroups.VARIABLE)
    val functionKinds = profile.kinds(SyntaxGroups.FUNCTION)
    val functionBodyKinds = profile.kinds(SyntaxGroups.FUNCTION_BODY)
    val ifKinds = profile.kinds(SyntaxGroups.IF)
    val loopKinds = profile.kinds(SyntaxGroups.LOOP)
    val controlBodyKinds = profile.kinds(SyntaxGroups.CONTROL_BODY)
    val elseKinds = profile.kinds(SyntaxGroups.ELSE)
    val elseClauseKinds = profile.kinds(SyntaxGroups.ELSE_CLAUSE)
    val statementListKinds = profile.kinds(SyntaxGroups.STATEMENT_LIST)
    val statementWrapperKinds = profile.kinds(SyntaxGroups.STATEMENT_WRAPPER)
    val jumpKinds = profile.kinds(SyntaxGroups.JUMP)
    val tryKinds = profile.kinds(SyntaxGroups.TRY)
    val catchKinds = profile.kinds(SyntaxGroups.CATCH)
    val finallyKinds = profile.kinds(SyntaxGroups.FINALLY)
    val assignmentKinds = profile.kinds(SyntaxGroups.ASSIGNMENT)
    val assignableKinds = profile.kinds(SyntaxGroups.ASSIGNABLE)
    val incrementKinds = profile.kinds(SyntaxGroups.INCREMENT)
    val annotationKinds = profile.kinds(SyntaxGroups.ANNOTATION)
    val modifierKinds = profile.kinds(SyntaxGroups.MODIFIER)
    val commentKinds = profile.kinds(SyntaxGroups.COMMENT)
    val lambdaKinds = profile.kinds(SyntaxGroups.LAMBDA)
    val typeReferenceKinds = profile.kinds(SyntaxGroups.TYPE_REFERENCE)
    val declarationWrapperKinds = profile.kinds(SyntaxGroups.DECLARATION_WRAPPER)
    val blockCloseKinds = profile.kinds(SyntaxGroups.BLOCK_CLOSE)
    val openBraceKinds = profile.kinds(SyntaxGroups.OPEN_BRACE)
    val equalsKinds = profile.kinds(SyntaxGroups.EQUALS)
    val ifTokenKinds = profile.kinds(SyntaxGroups.IF_TOKEN)
    val elseTokenKinds = profile.kinds(SyntaxGroups.ELSE_TOKEN)
    val loopTokenKinds = profile.kinds(SyntaxGroups.LOOP_TOKEN)
    val tryTokenKinds = profile.kinds(SyntaxGroups.TRY_TOKEN)
    val conditionExcludedKinds = profile.kinds(SyntaxGroups.CONDITION_EXCLUDED)
    val loopConditionExcludedKinds = profile.kinds(SyntaxGroups.LOOP_CONDITION_EXCLUDED)
    val parenthesizedKinds = profile.kinds(SyntaxGroups.PARENTHESIZED)
    val referenceKinds = profile.kinds(SyntaxGroups.REFERENCE)
    val effectKinds = profile.kinds(SyntaxGroups.EFFECT)
    val effectFragments = profile.kinds(SyntaxGroups.EFFECT_FRAGMENT)
    val incrementOperators = profile.operators(SyntaxOperators.INCREMENT)
    val exitKeywords = profile.keywords(SyntaxKeywords.EXIT)
    val throwKeyword = profile.token(SyntaxTokens.THROW_KEYWORD)
    val importPrefix = profile.token(SyntaxTokens.IMPORT_PREFIX)

    fun fieldNode(
        node: SyntaxNode,
        name: String,
    ): SyntaxNode? = profile.fields(name).firstNotNullOfOrNull { node.childByField(it) }
}

class ProfileSyntaxModel(
    profile: SyntaxProfile,
) : SyntaxModel {
    private val kinds = ProfileSyntaxKinds(profile)
    private val callFacts = CallFacts(kinds)
    private val declarations = DeclarationFacts(kinds, callFacts)
    private val controlFlow = ControlFlowFacts(kinds)
    private val semantics = SemanticFacts(kinds)

    override fun calls(tree: SyntaxTree): Sequence<CallFact> = callFacts.calls(tree)

    override fun callsIn(node: SyntaxNode): Sequence<CallFact> = callFacts.callsIn(node)

    override fun properties(tree: SyntaxTree): Sequence<PropertyFact> = declarations.properties(tree)

    override fun functions(tree: SyntaxTree): Sequence<FunctionFact> = declarations.functions(tree)

    override fun ifs(tree: SyntaxTree): Sequence<IfFact> = controlFlow.ifs(tree)

    override fun loops(tree: SyntaxTree): Sequence<LoopFact> = controlFlow.loops(tree)

    override fun loopBody(node: SyntaxNode): SyntaxNode? = controlFlow.loopBody(node)

    override fun tries(tree: SyntaxTree): Sequence<TryFact> = controlFlow.tries(tree)

    override fun catches(tree: SyntaxTree): Sequence<SyntaxNode> = controlFlow.catches(tree)

    override fun guardConditions(tree: SyntaxTree): Sequence<SyntaxNode> = controlFlow.guardConditions(tree)

    override fun imports(tree: SyntaxTree): List<String> = semantics.imports(tree)

    override fun referencesByName(tree: SyntaxTree): Map<String, List<SyntaxNode>> = semantics.referencesByName(tree)

    override fun variableName(declaration: SyntaxNode): String? = declarations.variableName(declaration)

    override fun nameNode(declaration: SyntaxNode): SyntaxNode? = declarations.nameNode(declaration)

    override fun functionNameNode(declaration: SyntaxNode): SyntaxNode? = declarations.functionNameNode(declaration)

    override fun initializerOf(declaration: SyntaxNode): SyntaxNode? = declarations.initializerOf(declaration)

    override fun bodyOf(declaration: SyntaxNode): SyntaxNode? = declarations.bodyOf(declaration)

    override fun statementList(container: SyntaxNode): SyntaxNode? = controlFlow.statementList(container)

    override fun statements(container: SyntaxNode): List<SyntaxNode>? = controlFlow.statements(container)

    override fun statementExpression(node: SyntaxNode): SyntaxNode? = controlFlow.statementExpression(node)

    override fun enclosingFunction(node: SyntaxNode): SyntaxNode? = declarations.enclosingFunction(node)

    override fun enclosingDeclaration(node: SyntaxNode): SyntaxNode? = declarations.enclosingDeclaration(node)

    override fun declarationHolder(node: SyntaxNode): SyntaxNode? = declarations.declarationHolder(node)

    override fun enclosingStatement(node: SyntaxNode): SyntaxNode = controlFlow.enclosingStatement(node)

    override fun writes(reference: SyntaxNode): SyntaxNode? = semantics.writes(reference)

    override fun annotationTexts(declaration: SyntaxNode): List<String> = declarations.annotationTexts(declaration)

    override fun precedingComments(node: SyntaxNode): Sequence<SyntaxNode> = declarations.precedingComments(node)

    override fun closestCommentBefore(
        scope: SyntaxNode,
        node: SyntaxNode,
    ): SyntaxNode? = declarations.closestCommentBefore(scope, node)

    override fun isConstant(node: SyntaxNode): Boolean = semantics.isConstant(node)

    override fun isSideEffectFree(node: SyntaxNode): Boolean = semantics.isSideEffectFree(node)

    override fun isThrow(node: SyntaxNode): Boolean = semantics.isThrow(node)

    override fun isExit(node: SyntaxNode): Boolean = semantics.isExit(node)

    override fun isReturn(node: SyntaxNode): Boolean = semantics.isReturn(node)

    override fun exitExpression(node: SyntaxNode): SyntaxNode? = semantics.exitExpression(node)

    override fun isDeclarationName(node: SyntaxNode): Boolean = declarations.isDeclarationName(node)

    override fun isIdentifier(node: SyntaxNode): Boolean = semantics.isIdentifier(node)

    override fun isBlockClose(node: SyntaxNode): Boolean = controlFlow.isBlockClose(node)

    override fun previousBlockClose(node: SyntaxNode): SyntaxNode? = controlFlow.previousBlockClose(node)

    override fun previousSibling(node: SyntaxNode): SyntaxNode? = controlFlow.previousSibling(node)

    override fun ifCondition(node: SyntaxNode): SyntaxNode? = controlFlow.ifCondition(node)

    override fun loopCondition(node: SyntaxNode): SyntaxNode? = controlFlow.loopCondition(node)

    override fun elseBodyOf(node: SyntaxNode): SyntaxNode? = controlFlow.elseBodyOf(node)

    override fun callPath(call: SyntaxNode): String? = callFacts.callPath(call)

    override fun calleeName(call: SyntaxNode): String? = callFacts.calleeName(call)

    override fun receiverNode(call: SyntaxNode): SyntaxNode? = callFacts.receiverNode(call)

    override fun receiverOffset(receiver: SyntaxNode): Int = callFacts.receiverOffset(receiver)

    override fun findFunction(
        tree: SyntaxTree,
        name: String,
    ): SyntaxNode? = declarations.findFunction(tree, name)

    override fun isAnnotatedPure(
        declaration: SyntaxNode,
        patterns: List<String>,
    ): Boolean = declarations.isAnnotatedPure(declaration, patterns)

    override fun isAnnotatedPureCall(
        tree: SyntaxTree,
        call: SyntaxNode,
        patterns: List<String>,
    ): Boolean = declarations.isAnnotatedPureCall(tree, call, patterns)
}
