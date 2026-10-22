package dev.cint.rulepacks.intents

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
            val declared = declaredType
            if (declared == null) return default?.let(::looksLikeFunctionDefault) == true
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

private fun outerTypeName(type: String): String {
    val trimmed = type.trim().removePrefix("suspend ").trim()
    return trimmed
        .substringBefore('<')
        .substringAfterLast('.')
        .removeSuffix("?")
        .trim()
}

private fun isKotlinFunctionType(type: String): Boolean {
    val trimmed = type.trim()
    if (trimmed.startsWith("(") || trimmed.startsWith("suspend (")) return true
    var angle = 0
    var parens = 0
    var brackets = 0
    var braces = 0
    for (index in trimmed.indices) {
        when (trimmed[index]) {
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

            '-' -> {
                if (angle == 0 && parens == 0 && brackets == 0 && braces == 0 &&
                    trimmed.getOrNull(index + 1) == '>'
                ) {
                    return true
                }
            }
        }
    }
    return false
}

private val FUNCTION_TYPE_MARKERS =
    setOf(
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

private val FUNCTION_DEFAULT_MARKERS = setOf("::", "=>", "->", "{")

private val REFERENCE_DEFAULT = Regex("""^[A-Za-z_$][A-Za-z0-9_$]*(?:\s*\.\s*[A-Za-z_$][A-Za-z0-9_$]*)*$""")

private val DEFAULT_LITERALS = setOf("null", "true", "false", "undefined", "nil", "None", "this", "super")

private val IDENTIFIER = Regex("""[A-Za-z_$][A-Za-z0-9_$]*""")

private val PARAMETER_LIST_KINDS =
    setOf(
        "function_value_parameters",
        "formal_parameters",
        "parameter_list",
        "parameters",
        "function_parameters",
        "lambda_parameters",
    )

private val LAMBDA_KINDS =
    setOf(
        "arrow_function",
        "function_expression",
        "lambda_literal",
        "annotated_lambda",
        "lambda_expression",
        "lambda",
    )

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

private fun callbackParameter(parameter: String): CallbackParameter? {
    val declaration = parameter.replace(Regex("""^\s*\[[^\]]*]\s*"""), "").trim()
    if (declaration.isEmpty()) return null
    val assignment = assignmentIndex(declaration)
    val signature = if (assignment >= 0) declaration.substring(0, assignment).trim() else declaration
    val default = if (assignment >= 0) declaration.substring(assignment + 1).trim().ifEmpty { null } else null
    val colon = separatorColon(signature)
    val name: String?
    val type: String?
    if (colon >= 0) {
        name = identifier(signature.substring(0, colon))
        type = signature.substring(colon + 1).trim().ifEmpty { null }
    } else {
        name = identifier(signature)
        type =
            name
                ?.let { signature.removeSuffix(it).trim() }
                ?.ifEmpty { null }
    }
    return name?.let { CallbackParameter(it, type, default) }
}

private fun parameterText(function: SyntaxNode): String? {
    val list =
        function.children.firstOrNull { isParameterList(it.kind) }
            ?: function.descendants().firstOrNull { isParameterList(it.kind) }
    if (list != null) {
        val text = list.text
        val open = text.indexOf('(')
        if (open >= 0) {
            val close = matchingClose(text, open)
            if (close > open) return text.substring(open + 1, close)
        }
        return text.trim().removePrefix("(").removeSuffix(")")
    }
    val text = function.text
    val open = text.indexOf('(')
    if (open < 0) return null
    val close = matchingClose(text, open)
    return if (close > open) text.substring(open + 1, close) else null
}

private fun isParameterList(kind: String): Boolean =
    kind in PARAMETER_LIST_KINDS ||
        (kind.contains("parameter") && !kind.contains("type"))

private fun identifier(value: String): String? = IDENTIFIER.findAll(value).lastOrNull()?.value

private fun looksLikeFunctionDefault(value: String): Boolean {
    val trimmed = value.trim()
    if (FUNCTION_DEFAULT_MARKERS.any { marker -> marker in trimmed }) return true
    if (trimmed in DEFAULT_LITERALS) return false
    return REFERENCE_DEFAULT.matches(trimmed)
}

private fun matchingClose(
    text: String,
    open: Int,
): Int {
    var depth = 0
    for (index in open until text.length) {
        when (text[index]) {
            '(' -> {
                depth++
            }

            ')' -> {
                depth--
                if (depth == 0) return index
            }
        }
    }
    return -1
}

private fun assignmentIndex(text: String): Int {
    var angle = 0
    var parens = 0
    var brackets = 0
    var braces = 0
    for (index in text.indices) {
        when (text[index]) {
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

            '=' -> {
                if (angle == 0 && parens == 0 && brackets == 0 && braces == 0) {
                    val previous = text.getOrNull(index - 1)
                    val next = text.getOrNull(index + 1)
                    if (next != '>' && next != '=' && previous != '=' && previous != '!' && previous != '<' && previous != '>') {
                        return index
                    }
                }
            }
        }
    }
    return -1
}

private fun separatorColon(text: String): Int {
    var angle = 0
    var parens = 0
    var brackets = 0
    var braces = 0
    for (index in text.indices) {
        when (text[index]) {
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

            ':' -> {
                if (angle == 0 && parens == 0 && brackets == 0 && braces == 0 &&
                    text.getOrNull(index + 1) != ':' && text.getOrNull(index - 1) != ':'
                ) {
                    return index
                }
            }
        }
    }
    return -1
}

private fun splitTopLevel(parameters: String): List<String> {
    val result = mutableListOf<String>()
    var angle = 0
    var parens = 0
    var brackets = 0
    var braces = 0
    var start = 0
    for (index in parameters.indices) {
        val character = parameters[index]
        if (character == '<') {
            angle++
        } else if (character == '>' && angle > 0) {
            angle--
        } else if (character == '(') {
            parens++
        } else if (character == ')' && parens > 0) {
            parens--
        } else if (character == '[') {
            brackets++
        } else if (character == ']' && brackets > 0) {
            brackets--
        } else if (character == '{') {
            braces++
        } else if (character == '}' && braces > 0) {
            braces--
        } else if (character == ',' && angle == 0 && parens == 0 && brackets == 0 && braces == 0) {
            result += parameters.substring(start, index).trim()
            start = index + 1
        }
    }
    result += parameters.substring(start).trim()
    return result.filter { it.isNotEmpty() }
}
