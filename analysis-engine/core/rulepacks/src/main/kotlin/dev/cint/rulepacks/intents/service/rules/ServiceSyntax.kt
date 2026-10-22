package dev.cint.rulepacks.intents.service.rules

import dev.cint.api.CallFact
import dev.cint.api.FunctionFact
import dev.cint.api.SyntaxNode
import dev.cint.api.View

internal fun bodyUnits(
    view: View,
    function: FunctionFact,
): List<SyntaxNode> {
    val body = function.body
    if (body != null) {
        val statements = view.statements(body)
        if (statements != null) return statements.filter { it.text.isNotBlank() }
        return if (containsCall(view, body)) listOf(body) else emptyList()
    }
    val nameEnd = function.nameNode?.endByte ?: function.node.startByte
    return function.node.children
        .lastOrNull { it.startByte >= nameEnd && containsCall(view, it) }
        ?.let { listOf(it) }
        .orEmpty()
}

private fun containsCall(
    view: View,
    node: SyntaxNode,
): Boolean = view.calls().any { within(it.node, node) }

internal fun functionCalls(
    view: View,
    function: FunctionFact,
): List<CallFact> =
    view.calls().filter { call ->
        within(call.node, function.node) && sameFunction(view.enclosingFunction(call.node), function.node)
    }

internal fun sameFunction(
    node: SyntaxNode?,
    function: SyntaxNode,
): Boolean = node != null && node.startByte == function.startByte && node.endByte == function.endByte

internal fun within(
    node: SyntaxNode,
    container: SyntaxNode,
): Boolean = node.startByte >= container.startByte && node.endByte <= container.endByte

internal fun outermost(calls: List<CallFact>): List<CallFact> =
    calls.filterNot { call ->
        calls.any { other ->
            other !== call && within(call.node, other.node) && other.node.startByte != call.node.startByte
        }
    }

internal fun enclosingClassName(
    view: View,
    function: FunctionFact,
): String? {
    val owner = view.enclosingScope(function.node)?.owner ?: return null
    return classNameOf(owner)
}

internal fun classNameOf(node: SyntaxNode): String? = node.childByField("name")?.text ?: TYPE_NAME.find(node.text)?.groupValues?.get(1)

private val TYPE_NAME = Regex("""\b(?:class|object|interface|record|struct)\s+([A-Za-z_][A-Za-z0-9_]*)""")
