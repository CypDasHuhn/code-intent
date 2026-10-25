package dev.cint.contracts.facts

object SymbolNames {
    fun tokenize(value: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()

        fun flush() {
            if (current.isNotEmpty()) {
                tokens += current.toString().lowercase()
                current.clear()
            }
        }
        for (index in value.indices) {
            val character = value[index]
            if (!character.isLetterOrDigit()) {
                flush()
                continue
            }
            if (current.isNotEmpty()) {
                val previous = value[index - 1]
                val next = value.getOrNull(index + 1)
                if ((previous.isLowerCase() || previous.isDigit()) && character.isUpperCase()) flush()
                if (previous.isUpperCase() && character.isUpperCase() && next != null && next.isLowerCase()) flush()
            }
            current.append(character)
        }
        flush()
        return tokens
    }
}

fun CallFact.matchesCallee(
    exact: Set<String>,
    ignoreCase: Boolean = true,
): Boolean = exact.any { expected -> callee?.equals(expected, ignoreCase) == true }

fun CallFact.matchesCalleePrefix(
    prefixes: List<String>,
    ignoreCase: Boolean = true,
): Boolean {
    if (prefixes.isEmpty()) return false
    val actual = callee.orEmpty()
    return prefixes.any { prefix -> actual.startsWith(prefix, ignoreCase) }
}

fun CallFact.matchesCalleeToken(
    token: String,
    ignoreCase: Boolean = true,
): Boolean = tokenizedCallee().any { it.equals(token, ignoreCase) }

fun CallFact.tokenizedCallee(): List<String> = calleeTokens.ifEmpty { SymbolNames.tokenize(callee.orEmpty()) }

fun CallFact.receiverQualifierMatches(qualifiers: Set<String>): Boolean =
    qualifiers.any { qualifier ->
        receiverSegments.any { segment -> segment.equals(qualifier, ignoreCase = true) } ||
            receiverPath?.equals(qualifier, ignoreCase = true) == true
    }

fun CallFact.receiverSuffixMatches(suffixes: Set<String>): Boolean =
    suffixes.any { suffix -> receiverName?.endsWith(suffix, ignoreCase = true) == true }

fun CallFact.pathMatches(
    exact: Set<String>,
    prefixes: List<String> = emptyList(),
    suffixes: List<String> = emptyList(),
): Boolean {
    val segments = pathSegments
    if (segments.isEmpty()) return false
    return exact.any { path ->
        val expected = SymbolNames.tokenize(path)
        expected.isNotEmpty() &&
            segments.size >= expected.size &&
            expected.indices.all { index ->
                segments[segments.size - expected.size + index].equals(expected[index], ignoreCase = true)
            }
    } ||
        prefixes.any { prefix ->
            val expected = SymbolNames.tokenize(prefix)
            expected.isNotEmpty() &&
                expected.indices.all { index -> segments.getOrNull(index).equals(expected[index], ignoreCase = true) }
        } ||
        suffixes.any { suffix ->
            val expected = SymbolNames.tokenize(suffix)
            expected.isNotEmpty() &&
                segments.size >= expected.size &&
                expected.indices.all { index ->
                    segments[segments.size - expected.size + index].equals(expected[index], ignoreCase = true)
                }
        }
}

fun ImportFact.matchesPackage(packages: Set<String>): Boolean =
    packages.any { expected -> path == expected || pathSegmentsPrefix(expected) }

private fun ImportFact.pathSegmentsPrefix(expected: String): Boolean {
    val prefix = SymbolNames.tokenize(expected)
    val actual = SymbolNames.tokenize(path)
    return prefix.isNotEmpty() &&
        actual.size >= prefix.size &&
        prefix.indices.all { index -> actual[index].equals(prefix[index], ignoreCase = false) }
}

fun GuardFact.matchesConditionName(names: Set<String>): Boolean {
    val condition = conditionNames.joinToString("").lowercase()
    return names.any { name ->
        val exact = conditionNames.any { it.equals(name, ignoreCase = true) }
        exact || (condition.isNotEmpty() && condition.contains(SymbolNames.tokenize(name).joinToString("").lowercase()))
    }
}

fun ParameterFact.matchesType(
    types: Set<String>,
    suffixes: Set<String>,
): Boolean = typeNames.any { name -> typeNameMatches(name, types, suffixes) }

fun CreationFact.matchesType(
    types: Set<String>,
    suffixes: Set<String>,
): Boolean = typeNames.any { name -> typeNameMatches(name, types, suffixes) }

private fun typeNameMatches(
    actual: String,
    types: Set<String>,
    suffixes: Set<String>,
): Boolean {
    val simple = actual.substringAfterLast('.')
    return types.any { expected ->
        actual == expected ||
            actual.endsWith(".$expected") ||
            (!actual.contains('.') && expected.substringAfterLast('.') == actual)
    } || suffixes.any { simple.endsWith(it) }
}

fun isFieldName(
    name: String,
    prefixes: List<String>,
    receiverPrefix: String?,
    underscorePrefix: String?,
): Boolean {
    val simple = name.substringAfterLast('.')
    return (receiverPrefix != null && name.startsWith(receiverPrefix)) ||
        (underscorePrefix != null && simple.startsWith(underscorePrefix)) ||
        prefixes.any { simple.startsWith(it) }
}

fun CommentFact.matchesDirective(directive: String): Boolean =
    directives.any { it.equals(directive, ignoreCase = true) } ||
        magicComment?.equals(directive, ignoreCase = true) == true

fun CallFact.matchesLocalCandidate(
    exact: Set<String>,
    prefixes: List<String>,
): Boolean {
    val callee = callee.orEmpty()
    if (exact.any { it.equals(callee, ignoreCase = true) }) return true
    val first = tokenizedCallee().firstOrNull() ?: return false
    return prefixes.any { prefix -> first.equals(prefix, ignoreCase = true) } && tokenizedCallee().size > 1
}

fun CallFact.matchesCalleeSuffix(suffixes: Set<String>): Boolean =
    suffixes.any { suffix -> callee?.endsWith(suffix, ignoreCase = false) == true }
