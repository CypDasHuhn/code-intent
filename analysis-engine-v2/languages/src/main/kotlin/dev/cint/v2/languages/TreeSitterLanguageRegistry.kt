package dev.cint.v2.languages

import dev.cint.contracts.facts.LanguageDescriptor
import dev.cint.contracts.ports.ProviderMode

object TreeSitterLanguageRegistry : LanguageCatalog {
    override val descriptors: List<LanguageDescriptor> =
        listOf(
            LanguageDescriptor(
                id = "kotlin",
                name = "Kotlin",
                extensions = listOf("kt", "kts"),
                grammarName = "tree-sitter-kotlin",
                mimeTypes = listOf("text/x-kotlin"),
                providerMode = ProviderMode.MANAGED,
                rootMarkers = listOf("settings.gradle.kts", "settings.gradle", "build.gradle.kts", "build.gradle", ".git"),
                profile = "kotlin",
                aliases = listOf("kt"),
            ),
            LanguageDescriptor(
                id = "c",
                name = "C",
                extensions = listOf("c", "h"),
                grammarName = "tree-sitter-c",
                mimeTypes = listOf("text/x-c"),
                rootMarkers = listOf("compile_commands.json", "CMakeLists.txt", ".git"),
                profile = "c",
                aliases = listOf("h"),
            ),
            LanguageDescriptor(
                id = "javascript",
                name = "JavaScript",
                extensions = listOf("js", "jsx", "mjs", "cjs"),
                grammarName = "tree-sitter-javascript",
                mimeTypes = listOf("text/javascript"),
                rootMarkers = listOf("package.json", ".git"),
                profile = "javascript",
                aliases = listOf("js", "jsx"),
            ),
            LanguageDescriptor(
                id = "typescript",
                name = "TypeScript",
                extensions = listOf("ts", "tsx", "mts", "cts"),
                grammarName = "tree-sitter-typescript",
                mimeTypes = listOf("text/typescript"),
                rootMarkers = listOf("tsconfig.json", "package.json", ".git"),
                profile = "typescript",
                aliases = listOf("ts", "tsx"),
            ),
            LanguageDescriptor(
                id = "vue",
                name = "Vue",
                extensions = listOf("vue"),
                grammarName = "tree-sitter-vue",
                mimeTypes = listOf("text/x-vue"),
                rootMarkers = listOf("package.json", ".git"),
                profile = "vue",
                aliases = listOf("vue-sfc"),
            ),
            LanguageDescriptor(
                id = "csharp",
                name = "C#",
                extensions = listOf("cs"),
                grammarName = "tree-sitter-c-sharp",
                mimeTypes = listOf("text/x-csharp"),
                rootMarkers = listOf("*.sln", "*.csproj", ".git"),
                profile = "csharp",
                aliases = listOf("c#", "cs", "c-sharp"),
            ),
        )

    private val catalog = LanguageCatalog.of(descriptors)

    override fun forLanguage(language: String): LanguageDescriptor? = catalog.forLanguage(language)

    override fun forExtension(extension: String): LanguageDescriptor? = catalog.forExtension(extension)

    override fun forPath(path: String): LanguageDescriptor? = catalog.forPath(path)
}
