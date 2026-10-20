package dev.cint.core.syntax

import dev.cint.api.CallFact
import dev.cint.api.CommentFact
import dev.cint.api.DeclarationFact
import dev.cint.api.FunctionFact
import dev.cint.api.GuardFact
import dev.cint.api.IfFact
import dev.cint.api.PropertyFact
import dev.cint.api.ScopeFact
import dev.cint.api.SyntaxNode
import dev.cint.api.TryFact
import dev.cint.api.WriteFact
import dev.cint.core.facts.model.SourceFile

interface SyntaxTree {
    val source: SourceFile
    val root: SyntaxNode
}

data class LoopFact(
    val node: SyntaxNode,
    val body: SyntaxNode?,
    val condition: SyntaxNode?,
)

interface SyntaxModel {
    fun calls(tree: SyntaxTree): Sequence<CallFact>

    fun callsIn(node: SyntaxNode): Sequence<CallFact>

    fun properties(tree: SyntaxTree): Sequence<PropertyFact>

    fun functions(tree: SyntaxTree): Sequence<FunctionFact>

    fun declarations(tree: SyntaxTree): Sequence<DeclarationFact>

    fun guards(tree: SyntaxTree): Sequence<GuardFact>

    fun writes(tree: SyntaxTree): Sequence<WriteFact>

    fun writeKind(node: SyntaxNode): dev.cint.api.WriteKind

    fun comments(tree: SyntaxTree): Sequence<CommentFact>

    fun commentBefore(node: SyntaxNode): CommentFact?

    fun scopes(tree: SyntaxTree): Sequence<ScopeFact>

    fun enclosingScope(node: SyntaxNode): ScopeFact?

    fun enclosingBlock(node: SyntaxNode): SyntaxNode?

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

data class SyntaxProfile(
    val language: String,
    val groups: Map<String, Set<String>>,
    val tokens: Map<String, String>,
    val keywords: Map<String, Set<String>>,
    val operators: Map<String, Set<String>>,
    val fields: Map<String, List<String>>,
) {
    fun kinds(group: String): Set<String> = groups[group].orEmpty()

    fun token(name: String): String? = tokens[name]

    fun keywords(name: String): Set<String> = keywords[name].orEmpty()

    fun operators(name: String): Set<String> = operators[name].orEmpty()

    fun fields(name: String): List<String> = fields[name].orEmpty()
}

object SyntaxGroups {
    const val CALL = "call"
    const val NAVIGATION = "navigation"
    const val CALLEE_SUFFIX = "calleeSuffix"
    const val CALL_SUFFIX = "callSuffix"
    const val IDENTIFIER = "identifier"
    const val IMPORT = "import"
    const val PROPERTY = "property"
    const val VARIABLE = "variable"
    const val FUNCTION = "function"
    const val FUNCTION_BODY = "functionBody"
    const val IF = "if"
    const val LOOP = "loop"
    const val CONTROL_BODY = "controlBody"
    const val ELSE = "else"
    const val ELSE_CLAUSE = "elseClause"
    const val STATEMENT_LIST = "statementList"
    const val STATEMENT_WRAPPER = "statementWrapper"
    const val JUMP = "jump"
    const val TRY = "try"
    const val CATCH = "catch"
    const val FINALLY = "finally"
    const val ASSIGNMENT = "assignment"
    const val ASSIGNABLE = "assignable"
    const val INCREMENT = "increment"
    const val WRITE = "write"
    const val GUARD = "guard"
    const val ANNOTATION = "annotation"
    const val MODIFIER = "modifier"
    const val COMMENT = "comment"
    const val LAMBDA = "lambda"
    const val OBJECT = "object"
    const val CLASS = "class"
    const val TYPE_DECLARATION = CLASS
    const val CLASS_BODY = "classBody"
    const val CALL_ARGUMENT = "callArgument"
    const val PARAMETER = "parameter"
    const val PARAMETER_LIST = "parameterList"
    const val BINDING = "binding"
    const val BINDING_NAME = "bindingName"
    const val PATTERN = "pattern"
    const val PATTERN_BINDING = "patternBinding"
    const val LOOP_BINDING_CONTAINER = "loopBindingContainer"
    const val TYPE_REFERENCE = "typeReference"
    const val DECLARATION_WRAPPER = "declarationWrapper"
    const val BLOCK_CLOSE = "blockClose"
    const val EQUALS = "equals"
    const val OPEN_BRACE = "openBrace"
    const val IF_TOKEN = "ifToken"
    const val ELSE_TOKEN = "elseToken"
    const val LOOP_TOKEN = "loopToken"
    const val TRY_TOKEN = "tryToken"
    const val CONDITION_EXCLUDED = "conditionExcluded"
    const val LOOP_CONDITION_EXCLUDED = "loopConditionExcluded"
    const val PARENTHESIZED = "parenthesized"
    const val REFERENCE = "reference"
    const val EFFECT = "effect"
    const val EFFECT_FRAGMENT = "effectFragment"
}

object SyntaxTokens {
    const val IMPORT_PREFIX = "importPrefix"
    const val THROW_KEYWORD = "throwKeyword"
}

object SyntaxKeywords {
    const val EXIT = "exit"
}

object SyntaxOperators {
    const val INCREMENT = "increment"
}

object SyntaxFields {
    const val IF_CONDITION = "ifCondition"
    const val IF_THEN = "ifThen"
    const val IF_ELSE = "ifElse"
    const val LOOP_CONDITION = "loopCondition"
    const val LOOP_BODY = "loopBody"
    const val LOOP_BINDING = "loopBinding"
    const val FUNCTION_BODY = "functionBody"
    const val FUNCTION_NAME = "functionName"
    const val VARIABLE_NAME = "variableName"
    const val PATTERN_BINDING = "patternBinding"
    const val VARIABLE_VALUE = "variableValue"
    const val CALL_FUNCTION = "callFunction"
    const val MEMBER_OBJECT = "memberObject"
    const val MEMBER_PROPERTY = "memberProperty"
    const val TRY_BODY = "tryBody"
    const val CATCH_BODY = "catchBody"
    const val RETURN_ARGUMENT = "returnArgument"
    const val IMPORT_SOURCE = "importSource"
}
