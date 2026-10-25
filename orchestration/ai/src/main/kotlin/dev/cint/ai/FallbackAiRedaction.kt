package dev.cint.ai

import java.nio.file.FileSystems
import java.nio.file.Path

data class FallbackAiRedaction(
    val allowedPathGlobs: List<String> = emptyList(),
    val redactPatterns: List<String> = emptyList(),
) {
    init {
        redactPatterns
            .map(String::trim)
            .filter(String::isNotEmpty)
            .forEach { pattern ->
                require(runCatching { Regex(pattern) }.isSuccess) {
                    "Invalid fallback AI redaction pattern: $pattern"
                }
            }
    }

    fun allows(path: String?): Boolean {
        val globs = allowedPathGlobs.map(String::trim).filter(String::isNotEmpty)
        if (globs.isEmpty()) return true
        val candidate = path?.let { runCatching { Path.of(it) }.getOrNull() } ?: return false
        return globs.any { glob ->
            runCatching { FileSystems.getDefault().getPathMatcher("glob:$glob").matches(candidate) }
                .getOrDefault(false)
        }
    }

    fun redact(text: String): String {
        val patterns =
            redactPatterns
                .map(String::trim)
                .filter(String::isNotEmpty)
                .map(::Regex)
        if (patterns.isEmpty()) return text
        var redacted = text
        for (pattern in patterns) {
            redacted = pattern.replace(redacted) { match -> "*".repeat(match.value.length) }
        }
        return redacted
    }

    companion object {
        val NONE = FallbackAiRedaction()
    }
}
