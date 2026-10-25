package dev.cint.v2.testing

import dev.cint.v2.testing.corpus.FrozenV1Corpus
import java.nio.file.NoSuchFileException
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FrozenCorpusLoaderTest {
    private val corpus = FrozenV1Corpus(
        Paths.get(
            System.getProperty("v2.frozenCorpusRoot")
                ?: error("v2.frozenCorpusRoot must be set by the integration test task"),
        ),
    )

    @Test
    fun loadsCliArtifactsWithoutProductionCoupling() {
        val kotlin = corpus.cli("kotlin")
        assertEquals("kotlin", kotlin.language)
        assertTrue(kotlin.files.isNotEmpty())
        assertTrue(kotlin.files.any { it.path == "Bookkeeping.kt" })
        assertEquals(kotlin.files.size, corpus.cli("kotlin-all").files.size)
    }

    @Test
    fun normalizesProtocolNameSuffixes() {
        val byBareName = corpus.protocol("kotlin-analyze")
        val byNdjson = corpus.protocol("protocol-kotlin-analyze.ndjson")
        val bySession = corpus.protocol("protocol-kotlin-analyze.session.json")

        assertEquals(byBareName, byNdjson)
        assertEquals(byBareName, bySession)
        assertEquals("protocol-kotlin-analyze", byBareName.name)
        assertTrue(byBareName.messages.isNotEmpty())
        assertNotNullSession(byBareName.session)
    }

    @Test
    fun verifiesManifestHashesAndMissingFiles() {
        assertEquals(emptyList(), corpus.manifestHashMismatches())
        assertFailsWith<NoSuchFileException> { corpus.readText("missing-artifact.json") }
        assertFailsWith<NoSuchFileException> { corpus.cli("missing") }
    }

    @Test
    fun loadsFrozenSourcesScenariosAndMetadata() {
        val scenarios = corpus.scenarios()
        assertTrue("kotlin-analyze" in scenarios)
        assertEquals("kotlin", scenarios.getValue("kotlin-analyze").source)

        val source = corpus.source("kotlin", "Bookkeeping.kt")
        assertTrue(source.isNotBlank())

        assertTrue(corpus.manifest().containsKey("formatVersion"))
        assertTrue(corpus.knownDifferences().isNotEmpty())
        assertTrue(corpus.rendererVisible().isNotEmpty())
    }

    private fun assertNotNullSession(session: kotlinx.serialization.json.JsonObject?) {
        assertTrue(session != null)
    }
}
