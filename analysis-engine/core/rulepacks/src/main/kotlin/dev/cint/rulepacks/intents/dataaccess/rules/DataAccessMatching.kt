package dev.cint.rulepacks.intents.dataaccess

import dev.cint.api.TypeRef

internal fun isAmbiguous(
    callee: String,
    language: DataAccessLanguageConfig,
): Boolean {
    if (containsName(language.compoundMutationCalls, callee)) return false
    if (containsName(language.lookupCalls, callee)) return false
    if (containsName(language.searchCalls, callee)) return false
    if (language.searchPrefixes.any { callee.startsWith(it, ignoreCase = true) }) return false
    return containsName(language.ambiguousCalls, callee) ||
        language.ambiguousPrefixes.any { callee.startsWith(it, ignoreCase = true) }
}

internal fun isKnownMap(
    receiver: String,
    language: DataAccessLanguageConfig,
): Boolean =
    matchesName(receiver, language.knownMaps) ||
        language.knownMapSuffixes.any { hasTokenSuffix(receiverNameTokens(receiver), tokenize(it)) }

internal fun matchesName(
    receiver: String,
    names: Set<String>,
): Boolean {
    val segments = receiverSegments(receiver)
    val allTokens = segments.flatten()
    return names.any { expected ->
        val expectedTokens = tokenize(expected)
        when {
            expectedTokens.isEmpty() -> {
                false
            }

            expectedTokens.size == 1 -> {
                allTokens.any { it == expectedTokens.single() }
            }

            else -> {
                segments.any { containsTokens(it, expectedTokens) } ||
                    containsTokens(allTokens, expectedTokens)
            }
        }
    }
}

internal fun containsTokens(
    tokens: List<String>,
    expected: List<String>,
): Boolean = tokens.windowed(expected.size).any { it == expected }

internal fun hasTokenSuffix(
    tokens: List<String>,
    suffix: List<String>,
): Boolean = suffix.isNotEmpty() && tokens.size >= suffix.size && tokens.takeLast(suffix.size) == suffix

internal fun receiverName(receiver: String): String =
    receiver
        .split('.')
        .lastOrNull()
        ?.substringBefore('(')
        ?.substringBefore('[')
        ?.trim()
        .orEmpty()

internal fun receiverNameTokens(receiver: String): List<String> = receiverSegments(receiver).lastOrNull().orEmpty()

internal fun receiverSegments(receiver: String): List<List<String>> =
    receiver
        .split('.')
        .map { tokenize(it.substringBefore('(').substringBefore('[')) }
        .filter { it.isNotEmpty() }

internal fun tokenize(value: String): List<String> {
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
            val boundary =
                ((previous.isLowerCase() || previous.isDigit()) && character.isUpperCase()) ||
                    (
                        previous.isUpperCase() &&
                            character.isUpperCase() &&
                            next != null &&
                            next.isLowerCase()
                    )
            if (boundary) flush()
        }
        current.append(character)
    }
    flush()
    return tokens
}

internal fun containsName(
    names: Set<String>,
    value: String,
): Boolean = names.any { it.equals(value, ignoreCase = true) }

internal fun typeMatches(
    type: TypeRef?,
    configured: Set<String>,
): Boolean {
    if (type == null) return false
    val actual = type.fqName
    return configured.any { raw ->
        val expected = raw.trim()
        when {
            expected.isEmpty() -> false
            expected.contains('.') -> actual == expected || actual.endsWith(".$expected")
            else -> hasTokenSuffix(tokenize(actual.substringAfterLast('.')), tokenize(expected))
        }
    }
}

internal fun matchesTypeSuffix(
    type: TypeRef?,
    suffixes: Set<String>,
): Boolean {
    val actual = type?.fqName?.substringAfterLast('.') ?: return false
    val tokens = tokenize(actual)
    return suffixes.any { hasTokenSuffix(tokens, tokenize(it)) }
}
