package dev.cint.rulepacks.intents.support

import dev.cint.api.SyntaxNode

internal fun callbackParameter(parameter: String): CallbackParameter? {
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

internal fun parameterText(function: SyntaxNode): String? {
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

internal fun splitTopLevel(parameters: String): List<String> {
    val result = mutableListOf<String>()
    var angle = 0
    var parens = 0
    var brackets = 0
    var braces = 0
    var start = 0
    for (index in parameters.indices) {
        val character = parameters[index]
        when (character) {
            '<' -> {
                angle++
            }

            '>' if angle > 0 -> {
                angle--
            }

            '(' -> {
                parens++
            }

            ')' if parens > 0 -> {
                parens--
            }

            '[' -> {
                brackets++
            }

            ']' if brackets > 0 -> {
                brackets--
            }

            '{' -> {
                braces++
            }

            '}' if braces > 0 -> {
                braces--
            }

            ',' if angle == 0 && parens == 0 && brackets == 0 && braces == 0 -> {
                result += parameters.substring(start, index).trim()
                start = index + 1
            }
        }
    }
    result += parameters.substring(start).trim()
    return result.filter { it.isNotEmpty() }
}

private val PARAMETER_LIST_KINDS =
    setOf(
        "function_value_parameters",
        "formal_parameters",
        "parameter_list",
        "parameters",
        "function_parameters",
        "lambda_parameters",
    )
