package dev.cint.knowledge.persistence

import dev.cint.contracts.data.Intents
import dev.cint.contracts.knowledge.FunctionSelector
import dev.cint.contracts.knowledge.PackDefault
import dev.cint.contracts.util.ContractError
import dev.cint.knowledge.InMemoryKnowledgeStore
import kotlinx.datetime.Instant
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KnowledgeStoreFilesTest {
    private val fixedNow = Instant.parse("2025-01-01T00:00:00Z")
    private val clock = { fixedNow }

    @Test
    fun roundTripsThroughTheFilesystemAdapter() {
        val directory = Files.createTempDirectory("cint-knowledge-test")
        try {
            val path = directory.resolve("knowledge.json")
            val store = InMemoryKnowledgeStore.empty(clock)
            store.materializeDefaults(
                listOf(
                    PackDefault(
                        selector = FunctionSelector(qualifiedName = "packed"),
                        intents = setOf(Intents.LOGGING),
                        packId = "logging",
                        packVersion = "1.0.0",
                        createdAt = fixedNow,
                    ),
                ),
            )
            val snapshot = store.snapshot().getOrThrow()

            assertTrue(KnowledgeStoreFiles.save(path, snapshot).isSuccess)
            assertTrue(KnowledgeStoreFiles.exists(path))

            val loaded = KnowledgeStoreFiles.load(path).getOrThrow()
            assertEquals(snapshot, loaded)
            val restored = InMemoryKnowledgeStore.fromSnapshot(loaded, now = clock).getOrThrow()
            assertEquals(
                "logging",
                restored.packOrigin(FunctionSelector(qualifiedName = "packed")).getOrThrow()?.packId,
            )
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun missingFilesAreDistinguishableFromEmptyStores() {
        val directory = Files.createTempDirectory("cint-knowledge-missing")
        try {
            val path = directory.resolve("absent.json")
            val error = KnowledgeStoreFiles.load(path)
            assertTrue(error.isFailure)
            assertIs<ContractError.NotFound>(error.errorOrNull)
            assertNull(KnowledgeStoreFiles.loadIfPresent(path).getOrThrow())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
