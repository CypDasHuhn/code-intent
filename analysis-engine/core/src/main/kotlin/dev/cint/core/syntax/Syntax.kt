package dev.cint.core.syntax

import dev.cint.api.CallFact
import dev.cint.api.FunctionFact
import dev.cint.api.IfFact
import dev.cint.api.PropertyFact
import dev.cint.api.SyntaxNode
import dev.cint.api.TryFact
import dev.cint.core.facts.model.SourceFile
import org.yaml.snakeyaml.Yaml

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
    const val ANNOTATION = "annotation"
    const val MODIFIER = "modifier"
    const val COMMENT = "comment"
    const val LAMBDA = "lambda"
    const val OBJECT = "object"
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
    const val FUNCTION_BODY = "functionBody"
    const val FUNCTION_NAME = "functionName"
    const val VARIABLE_NAME = "variableName"
    const val VARIABLE_VALUE = "variableValue"
    const val CALL_FUNCTION = "callFunction"
    const val MEMBER_OBJECT = "memberObject"
    const val MEMBER_PROPERTY = "memberProperty"
    const val TRY_BODY = "tryBody"
    const val CATCH_BODY = "catchBody"
    const val RETURN_ARGUMENT = "returnArgument"
    const val IMPORT_SOURCE = "importSource"
}

object SyntaxProfiles {
    fun load(language: String): SyntaxProfile {
        val resource = "/syntax/$language.yaml"
        val stream =
            SyntaxProfile::class.java.getResourceAsStream(resource)
                ?: error("syntax profile not found: $resource")
        val root =
            stream.bufferedReader().use { Yaml().load<Map<String, Any?>>(it) }
                ?: error("empty syntax profile: $resource")
        return SyntaxProfile(
            language = root["language"] as? String ?: language,
            groups = stringSetMap(root["groups"]),
            tokens = stringMap(root["tokens"]),
            keywords = stringSetMap(root["keywords"]),
            operators = stringSetMap(root["operators"]),
            fields = stringListMap(root["fields"]),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun stringSetMap(value: Any?): Map<String, Set<String>> {
        val map = value as? Map<String, Any?> ?: return emptyMap()
        return map.mapValues { (_, item) -> stringSet(item) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun stringMap(value: Any?): Map<String, String> {
        val map = value as? Map<String, Any?> ?: return emptyMap()
        return map.mapValues { (_, item) -> item.toString() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun stringListMap(value: Any?): Map<String, List<String>> {
        val map = value as? Map<String, Any?> ?: return emptyMap()
        return map.mapValues { (_, item) -> stringList(item) }
    }

    private fun stringList(value: Any?): List<String> =
        when (value) {
            null -> emptyList()
            is List<*> -> value.map { it.toString() }
            else -> listOf(value.toString())
        }

    private fun stringSet(value: Any?): Set<String> =
        when (value) {
            null -> emptySet()
            is List<*> -> value.map { it.toString() }.toSet()
            else -> setOf(value.toString())
        }
}
