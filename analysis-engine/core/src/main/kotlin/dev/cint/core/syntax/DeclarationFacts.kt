package dev.cint.core.syntax

import dev.cint.api.FunctionFact
import dev.cint.api.IfFact
import dev.cint.api.PropertyFact
import dev.cint.api.SyntaxNode
import dev.cint.api.TryFact

internal class DeclarationFacts(
    private val kinds: ProfileSyntaxKinds,
    private val calls: CallFacts,
) {
    fun properties(tree: SyntaxTree): Sequence<PropertyFact> =
        tree.root
            .descendants()
            .filter { it.kind in kinds.propertyKinds }
            .map { declaration ->
                PropertyFact(
                    node = declaration,
                    name = variableName(declaration),
                    nameNode = nameNode(declaration),
                    initializer = initializerOf(declaration),
                )
            }

    fun functions(tree: SyntaxTree): Sequence<FunctionFact> =
        tree.root
            .descendants()
            .filter { it.kind in kinds.functionKinds }
            .map { declaration ->
                FunctionFact(
                    node = declaration,
                    name = functionNameNode(declaration)?.text,
                    nameNode = functionNameNode(declaration),
                    body = bodyOf(declaration),
                )
            }

    fun variableName(declaration: SyntaxNode): String? = nameNode(declaration)?.text

    fun nameNode(declaration: SyntaxNode): SyntaxNode? {
        kinds.fieldNode(declaration, SyntaxFields.VARIABLE_NAME)?.let { return it }
        val variable = declaration.children.firstOrNull { it.kind in kinds.variableKinds }
        if (variable != null) {
            return variable.children.firstOrNull { it.kind in kinds.identifierKinds }
        }
        if (kinds.variableKinds.isEmpty()) {
            return declaration.children.firstOrNull { it.kind in kinds.identifierKinds }
        }
        return null
    }

    fun functionNameNode(declaration: SyntaxNode): SyntaxNode? =
        kinds.fieldNode(declaration, SyntaxFields.FUNCTION_NAME)
            ?: declaration.children.firstOrNull { it.kind in kinds.identifierKinds }

    fun initializerOf(declaration: SyntaxNode): SyntaxNode? {
        kinds.fieldNode(declaration, SyntaxFields.VARIABLE_VALUE)?.let { return it }
        val equals = declaration.children.firstOrNull { it.kind in kinds.equalsKinds } ?: return null
        return declaration.children.firstOrNull { it.startByte >= equals.endByte }
    }

    fun bodyOf(declaration: SyntaxNode): SyntaxNode? =
        kinds.fieldNode(declaration, SyntaxFields.FUNCTION_BODY)
            ?: declaration.children.firstOrNull { it.kind in kinds.functionBodyKinds }

    fun enclosingFunction(node: SyntaxNode): SyntaxNode? = ancestorOf(node, kinds.functionKinds)

    fun enclosingDeclaration(node: SyntaxNode): SyntaxNode? = ancestorOf(node, kinds.propertyKinds + kinds.functionKinds)

    fun declarationHolder(node: SyntaxNode): SyntaxNode? {
        var anchor = node
        var holder = anchor.parent
        while (holder != null && holder.kind in kinds.declarationWrapperKinds) {
            anchor = holder
            holder = anchor.parent
        }
        return holder
    }

    fun isDeclarationName(node: SyntaxNode): Boolean {
        val parent = node.parent ?: return false
        return parent.kind in kinds.propertyKinds + kinds.functionKinds
    }

    fun findFunction(
        tree: SyntaxTree,
        name: String,
    ): SyntaxNode? = functions(tree).firstOrNull { it.name == name }?.node

    fun annotationTexts(declaration: SyntaxNode): List<String> {
        var node: SyntaxNode? = declaration
        while (node != null) {
            val container = node.children.firstOrNull { it.kind in kinds.modifierKinds }
            val annotations =
                container?.children?.filter { it.kind in kinds.annotationKinds }
                    ?: node.children.filter { it.kind in kinds.annotationKinds }
            if (annotations.isNotEmpty()) return annotations.map { normalizeAnnotation(it.text) }
            val ascend = node.kind in kinds.declarationWrapperKinds || node.kind in kinds.propertyKinds
            node = if (ascend) node.parent else null
        }
        return emptyList()
    }

    fun precedingComments(node: SyntaxNode): Sequence<SyntaxNode> =
        sequence {
            var anchor = node
            var wrapper = anchor.parent
            while (wrapper != null && wrapper.kind in kinds.declarationWrapperKinds) {
                anchor = wrapper
                wrapper = anchor.parent
            }
            val parent = anchor.parent ?: return@sequence
            val index = parent.children.indexOfFirst { it.startByte == anchor.startByte && it.endByte == anchor.endByte }
            var current = index - 1
            while (current >= 0 && parent.children[current].kind in kinds.commentKinds) {
                yield(parent.children[current])
                current--
            }
        }

    fun closestCommentBefore(
        scope: SyntaxNode,
        node: SyntaxNode,
    ): SyntaxNode? =
        scope
            .descendants()
            .filter { it.kind in kinds.commentKinds && it.endByte <= node.startByte }
            .maxByOrNull { it.startByte }

    fun isAnnotatedPure(
        declaration: SyntaxNode,
        patterns: List<String>,
    ): Boolean = annotationTexts(declaration).any { annotation -> patterns.any { matches(annotation, it) } }

    fun isAnnotatedPureCall(
        tree: SyntaxTree,
        call: SyntaxNode,
        patterns: List<String>,
    ): Boolean {
        if (calls.receiverNode(call) != null) return false
        val callee = calls.calleeName(call) ?: return false
        val function = findFunction(tree, callee) ?: return false
        return isAnnotatedPure(function, patterns)
    }

    private fun ancestorOf(
        node: SyntaxNode,
        targetKinds: Set<String>,
    ): SyntaxNode? {
        var current = node.parent
        while (current != null) {
            if (current.kind in targetKinds) return current
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

internal class ControlFlowFacts(
    private val kinds: ProfileSyntaxKinds,
) {
    fun ifs(tree: SyntaxTree): Sequence<IfFact> =
        tree.root
            .descendants()
            .filter { it.kind in kinds.ifKinds }
            .map(::ifFact)

    fun loops(tree: SyntaxTree): Sequence<LoopFact> =
        tree.root
            .descendants()
            .filter { it.kind in kinds.loopKinds }
            .map { node ->
                LoopFact(
                    node = node,
                    condition = loopCondition(node),
                    body = loopBody(node),
                )
            }

    fun loopBody(node: SyntaxNode): SyntaxNode? =
        kinds.fieldNode(node, SyntaxFields.LOOP_BODY)
            ?: node.children.firstOrNull { it.kind in kinds.controlBodyKinds }

    fun tries(tree: SyntaxTree): Sequence<TryFact> =
        tree.root
            .descendants()
            .filter { it.kind in kinds.tryKinds }
            .map { node ->
                val body = kinds.fieldNode(node, SyntaxFields.TRY_BODY)
                TryFact(
                    node = node,
                    body = body,
                    catches = node.children.filter { it.kind in kinds.catchKinds },
                    finallyBlock = node.children.firstOrNull { it.kind in kinds.finallyKinds },
                    headerStart = node.children.firstOrNull { it.kind in kinds.tryTokenKinds },
                    headerEnd =
                        node.children.firstOrNull { it.kind in kinds.openBraceKinds }
                            ?: body?.children?.firstOrNull { it.kind in kinds.openBraceKinds },
                )
            }

    fun catches(tree: SyntaxTree): Sequence<SyntaxNode> = tree.root.descendants().filter { it.kind in kinds.catchKinds }

    fun guardConditions(tree: SyntaxTree): Sequence<SyntaxNode> =
        sequence {
            for (fact in ifs(tree)) {
                fact.condition?.let { yield(it) }
            }
            for (loop in tree.root.descendants().filter { it.kind in kinds.loopKinds }) {
                loopCondition(loop)?.let { yield(it) }
            }
        }

    fun statementList(container: SyntaxNode): SyntaxNode? {
        if (container.kind in kinds.statementListKinds) return container
        return container.children.firstOrNull { it.kind in kinds.statementListKinds }
    }

    fun statements(container: SyntaxNode): List<SyntaxNode>? {
        val list = statementList(container) ?: return null
        return list.children.filterNot {
            it.kind in kinds.commentKinds || it.kind in kinds.openBraceKinds || it.kind in kinds.blockCloseKinds
        }
    }

    fun statementExpression(node: SyntaxNode): SyntaxNode? {
        if (node.kind !in kinds.statementWrapperKinds) return null
        return node.children.firstOrNull {
            it.kind !in kinds.commentKinds && it.kind !in kinds.blockCloseKinds && it.kind != ";"
        }
    }

    fun enclosingStatement(node: SyntaxNode): SyntaxNode {
        var current = node
        var parent = current.parent
        while (parent != null) {
            if (parent.kind in kinds.statementListKinds) return current
            if (parent.kind in kinds.controlBodyKinds + kinds.catchKinds + kinds.finallyKinds + kinds.tryKinds + kinds.functionBodyKinds) {
                return current
            }
            current = parent
            parent = current.parent
        }
        return current
    }

    fun ifCondition(node: SyntaxNode): SyntaxNode? {
        kinds.fieldNode(node, SyntaxFields.IF_CONDITION)?.let { return unwrapParenthesized(it) }
        return node.children.firstOrNull {
            it.kind !in kinds.conditionExcludedKinds && it.kind !in kinds.ifTokenKinds && it.kind !in kinds.elseTokenKinds
        }
    }

    fun loopCondition(node: SyntaxNode): SyntaxNode? {
        kinds.fieldNode(node, SyntaxFields.LOOP_CONDITION)?.let { return unwrapParenthesized(it) }
        return node.children.firstOrNull {
            it.kind !in kinds.loopConditionExcludedKinds && it.kind !in kinds.loopTokenKinds
        }
    }

    fun elseBodyOf(node: SyntaxNode): SyntaxNode? {
        val elseIndex = node.children.indexOfFirst { it.kind in kinds.elseKinds || it.kind in kinds.elseTokenKinds }
        if (elseIndex < 0) return null
        return node.children
            .drop(elseIndex + 1)
            .firstOrNull { it.kind in kinds.controlBodyKinds || it.kind in kinds.ifKinds }
    }

    fun isBlockClose(node: SyntaxNode): Boolean = node.kind in kinds.blockCloseKinds

    fun previousBlockClose(node: SyntaxNode): SyntaxNode? {
        val previous = previousSibling(node) ?: return null
        if (isBlockClose(previous)) return previous
        return previous.children.lastOrNull { isBlockClose(it) }
    }

    fun previousSibling(node: SyntaxNode): SyntaxNode? {
        val parent = node.parent ?: return null
        val index = parent.children.indexOfFirst { it.startByte == node.startByte && it.endByte == node.endByte }
        return if (index > 0) parent.children[index - 1] else null
    }

    private fun ifFact(node: SyntaxNode): IfFact {
        val elseToken = node.children.firstOrNull { it.kind in kinds.elseKinds || it.kind in kinds.elseTokenKinds }
        val rawElse = kinds.fieldNode(node, SyntaxFields.IF_ELSE) ?: elseBodyOf(node)
        return IfFact(
            node = node,
            condition = ifCondition(node),
            thenBody = kinds.fieldNode(node, SyntaxFields.IF_THEN) ?: node.children.firstOrNull { it.kind in kinds.controlBodyKinds },
            elseBody = rawElse?.let(::unwrapElseClause),
            elseKeyword = elseToken ?: rawElse,
        )
    }

    private fun unwrapElseClause(node: SyntaxNode): SyntaxNode {
        if (node.kind !in kinds.elseClauseKinds) return node
        return node.children.firstOrNull { it.kind !in kinds.elseKinds && it.kind !in kinds.elseTokenKinds } ?: node
    }

    private fun unwrapParenthesized(node: SyntaxNode): SyntaxNode {
        if (node.kind !in kinds.parenthesizedKinds) return node
        return node.children.firstOrNull {
            it.kind !in kinds.parenthesizedKinds && it.kind != "(" && it.kind != ")"
        } ?: node
    }
}
