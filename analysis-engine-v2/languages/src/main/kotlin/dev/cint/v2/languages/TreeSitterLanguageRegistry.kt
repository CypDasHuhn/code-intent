package dev.cint.v2.languages

import dev.cint.contracts.facts.LanguageDescriptor

object TreeSitterLanguageRegistry {
    private data class Entry(
        val descriptor: LanguageDescriptor,
        val aliases: Set<String>,
    )

    private val registryEntries = listOf(
        Entry(
            LanguageDescriptor(
                id = "kotlin",
                name = "Kotlin",
                extensions = listOf("kt", "kts"),
                grammarName = "tree-sitter-kotlin",
                mimeTypes = listOf("text/x-kotlin"),
            ),
            aliases = setOf("kt"),
        ),
        Entry(
            LanguageDescriptor(
                id = "c",
                name = "C",
                extensions = listOf("c", "h"),
                grammarName = "tree-sitter-c",
                mimeTypes = listOf("text/x-c"),
            ),
            aliases = setOf("h"),
        ),
        Entry(
            LanguageDescriptor(
                id = "javascript",
                name = "JavaScript",
                extensions = listOf("js", "jsx", "mjs", "cjs"),
                grammarName = "tree-sitter-javascript",
                mimeTypes = listOf("text/javascript"),
            ),
            aliases = setOf("js", "jsx"),
        ),
        Entry(
            LanguageDescriptor(
                id = "typescript",
                name = "TypeScript",
                extensions = listOf("ts", "tsx", "mts", "cts"),
                grammarName = "tree-sitter-typescript",
                mimeTypes = listOf("text/typescript"),
            ),
            aliases = setOf("ts", "tsx"),
        ),
        Entry(
            LanguageDescriptor(
                id = "vue",
                name = "Vue",
                extensions = listOf("vue"),
                grammarName = "tree-sitter-vue",
                mimeTypes = listOf("text/x-vue"),
            ),
            aliases = setOf("vue-sfc"),
        ),
        Entry(
            LanguageDescriptor(
                id = "csharp",
                name = "C#",
                extensions = listOf("cs"),
                grammarName = "tree-sitter-c-sharp",
                mimeTypes = listOf("text/x-csharp"),
            ),
            aliases = setOf("c#", "cs", "c-sharp"),
        ),
    )

    private val byName: Map<String, LanguageDescriptor> = buildMap {
        for (entry in registryEntries) {
            put(entry.descriptor.id, entry.descriptor)
            for (alias in entry.aliases) put(alias, entry.descriptor)
        }
    }

    val descriptors: List<LanguageDescriptor> = registryEntries.map { it.descriptor }

    fun resolve(language: String): LanguageDescriptor? =
        byName[language.trim().lowercase().removePrefix(".")]

    fun forExtension(extension: String): LanguageDescriptor? {
        val normalized = extension.trim().lowercase().removePrefix(".")
        return descriptors.firstOrNull { normalized in it.extensions }
    }

    fun forPath(path: String): LanguageDescriptor? {
        val extension = path.substringAfterLast('.', "")
        if (extension.isEmpty()) return null
        return forExtension(extension)
    }
}
