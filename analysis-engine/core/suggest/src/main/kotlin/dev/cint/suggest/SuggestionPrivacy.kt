package dev.cint.suggest

import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Path

internal object SuggestionPrivacy {
    fun allows(
        path: String,
        allowGlobs: List<String>,
    ): Boolean {
        val globs = allowGlobs.map { it.trim() }.filter { it.isNotEmpty() }
        if (globs.isEmpty()) return true
        val candidate = runCatching { Path.of(path) }.getOrNull() ?: return false
        return globs.any { glob ->
            runCatching { FileSystems.getDefault().getPathMatcher("glob:$glob").matches(candidate) }
                .getOrDefault(false)
        }
    }

    fun redact(
        text: String,
        redactPatterns: List<String>,
    ): String {
        val patterns =
            redactPatterns
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { pattern -> runCatching { Regex(pattern) }.getOrNull() }
        if (patterns.isEmpty()) return text
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        var changed = false
        for (pattern in patterns) {
            for (match in pattern.findAll(text)) {
                val start = text.substring(0, match.range.first).toByteArray(StandardCharsets.UTF_8).size
                val end = text.substring(0, match.range.last + 1).toByteArray(StandardCharsets.UTF_8).size
                for (index in start until end) {
                    if (bytes[index].toInt() != '\n'.code) {
                        bytes[index] = '*'.code.toByte()
                        changed = true
                    }
                }
            }
        }
        return if (changed) String(bytes, StandardCharsets.UTF_8) else text
    }
}
