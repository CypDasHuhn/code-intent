package dev.cint.core.syntax

class ProfileSyntaxModel(
    private val profile: SyntaxProfile,
) : SyntaxModel {
    private val callKinds = profile.kinds(SyntaxGroups.CALL)
    private val navigationKinds = profile.kinds(SyntaxGroups.NAVIGATION)
    private val calleeSuffixKinds = profile.kinds(SyntaxGroups.CALLEE_SUFFIX)
    private val callSuffixKinds = profile.kinds(SyntaxGroups.CALL_SUFFIX)
    private val identifierKinds = profile.kinds(SyntaxGroups.IDENTIFIER)
    private val importKinds = profile.kinds(SyntaxGroups.IMPORT)
    private val propertyKinds = profile.kinds(SyntaxGroups.PROPERTY)
    private val variableKinds = profile.kinds(SyntaxGroups.VARIABLE)
    private val functionKinds = profile.kinds(SyntaxGroups.FUNCTION)
    private val functionBodyKinds = profile.kinds(SyntaxGroups.FUNCTION_BODY)
    private val ifKinds = profile.kinds(SyntaxGroups.IF)
    private val loopKinds = profile.kinds(SyntaxGroups.LOOP)
    private val controlBodyKinds = profile.kinds(SyntaxGroups.CONTROL_BODY)
    private val elseKinds = profile.kinds(SyntaxGroups.ELSE)
    private val elseClauseKinds = profile.kinds(SyntaxGroups.ELSE_CLAUSE)
    private val statementListKinds = profile.kinds(SyntaxGroups.STATEMENT_LIST)
    private val statementWrapperKinds = profile.kinds(SyntaxGroups.STATEMENT_WRAPPER)
    private val jumpKinds = profile.kinds(SyntaxGroups.JUMP)
    private val tryKinds = profile.kinds(SyntaxGroups.TRY)
    private val catchKinds = profile.kinds(SyntaxGroups.CATCH)
    private val finallyKinds = profile.kinds(SyntaxGroups.FINALLY)
    private val assignmentKinds = profile.kinds(SyntaxGroups.ASSIGNMENT)
    private val assignableKinds = profile.kinds(SyntaxGroups.ASSIGNABLE)
    private val incrementKinds = profile.kinds(SyntaxGroups.INCREMENT)
    private val annotationKinds = profile.kinds(SyntaxGroups.ANNOTATION)
    private val modifierKinds = profile.kinds(SyntaxGroups.MODIFIER)
    private val commentKinds = profile.kinds(SyntaxGroups.COMMENT)
    private val lambdaKinds = profile.kinds(SyntaxGroups.LAMBDA)
    private val typeReferenceKinds = profile.kinds(SyntaxGroups.TYPE_REFERENCE)
    private val declarationWrapperKinds = profile.kinds(SyntaxGroups.DECLARATION_WRAPPER)
    private val blockCloseKinds = profile.kinds(SyntaxGroups.BLOCK_CLOSE)
    private val openBraceKinds = profile.kinds(SyntaxGroups.OPEN_BRACE)
    private val equalsKinds = profile.kinds(SyntaxGroups.EQUALS)
    private val ifTokenKinds = profile.kinds(SyntaxGroups.IF_TOKEN)
    private val elseTokenKinds = profile.kinds(SyntaxGroups.ELSE_TOKEN)
    private val loopTokenKinds = profile.kinds(SyntaxGroups.LOOP_TOKEN)
    private val tryTokenKinds = profile.kinds(SyntaxGroups.TRY_TOKEN)
    private val conditionExcludedKinds = profile.kinds(SyntaxGroups.CONDITION_EXCLUDED)
    private val loopConditionExcludedKinds = profile.kinds(SyntaxGroups.LOOP_CONDITION_EXCLUDED)
    private val parenthesizedKinds = profile.kinds(SyntaxGroups.PARENTHESIZED)
    private val referenceKinds = profile.kinds(SyntaxGroups.REFERENCE)
    private val effectKinds = profile.kinds(SyntaxGroups.EFFECT)
    private val effectFragments = profile.kinds(SyntaxGroups.EFFECT_FRAGMENT)
    private val incrementOperators = profile.operators(SyntaxOperators.INCREMENT)
    private val exitKeywords = profile.keywords(SyntaxKeywords.EXIT)
    private val throwKeyword = profile.token(SyntaxTokens.THROW_KEYWORD)
    private val importPrefix = profile.token(SyntaxTokens.IMPORT_PREFIX)

    override fun calls(tree: SyntaxTree): Sequence<CallFact> = callsIn(tree.root)

    override fun callsIn(node: SyntaxNode): Sequence<CallFact> =
        node
            .descendants()
            .filter { it.kind in callKinds }
            .map(::callFact)

    override fun properties(tree: SyntaxTree): Sequence<PropertyFact> =
        tree.root
            .descendants()
            .filter { it.kind in propertyKinds }
            .map { declaration ->
                PropertyFact(
                    node = declaration,
                    name = variableName(declaration),
                    nameNode = nameNode(declaration),
                    initializer = initializerOf(declaration),
                )
            }

    override fun functions(tree: SyntaxTree): Sequence<FunctionFact> =
        tree.root
            .descendants()
            .filter { it.kind in functionKinds }
            .map { declaration ->
                FunctionFact(
                    node = declaration,
                    name = functionNameNode(declaration)?.text,
                    nameNode = functionNameNode(declaration),
                    body = bodyOf(declaration),
                )
            }

    override fun ifs(tree: SyntaxTree): Sequence<IfFact> =
        tree.root
            .descendants()
            .filter { it.kind in ifKinds }
            .map(::ifFact)

    override fun loops(tree: SyntaxTree): Sequence<LoopFact> =
        tree.root
            .descendants()
            .filter { it.kind in loopKinds }
            .map { node ->
                LoopFact(
                    node = node,
                    condition = loopCondition(node),
                    body = loopBody(node),
                )
            }

    override fun loopBody(node: SyntaxNode): SyntaxNode? =
        fieldNode(node, SyntaxFields.LOOP_BODY)
            ?: node.children.firstOrNull { it.kind in controlBodyKinds }

    override fun tries(tree: SyntaxTree): Sequence<TryFact> =
        tree.root
            .descendants()
            .filter { it.kind in tryKinds }
            .map { node ->
                val body = fieldNode(node, SyntaxFields.TRY_BODY)
                TryFact(
                    node = node,
                    body = body,
                    catches = node.children.filter { it.kind in catchKinds },
                    finallyBlock = node.children.firstOrNull { it.kind in finallyKinds },
                    headerStart = node.children.firstOrNull { it.kind in tryTokenKinds },
                    headerEnd =
                        node.children.firstOrNull { it.kind in openBraceKinds }
                            ?: body?.children?.firstOrNull { it.kind in openBraceKinds },
                )
            }

    override fun catches(tree: SyntaxTree): Sequence<SyntaxNode> = tree.root.descendants().filter { it.kind in catchKinds }

    override fun guardConditions(tree: SyntaxTree): Sequence<SyntaxNode> =
        sequence {
            for (fact in ifs(tree)) {
                fact.condition?.let { yield(it) }
            }
            for (loop in tree.root.descendants().filter { it.kind in loopKinds }) {
                loopCondition(loop)?.let { yield(it) }
            }
        }

    override fun imports(tree: SyntaxTree): List<String> =
        tree.root
            .descendants()
            .filter { it.kind in importKinds }
            .map { node ->
                val source = fieldNode(node, SyntaxFields.IMPORT_SOURCE)
                val value =
                    when {
                        source != null -> source.text.trim('"', '\'', '`')
                        importPrefix != null -> node.text.removePrefix(importPrefix).trim()
                        else -> node.text.trim()
                    }
                value.trimEnd(';')
            }.toList()

    override fun referencesByName(tree: SyntaxTree): Map<String, List<SyntaxNode>> =
        tree.root
            .descendants()
            .filter { it.kind in identifierKinds }
            .groupBy { it.text }

    override fun variableName(declaration: SyntaxNode): String? = nameNode(declaration)?.text

    override fun nameNode(declaration: SyntaxNode): SyntaxNode? {
        fieldNode(declaration, SyntaxFields.VARIABLE_NAME)?.let { return it }
        val variable = declaration.children.firstOrNull { it.kind in variableKinds }
        if (variable != null) {
            return variable.children.firstOrNull { it.kind in identifierKinds }
        }
        if (variableKinds.isEmpty()) {
            return declaration.children.firstOrNull { it.kind in identifierKinds }
        }
        return null
    }

    override fun functionNameNode(declaration: SyntaxNode): SyntaxNode? =
        fieldNode(declaration, SyntaxFields.FUNCTION_NAME)
            ?: declaration.children.firstOrNull { it.kind in identifierKinds }

    override fun initializerOf(declaration: SyntaxNode): SyntaxNode? {
        fieldNode(declaration, SyntaxFields.VARIABLE_VALUE)?.let { return it }
        val equals = declaration.children.firstOrNull { it.kind in equalsKinds } ?: return null
        return declaration.children.firstOrNull { it.startByte >= equals.endByte }
    }

    override fun bodyOf(declaration: SyntaxNode): SyntaxNode? =
        fieldNode(declaration, SyntaxFields.FUNCTION_BODY)
            ?: declaration.children.firstOrNull { it.kind in functionBodyKinds }

    override fun statementList(container: SyntaxNode): SyntaxNode? {
        if (container.kind in statementListKinds) return container
        return container.children.firstOrNull { it.kind in statementListKinds }
    }

    override fun statements(container: SyntaxNode): List<SyntaxNode>? {
        val list = statementList(container) ?: return null
        return list.children.filterNot {
            it.kind in commentKinds || it.kind in openBraceKinds || it.kind in blockCloseKinds
        }
    }

    override fun statementExpression(node: SyntaxNode): SyntaxNode? {
        if (node.kind !in statementWrapperKinds) return null
        return node.children.firstOrNull {
            it.kind !in commentKinds && it.kind !in blockCloseKinds && it.kind != ";"
        }
    }

    override fun enclosingFunction(node: SyntaxNode): SyntaxNode? = ancestorOf(node, functionKinds)

    override fun enclosingDeclaration(node: SyntaxNode): SyntaxNode? = ancestorOf(node, propertyKinds + functionKinds)

    override fun declarationHolder(node: SyntaxNode): SyntaxNode? {
        var anchor = node
        var holder = anchor.parent
        while (holder != null && holder.kind in declarationWrapperKinds) {
            anchor = holder
            holder = anchor.parent
        }
        return holder
    }

    override fun enclosingStatement(node: SyntaxNode): SyntaxNode {
        var current = node
        var parent = current.parent
        while (parent != null) {
            if (parent.kind in statementListKinds) return current
            if (parent.kind in controlBodyKinds + catchKinds + finallyKinds + tryKinds + functionBodyKinds) {
                return current
            }
            current = parent
            parent = current.parent
        }
        return current
    }

    override fun writes(reference: SyntaxNode): SyntaxNode? {
        val parent = reference.parent ?: return null
        return when {
            parent.kind in incrementKinds -> {
                if (incrementOperators.any { parent.text.contains(it) }) parent else null
            }

            parent.kind in assignableKinds -> {
                val assignment = parent.parent ?: return null
                if (assignment.kind in assignmentKinds) assignment else null
            }

            parent.kind in assignmentKinds -> {
                val left = parent.childByField("left") ?: parent.children.firstOrNull()
                if (left != null && (left.startByte == reference.startByte || reference.startByte in left.startByte until left.endByte)) {
                    parent
                } else {
                    null
                }
            }

            parent.kind in navigationKinds -> {
                val operation = parent.parent ?: return null
                when {
                    operation.kind in incrementKinds -> {
                        if (incrementOperators.any { operation.text.contains(it) }) operation else null
                    }

                    operation.kind in assignmentKinds -> {
                        operation
                    }

                    else -> {
                        null
                    }
                }
            }

            else -> {
                null
            }
        }
    }

    override fun annotationTexts(declaration: SyntaxNode): List<String> {
        var node: SyntaxNode? = declaration
        while (node != null) {
            val container = node.children.firstOrNull { it.kind in modifierKinds }
            val annotations =
                if (container != null) {
                    container.children.filter { it.kind in annotationKinds }
                } else {
                    node.children.filter { it.kind in annotationKinds }
                }
            if (annotations.isNotEmpty()) return annotations.map { normalizeAnnotation(it.text) }
            val ascend = node.kind in declarationWrapperKinds || node.kind in propertyKinds
            node = if (ascend) node.parent else null
        }
        return emptyList()
    }

    override fun precedingComments(node: SyntaxNode): Sequence<SyntaxNode> =
        sequence {
            var anchor = node
            var wrapper = anchor.parent
            while (wrapper != null && wrapper.kind in declarationWrapperKinds) {
                anchor = wrapper
                wrapper = anchor.parent
            }
            val parent = anchor.parent ?: return@sequence
            val index = parent.children.indexOfFirst { it.startByte == anchor.startByte && it.endByte == anchor.endByte }
            var current = index - 1
            while (current >= 0 && parent.children[current].kind in commentKinds) {
                yield(parent.children[current])
                current--
            }
        }

    override fun closestCommentBefore(
        scope: SyntaxNode,
        node: SyntaxNode,
    ): SyntaxNode? =
        scope
            .descendants()
            .filter { it.kind in commentKinds && it.endByte <= node.startByte }
            .maxByOrNull { it.startByte }

    override fun isConstant(node: SyntaxNode): Boolean = node.descendants().none { it.kind in referenceKinds }

    override fun isSideEffectFree(node: SyntaxNode): Boolean =
        node.descendants().none { child ->
            child.kind in effectKinds ||
                effectFragments.any { child.kind.contains(it) } ||
                (
                    child.kind in incrementKinds &&
                        incrementOperators.any { child.text.contains(it) }
                )
        }

    override fun isThrow(node: SyntaxNode): Boolean =
        node.kind in jumpKinds &&
            throwKeyword != null &&
            node.text.trimStart().startsWith(throwKeyword)

    override fun isExit(node: SyntaxNode): Boolean = node.kind in jumpKinds && exitKeywords.any { node.text.trimStart().startsWith(it) }

    override fun isReturn(node: SyntaxNode): Boolean = node.kind in jumpKinds && node.text.trimStart().startsWith("return")

    override fun exitExpression(node: SyntaxNode): SyntaxNode? {
        if (!isExit(node)) return null
        fieldNode(node, SyntaxFields.RETURN_ARGUMENT)?.let { return it }
        return node.children.lastOrNull { it.kind !in exitKeywords && it.kind !in commentKinds }
    }

    override fun isDeclarationName(node: SyntaxNode): Boolean {
        val parent = node.parent ?: return false
        return parent.kind in propertyKinds + functionKinds
    }

    override fun isIdentifier(node: SyntaxNode): Boolean = node.kind in identifierKinds

    override fun isBlockClose(node: SyntaxNode): Boolean = node.kind in blockCloseKinds

    override fun previousBlockClose(node: SyntaxNode): SyntaxNode? {
        val previous = previousSibling(node) ?: return null
        if (isBlockClose(previous)) return previous
        return previous.children.lastOrNull { isBlockClose(it) }
    }

    override fun previousSibling(node: SyntaxNode): SyntaxNode? {
        val parent = node.parent ?: return null
        val index = parent.children.indexOfFirst { it.startByte == node.startByte && it.endByte == node.endByte }
        return if (index > 0) parent.children[index - 1] else null
    }

    override fun ifCondition(node: SyntaxNode): SyntaxNode? {
        fieldNode(node, SyntaxFields.IF_CONDITION)?.let { return unwrapParenthesized(it) }
        return node.children.firstOrNull {
            it.kind !in conditionExcludedKinds && it.kind !in ifTokenKinds && it.kind !in elseTokenKinds
        }
    }

    override fun loopCondition(node: SyntaxNode): SyntaxNode? {
        fieldNode(node, SyntaxFields.LOOP_CONDITION)?.let { return unwrapParenthesized(it) }
        return node.children.firstOrNull {
            it.kind !in loopConditionExcludedKinds && it.kind !in loopTokenKinds
        }
    }

    override fun elseBodyOf(node: SyntaxNode): SyntaxNode? {
        val elseIndex = node.children.indexOfFirst { it.kind in elseKinds || it.kind in elseTokenKinds }
        if (elseIndex < 0) return null
        return node.children
            .drop(elseIndex + 1)
            .firstOrNull { it.kind in controlBodyKinds || it.kind in ifKinds }
    }

    override fun callPath(call: SyntaxNode): String? {
        val callee = calleeName(call) ?: return null
        val receiver = receiverNode(call)
        return if (receiver != null) "${receiver.text}.$callee" else callee
    }

    override fun calleeName(call: SyntaxNode): String? {
        val navigation = navigationOf(call)
        if (navigation != null) {
            fieldNode(navigation, SyntaxFields.MEMBER_PROPERTY)?.let { return it.text.substringBefore('<') }
            val suffix = navigation.children.lastOrNull { it.kind in calleeSuffixKinds }
            return suffix?.children?.firstOrNull { it.kind in identifierKinds }?.text ?: suffix?.text?.substringBefore('<')
        }
        val function = callFunctionNode(call)
        if (function != null && function.kind in identifierKinds) return function.text
        return (function ?: call).children.firstOrNull { it.kind in identifierKinds }?.text
    }

    override fun receiverNode(call: SyntaxNode): SyntaxNode? {
        val navigation = navigationOf(call) ?: return null
        fieldNode(navigation, SyntaxFields.MEMBER_OBJECT)?.let { return it }
        return navigation.children.firstOrNull { it.kind !in calleeSuffixKinds }
    }

    override fun receiverOffset(receiver: SyntaxNode): Int =
        if (receiver.kind in callKinds) {
            (receiver.endByte - 1).coerceAtLeast(receiver.startByte)
        } else {
            receiver.startByte
        }

    override fun findFunction(
        tree: SyntaxTree,
        name: String,
    ): SyntaxNode? = functions(tree).firstOrNull { it.name == name }?.node

    override fun isAnnotatedPure(
        declaration: SyntaxNode,
        patterns: List<String>,
    ): Boolean = annotationTexts(declaration).any { annotation -> patterns.any { matches(annotation, it) } }

    override fun isAnnotatedPureCall(
        tree: SyntaxTree,
        call: SyntaxNode,
        patterns: List<String>,
    ): Boolean {
        if (receiverNode(call) != null) return false
        val callee = calleeName(call) ?: return false
        val function = findFunction(tree, callee) ?: return false
        return isAnnotatedPure(function, patterns)
    }

    private fun fieldNode(
        node: SyntaxNode,
        name: String,
    ): SyntaxNode? = profile.fields(name).firstNotNullOfOrNull { node.childByField(it) }

    private fun callFact(call: SyntaxNode): CallFact =
        CallFact(
            node = call,
            site = callSite(call),
            callee = calleeName(call),
            receiver = receiverNode(call),
        )

    private fun callFunctionNode(call: SyntaxNode): SyntaxNode? = fieldNode(call, SyntaxFields.CALL_FUNCTION)

    private fun navigationOf(call: SyntaxNode): SyntaxNode? {
        val function = callFunctionNode(call)
        if (function != null) {
            if (function.kind in navigationKinds) return function
            return function.children.firstOrNull { it.kind in navigationKinds }
        }
        return call.children.firstOrNull { it.kind in navigationKinds }
    }

    private fun callSite(call: SyntaxNode): SyntaxNode {
        var current = call
        var parent = current.parent
        while (parent != null && parent.kind in callKinds) {
            val suffix = parent.children.firstOrNull { it.kind in callSuffixKinds }
            val hasLambda = suffix?.children?.any { it.kind in lambdaKinds } == true
            if (!hasLambda) break
            current = parent
            parent = current.parent
        }
        return current
    }

    private fun ifFact(node: SyntaxNode): IfFact {
        val elseToken = node.children.firstOrNull { it.kind in elseKinds || it.kind in elseTokenKinds }
        val rawElse = fieldNode(node, SyntaxFields.IF_ELSE) ?: elseBodyOf(node)
        return IfFact(
            node = node,
            condition = ifCondition(node),
            thenBody = fieldNode(node, SyntaxFields.IF_THEN) ?: node.children.firstOrNull { it.kind in controlBodyKinds },
            elseBody = rawElse?.let(::unwrapElseClause),
            elseKeyword = elseToken ?: rawElse,
        )
    }

    private fun unwrapElseClause(node: SyntaxNode): SyntaxNode {
        if (node.kind !in elseClauseKinds) return node
        return node.children.firstOrNull { it.kind !in elseKinds && it.kind !in elseTokenKinds } ?: node
    }

    private fun unwrapParenthesized(node: SyntaxNode): SyntaxNode {
        if (node.kind !in parenthesizedKinds) return node
        return node.children.firstOrNull {
            it.kind !in parenthesizedKinds && it.kind != "(" && it.kind != ")"
        } ?: node
    }

    private fun ancestorOf(
        node: SyntaxNode,
        kinds: Set<String>,
    ): SyntaxNode? {
        var current = node.parent
        while (current != null) {
            if (current.kind in kinds) return current
            current = current.parent
        }
        return null
    }

    private fun normalizeAnnotation(text: String): String =
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
        val expected = normalizeAnnotation(pattern)
        if (annotation.contains('(') || expected.contains('(')) {
            return annotation == expected
        }
        return annotation == expected || annotation.endsWith(".$expected")
    }
}
