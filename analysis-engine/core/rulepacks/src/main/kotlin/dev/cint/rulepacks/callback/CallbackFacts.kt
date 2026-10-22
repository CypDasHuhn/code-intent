package dev.cint.rulepacks.callback

import dev.cint.api.CallFact
import dev.cint.api.SyntaxNode
import dev.cint.api.View

internal data class CallbackParameter(
    val name: String,
    val type: String?,
    val default: String?,
) {
    private val declaredType: String? get() = type?.trim()?.ifEmpty { null }

    val isFunctionTyped: Boolean
        get() {
            val declared = declaredType ?: return default?.let(::looksLikeFunctionDefault) == true
            if (FUNCTION_TYPE_MARKERS.any { declared.contains(it, ignoreCase = true) }) return true
            return default?.let(::looksLikeFunctionDefault) == true
        }

    val invocationMethod: String?
        get() {
            val declared = declaredType ?: return null
            return when (outerTypeName(declared)) {
                "Consumer", "BiConsumer" -> "accept"
                "Runnable" -> "run"
                "Supplier" -> "get"
                "Callable" -> "call"
                "Predicate", "BiPredicate" -> "test"
                "Function", "BiFunction", "UnaryOperator" -> "apply"
                else -> if (isKotlinFunctionType(declared)) "invoke" else null
            }
        }
}

internal fun callbackParameterAt(
    view: View,
    call: CallFact,
): CallbackParameter? {
    val callee = call.callee ?: return null
    val receiver = call.receiver
    for (callable in callableCandidates(view, call.node)) {
        val parameters = callbackParameters(callable)
        val parameter =
            if (receiver == null) {
                parameters[callee]
            } else {
                val receiverName = IDENTIFIER.findAll(receiver.text).lastOrNull()?.value ?: continue
                parameters[receiverName]?.takeIf { it.invocationMethod == callee }
            }
        if (parameter != null && parameter.isFunctionTyped) return parameter
    }
    return null
}

private fun callableCandidates(
    view: View,
    node: SyntaxNode,
): List<SyntaxNode> {
    val function = view.enclosingFunction(node)
    val result = mutableListOf<SyntaxNode>()
    var current = node.parent
    while (current != null) {
        if (isLambda(current.kind)) result += current
        if (function != null && current.startByte == function.startByte && current.endByte == function.endByte) {
            result += current
            break
        }
        current = current.parent
    }
    return result
}

private fun isLambda(kind: String): Boolean = kind in LAMBDA_KINDS || kind.contains("lambda")

internal fun callbackParameters(function: SyntaxNode): Map<String, CallbackParameter> {
    val parameters = parameterText(function) ?: return emptyMap()
    return splitTopLevel(parameters)
        .mapNotNull(::callbackParameter)
        .associateBy { it.name }
}

internal fun containsConfiguredTarget(
    text: String,
    targets: Set<String>,
): Boolean {
    val normalized = text.replace("::", ".")
    return targets.any { target ->
        target.contains('.') &&
            Regex("""(?<![A-Za-z0-9_$])${Regex.escape(target)}(?![A-Za-z0-9_$])""")
                .containsMatchIn(normalized)
    }
}

internal val IDENTIFIER = Regex("""[A-Za-z_$][A-Za-z0-9_$]*""")

private val LAMBDA_KINDS =
    setOf(
        "arrow_function",
        "function_expression",
        "lambda_literal",
        "annotated_lambda",
        "lambda_expression",
        "lambda",
    )
