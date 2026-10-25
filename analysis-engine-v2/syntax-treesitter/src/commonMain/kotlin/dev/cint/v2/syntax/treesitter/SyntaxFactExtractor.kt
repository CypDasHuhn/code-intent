package dev.cint.v2.syntax.treesitter

import dev.cint.contracts.facts.CallFact
import dev.cint.contracts.facts.CommentFact
import dev.cint.contracts.facts.CommentKind
import dev.cint.contracts.facts.DeclarationFact
import dev.cint.contracts.facts.DeclarationKind
import dev.cint.contracts.facts.FactBatch
import dev.cint.contracts.facts.FunctionFact
import dev.cint.contracts.facts.GuardFact
import dev.cint.contracts.facts.GuardKind
import dev.cint.contracts.facts.IfFact
import dev.cint.contracts.facts.ImportFact
import dev.cint.contracts.facts.PropertyFact
import dev.cint.contracts.facts.ReferenceFact
import dev.cint.contracts.facts.ReturnFact
import dev.cint.contracts.facts.ScopeFact
import dev.cint.contracts.facts.ScopeKind
import dev.cint.contracts.facts.SyntaxNode
import dev.cint.contracts.facts.SyntaxTree
import dev.cint.contracts.facts.TryFact
import dev.cint.contracts.facts.WriteFact
import dev.cint.contracts.facts.WriteKind
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory

private enum class FactCategory(val key: String) {
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
            val normalized = raw.trim().lowercase()
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
)

