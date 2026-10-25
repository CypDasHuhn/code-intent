package dev.cint.rulepacks

import dev.cint.v2.languages.TreeSitterLanguageRegistry
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PacksBoundaryTest {
    @Test
    fun mainSourcesUseOnlyContractsAndTheirOwnPackage() {
        val sources = mainSources()
        val allowedImports =
            listOf(
                "dev.cint.contracts.",
                "dev.cint.rulepacks.",
                "kotlinx.serialization.",
                "kotlinx.datetime.",
                "kotlin.",
            )
        for (file in sources) {
            val imports =
                Files
                    .readAllLines(file.toPath())
                    .filter { it.startsWith("import ") }
                    .map { it.removePrefix("import ").trim() }
            for (import in imports) {
                assertTrue(
                    allowedImports.any { import.startsWith(it) },
                    "${file.path} imports outside contracts/own package: $import",
                )
            }
        }
    }

    @Test
    fun ruleCodeHasNoRawSyntaxOrTextParsing() {
        val forbidden =
            listOf(
                ".text",
                "Regex",
                "substring",
                "split(",
                "tokenize(",
                "startsWith",
                "endsWith",
            )
        for (file in mainSources()) {
            val text = Files.readString(file.toPath())
            for (marker in forbidden) {
                assertFalse(text.contains(marker), "${file.path} contains raw parsing marker '$marker'")
            }
        }
    }

    @Test
    fun bundledPacksAreDirectoriesWithManifestPlaybookDefaultsAndRules() {
        val root = File("src/main/resources/default-rulepacks")
        assertTrue(root.isDirectory)
        assertTrue(File(root, "index.json").isFile)
        val packs = BundledPacks.all
        assertTrue(packs.isNotEmpty())
        for (definition in packs) {
            val directory = File(root, definition.manifest.id)
            assertTrue(directory.isDirectory, "missing pack directory ${definition.manifest.id}")
            for (name in listOf("manifest.json", "playbook.json", "defaults.json", "rules.json")) {
                assertTrue(File(directory, name).isFile, "missing ${definition.manifest.id}/$name")
            }
        }
        val mainSources = mainSources().joinToString("\n") { it.readText() }
        assertFalse(mainSources.contains("fun functionDefault("))
        assertFalse(mainSources.contains("internal val loggingDefaults"))
        assertFalse(File("src/main/resources/packs/bundled-packs.json").exists())
    }

    @Test
    fun packLanguagesComeFromAuthoritativeDescriptorsAndMatchPlaybookRules() {
        val registered = TreeSitterLanguageRegistry.descriptors.map { it.id }.toSet()
        assertTrue(registered.isNotEmpty())
        val source = BundledRulePackSource()
        for (manifest in source.manifests().getOrThrow()) {
            val playbook = source.playbook(manifest.id).getOrThrow()
            val ruleIds = playbook.rules.map { it.id }.toSet()
            val definition = BundledPacks.all.first { it.manifest.id == manifest.id }
            assertTrue(definition.rules.keys == ruleIds, "pack ${manifest.id} rule data does not match playbook")
            for (language in manifest.languages) {
                assertTrue(language in registered, "pack ${manifest.id} references unregistered language '$language'")
            }
        }
    }

    private fun mainSources(): List<File> =
        File("src/main/kotlin")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
}
