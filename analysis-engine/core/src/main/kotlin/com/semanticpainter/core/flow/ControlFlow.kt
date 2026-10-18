package com.semanticpainter.core.flow

import com.semanticpainter.core.syntax.SyntaxModel
import com.semanticpainter.core.syntax.SyntaxNode
import com.semanticpainter.core.syntax.SyntaxTree

data class Guard(
    val condition: SyntaxNode,
    val node: SyntaxNode,
    val onTrue: Boolean,
)

data class ReturnSite(
    val node: SyntaxNode,
    val expression: SyntaxNode?,
    val guards: List<Guard>,
    val loop: SyntaxNode?,
    val handler: SyntaxNode?,
)

data class ControlFlow(
    val returns: List<ReturnSite>,
)

class ControlFlowBuilder(
    private val tree: SyntaxTree,
    private val syntax: SyntaxModel,
) {
    private val ifs = syntax.ifs(tree).associateBy { key(it.node) }
    private val loops = syntax.loops(tree).associateBy { key(it.node) }
    private val tries = syntax.tries(tree).associateBy { key(it.node) }

    fun build(): ControlFlow {
        val returns = mutableListOf<ReturnSite>()
        for (function in syntax.functions(tree)) {
            val body = function.body ?: continue
            walkBody(body, emptyList(), null, null, returns)
        }
        return ControlFlow(returns)
    }

    private fun walkBody(
        container: SyntaxNode,
        guards: List<Guard>,
        loop: SyntaxNode?,
        handler: SyntaxNode?,
        out: MutableList<ReturnSite>,
    ) {
        val statements = syntax.statements(container)
        if (statements != null) {
            statements.forEach { walkStatement(it, guards, loop, handler, out) }
        } else {
            walkStatement(container, guards, loop, handler, out)
        }
    }

    private fun walkStatement(
        statement: SyntaxNode,
        guards: List<Guard>,
        loop: SyntaxNode?,
        handler: SyntaxNode?,
        out: MutableList<ReturnSite>,
    ) {
        val ifFact = ifs[key(statement)]
        if (ifFact != null) {
            val condition = ifFact.condition
            if (condition != null) {
                ifFact.thenBody?.let {
                    walkBody(it, guards + Guard(condition, ifFact.node, true), loop, handler, out)
                }
                ifFact.elseBody?.let {
                    walkBody(it, guards + Guard(condition, ifFact.node, false), loop, handler, out)
                }
            } else {
                ifFact.thenBody?.let { walkBody(it, guards, loop, handler, out) }
                ifFact.elseBody?.let { walkBody(it, guards, loop, handler, out) }
            }
            return
        }

        val loopFact = loops[key(statement)]
        if (loopFact != null) {
            loopFact.body?.let { walkBody(it, guards, loopFact.node, handler, out) }
            return
        }

        val tryFact = tries[key(statement)]
        if (tryFact != null) {
            tryFact.body?.let { walkBody(it, guards, loop, handler, out) }
            tryFact.catches.forEach { walkBody(it, guards, loop, it, out) }
            tryFact.finallyBlock?.let { walkBody(it, guards, loop, it, out) }
            return
        }

        if (syntax.isReturn(statement)) {
            out +=
                ReturnSite(
                    node = statement,
                    expression = syntax.exitExpression(statement),
                    guards = guards,
                    loop = loop,
                    handler = handler,
                )
            return
        }

        if (syntax.statements(statement) != null) {
            walkBody(statement, guards, loop, handler, out)
        }
    }

    private fun key(node: SyntaxNode): NodeKey = NodeKey(node.startByte, node.endByte)

    private data class NodeKey(
        val start: Int,
        val end: Int,
    )
}
