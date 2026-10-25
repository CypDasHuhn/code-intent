package dev.cint.v2.syntax.treesitter

import dev.cint.contracts.data.Span
import dev.cint.contracts.facts.CallFact
import dev.cint.contracts.facts.ClassFact
import dev.cint.contracts.facts.CoalesceFact
import dev.cint.contracts.facts.CommentFact
import dev.cint.contracts.facts.CommentKind
import dev.cint.contracts.facts.CreationFact
import dev.cint.contracts.facts.DeclarationFact
import dev.cint.contracts.facts.DeclarationKind
import dev.cint.contracts.facts.ExitFact
import dev.cint.contracts.facts.FactBatch
import dev.cint.contracts.facts.FunctionFact
import dev.cint.contracts.facts.GuardFact
import dev.cint.contracts.facts.GuardKind
import dev.cint.contracts.facts.IfFact
import dev.cint.contracts.facts.ImportFact
import dev.cint.contracts.facts.JumpKind
import dev.cint.contracts.facts.ParameterFact
import dev.cint.contracts.facts.PropertyFact
import dev.cint.contracts.facts.ReferenceFact
import dev.cint.contracts.facts.ReturnFact
import dev.cint.contracts.facts.ScopeFact
import dev.cint.contracts.facts.ScopeKind
import dev.cint.contracts.facts.StatementKind
import dev.cint.contracts.facts.SyntaxNode
import dev.cint.contracts.facts.SyntaxTree
import dev.cint.contracts.facts.TryFact
import dev.cint.contracts.facts.ValueFact
import dev.cint.contracts.facts.ValueKind
import dev.cint.contracts.facts.WriteFact
import dev.cint.contracts.facts.WriteKind
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory

private enum class FactCategory(
    val key: String,
) {
    CALLS("calls"),
    WRITES("writes"),
    DECLARATIONS("declarations"),
    COMMENTS("comments"),
    SCOPES("scopes"),
    GUARDS("guards"),
    RETURNS("returns"),
    PROPERTIES("properties"),
    FUNCTIONS("functions"),
    IFS("ifs"),
    TRIES("tries"),
    IMPORTS("imports"),
    REFERENCES("references"),
    ;

    companion object {
        fun from(raw: String): FactCategory? {
            val normalized =
                raw
                    .trim()
                    .lowercase()
                    .removePrefix("syntax.")
                    .removePrefix("syntax/")
                    .removeSuffix(".query")
            return entries.firstOrNull { it.key == normalized || it.name.lowercase() == normalized }
        }
    }
}

private data class LanguageProfile(
    val callKinds: Set<String>,
    val functionKinds: Set<String>,
    val propertyKinds: Set<String>,
    val variableKinds: Set<String>,
    val classKinds: Set<String>,
    val scopeKinds: Set<String>,
    val blockKinds: Set<String>,
    val lambdaKinds: Set<String>,
    val ifKinds: Set<String>,
    val loopKinds: Set<String>,
    val tryKinds: Set<String>,
    val catchKinds: Set<String>,
    val finallyKinds: Set<String>,
    val assignmentKinds: Set<String>,
    val incrementKinds: Set<String>,
    val commentKinds: Set<String>,
    val annotationKinds: Set<String>,
    val importKinds: Set<String>,
    val returnKinds: Set<String>,
    val identifierKinds: Set<String>,
    val declarationKinds: Set<String>,
    val bodyKinds: Set<String>,
    val conditionSkipKinds: Set<String>,
    val callSuffixKinds: Set<String>,
    val navigationKinds: Set<String>,
    val memberKinds: Set<String>,
    val receiverIsFirstChild: Boolean = false,
    val statementListKinds: Set<String> = emptySet(),
    val statementWrapperKinds: Set<String> = emptySet(),
    val referenceKinds: Set<String> = emptySet(),
    val effectKinds: Set<String> = emptySet(),
    val effectFragments: Set<String> = emptySet(),
    val literalKinds: Set<String> = emptySet(),
)

private object Profiles {
    private val kotlin =
        LanguageProfile(
            callKinds = setOf("call_expression"),
            functionKinds = setOf("function_declaration", "constructor_declaration", "anonymous_function"),
            propertyKinds = setOf("property_declaration"),
            variableKinds = setOf("variable_declaration", "multi_variable_declaration"),
            classKinds = setOf("class_declaration", "object_declaration", "companion_object", "object_literal"),
            scopeKinds =
                setOf(
                    "class_declaration",
                    "object_declaration",
                    "companion_object",
                    "object_literal",
                    "function_declaration",
                    "constructor_declaration",
                    "anonymous_function",
                    "lambda_literal",
                    "annotated_lambda",
                    "control_structure_body",
                    "statements",
                    "class_body",
                ),
            blockKinds = setOf("control_structure_body", "statements", "class_body", "function_body"),
            lambdaKinds = setOf("lambda_literal", "annotated_lambda", "anonymous_function"),
            ifKinds = setOf("if_expression"),
            loopKinds = setOf("while_statement", "for_statement", "do_while_statement"),
            tryKinds = setOf("try_expression"),
            catchKinds = setOf("catch_block"),
            finallyKinds = setOf("finally_block"),
            assignmentKinds = setOf("assignment", "augmented_assignment"),
            incrementKinds = setOf("postfix_expression", "prefix_expression"),
            commentKinds = setOf("comment", "line_comment", "multiline_comment"),
            annotationKinds = setOf("annotation"),
            importKinds = setOf("import_header"),
            returnKinds = setOf("jump_expression", "return_statement"),
            identifierKinds = setOf("simple_identifier", "type_identifier"),
            declarationKinds = setOf("property_declaration", "variable_declaration", "function_declaration"),
            bodyKinds = setOf("function_body", "control_structure_body", "statements", "class_body", "block"),
            conditionSkipKinds = setOf("control_structure_body", "statements", "block", "function_body"),
            callSuffixKinds = setOf("call_suffix", "value_arguments", "argument_list"),
            navigationKinds = setOf("navigation_expression"),
            memberKinds = setOf("navigation_suffix"),
            statementListKinds = setOf("statements"),
            referenceKinds = setOf("simple_identifier", "navigation_expression", "call_expression", "user_type"),
            effectKinds =
                setOf(
                    "call_expression",
                    "assignment",
                    "jump_expression",
                    "lambda_literal",
                    "annotated_lambda",
                    "object_literal",
                    "object_declaration",
                ),
            effectFragments = setOf("assignment", "lambda", "object", "increment", "decrement"),
            literalKinds =
                setOf(
                    "string_literal",
                    "character_literal",
                    "integer_literal",
                    "hex_literal",
                    "real_literal",
                    "boolean_literal",
                    "null",
                    "collection_literal",
                ),
        )

