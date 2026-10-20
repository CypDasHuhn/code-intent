package dev.cint.core.syntax

import dev.cint.api.GuardFact
import dev.cint.api.GuardKind
import dev.cint.api.IfFact
import dev.cint.api.ScopeFact
import dev.cint.api.ScopeKind
import dev.cint.api.SyntaxNode
import dev.cint.api.TryFact

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
            it.kind !in kinds.loopConditionExcludedKinds &&
                it.kind !in kinds.loopTokenKinds &&
                it.kind !in kinds.bindingKinds &&
                it.kind !in kinds.loopBindingContainerKinds
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
