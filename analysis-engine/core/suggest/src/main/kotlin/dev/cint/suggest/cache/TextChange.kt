package dev.cint.suggest.cache

import dev.cint.core.support.hashing.normalizedSourceText

data class ChangeSet(
    val ranges: List<IntRange>,
    val names: Set<String>,
) {
    val empty: Boolean get() = ranges.isEmpty() && names.isEmpty()

    companion object {
        val NONE: ChangeSet = ChangeSet(emptyList(), emptySet())
    }
}

object TextChange {
    fun between(
        oldText: String,
        newText: String,
    ): ChangeSet {
        if (oldText == newText) return ChangeSet.NONE
        if (normalizedSourceText(oldText) == normalizedSourceText(newText)) {
            return ChangeSet.NONE
        }
        var prefix = 0
        val maxPrefix = minOf(oldText.length, newText.length)
        while (prefix < maxPrefix && oldText[prefix] == newText[prefix]) prefix += 1
        var suffix = 0
        val maxSuffix = minOf(oldText.length - prefix, newText.length - prefix)
        while (
            suffix < maxSuffix &&
            oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]
        ) {
            suffix += 1
        }
        val startByte = newText.substring(0, prefix).toByteArray(Charsets.UTF_8).size
        val endByte = newText.substring(0, newText.length - suffix).toByteArray(Charsets.UTF_8).size
        val range =
            if (endByte > startByte) {
                listOf(startByte until endByte)
            } else {
                listOf(startByte..startByte)
            }
        val changedSource =
            oldText.substring(prefix, oldText.length - suffix) +
                "\n" +
                newText.substring(prefix, newText.length - suffix)
        return ChangeSet(range, identifiers(changedSource))
    }

    private fun identifiers(text: String): Set<String> =
        Regex("[A-Za-z_][A-Za-z0-9_]*")
            .findAll(text)
            .map { it.value }
            .filter { it !in KEYWORDS }
            .toSet()

    private val KEYWORDS =
        setOf(
            "val",
            "var",
            "fun",
            "class",
            "object",
            "interface",
            "if",
            "else",
            "when",
            "for",
            "while",
            "do",
            "return",
            "throw",
            "try",
            "catch",
            "finally",
            "import",
            "package",
            "private",
            "public",
            "protected",
            "internal",
            "override",
            "const",
            "let",
            "const",
            "function",
            "new",
            "this",
            "super",
            "null",
            "true",
            "false",
        )
}
