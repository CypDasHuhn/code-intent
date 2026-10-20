package dev.cint.core.syntax

import dev.cint.api.CommentFact
import dev.cint.api.DeclarationFact
import dev.cint.api.DeclarationKind
import dev.cint.api.FunctionFact
import dev.cint.api.GuardFact
import dev.cint.api.GuardKind
import dev.cint.api.IfFact
import dev.cint.api.PropertyFact
import dev.cint.api.ScopeFact
import dev.cint.api.ScopeKind
import dev.cint.api.SyntaxNode
import dev.cint.api.TryFact
import dev.cint.api.WriteFact

private data class WriteTarget(
    val name: String,
    val node: SyntaxNode,
    val nameNode: SyntaxNode,
    val scope: ScopeFact?,
    val kind: DeclarationKind,
)

private enum class WriteQualification {
    UNQUALIFIED,
    OWNER,
    OTHER,
}

internal class DeclarationFacts(
    private val kinds: ProfileSyntaxKinds,
    private val calls: CallFacts,
    private val semantics: SemanticFacts,
    private val controlFlow: ControlFlowFacts,
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

    fun declarations(tree: SyntaxTree): Sequence<DeclarationFact> {
        val references = semantics.referencesByName(tree)
        val propertyEntries =
            properties(tree)
                .map { property -> property to controlFlow.enclosingScope(property.node) }
                .toList()
        val writesByDeclaration = declarationWrites(tree, propertyEntries, references)
        val properties =
            propertyEntries.map { (property, scope) ->
                val annotationNodes = annotationNodes(property.node)
                DeclarationFact(
                    node = property.node,
                    kind = declarationKind(scope),
                    name = property.name,
                    nameNode = property.nameNode,
                    annotations = annotationNodes.map { normalizeAnnotation(it.text) },
                    annotationNodes = annotationNodes,
                    precedingComments = commentBlock(property.node),
                    scope = scope,
                    writes = writesByDeclaration[declarationKey(property.node)].orEmpty(),
                )
            }
        val functions =
            functions(tree).map { function ->
                val annotationNodes = annotationNodes(function.node)
                DeclarationFact(
                    node = function.node,
                    kind = DeclarationKind.FUNCTION,
                    name = function.name,
                    nameNode = function.nameNode,
                    annotations = annotationNodes.map { normalizeAnnotation(it.text) },
                    annotationNodes = annotationNodes,
                    precedingComments = commentBlock(function.node),
                    scope = controlFlow.enclosingScope(function.node),
                )
            }
        return (properties + functions).asSequence()
    }

    private fun declarationKind(scope: ScopeFact?): DeclarationKind =
        when (scope?.kind) {
            ScopeKind.FUNCTION, ScopeKind.LAMBDA, ScopeKind.BLOCK -> DeclarationKind.VARIABLE
            else -> DeclarationKind.PROPERTY
        }

    private fun declarationWrites(
        tree: SyntaxTree,
        entries: List<Pair<PropertyFact, ScopeFact?>>,
        references: Map<String, List<SyntaxNode>>,
    ): Map<Pair<Int, Int>, List<WriteFact>> {
        val propertyTargets =
            entries.mapNotNull { (property, scope) ->
                val name = property.name ?: return@mapNotNull null
                val nameNode = property.nameNode ?: return@mapNotNull null
                WriteTarget(name, property.node, nameNode, scope, declarationKind(scope))
            }
        val byName = (propertyTargets + bindingTargets(tree)).groupBy { it.name }
        val writes = linkedMapOf<Pair<Int, Int>, MutableList<WriteFact>>()
        val seen = mutableMapOf<Pair<Int, Int>, MutableSet<Pair<Int, Int>>>()
        for ((name, targets) in byName) {
            for (reference in references[name].orEmpty()) {
                val write = semantics.writes(reference) ?: continue
                val qualification = writeQualification(write, reference)
                if (qualification == WriteQualification.OTHER) continue
                val target =
                    targets
                        .filter { candidate ->
                            !sameNode(candidate.nameNode, reference) &&
                                scopeContains(candidate.scope, reference) &&
                                (qualification != WriteQualification.OWNER || candidate.kind == DeclarationKind.PROPERTY) &&
                                (
                                    qualification == WriteQualification.OWNER ||
                                        candidate.kind != DeclarationKind.VARIABLE ||
                                        candidate.nameNode.startByte <= reference.startByte
                                )
                        }.minWithOrNull(
                            compareBy(
                                { scopeSpan(it.scope) },
                                { it.node.startByte },
                            ),
                        ) ?: continue
                val key = declarationKey(target.node)
                if (seen.getOrPut(key) { mutableSetOf() }.add(write.startByte to write.endByte)) {
                    writes.getOrPut(key) { mutableListOf() } +=
                        WriteFact(write, semantics.writeKind(write), reference)
                }
            }
        }
        return writes
    }

    private fun bindingTargets(tree: SyntaxTree): List<WriteTarget> {
        val result = mutableListOf<WriteTarget>()

        fun add(
            nameNode: SyntaxNode?,
            scope: ScopeFact?,
        ) {
            if (nameNode == null || nameNode.text.isBlank() || scope == null) return
            result += WriteTarget(nameNode.text, nameNode, nameNode, scope, DeclarationKind.VARIABLE)
        }
        for (node in tree.root.descendants()) {
            when {
                node.kind in kinds.parameterKinds -> {
                    add(bindingNameNode(node), callableScope(node))
                }

                node.kind in kinds.parameterListKinds -> {
                    for (child in node.children.filter { it.kind in kinds.identifierKinds }) {
                        add(child, callableScope(child))
                    }
                }
            }
        }
        for (loop in tree.root.descendants().filter { it.kind in kinds.loopKinds }) {
            val limit = controlFlow.loopBody(loop)?.startByte ?: loop.endByte
            val nameNode =
                if (loop.kind in kinds.loopBindingContainerKinds) {
                    loop.children.firstOrNull { it.startByte < limit && it.kind in kinds.identifierKinds }
                } else {
                    loop
                        .descendants()
                        .firstOrNull { it.startByte < limit && it.kind in kinds.bindingKinds }
                        ?.let(::bindingNameNode)
                }
            add(nameNode, ScopeFact(loop, ScopeKind.BLOCK, loop))
        }
        for (catch in tree.root.descendants().filter { it.kind in kinds.catchKinds }) {
            val bodyStart =
                catch
                    .descendants()
                    .firstOrNull {
                        it.kind in kinds.statementListKinds || it.kind in kinds.controlBodyKinds
                    }?.startByte ?: catch.endByte
            val nameNode =
                catch
                    .descendants()
                    .filter {
                        it.startByte < bodyStart &&
                            it.kind in kinds.identifierKinds &&
                            !isWithinTypeReference(it, catch)
                    }.lastOrNull()
            add(nameNode, ScopeFact(catch, ScopeKind.BLOCK, catch))
        }
        return result.distinctBy { it.nameNode.startByte to it.nameNode.endByte }
    }

    private fun bindingNameNode(node: SyntaxNode): SyntaxNode? = node.descendants().firstOrNull { it.kind in kinds.identifierKinds }

    private fun isWithinTypeReference(
        node: SyntaxNode,
        stop: SyntaxNode,
    ): Boolean {
        var current = node.parent
        while (current != null && !sameNode(current, stop)) {
            if (current.kind in kinds.typeReferenceKinds) return true
            current = current.parent
        }
        return false
    }

    private fun callableScope(node: SyntaxNode): ScopeFact? {
        var current = node.parent
        while (current != null) {
            if (current.kind in kinds.functionKinds) return ScopeFact(current, ScopeKind.FUNCTION, current)
            if (current.kind in kinds.lambdaKinds) return ScopeFact(current, ScopeKind.LAMBDA, current)
            current = current.parent
        }
        return controlFlow.enclosingScope(node)
    }

    private fun writeQualification(
        write: SyntaxNode,
        reference: SyntaxNode,
    ): WriteQualification {
        val operand = writeOperand(write, reference) ?: return WriteQualification.OTHER
        val target = operand.text.trim()
        if (!target.contains('.')) {
            return if (target == reference.text.trim()) WriteQualification.UNQUALIFIED else WriteQualification.OTHER
        }
        val receiver = target.substringBeforeLast('.').trim()
        val member = target.substringAfterLast('.').trim()
        if (member != reference.text.trim()) return WriteQualification.OTHER
        return if (isOwnerReceiver(receiver)) WriteQualification.OWNER else WriteQualification.OTHER
    }

    private fun writeOperand(
        write: SyntaxNode,
        reference: SyntaxNode,
    ): SyntaxNode? {
        var current = reference
        while (true) {
            val parent = current.parent ?: return null
            if (sameNode(parent, write)) return current
            current = parent
        }
    }

    private fun isOwnerReceiver(receiver: String): Boolean =
        receiver == "this" || receiver == "self" || receiver == "Me" || receiver.startsWith("this@")

    private fun declarationKey(node: SyntaxNode): Pair<Int, Int> = node.startByte to node.endByte

    private fun scopeContains(
        scope: ScopeFact?,
        node: SyntaxNode,
    ): Boolean {
        val range = scope?.node ?: return false
        return node.startByte >= range.startByte && node.endByte <= range.endByte
    }

    private fun scopeSpan(scope: ScopeFact?): Int {
        val node = scope?.node ?: return Int.MAX_VALUE
        return node.endByte - node.startByte
    }

    private fun sameNode(
        first: SyntaxNode?,
        second: SyntaxNode,
    ): Boolean = first != null && first.startByte == second.startByte && first.endByte == second.endByte

    private fun commentBlock(node: SyntaxNode): List<CommentFact> = precedingComments(node).map(::commentFact).toList().reversed()

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

    fun annotationNodes(declaration: SyntaxNode): List<SyntaxNode> {
        var node: SyntaxNode? = declaration
        while (node != null) {
            val container = node.children.firstOrNull { it.kind in kinds.modifierKinds }
            val annotations =
                container?.children?.filter { it.kind in kinds.annotationKinds }
                    ?: node.children.filter { it.kind in kinds.annotationKinds }
            if (annotations.isNotEmpty()) return annotations
            val ascend = node.kind in kinds.declarationWrapperKinds || node.kind in kinds.propertyKinds
            node = if (ascend) node.parent else null
        }
        return emptyList()
    }

    fun annotationTexts(declaration: SyntaxNode): List<String> = annotationNodes(declaration).map { normalizeAnnotation(it.text) }

    fun commentBefore(node: SyntaxNode): CommentFact? = precedingComments(node).firstOrNull()?.let(::commentFact)

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

    fun guards(tree: SyntaxTree): Sequence<GuardFact> {
        val ifFacts = ifs(tree).associateBy { nodeKey(it.node) }
        val loopFacts = loops(tree).associateBy { nodeKey(it.node) }
        return tree.root
            .descendants()
            .filter { it.kind in kinds.guardKinds }
            .mapNotNull { node ->
                val ifFact = ifFacts[nodeKey(node)]
                val loopFact = loopFacts[nodeKey(node)]
                when {
                    ifFact != null -> {
                        GuardFact(node, GuardKind.CONDITIONAL, ifFact.condition, ifFact.thenBody, ifFact.elseBody)
                    }

                    loopFact != null -> {
                        GuardFact(node, GuardKind.LOOP, loopFact.condition, loopFact.body, null)
                    }

                    else -> {
                        GuardFact(node, GuardKind.OTHER, null, null, null)
                    }
                }
            }.distinctBy { nodeKey(it.node) }
    }

    private fun nodeKey(node: SyntaxNode): Pair<Int, Int> = node.startByte to node.endByte

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

    fun scopes(tree: SyntaxTree): Sequence<ScopeFact> =
        sequence {
            yield(ScopeFact(tree.root, ScopeKind.FILE))
            for (node in tree.root.descendants()) {
                if (node.startByte == tree.root.startByte && node.endByte == tree.root.endByte) continue
                val kind = scopeKind(node) ?: continue
                yield(ScopeFact(node, kind, scopeOwner(node)))
            }
        }.distinctBy { it.node.startByte to it.node.endByte }

    fun enclosingScope(node: SyntaxNode): ScopeFact? {
        var current = node.parent ?: return null
        var outermost = current
        while (true) {
            val kind = scopeKind(current)
            if (kind != null) return ScopeFact(current, kind, scopeOwner(current))
            val parent = current.parent ?: break
            current = parent
            outermost = parent
        }
        return ScopeFact(outermost, ScopeKind.FILE)
    }

    fun enclosingBlock(node: SyntaxNode): SyntaxNode? {
        var current: SyntaxNode? = node
        while (current != null) {
            if (
                current.kind in kinds.statementListKinds ||
                current.kind in kinds.functionBodyKinds ||
                current.kind in kinds.controlBodyKinds ||
                current.kind in kinds.catchKinds ||
                current.kind in kinds.finallyKinds ||
                current.kind in kinds.tryKinds
            ) {
                return current
            }
            current = current.parent
        }
        return null
    }

    private fun scopeKind(node: SyntaxNode): ScopeKind? {
        if (node.kind in kinds.classBodyKinds) {
            return if (ancestorOf(node, kinds.classKinds) != null) ScopeKind.CLASS else null
        }
        if (node.kind in kinds.classKinds) return ScopeKind.CLASS
        if (node.kind in kinds.statementListKinds) {
            return if (node.parent == null) ScopeKind.FILE else ScopeKind.BLOCK
        }
        return when (node.kind) {
            in kinds.functionKinds -> ScopeKind.FUNCTION
            in kinds.lambdaKinds -> ScopeKind.LAMBDA
            in kinds.functionBodyKinds -> ScopeKind.BLOCK
            in kinds.controlBodyKinds -> ScopeKind.BLOCK
            else -> null
        }
    }

    private fun scopeOwner(node: SyntaxNode): SyntaxNode? {
        val kind = scopeKind(node)
        if (kind == ScopeKind.FUNCTION || kind == ScopeKind.LAMBDA) return node
        if (kind == ScopeKind.CLASS) {
            return if (node.kind in kinds.classBodyKinds) ancestorOf(node, kinds.classKinds) else node
        }
        var current = node.parent
        while (current != null && scopeKind(current) == ScopeKind.BLOCK) {
            current = current.parent
        }
        return current
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
