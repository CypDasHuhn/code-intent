package com.semanticpainter.core.syntax

import org.yaml.snakeyaml.Yaml

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
