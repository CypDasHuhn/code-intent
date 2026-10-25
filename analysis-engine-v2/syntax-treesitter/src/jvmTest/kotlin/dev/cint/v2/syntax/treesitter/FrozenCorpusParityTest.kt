package dev.cint.v2.syntax.treesitter

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrozenCorpusParityTest {
    private val corpusRoot = File(
        System.getProperty("cint.repo.root")
            ?: File(".").absoluteFile.parentFile.parentFile.parentFile.absolutePath
    ).resolve("frozen-v1-corpus/fixtures/sources")

    @Test
    fun everyFrozenKotlinFixtureParsesWithoutErrors() {
        val kotlinFiles = corpusRoot.resolve("kotlin").listFiles { file -> file.extension == "kt" }
            ?.sortedBy { it.name }
            .orEmpty()
        assertEquals(11, kotlinFiles.size, "frozen Kotlin fixture set changed")

        val engine = createSyntaxEngine("kotlin")
        for (file in kotlinFiles) {
            val tree = engine.parse(file.readBytes(), previous = null).getOrThrow()
            assertEquals("source_file", tree.getNode(tree.rootId)?.kind, file.name)
            assertTrue(tree.nodes.size > 10, "${file.name} produced only ${tree.nodes.size} nodes")
            val errors = tree.nodes.values.filter { it.kind == "ERROR" }
            assertTrue(errors.isEmpty(), "${file.name} produced ERROR nodes: ${errors.map { it.text }}")
        }
    }

    @Test
    fun pinnedV1SourceRegeneratesTheCheckedInSnapshot() {
        Assume.assumeTrue(
            "Set CINT_V1_SYNTAX_PROVENANCE=1 to run the full pinned-V1 rebuild",
            System.getenv("CINT_V1_SYNTAX_PROVENANCE") == "1",
        )
        val repoRoot = File(System.getProperty("cint.repo.root"))
        val verifier = repoRoot.resolve("analysis-engine-v2/scripts/verify-v1-syntax-snapshot.sh")
        val process = ProcessBuilder("bash", verifier.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, process.waitFor(), output)
    }

    @Test
    fun snapshotPinsTheFrozenV1SourceCommit() {
        val manifest = Json.parseToJsonElement(
            corpusRoot.parentFile.parentFile.resolve("manifest.json").readText()
        ).jsonObject.getValue("v1Commit").jsonPrimitive.content
        assertEquals(manifest, V1KotlinSnapshot.v1Commit)
    }

    @Test
    fun kotlinFilesMatchTheCheckedInV1Snapshot() {
        for ((name, expected) in V1KotlinSnapshot.fingerprints) {
            val tree = createSyntaxEngine("kotlin")
                .parse(corpusRoot.resolve("kotlin/$name").readBytes(), null)
                .getOrThrow()
            assertEquals(expected, syntaxFingerprint(tree), name)
        }
    }

    @Test
    fun v1LanguageFamiliesParseTheirFrozenFixtures() {
        val expected = mapOf(
            "javascript" to listOf("javascript/Sample.js"),
            "typescript" to listOf("typescript/Sample.ts", "typescript/ServiceIntents.ts"),
            "csharp" to listOf("csharp/Sample.cs"),
            "vue" to listOf("vue/Sample.vue"),
        )
        for ((language, paths) in expected) {
            val engine = createSyntaxEngine(language)
            for (path in paths) {
                val file = corpusRoot.resolve(path)
                val tree = engine.parse(file.readBytes(), previous = null).getOrThrow()
                assertTrue(tree.nodes.size > 5, "$language/$path produced only ${tree.nodes.size} nodes")
            }
        }
    }
}
