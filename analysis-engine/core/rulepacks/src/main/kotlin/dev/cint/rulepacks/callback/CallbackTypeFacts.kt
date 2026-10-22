package dev.cint.rulepacks.callback

internal fun outerTypeName(type: String): String {
    val trimmed = type.trim().removePrefix("suspend ").trim()
    return trimmed
        .substringBefore('<')
        .substringAfterLast('.')
        .removeSuffix("?")
        .trim()
}

internal fun isKotlinFunctionType(type: String): Boolean {
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

internal fun looksLikeFunctionDefault(value: String): Boolean {
    val trimmed = value.trim()
    if (FUNCTION_DEFAULT_MARKERS.any { marker -> marker in trimmed }) return true
    if (trimmed in DEFAULT_LITERALS) return false
    return REFERENCE_DEFAULT.matches(trimmed)
}

internal val FUNCTION_TYPE_MARKERS =
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
