package dev.cint.v2.cli

object CliLanguages {
    private val supported = setOf("kotlin", "javascript", "typescript", "vue", "csharp", "c")

    private val aliases =
        mapOf(
            "kt" to "kotlin",
            "js" to "javascript",
            "jsx" to "javascript",
            "ts" to "typescript",
            "tsx" to "typescript",
            "vue-sfc" to "vue",
            "c#" to "csharp",
            "cs" to "csharp",
            "c-sharp" to "csharp",
            "h" to "c",
        )

    private val extensions =
        mapOf(
            "kotlin" to setOf("kt", "kts"),
            "javascript" to setOf("js", "jsx", "mjs", "cjs"),
            "typescript" to setOf("ts", "tsx", "mts", "cts"),
            "vue" to setOf("vue"),
            "csharp" to setOf("cs"),
            "c" to setOf("c", "h"),
        )

    fun canonical(raw: String): String? {
        val normalized = raw.trim().lowercase().removePrefix(".")
        if (normalized.isEmpty()) return null
        val canonical = aliases[normalized] ?: normalized
        return canonical.takeIf { it in supported }
    }

    fun extensions(language: String): Set<String> = extensions[language].orEmpty().ifEmpty { setOf(language) }
}