    private val javascript =
        LanguageProfile(
            callKinds = setOf("call_expression", "new_expression"),
            functionKinds =
                setOf(
                    "function_declaration",
                    "function_expression",
                    "generator_function_declaration",
                    "method_definition",
                    "arrow_function",
                ),
            propertyKinds = setOf("public_field_definition", "field_definition", "pair"),
            variableKinds = setOf("lexical_declaration", "variable_declaration", "variable_declarator"),
            classKinds = setOf("class_declaration", "class", "class_expression"),
            scopeKinds =
                setOf(
                    "class_declaration",
                    "class",
                    "class_expression",
                    "function_declaration",
                    "function_expression",
                    "generator_function_declaration",
                    "method_definition",
                    "arrow_function",
                    "statement_block",
                ),
            blockKinds = setOf("statement_block", "class_body"),
            lambdaKinds = setOf("arrow_function", "function_expression"),
            ifKinds = setOf("if_statement", "ternary_expression"),
            loopKinds = setOf("while_statement", "for_statement", "for_in_statement", "do_statement"),
            tryKinds = setOf("try_statement"),
            catchKinds = setOf("catch_clause"),
            finallyKinds = setOf("finally_clause"),
            assignmentKinds = setOf("assignment_expression", "augmented_assignment_expression"),
            incrementKinds = setOf("update_expression"),
            commentKinds = setOf("comment"),
            annotationKinds = setOf("decorator", "decorator_list"),
            importKinds = setOf("import_statement", "import_declaration", "export_statement"),
            returnKinds = setOf("return_statement"),
            identifierKinds =
                setOf(
                    "identifier",
                    "property_identifier",
                    "shorthand_property_identifier",
                    "shorthand_property_identifier_pattern",
                ),
            declarationKinds =
                setOf(
                    "function_declaration",
                    "function_expression",
                    "method_definition",
                    "lexical_declaration",
                    "variable_declaration",
                    "variable_declarator",
                    "public_field_definition",
                    "field_definition",
                ),
            bodyKinds = setOf("statement_block", "class_body", "function_body"),
            conditionSkipKinds = setOf("statement_block", "class_body"),
            callSuffixKinds = setOf("arguments", "type_arguments"),
            navigationKinds = setOf("member_expression", "subscript_expression"),
            memberKinds = setOf("property_identifier"),
            statementListKinds = setOf("statement_block", "program"),
            statementWrapperKinds = setOf("expression_statement"),
            referenceKinds =
                setOf(
                    "identifier",
                    "property_identifier",
                    "private_property_identifier",
                    "member_expression",
                    "call_expression",
                ),
            effectKinds =
                setOf(
                    "call_expression",
                    "assignment_expression",
                    "augmented_assignment_expression",
                    "update_expression",
                    "new_expression",
                    "yield_expression",
                    "await_expression",
                    "throw_statement",
                    "arrow_function",
                    "function_expression",
                    "object",
                ),
            literalKinds = setOf("string", "template_string", "number", "regex", "true", "false", "null", "undefined"),
        )

    private val csharp =
        LanguageProfile(
            callKinds = setOf("invocation_expression", "object_creation_expression"),
            functionKinds =
                setOf(
                    "method_declaration",
                    "local_function_statement",
                    "constructor_declaration",
                    "destructor_declaration",
                    "operator_declaration",
                ),
            propertyKinds = setOf("property_declaration", "field_declaration", "event_declaration"),
            variableKinds = setOf("variable_declaration", "variable_declarator"),
            classKinds = setOf("class_declaration", "struct_declaration", "record_declaration", "interface_declaration"),
            scopeKinds =
                setOf(
                    "class_declaration",
                    "struct_declaration",
                    "record_declaration",
                    "interface_declaration",
                    "method_declaration",
                    "local_function_statement",
                    "constructor_declaration",
                    "lambda_expression",
                    "anonymous_method_expression",
                    "block",
                ),
            blockKinds = setOf("block", "declaration_list"),
            lambdaKinds = setOf("lambda_expression", "anonymous_method_expression"),
            ifKinds = setOf("if_statement", "conditional_expression", "switch_statement"),
            loopKinds = setOf("while_statement", "for_statement", "for_each_statement", "do_statement"),
            tryKinds = setOf("try_statement"),
            catchKinds = setOf("catch_clause"),
            finallyKinds = setOf("finally_clause"),
            assignmentKinds = setOf("assignment_expression", "coalesce_assignment_expression"),
            incrementKinds = setOf("postfix_unary_expression", "prefix_unary_expression"),
            commentKinds = setOf("comment"),
            annotationKinds = setOf("attribute", "attribute_list"),
            importKinds = setOf("using_directive", "global_statement"),
            returnKinds = setOf("return_statement"),
            identifierKinds = setOf("identifier", "property_identifier"),
            declarationKinds =
                setOf(
                    "method_declaration",
                    "local_function_statement",
                    "constructor_declaration",
                    "property_declaration",
                    "field_declaration",
                    "variable_declaration",
                    "variable_declarator",
                ),
            bodyKinds = setOf("block", "declaration_list", "accessor_list"),
            conditionSkipKinds = setOf("block", "declaration_list"),
            callSuffixKinds = setOf("argument_list", "type_argument_list"),
            navigationKinds = setOf("member_access_expression", "element_access_expression"),
            memberKinds = setOf("identifier"),
            receiverIsFirstChild = true,
            statementListKinds = setOf("block"),
            statementWrapperKinds = setOf("expression_statement"),
            referenceKinds = setOf("identifier", "member_access_expression", "invocation_expression", "predefined_type"),
            effectKinds =
                setOf(
                    "invocation_expression",
                    "assignment_expression",
                    "postfix_unary_expression",
                    "prefix_unary_expression",
                    "object_creation_expression",
                    "await_expression",
                    "throw_statement",
                    "throw_expression",
                    "lambda_expression",
                ),
            literalKinds =
                setOf(
                    "integer_literal",
                    "real_literal",
                    "string_literal",
                    "character_literal",
                    "boolean_literal",
                    "null_literal",
                ),
        )

    private val c =
        LanguageProfile(
            callKinds = setOf("call_expression"),
            functionKinds = setOf("function_definition"),
            propertyKinds = setOf("declaration"),
            variableKinds = setOf("declaration", "parameter_declaration"),
            classKinds = setOf("struct_specifier", "union_specifier", "enum_specifier"),
            scopeKinds =
                setOf(
                    "function_definition",
                    "struct_specifier",
                    "union_specifier",
                    "enum_specifier",
                    "compound_statement",
                    "if_statement",
                    "for_statement",
                    "while_statement",
                    "switch_statement",
                ),
            blockKinds = setOf("compound_statement"),
            lambdaKinds = setOf("lambda_expression"),
            ifKinds = setOf("if_statement", "conditional_expression"),
            loopKinds = setOf("for_statement", "while_statement", "do_statement"),
            tryKinds = emptySet(),
            catchKinds = emptySet(),
            finallyKinds = emptySet(),
            assignmentKinds = setOf("assignment_expression"),
            incrementKinds = setOf("update_expression"),
            commentKinds = setOf("comment"),
            annotationKinds = emptySet(),
            importKinds = setOf("preproc_include"),
            returnKinds = setOf("return_statement"),
            identifierKinds = setOf("identifier", "field_identifier", "type_identifier"),
            declarationKinds = setOf("function_definition", "declaration", "parameter_declaration"),
            bodyKinds = setOf("compound_statement", "field_declaration_list", "enumerator_list"),
            conditionSkipKinds = setOf("compound_statement"),
            callSuffixKinds = setOf("argument_list"),
            navigationKinds = setOf("field_expression", "subscript_expression"),
            memberKinds = setOf("field_identifier"),
            statementListKinds = setOf("compound_statement"),
            referenceKinds = setOf("identifier", "field_identifier", "type_identifier", "call_expression"),
            effectKinds = setOf("call_expression", "assignment_expression", "update_expression"),
            literalKinds = setOf("string_literal", "char_literal", "number_literal"),
        )