private object Profiles {
    private val kotlin = LanguageProfile(
        callKinds = setOf("call_expression"),
        functionKinds = setOf("function_declaration", "constructor_declaration", "anonymous_function"),
        propertyKinds = setOf("property_declaration"),
        variableKinds = setOf("variable_declaration", "multi_variable_declaration"),
        classKinds = setOf("class_declaration", "object_declaration", "companion_object", "object_literal"),
        scopeKinds = setOf(
            "class_declaration", "object_declaration", "companion_object", "object_literal",
            "function_declaration", "constructor_declaration", "anonymous_function",
            "lambda_literal", "annotated_lambda", "control_structure_body", "statements", "class_body",
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
    )

    private val javascript = LanguageProfile(
        callKinds = setOf("call_expression", "new_expression"),
        functionKinds = setOf("function_declaration", "function_expression", "generator_function_declaration", "method_definition", "arrow_function"),
        propertyKinds = setOf("public_field_definition", "field_definition", "pair"),
        variableKinds = setOf("lexical_declaration", "variable_declaration", "variable_declarator"),
        classKinds = setOf("class_declaration", "class", "class_expression"),
        scopeKinds = setOf(
            "class_declaration", "class", "class_expression", "function_declaration", "function_expression",
            "generator_function_declaration", "method_definition", "arrow_function", "statement_block",
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
        identifierKinds = setOf("identifier", "property_identifier", "shorthand_property_identifier", "shorthand_property_identifier_pattern"),
        declarationKinds = setOf("function_declaration", "function_expression", "method_definition", "lexical_declaration", "variable_declaration", "variable_declarator", "public_field_definition", "field_definition"),
        bodyKinds = setOf("statement_block", "class_body", "function_body"),
        conditionSkipKinds = setOf("statement_block", "class_body"),
        callSuffixKinds = setOf("arguments", "type_arguments"),
        navigationKinds = setOf("member_expression", "subscript_expression"),
        memberKinds = setOf("property_identifier"),
    )

    private val csharp = LanguageProfile(
        callKinds = setOf("invocation_expression", "object_creation_expression"),
        functionKinds = setOf("method_declaration", "local_function_statement", "constructor_declaration", "destructor_declaration", "operator_declaration"),
        propertyKinds = setOf("property_declaration", "field_declaration", "event_declaration"),
        variableKinds = setOf("variable_declaration", "variable_declarator"),
        classKinds = setOf("class_declaration", "struct_declaration", "record_declaration", "interface_declaration"),
        scopeKinds = setOf(
            "class_declaration", "struct_declaration", "record_declaration", "interface_declaration",
            "method_declaration", "local_function_statement", "constructor_declaration",
            "lambda_expression", "anonymous_method_expression", "block",
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
        declarationKinds = setOf("method_declaration", "local_function_statement", "constructor_declaration", "property_declaration", "field_declaration", "variable_declaration", "variable_declarator"),
        bodyKinds = setOf("block", "declaration_list", "accessor_list"),
        conditionSkipKinds = setOf("block", "declaration_list"),
        callSuffixKinds = setOf("argument_list", "type_argument_list"),
        navigationKinds = setOf("member_access_expression", "element_access_expression"),
        memberKinds = setOf("identifier"),
    )

    private val c = LanguageProfile(
        callKinds = setOf("call_expression"),
        functionKinds = setOf("function_definition"),
        propertyKinds = setOf("declaration"),
        variableKinds = setOf("declaration", "parameter_declaration"),
        classKinds = setOf("struct_specifier", "union_specifier", "enum_specifier"),
        scopeKinds = setOf(
            "function_definition", "struct_specifier", "union_specifier", "enum_specifier",
            "compound_statement", "if_statement", "for_statement", "while_statement", "switch_statement",
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
    )

    private val vue = javascript.copy(
        callKinds = javascript.callKinds + setOf("call_expression"),
        functionKinds = javascript.functionKinds,
        propertyKinds = javascript.propertyKinds + setOf("pair"),
        commentKinds = javascript.commentKinds + setOf("comment", "html_comment"),
        importKinds = javascript.importKinds,
    )

    fun forLanguage(language: String): LanguageProfile? = when (language) {
        "kotlin" -> kotlin
        "csharp" -> csharp
        "c" -> c
        "vue" -> vue
        "javascript", "typescript" -> javascript
        else -> null
    }
}

internal object SyntaxFactExtractor {
    fun extract(tree: SyntaxTree, queryIds: List<String>): ContractResult<FactBatch> {
        val requested = if (queryIds.isEmpty()) {
            FactCategory.entries.toSet()
        } else {
            val parsed = mutableSetOf<FactCategory>()
            for (query in queryIds) {
                val category = FactCategory.from(query)
                    ?: return ResultFactory.failure(
                        ContractError.InvalidInput("Unknown syntax query id '$query'", query)
                    )
                parsed += category
            }
            parsed
        }
        val profile = Profiles.forLanguage(tree.language)
            ?: return ResultFactory.failure(
                ContractError.InvalidInput(
                    "no syntax fact profile is registered for '${tree.language}'",
                    tree.language,
                )
            )
        val nodes = tree.nodes.values.sortedWith(compareBy<SyntaxNode> { it.startByte }.thenBy { it.endByte })
        val byId = tree.nodes
        val children = tree.nodes.values.groupBy { it.parentId }

        fun childrenOf(node: SyntaxNode): List<SyntaxNode> =
            node.childIds.mapNotNull(byId::get)

        fun descendantsOf(node: SyntaxNode): Sequence<SyntaxNode> = sequence {
            for (child in childrenOf(node)) {
                yield(child)
                yieldAll(descendantsOf(child))
            }
        }

        fun ancestorsOf(node: SyntaxNode): Sequence<SyntaxNode> = sequence {
            var parent = node.parentId?.let(byId::get)
            while (parent != null) {
                yield(parent)
                parent = parent.parentId?.let(byId::get)
            }
        }

        fun firstIdentifier(node: SyntaxNode): SyntaxNode? =
            descendantsOf(node).firstOrNull { it.kind in profile.identifierKinds }

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
                firstIdentifier(node)?.text ?: node.text.substringAfterLast('.').substringBefore('(').trim()
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
                }
                ?.takeIf { it.isNotBlank() }

        fun signature(node: SyntaxNode): String? {
            val body = bodyOf(node)?.text?.takeIf { it.isNotBlank() } ?: return node.text
            val index = node.text.indexOf(body)
            return if (index > 0) node.text.substring(0, index).trimEnd() else node.text
        }

        val functionNodes = nodes.filter { it.kind in profile.functionKinds }
        val propertyNodes = nodes.filter { it.kind in profile.propertyKinds }
        val variableNodes = nodes
            .filter { it.kind in profile.variableKinds }
            .filter { variable ->
                propertyNodes.none { property ->
                    variable.startByte >= property.startByte && variable.endByte <= property.endByte
                }
            }
        val classNodes = nodes.filter { it.kind in profile.classKinds }
        val lambdaNodes = nodes.filter { it.kind in profile.lambdaKinds }
        val scopeNodes = buildList {
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

        val scopes = if (FactCategory.SCOPES in requested) {
            scopeNodes.map { node ->
                val kind = when {
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

        val functions = if (FactCategory.FUNCTIONS in requested) {
            functionNodes.map { node ->
                val nameNode = firstIdentifier(node)?.takeIf { it.startByte < (bodyOf(node)?.startByte ?: Int.MAX_VALUE) }
                FunctionFact(
                    nodeId = node.id,
                    name = nameNode?.text,
                    nameNodeId = nameNode?.id,
                    bodyId = bodyOf(node)?.id,
                    declaringType = declaringType(node),
                    signature = signature(node),
                    arity = parameterCount(node),
                )
            }
        } else {
            emptyList()
        }

        fun declarationNameNode(node: SyntaxNode): SyntaxNode? =
            if (node.kind in profile.propertyKinds) {
                descendantsOf(node).firstOrNull { it.kind in profile.variableKinds }?.let { firstIdentifier(it) }
                    ?: firstIdentifier(node)
            } else {
                firstIdentifier(node)
            }

        val properties = if (FactCategory.PROPERTIES in requested) {
            (propertyNodes + variableNodes).map { node ->
                val nameNode = declarationNameNode(node)
                val initializer = childrenOf(node).lastOrNull {
                    it.id != nameNode?.id && it.kind !in profile.bodyKinds && it.text != "="
                }
                PropertyFact(node.id, nameNode?.text, nameNode?.id, initializer?.id)
            }
        } else {
            emptyList()
        }

        val annotationsFor: (SyntaxNode) -> Pair<List<String>, List<String>> = { node ->
            val body = bodyOf(node)
            val found = descendantsOf(node)
                .filter { it.kind in profile.annotationKinds }
                .filter { body == null || it.startByte < body.startByte }
                .toList()
            found.map { it.text } to found.map { it.id }
        }

        fun declarationKind(node: SyntaxNode): DeclarationKind = when {
            node.kind in profile.functionKinds -> DeclarationKind.FUNCTION
            node.kind in profile.propertyKinds -> DeclarationKind.PROPERTY
            else -> DeclarationKind.VARIABLE
        }

        val writes = if (FactCategory.WRITES in requested) {
            nodes.mapNotNull { node ->
                when {
                    node.kind in profile.assignmentKinds -> {
                        val target = childrenOf(node).firstOrNull()
                        WriteFact(
                            node.id,
                            WriteKind.ASSIGNMENT,
                            target?.id,
                            targetName = target?.text?.substringBefore('=')?.substringBefore('+')?.substringBefore('-')?.trim()?.substringAfterLast('.')?.trim(),
                        )
                    }
                    node.kind in profile.incrementKinds && (node.text.contains("++") || node.text.contains("--")) -> {
                        val target = childrenOf(node).firstOrNull { child -> child.text.trim() !in setOf("++", "--") }
                        WriteFact(
                            node.id,
                            WriteKind.INCREMENT,
                            target?.id,
                            targetName = target?.text?.trim()?.substringAfterLast('.')?.trim(),
                        )
                    }
                    else -> null
                }
            }
        } else {
            emptyList()
        }

        val declarations = if (FactCategory.DECLARATIONS in requested) {
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
                    scopeId = owningScope(node),
                    writeIds = writes.filter { write -> byId[write.nodeId]?.let { it.startByte >= node.startByte && it.endByte <= node.endByte } == true }.map { it.nodeId },
                )
            }
        } else {
            emptyList()
        }

        val comments = if (FactCategory.COMMENTS in requested) {
            nodes.filter { it.kind in profile.commentKinds }.map { node ->
                val text = node.text.trim()
                val kind = when {
                    text.startsWith("/**") || text.startsWith("///") -> CommentKind.DOC
                    text.startsWith("//") || text.startsWith("#") -> CommentKind.LINE
                    text.startsWith("/*") || text.startsWith("<!--") -> CommentKind.BLOCK
                    else -> CommentKind.OTHER
                }
                val magic = if (text.contains("cint:")) text else null
                CommentFact(node.id, magic, kind)
            }
        } else {
            emptyList()
        }

        val calls = if (FactCategory.CALLS in requested) {
            nodes.filter { it.kind in profile.callKinds }.map { node ->
                val callChildren = childrenOf(node)
                val callee = callChildren.firstOrNull { it.kind !in profile.callSuffixKinds } ?: callChildren.firstOrNull()
                val receiver = callee?.takeIf { it.kind in profile.navigationKinds }?.let { navigation ->
                    childrenOf(navigation).firstOrNull { it.kind !in profile.memberKinds } ?: childrenOf(navigation).firstOrNull()
                }
                val trailingLambda = callChildren.asReversed().firstOrNull { it.kind in profile.lambdaKinds }
                    ?: descendantsOf(node).lastOrNull { it.kind in profile.lambdaKinds }
                CallFact(
                    nodeId = node.id,
                    siteId = callChildren.firstOrNull { it.kind in profile.callSuffixKinds }?.id ?: node.id,
                    callee = callee?.let { calleeName(it) },
                    receiverId = receiver?.id,
                    path = callee?.text,
                    trailingLambdaId = trailingLambda?.id,
                    arity = argumentCount(node),
                )
            }
        } else {
            emptyList()
        }

        fun conditionOf(node: SyntaxNode): SyntaxNode? =
            childrenOf(node).firstOrNull { it.kind !in profile.conditionSkipKinds && !it.text.trim().let { text -> text == "if" || text == "while" || text == "for" || text == "(" || text == ")" } }

        fun bodiesOf(node: SyntaxNode): List<SyntaxNode> =
            childrenOf(node).filter { it.kind in profile.bodyKinds }

        val ifs = if (FactCategory.IFS in requested) {
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

        val guards = if (FactCategory.GUARDS in requested) {
            ifNodes.map { node ->
                GuardFact(node.id, GuardKind.CONDITIONAL, conditionOf(node)?.id, bodiesOf(node).getOrNull(0)?.id, bodiesOf(node).getOrNull(1)?.id)
            } + loopNodes.map { node ->
                GuardFact(node.id, GuardKind.LOOP, conditionOf(node)?.id, bodiesOf(node).firstOrNull()?.id, null)
            }
        } else {
            emptyList()
        }

        val returns = if (FactCategory.RETURNS in requested) {
            returnNodes.map { node ->
                ReturnFact(
                    nodeId = node.id,
                    expressionId = childrenOf(node).firstOrNull { it.text.trim() != "return" && it.text.trim() != ";" }?.id,
                    guardIds = ancestorsOf(node).filter { it.kind in profile.ifKinds || it.kind in profile.loopKinds }.map { it.id }.toList(),
                    loopId = ancestorsOf(node).firstOrNull { it.kind in profile.loopKinds }?.id,
                    handlerId = ancestorsOf(node).firstOrNull { it.kind in profile.catchKinds }?.id,
                    scopeId = owningScope(node),
                )
            }
        } else {
            emptyList()
        }

        val tries = if (FactCategory.TRIES in requested) {
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

        val imports = if (FactCategory.IMPORTS in requested) {
            nodes.filter { it.kind in profile.importKinds }.map { node ->
                val text = node.text
                val path = text.removePrefix("import").removePrefix("using").removePrefix("#include").trim()
                val alias = text.split(Regex("\\s+as\\s+")).getOrNull(1)?.trim()
                ImportFact(node.id, path, alias)
            }
        } else {
            emptyList()
        }

        val references = if (FactCategory.REFERENCES in requested) {
            nodes.filter { it.kind in profile.identifierKinds }
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
            )
        )
    }
}