    private val vue =
        javascript.copy(
            callKinds = javascript.callKinds + setOf("call_expression"),
            functionKinds = javascript.functionKinds,
            propertyKinds = javascript.propertyKinds + setOf("pair"),
            commentKinds = javascript.commentKinds + setOf("comment", "html_comment"),
            importKinds = javascript.importKinds,
        )

    fun forProfile(language: String): LanguageProfile? =
        when (language) {
            "kotlin" -> kotlin
            "csharp" -> csharp
            "c" -> c
            "vue" -> vue
            "javascript", "typescript" -> javascript
            else -> null
        }
}

private val ASSIGNMENT_OPERATORS =
    setOf("=", "+=", "-=", "*=", "/=", "%=", "**=", "&=", "|=", "^=", "<<=", ">>=", ">>>=", "??=", "?:=")

private val TYPE_REFERENCE_KINDS =
    setOf("type_identifier", "predefined_type", "scoped_type_identifier", "generic_type")
private val TYPE_CONTEXT_KINDS =
    setOf(
        "type_argument_list",
        "type_arguments",
        "type_parameters",
        "generic_name",
        "generic_type",
        "nullable_type",
        "pointer_type",
        "array_type",
        "type_annotation",
        "user_type",
        "type_projection",
        "type_parameter",
        "type_reference",
        "supertype",
    )

private val REJECTION_TEXTS =
    setOf(
        "null",
        "undefined",
        "false",
        "0",
        "-1",
        "\"\"",
        "''",
        "[]",
        "{}",
        "emptyList()",
        "emptyMap()",
        "emptySet()",
        "listOf()",
        "mapOf()",
        "setOf()",
        "arrayOf()",
        "String.Empty",
    )
private val CONSTANT_COMPARISON = Regex("""^(?:true|false|-?\d+)\s*(?:==|!=)\s*(?:true|false|-?\d+)$""")
private val BARE_LABELED_RETURN = Regex("""^return@[A-Za-z_][A-Za-z0-9_]*$""")
private val DIRECTIVE = Regex("""cint\s*:\s*([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
private val SIMPLE_IDENTIFIER = Regex("""^[A-Za-z_$][A-Za-z0-9_$]*$""")
private val COALESCE_TOKENS = setOf("??", "?:")
private val PRIMARY_CONSTRUCTOR =
    Regex(
        """\b(?:class|record)\s+\w+(?:<[^>]*>)?(?:\s*@[A-Za-z_][\w.]*(?:\s*\([^)]*\))?)*\s*(?:(?:public|private|protected|internal)\s+)?(?:constructor\s*)?\(([^)]*)\)""",
    )
private val CLASS_NAME = Regex("""\b(?:class|object|interface|record|struct)\s+([A-Za-z_][A-Za-z0-9_]*)""")
private val FUNCTION_PARAMETERS = Regex("""^[^(]*\(([^)]*)\)""")

private fun annotationName(text: String): String =
    text
        .trim()
        .trim('[', ']')
        .removePrefix("@")
        .substringBefore('(')
        .trim()
        .substringAfterLast('.')
        .substringAfterLast(':')

private fun tokenize(value: String): List<String> {
    val tokens = mutableListOf<String>()
    val current = StringBuilder()

    fun flush() {
        if (current.isNotEmpty()) {
            tokens += current.toString().lowercase()
            current.clear()
        }
    }
    for (index in value.indices) {
        val character = value[index]
        if (!character.isLetterOrDigit()) {
            flush()
            continue
        }
        if (current.isNotEmpty()) {
            val previous = value[index - 1]
            val next = value.getOrNull(index + 1)
            if ((previous.isLowerCase() || previous.isDigit()) && character.isUpperCase()) flush()
            if (previous.isUpperCase() && character.isUpperCase() && next != null && next.isLowerCase()) flush()
        }
        current.append(character)
    }
    flush()
    return tokens
}

private fun splitParameters(parameters: String): List<String> {
    val result = mutableListOf<String>()
    var angle = 0
    var parens = 0
    var brackets = 0
    var braces = 0
    var start = 0
    for (index in parameters.indices) {
        when (parameters[index]) {
            '<' -> {
                angle++
            }

            '>' -> {
                if (angle > 0) angle--
            }

            '(' -> {
                parens++
            }

            ')' -> {
                if (parens > 0) parens--
            }

            '[' -> {
                brackets++
            }

            ']' -> {
                if (brackets > 0) brackets--
            }

            '{' -> {
                braces++
            }

            '}' -> {
                if (braces > 0) braces--
            }

            ',' -> {
                if (angle == 0 && parens == 0 && brackets == 0 && braces == 0) {
                    result += parameters.substring(start, index).trim()
                    start = index + 1
                }
            }
        }
    }
    result += parameters.substring(start).trim()
    return result.filter { it.isNotEmpty() }
}

private fun parameterText(text: String): String? {
    val open = text.indexOf('(')
    if (open < 0) return null
    var depth = 0
    for (index in open until text.length) {
        when (text[index]) {
            '(' -> {
                depth++
            }

            ')' -> {
                depth--
                if (depth == 0) return text.substring(open + 1, index)
            }
        }
    }
    return null
}

private fun trimmedDeclarationEnd(node: SyntaxNode): Int {
    val text = node.text
    return if (text.trimEnd().endsWith(";")) node.endByte - 1 else node.endByte
}

private fun trimmedDeclarationSpan(node: SyntaxNode): Span {
    val end = trimmedDeclarationEnd(node)
    return if (end == node.endByte) {
        node.span
    } else {
        Span(node.startLine, node.startCol, node.endLine, (node.endCol - 1).coerceAtLeast(0))
    }
}

private fun conditionNames(text: String): List<String> =
    Regex("""[A-Za-z_$][A-Za-z0-9_$]*""")
        .findAll(text)
        .map { it.value }
        .distinct()
        .toList()

private fun trimmedConditionStart(
    text: String,
    startByte: Int,
    startCol: Int,
): Pair<Int, Int> {
    var value = text.trim()
    var byte = startByte
    var col = startCol
    while (value.startsWith("(") && value.endsWith(")")) {
        value = value.substring(1, value.length - 1).trim()
        byte++
        col++
    }
    return byte to col
}

private fun trimmedConditionEnd(
    text: String,
    endByte: Int,
    endCol: Int,
): Pair<Int, Int> {
    var value = text.trim()
    var byte = endByte
    var col = endCol
    while (value.startsWith("(") && value.endsWith(")")) {
        value = value.substring(1, value.length - 1).trim()
        byte--
        col--
    }
    return byte to col
}

private fun parameterName(parameter: String): String? =
    parameter
        .substringBefore(':')
        .substringBefore('=')
        .trim()
        .split(Regex("\\s+"))
        .lastOrNull()
        ?.takeIf { it.isNotBlank() }

private fun parameterDefault(parameter: String): String? {
    val assignment = parameter.indexOf('=')
    if (assignment < 0) return null
    return parameter.substring(assignment + 1).trim().ifEmpty { null }
}

private fun parameterType(parameter: String): String? {
    val assignment = parameter.indexOf('=')
    val signature = if (assignment >= 0) parameter.substring(0, assignment) else parameter
    val colon = signature.indexOf(':')
    if (colon >= 0) return signature.substring(colon + 1).trim().ifEmpty { null }
    return Regex("""^(.+?)\s+[A-Za-z_][A-Za-z0-9_]*$""")
        .find(signature.trim())
        ?.groupValues
        ?.get(1)
        ?.trim()
        ?.ifEmpty { null }
}

private fun typeNames(type: String?): List<String> {
    if (type.isNullOrBlank()) return emptyList()
    return Regex("""[A-Za-z_][A-Za-z0-9_.]*""").findAll(type).map { it.value }.toList()
}

private fun returnLiteral(text: String): Boolean {
    val value = text.trim()
    if (value == "true" || value in REJECTION_TEXTS) return true
    if (value.startsWith("\"") || value.startsWith("'")) return true
    if (value.startsWith("`")) return !value.contains("\${")
    return value.toLongOrNull() != null || value.toDoubleOrNull() != null
}

private fun isFunctionTyped(
    type: String?,
    default: String?,
): Boolean {
    val declared = type.orEmpty()
    val functionMarkers =
        listOf(
            "->",
            "=>",
            "function",
            "Func<",
            "Action<",
            "Consumer<",
            "Supplier<",
            "Predicate<",
            "Callable<",
            "Runnable",
            "BiFunction",
            "BiConsumer",
            "UnaryOperator",
            "Function<",
            "BiPredicate<",
        )
    if (functionMarkers.any { declared.contains(it) }) return true
    val defaultMarkers = setOf("::", "=>", "->", "{")
    return defaultMarkers.any { default.orEmpty().contains(it) }
}

private fun referencedPaths(value: String?): List<String> {
    if (value.isNullOrBlank()) return emptyList()
    return Regex("""[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+""")
        .findAll(value.replace("::", "."))
        .map { it.value }
        .toList()
}

private fun trimmedSpan(node: SyntaxNode): Span {
    var value = node.text.trim()
    var start = node.startByte
    var startCol = node.startCol
    var end = node.endByte
    var endCol = node.endCol
    while (value.startsWith("(") && value.endsWith(")")) {
        value = value.substring(1, value.length - 1).trim()
        start++
        startCol++
        end--
        endCol--
    }
    return Span(node.startLine, startCol, node.endLine, endCol)
}

private fun isConstantCondition(text: String): Boolean {
    val value = text.trim()
    if (value == "true" || value == "false") return true
    return CONSTANT_COMPARISON.matches(value)
}

private fun withinNode(
    node: SyntaxNode,
    container: SyntaxNode,
): Boolean = node.startByte >= container.startByte && node.endByte <= container.endByte

private fun expressionSpan(
    statement: SyntaxNode,
    expression: SyntaxNode,
): Span? {
    val statementText = statement.text
    val expressionText = expression.text
    val index = statementText.indexOf(expressionText)
    if (index < 0) return null
    val consumed = statementText.substring(0, index + expressionText.length)
    val lines = consumed.split('\n')
    return Span(
        statement.startLine,
        statement.startCol,
        statement.startLine + lines.size - 1,
        if (lines.size == 1) statement.startCol + consumed.length else lines.last().length,
    )
}

internal object SyntaxFactExtractor {
    fun extract(
        tree: SyntaxTree,
        queryIds: List<String>,
        profileId: String = tree.language,
    ): ContractResult<FactBatch> {
        val requested =
            if (queryIds.isEmpty()) {
                FactCategory.entries.toSet()
            } else {
                val parsed = mutableSetOf<FactCategory>()
                for (query in queryIds) {
                    val category =
                        FactCategory.from(query)
                            ?: return ResultFactory.failure(
                                ContractError.InvalidInput("Unknown syntax query id '$query'", query),
                            )
                    parsed += category
                }
                parsed
            }
        val profile =
            Profiles.forProfile(profileId) ?: Profiles.forProfile(tree.language)
                ?: return ResultFactory.failure(
                    ContractError.InvalidInput(
                        "no syntax fact profile is registered for '${tree.language}'",
                        tree.language,
                    ),
                )
        val nodes = tree.nodes.values.sortedWith(compareBy<SyntaxNode> { it.startByte }.thenBy { it.endByte })
        val byId = tree.nodes
        val children = tree.nodes.values.groupBy { it.parentId }

        fun childrenOf(node: SyntaxNode): List<SyntaxNode> = node.childIds.mapNotNull(byId::get)

        fun descendantsOf(node: SyntaxNode): Sequence<SyntaxNode> =
            sequence {
                for (child in childrenOf(node)) {
                    yield(child)
                    yieldAll(descendantsOf(child))
                }
            }

        fun ancestorsOf(node: SyntaxNode): Sequence<SyntaxNode> =
            sequence {
                var parent = node.parentId?.let(byId::get)
                while (parent != null) {
                    yield(parent)
                    parent = parent.parentId?.let(byId::get)
                }
            }

        fun firstIdentifier(
            node: SyntaxNode,
            skipAnnotations: Boolean = false,
        ): SyntaxNode? =
            descendantsOf(node).firstOrNull { candidate ->
                candidate.kind in profile.identifierKinds &&
                    (
                        !skipAnnotations ||
                            ancestorsOf(candidate).none { ancestor -> ancestor.kind in profile.annotationKinds }
                    )
            }

        fun bodyOf(node: SyntaxNode): SyntaxNode? =
            childrenOf(node).firstOrNull { it.kind in profile.bodyKinds }
                ?: descendantsOf(node).firstOrNull { it.kind in profile.bodyKinds && it.kind !in profile.lambdaKinds }

        fun calleeName(node: SyntaxNode): String? =
            if (node.kind in profile.navigationKinds) {
                descendantsOf(node)
                    .filter { it.kind in profile.identifierKinds }
                    .maxByOrNull { it.startByte }
                    ?.text
            } else {
                firstIdentifier(node)?.text ?: node.text
                    .substringAfterLast('.')
                    .substringBefore('(')
                    .trim()
            }

        fun parameterCount(node: SyntaxNode): Int? {
            val bodyStart = bodyOf(node)?.startByte ?: node.endByte
            val count =
                descendantsOf(node).count {
                    it.startByte < bodyStart &&
                        it.kind.contains("parameter") &&
                        !it.kind.contains("type_parameter") &&
                        !it.kind.endsWith("_parameters")
                }
            return count.takeIf { it > 0 }
        }

        fun suffixArguments(suffix: SyntaxNode): Int {
            val explicitList =
                childrenOf(suffix).firstOrNull {
                    it.kind in setOf("value_arguments", "arguments", "argument_list")
                }
            val list = explicitList ?: suffix
            val arguments =
                childrenOf(list).count { child ->
                    val text = child.text.trim()
                    text != "(" && text != ")" && text != "," &&
                        child.kind !in setOf("type_argument_list", "type_arguments", "comment")
                }
            val trailingLambdas =
                if (explicitList != null) {
                    childrenOf(suffix).count { it.kind in profile.lambdaKinds }
                } else {
                    0
                }
            return arguments + trailingLambdas
        }

        fun argumentCount(call: SyntaxNode): Int? {
            val direct = childrenOf(call).filter { it.kind in profile.callSuffixKinds }.sumOf(::suffixArguments)
            val nestedCallee = childrenOf(call).firstOrNull { it.kind in profile.callKinds }
            val nested = nestedCallee?.let { argumentCount(it) } ?: 0
            return direct + nested
        }

        fun declaringType(node: SyntaxNode): String? =
            ancestorsOf(node)
                .firstOrNull { it.kind in profile.classKinds }
                ?.let { classNode ->
                    childrenOf(classNode).firstOrNull { it.kind in profile.identifierKinds }?.text
                        ?: firstIdentifier(classNode)?.text
                        ?: classNode.text.substringBefore('{').trim()
                }?.takeIf { it.isNotBlank() }

        fun signature(node: SyntaxNode): String? {
            val body = bodyOf(node)?.text?.takeIf { it.isNotBlank() } ?: return node.text
            val index = node.text.indexOf(body)
            return if (index > 0) node.text.substring(0, index).trimEnd() else node.text
        }

        val functionNodes = nodes.filter { it.kind in profile.functionKinds }
        val propertyNodes = nodes.filter { it.kind in profile.propertyKinds }
        val variableNodes =
            nodes
                .filter { it.kind in profile.variableKinds }
                .filter { variable ->
                    propertyNodes.none { property ->
                        variable.startByte >= property.startByte && variable.endByte <= property.endByte
                    }
                }
        val classNodes = nodes.filter { it.kind in profile.classKinds }
        val lambdaNodes = nodes.filter { it.kind in profile.lambdaKinds }
        val scopeNodes =
            buildList {
                tree.getNode(tree.rootId)?.let { add(it) }
                for (node in nodes) {
                    if (node.kind in profile.scopeKinds && node.id != tree.rootId) add(node)
                }
            }
        val ifNodes = nodes.filter { it.kind in profile.ifKinds }
        val loopNodes = nodes.filter { it.kind in profile.loopKinds }
        val tryNodes = nodes.filter { it.kind in profile.tryKinds }
        val catchNodes = nodes.filter { it.kind in profile.catchKinds }
        val finallyNodes = nodes.filter { it.kind in profile.finallyKinds }
        val returnNodes = nodes.filter { it.kind in profile.returnKinds }

        fun jumpKind(text: String): JumpKind? {
            val value = text.trimStart()
            return when {
                value.startsWith("return@") -> JumpKind.RETURN
                value.startsWith("return") -> JumpKind.RETURN
                value.startsWith("throw") || value.startsWith("raise") -> JumpKind.THROW
                value.startsWith("continue") -> JumpKind.CONTINUE
                value.startsWith("break") -> JumpKind.BREAK
                else -> null
            }
        }

        fun isThrowNode(node: SyntaxNode): Boolean {
            val value = node.text.trimStart()
            return node.kind in setOf("throw_expression", "throw_statement", "raise_statement") ||
                value.startsWith("throw ") ||
                value.startsWith("raise ")
        }

        val exitNodes =
            nodes
                .filter { node ->
                    node.kind in profile.returnKinds ||
                        isThrowNode(node) ||
                        (
                            (node.kind.contains("statement") || node.kind.contains("expression")) &&
                                jumpKind(node.text) in setOf(JumpKind.CONTINUE, JumpKind.BREAK)
                        )
                }.distinctBy { it.id }

        fun returnExpression(node: SyntaxNode): SyntaxNode? =
            childrenOf(node).firstOrNull {
                it.text.trim() != "return" &&
                    it.text.trim() != ";" &&
                    it.text.trim() != "throw" &&
                    it.text.trim() != "raise" &&
                    !it.text.trim().startsWith("return@")
            }

        fun rejectionValue(expression: SyntaxNode?): Boolean {
            if (expression == null) return true
            val text = expression.text.trim()
            return text in REJECTION_TEXTS
        }

        val exits =
            if (FactCategory.RETURNS in requested || FactCategory.GUARDS in requested) {
                exitNodes.mapNotNull { node ->
                    val kind =
                        jumpKind(node.text)
                            ?: if (node.kind in profile.returnKinds) {
                                JumpKind.RETURN
                            } else {
                                null
                                    ?: return@mapNotNull null
                            }
                    val expression = if (kind == JumpKind.RETURN) returnExpression(node) else null
                    ExitFact(
                        nodeId = node.id,
                        kind = kind,
                        expressionId = expression?.id,
                        rejection =
                            when (kind) {
                                JumpKind.CONTINUE, JumpKind.BREAK, JumpKind.THROW -> true
                                JumpKind.RETURN -> rejectionValue(expression)
                            },
                        guardIds =
                            ancestorsOf(node)
                                .filter { it.kind in profile.ifKinds || it.kind in profile.loopKinds }
                                .map { it.id }
                                .toList(),
                        loopId = ancestorsOf(node).firstOrNull { it.kind in profile.loopKinds }?.id,
                        handlerId = ancestorsOf(node).firstOrNull { it.kind in profile.catchKinds }?.id,
                    )
                }
            } else {
                emptyList()
            }

        fun exitsWithin(body: SyntaxNode?): List<String> =
            body
                ?.let { container ->
                    exits.filter { exit -> byId[exit.nodeId]?.let { withinNode(it, container) } == true }.map { it.nodeId }
                }.orEmpty()

        val scopes =
            if (FactCategory.SCOPES in requested) {
                scopeNodes.map { node ->
                    val kind =
                        when {
                            node.id == tree.rootId -> ScopeKind.FILE
                            node.kind in profile.classKinds -> ScopeKind.CLASS
                            node.kind in profile.functionKinds -> ScopeKind.FUNCTION
                            node.kind in profile.lambdaKinds -> ScopeKind.LAMBDA
                            else -> ScopeKind.BLOCK
                        }
                    ScopeFact(node.id, kind, ownerId = ancestorsOf(node).firstOrNull { it.kind in profile.scopeKinds }?.id)
                }
            } else {
                emptyList()
            }

        val scopeIds = scopeNodes.mapTo(mutableSetOf()) { it.id }

        fun owningScope(node: SyntaxNode): String? = ancestorsOf(node).firstOrNull { it.id in scopeIds }?.id

        fun parameterNode(
            container: SyntaxNode,
            parameter: String,
        ): String? {
            val target = parameter.trim()
            return descendantsOf(container)
                .filter { it.text.trim() == target }
                .minByOrNull { it.endByte - it.startByte }
                ?.id
                ?: container.id
        }

        fun parametersOf(
            container: SyntaxNode,
            parameters: String,
        ): List<ParameterFact> =
            splitParameters(parameters).map { parameter ->
                val type = parameterType(parameter)
                val default = parameterDefault(parameter)
                ParameterFact(
                    nodeId = parameterNode(container, parameter),
                    name = parameterName(parameter),
                    type = type,
                    typeNames = typeNames(type),
                    defaultValue = default,
                    functionTyped = isFunctionTyped(type, default),
                    referencedPaths = referencedPaths(default),
                )
            }

        fun className(node: SyntaxNode): String? = CLASS_NAME.find(node.text)?.groupValues?.get(1)

        fun constructorParameters(node: SyntaxNode): List<ParameterFact> {
            val className = className(node) ?: return emptyList()
            val name = firstIdentifier(node, skipAnnotations = true)?.text
            val parameters =
                if (name == className) {
                    parameterText(node.text.removePrefix("constructor"))
                } else {
                    PRIMARY_CONSTRUCTOR.find(node.text)?.groupValues?.get(1)
                } ?: return emptyList()
            return parametersOf(node, parameters)
        }

        val functions =
            if (FactCategory.FUNCTIONS in requested) {
                functionNodes.map { node ->
                    val nameNode =
                        firstIdentifier(node, skipAnnotations = true)
                            ?.takeIf { it.startByte < (bodyOf(node)?.startByte ?: Int.MAX_VALUE) }
                    val functionName = nameNode?.text
                    val owner = declaringType(node)
                    val constructor =
                        node.kind.contains("constructor") ||
                            (functionName != null && (functionName == owner || functionName == "constructor"))
                    val parameters = parametersOf(node, parameterText(node.text).orEmpty())
                    FunctionFact(
                        nodeId = node.id,
                        name = functionName,
                        nameNodeId = nameNode?.id,
                        bodyId = bodyOf(node)?.id,
                        declaringType = owner,
                        signature = signature(node),
                        arity = parameterCount(node),
                        parameters = parameters,
                        constructor = constructor,
                    )
                }
            } else {
                emptyList()
            }

        val classes =
            if (FactCategory.FUNCTIONS in requested || FactCategory.DECLARATIONS in requested) {
                classNodes.map { node ->
                    ClassFact(
                        nodeId = node.id,
                        name = className(node),
                        constructorParameters = constructorParameters(node),
                    )
                }
            } else {
                emptyList()
            }

        fun declarationNameNode(node: SyntaxNode): SyntaxNode? =
            if (node.kind in profile.propertyKinds) {
                descendantsOf(node).firstOrNull { candidate ->
                    candidate.kind in profile.identifierKinds &&
                        ancestorsOf(candidate).none { it.kind in profile.annotationKinds }
                } ?: firstIdentifier(node, skipAnnotations = true)
            } else {
                firstIdentifier(node, skipAnnotations = true)
            }

        fun isTypeReference(node: SyntaxNode): Boolean {
            if (node.kind in TYPE_REFERENCE_KINDS) return true
            return ancestorsOf(node).any { it.kind in TYPE_CONTEXT_KINDS }
        }

        val properties =
            if (FactCategory.PROPERTIES in requested) {
                (propertyNodes + variableNodes).map { node ->
                    val nameNode = declarationNameNode(node)
                    val initializer =
                        childrenOf(node).lastOrNull {
                            it.id != nameNode?.id && it.kind !in profile.bodyKinds && it.text != "="
                        }
                    PropertyFact(
                        node.id,
                        nameNode?.text,
                        nameNode?.id,
                        initializer?.id,
                        initializerIdentifier = initializer?.text?.trim()?.takeIf { SIMPLE_IDENTIFIER.matches(it) },
                    )
                }
            } else {
                emptyList()
            }

        val annotationsFor: (SyntaxNode) -> Pair<List<String>, List<String>> = { node ->
            val body = bodyOf(node)
            val found =
                descendantsOf(node)
                    .filter { it.kind in profile.annotationKinds }
                    .filter { body == null || it.startByte < body.startByte }
                    .toList()
            found.map { it.text } to found.map { it.id }
        }

        fun scopeKindOf(id: String?): ScopeKind? {
            val scope = id?.let { scopeId -> scopeNodes.firstOrNull { it.id == scopeId } } ?: return null
            return when {
                scope.id == tree.rootId -> ScopeKind.FILE
                scope.kind in profile.classKinds || scope.kind == "class_body" -> ScopeKind.CLASS
                scope.kind in profile.functionKinds -> ScopeKind.FUNCTION
                scope.kind in profile.lambdaKinds -> ScopeKind.LAMBDA
                else -> ScopeKind.BLOCK
            }
        }

        fun declarationKind(node: SyntaxNode): DeclarationKind =
            when {
                node.kind in profile.functionKinds -> DeclarationKind.FUNCTION

                scopeKindOf(owningScope(node)) in
                    setOf(ScopeKind.FUNCTION, ScopeKind.LAMBDA, ScopeKind.BLOCK) -> DeclarationKind.VARIABLE

                else -> DeclarationKind.PROPERTY
            }

        val writes =
            if (FactCategory.WRITES in requested) {
                nodes.mapNotNull { node ->
                    when {
                        node.kind in profile.assignmentKinds -> {
                            val target = childrenOf(node).firstOrNull()
                            val value =
                                childrenOf(node).lastOrNull { child ->
                                    child.id != target?.id && child.text.trim() !in ASSIGNMENT_OPERATORS
                                }
                            WriteFact(
                                node.id,
                                WriteKind.ASSIGNMENT,
                                target?.id,
                                valueId = value?.id,
                                targetName =
                                    target
                                        ?.text
                                        ?.substringBefore(
                                            '=',
                                        )?.substringBefore('+')
                                        ?.substringBefore('-')
                                        ?.trim()
                                        ?.substringAfterLast('.')
                                        ?.trim(),
                                valueIdentifier = value?.text?.trim()?.takeIf { SIMPLE_IDENTIFIER.matches(it) },
                                inLoopHeader = false,
                            )
                        }

                        node.kind in profile.incrementKinds && (node.text.contains("++") || node.text.contains("--")) -> {
                            val target = childrenOf(node).firstOrNull { child -> child.text.trim() !in setOf("++", "--") }
                            WriteFact(
                                node.id,
                                WriteKind.INCREMENT,
                                target?.id,
                                targetName =
                                    target
                                        ?.text
                                        ?.trim()
                                        ?.substringAfterLast('.')
                                        ?.trim(),
                                inLoopHeader = node.parentId?.let(byId::get)?.kind in profile.loopKinds,
                                loopIncrement =
                                    node.parentId?.let(byId::get)?.kind in profile.loopKinds &&
                                        node.text.contains("++"),
                            )
                        }

                        else -> {
                            null
                        }
                    }
                }
            } else {
                emptyList()
            }

        val declarations =
            if (FactCategory.DECLARATIONS in requested) {
                (functionNodes + propertyNodes + variableNodes).distinctBy { it.id }.map { node ->
                    val nameNode = declarationNameNode(node)?.takeIf { it.startByte < (bodyOf(node)?.startByte ?: Int.MAX_VALUE) }
                    val (annotationTexts, annotationIds) = annotationsFor(node)
                    DeclarationFact(
                        nodeId = node.id,
                        kind = declarationKind(node),
                        name = nameNode?.text,
                        nameNodeId = nameNode?.id,
                        annotations = annotationTexts,
                        annotationNodeIds = annotationIds,
                        annotationNames = annotationTexts.map(::annotationName),
                        scopeId = owningScope(node),
                        writeIds =
                            writes
                                .filter { write ->
                                    byId[write.nodeId]?.let {
                                        it.startByte >= node.startByte &&
                                            it.endByte <= node.endByte
                                    } ==
                                        true
                                }.map { it.nodeId },
                        trimmedEndSpan = trimmedDeclarationSpan(node),
                        trimmedEndByte = trimmedDeclarationEnd(node),
                    )
                }
            } else {
                emptyList()
            }

        val comments =
            if (FactCategory.COMMENTS in requested) {
                nodes.filter { it.kind in profile.commentKinds }.map { node ->
                    val text = node.text.trim()
                    val kind =
                        when {
                            text.startsWith("/**") || text.startsWith("///") -> CommentKind.DOC
                            text.startsWith("//") || text.startsWith("#") -> CommentKind.LINE
                            text.startsWith("/*") || text.startsWith("<!--") -> CommentKind.BLOCK
                            else -> CommentKind.OTHER
                        }
                    val magic = if (text.contains("cint:")) text else null
                    val directives = DIRECTIVE.findAll(text).map { it.groupValues[1].lowercase() }.toList()
                    CommentFact(node.id, magic, kind, directives)
                }
            } else {
                emptyList()
            }

        val calls =
            if (FactCategory.CALLS in requested) {
                nodes.filter { it.kind in profile.callKinds }.map { node ->
                    val callChildren = childrenOf(node)
                    val callee = callChildren.firstOrNull { it.kind !in profile.callSuffixKinds } ?: callChildren.firstOrNull()
                    val receiver =
                        callee?.takeIf { it.kind in profile.navigationKinds }?.let { navigation ->
                            if (profile.receiverIsFirstChild) {
                                childrenOf(navigation).firstOrNull()
                            } else {
                                childrenOf(navigation).firstOrNull { it.kind !in profile.memberKinds }
                                    ?: childrenOf(navigation).firstOrNull()
                            }
                        }
                    val trailingLambda =
                        callChildren.asReversed().firstOrNull { it.kind in profile.lambdaKinds }
                            ?: descendantsOf(node).lastOrNull { it.kind in profile.lambdaKinds }
                    val calleeText = callee?.let { calleeName(it) }
                    val receiverPath = receiver?.text?.trim()?.takeIf { it.isNotEmpty() }
                    val receiverSegments =
                        receiverPath
                            ?.split('.')
                            ?.map { it.substringBefore('(').substringBefore('[').trim() }
                            ?.filter { it.isNotEmpty() }
                            .orEmpty()
                    val receiverName = receiverSegments.lastOrNull()
                    val callPath = callee?.text.orEmpty()
                    CallFact(
                        nodeId = node.id,
                        siteId = callChildren.firstOrNull { it.kind in profile.callSuffixKinds }?.id ?: node.id,
                        callee = calleeText,
                        receiverId = receiver?.id,
                        path = callee?.text,
                        trailingLambdaId = trailingLambda?.id,
                        arity = argumentCount(node),
                        calleeTokens = tokenize(calleeText.orEmpty()),
                        receiverPath = receiverPath,
                        receiverSegments = receiverSegments,
                        receiverName = receiverName,
                        receiverLeaf = receiverName?.trim('_'),
                        pathSegments = tokenize(callPath),
                        resolvedTarget = callPath,
                    )
                }
            } else {
                emptyList()
            }

        fun conditionOf(node: SyntaxNode): SyntaxNode? =
            childrenOf(node).firstOrNull {
                it.kind !in profile.conditionSkipKinds &&
                    !it.text.trim().let { text -> text == "if" || text == "while" || text == "for" || text == "(" || text == ")" }
            }

        fun bodiesOf(node: SyntaxNode): List<SyntaxNode> = childrenOf(node).filter { it.kind in profile.bodyKinds }

        val ifs =
            if (FactCategory.IFS in requested) {
                ifNodes.map { node ->
                    val bodies = bodiesOf(node)
                    IfFact(
                        nodeId = node.id,
                        conditionId = conditionOf(node)?.id,
                        thenBodyId = bodies.getOrNull(0)?.id,
                        elseBodyId = bodies.getOrNull(1)?.id,
                        elseKeywordId = childrenOf(node).firstOrNull { it.text.trim() == "else" }?.id,
                    )
                }
            } else {
                emptyList()
            }

        val guards =
            if (FactCategory.GUARDS in requested) {
                ifNodes.map { node ->
                    val condition = conditionOf(node)
                    GuardFact(
                        node.id,
                        GuardKind.CONDITIONAL,
                        condition?.id,
                        bodiesOf(node).getOrNull(0)?.id,
                        bodiesOf(node).getOrNull(1)?.id,
                        exitIds = exitsWithin(bodiesOf(node).getOrNull(0)) + exitsWithin(bodiesOf(node).getOrNull(1)),
                        conditionSpan = condition?.let(::trimmedSpan),
                        conditionNames = condition?.let { conditionNames(it.text) }.orEmpty(),
                        conditionStartByte =
                            condition?.let { trimmedConditionStart(it.text, it.startByte, it.startCol).first },
                        conditionEndByte =
                            condition?.let { trimmedConditionEnd(it.text, it.endByte, it.endCol).first },
                        conditionConstant = condition?.let { isConstantCondition(it.text) } == true,
                        conditionBoolean =
                            condition?.text?.trim()?.let { text ->
                                if (text ==
                                    "true"
                                ) {
                                    true
                                } else if (text == "false") {
                                    false
                                } else {
                                    null
                                }
                            },
                    )
                } +
                    loopNodes.map { node ->
                        val condition = conditionOf(node)
                        GuardFact(
                            node.id,
                            GuardKind.LOOP,
                            condition?.id,
                            bodiesOf(node).firstOrNull()?.id,
                            null,
                            exitIds = exitsWithin(bodiesOf(node).firstOrNull()),
                            conditionSpan = condition?.let(::trimmedSpan),
                            conditionNames = condition?.let { conditionNames(it.text) }.orEmpty(),
                            conditionStartByte =
                                condition?.let { trimmedConditionStart(it.text, it.startByte, it.startCol).first },
                            conditionEndByte =
                                condition?.let { trimmedConditionEnd(it.text, it.endByte, it.endCol).first },
                            conditionConstant = condition?.let { isConstantCondition(it.text) } == true,
                            conditionBoolean =
                                condition?.text?.trim()?.let { text ->
                                    if (text ==
                                        "true"
                                    ) {
                                        true
                                    } else if (text == "false") {
                                        false
                                    } else {
                                        null
                                    }
                                },
                        )
                    }
            } else {
                emptyList()
            }

        val returns =
            if (FactCategory.RETURNS in requested) {
                returnNodes.map { node ->
                    val kind = jumpKind(node.text) ?: JumpKind.RETURN
                    val expression = if (kind == JumpKind.RETURN) returnExpression(node) else null
                    ReturnFact(
                        nodeId = node.id,
                        expressionId = expression?.id,
                        guardIds =
                            ancestorsOf(
                                node,
                            ).filter { it.kind in profile.ifKinds || it.kind in profile.loopKinds }.map { it.id }.toList(),
                        loopId = ancestorsOf(node).firstOrNull { it.kind in profile.loopKinds }?.id,
                        handlerId = ancestorsOf(node).firstOrNull { it.kind in profile.catchKinds }?.id,
                        scopeId = owningScope(node),
                        jumpKind = kind,
                        rejection = rejectionValue(expression),
                        labeled = BARE_LABELED_RETURN.matches(node.text.trim()),
                        expressionSpan = expression?.let { expressionSpan(node, it) },
                    )
                }
            } else {
                emptyList()
            }

        val tries =
            if (FactCategory.TRIES in requested) {
                tryNodes.map { node ->
                    val catches = descendantsOf(node).filter { it.kind in profile.catchKinds }.map { it.id }.toList()
                    val finallyBlock = descendantsOf(node).firstOrNull { it.kind in profile.finallyKinds }
                    TryFact(
                        nodeId = node.id,
                        bodyId = bodyOf(node)?.id,
                        catchIds = catches,
                        finallyBlockId = finallyBlock?.id,
                        headerStartId = childrenOf(node).firstOrNull { it.text.trim() == "try" }?.id,
                        headerEndId =
                            childrenOf(node).firstOrNull { it.text.trim() == "{" }?.id
                                ?: bodyOf(node)?.let { body -> childrenOf(body).firstOrNull { it.text.trim() == "{" }?.id },
                    )
                }
            } else {
                emptyList()
            }

        fun isCommentKind(node: SyntaxNode): Boolean = node.kind in profile.commentKinds

        fun statementKind(node: SyntaxNode): dev.cint.contracts.facts.StatementKind {
            val text = node.text.trimStart()
            return when {
                text.startsWith("return") -> dev.cint.contracts.facts.StatementKind.RETURN
                text.startsWith("continue") -> dev.cint.contracts.facts.StatementKind.CONTINUE
                text.startsWith("break") -> dev.cint.contracts.facts.StatementKind.BREAK
                else -> dev.cint.contracts.facts.StatementKind.STATEMENT
            }
        }

        fun statementExpression(node: SyntaxNode): SyntaxNode? {
            if (node.kind !in profile.statementWrapperKinds) return null
            return childrenOf(node).firstOrNull {
                !isCommentKind(it) && it.text.trim() != ";"
            }
        }

        val statements =
            if (FactCategory.RETURNS in requested || FactCategory.IFS in requested || FactCategory.TRIES in requested) {
                nodes
                    .filter { it.kind in profile.statementListKinds }
                    .flatMap { list ->
                        childrenOf(list)
                            .filterNot { child ->
                                isCommentKind(child) ||
                                    child.text.trim() == "{" ||
                                    child.text.trim() == "}"
                            }.mapNotNull { child ->
                                val kind = statementKind(child)
                                if (child.text.isBlank()) return@mapNotNull null
                                dev.cint.contracts.facts.StatementFact(
                                    nodeId = child.id,
                                    containerId = list.id,
                                    expressionId =
                                        if (kind == dev.cint.contracts.facts.StatementKind.RETURN) {
                                            child.childIds
                                                .mapNotNull(byId::get)
                                                .filterNot { it.text.trim() == "return" || isCommentKind(it) }
                                                .lastOrNull()
                                                ?.id
                                        } else {
                                            statementExpression(child)?.id
                                        },
                                    kind = kind,
                                )
                            }
                    }
            } else {
                emptyList()
            }

        val values =
            if (FactCategory.RETURNS in requested || FactCategory.IFS in requested || FactCategory.TRIES in requested) {
                nodes.filter { it.parentId != null }.map { node ->
                    val subtree = sequenceOf(node) + descendantsOf(node)
                    val constant = subtree.none { child -> child.kind in profile.referenceKinds }
                    val sideEffectFree =
                        subtree.none { child ->
                            child.kind in profile.effectKinds ||
                                profile.effectFragments.any { fragment -> child.kind.contains(fragment) } ||
                                (child.kind in profile.incrementKinds && (child.text.contains("++") || child.text.contains("--")))
                        }
                    val text = node.text.trim()
                    val interpolatedTemplate = text.startsWith("`") && text.contains("\${")
                    val literal = (!interpolatedTemplate && node.kind in profile.literalKinds) || text in REJECTION_TEXTS
                    val valueKind =
                        when {
                            literal -> ValueKind.LITERAL
                            node.kind in profile.callKinds -> ValueKind.CALL
                            node.kind in profile.identifierKinds -> ValueKind.IDENTIFIER
                            else -> ValueKind.OTHER
                        }
                    ValueFact(
                        nodeId = node.id,
                        kind = valueKind,
                        constant = constant,
                        sideEffectFree = sideEffectFree,
                        rejection = literal && text in REJECTION_TEXTS,
                        booleanValue = if (literal && text in setOf("true", "false")) text == "true" else null,
                        literalText = text.takeIf { literal },
                        returnLiteral = returnLiteral(text),
                        identifier =
                            text.takeIf {
                                valueKind == ValueKind.IDENTIFIER && SIMPLE_IDENTIFIER.matches(it)
                            },
                    )
                }
            } else {
                emptyList()
            }

        val coalesces =
            if (FactCategory.GUARDS in requested || FactCategory.RETURNS in requested) {
                nodes.mapNotNull { node ->
                    val isCoalesce =
                        node.kind == "elvis_expression" ||
                            (
                                node.kind == "binary_expression" &&
                                    childrenOf(node).any { it.text.trim() in COALESCE_TOKENS }
                            )
                    if (!isCoalesce) return@mapNotNull null
                    val children = childrenOf(node)
                    val left = children.firstOrNull { it.text.trim() !in COALESCE_TOKENS }
                    val right = children.lastOrNull { it.text.trim() !in COALESCE_TOKENS && it.id != left?.id }
                    CoalesceFact(node.id, left?.id, right?.id)
                }
            } else {
                emptyList()
            }

        val creations =
            if (FactCategory.CALLS in requested || FactCategory.FUNCTIONS in requested) {
                nodes.mapNotNull { node ->
                    val text = node.text.trim()
                    if (!text.startsWith("new ")) return@mapNotNull null
                    val type =
                        text
                            .removePrefix("new ")
                            .substringBefore('(')
                            .substringBefore('<')
                            .trim()
                    type.takeIf { it.isNotEmpty() }?.let { CreationFact(node.id, it, typeNames(it)) }
                }
            } else {
                emptyList()
            }

        val imports =
            if (FactCategory.IMPORTS in requested) {
                nodes.filter { it.kind in profile.importKinds }.map { node ->
                    val text = node.text
                    val path =
                        text
                            .removePrefix("import")
                            .removePrefix("using")
                            .removePrefix("#include")
                            .trim()
                    val alias = text.split(Regex("\\s+as\\s+")).getOrNull(1)?.trim()
                    ImportFact(node.id, path, alias, tokenize(path))
                }
            } else {
                emptyList()
            }

        val references =
            if (FactCategory.REFERENCES in requested) {
                nodes
                    .filter { it.kind in profile.identifierKinds && !isTypeReference(it) }
                    .groupBy { it.text }
                    .map { (name, matches) -> ReferenceFact(name, matches.map { it.id }) }
                    .sortedBy { it.name }
            } else {
                emptyList()
            }

        return ResultFactory.success(
            FactBatch(
                syntaxTree = tree,
                calls = calls,
                writes = writes,
                declarations = declarations,
                comments = comments,
                scopes = scopes,
                guards = guards,
                returns = returns,
                properties = properties,
                functions = functions,
                ifs = ifs,
                tries = tries,
                imports = imports,
                references = references,
                statements = statements,
                values = values,
                exits = exits,
                creations = creations,
                classes = classes,
                coalesces = coalesces,
            ),
        )
    }
}
