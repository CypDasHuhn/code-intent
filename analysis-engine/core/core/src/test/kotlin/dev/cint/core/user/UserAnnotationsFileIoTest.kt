package dev.cint.core.user

import com.fasterxml.jackson.databind.ObjectMapper
import dev.cint.api.Span
import dev.cint.core.user.support.*
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class UserAnnotationsFileIoTest {
    private val json = ObjectMapper()

    @Test
    fun concurrentAddsPreserveEveryAnnotation() {
        val root = Files.createTempDirectory("cint-user-annotations-concurrent")
        try {
            val threads = 8
            val executor = Executors.newFixedThreadPool(threads)
            val start = CountDownLatch(1)
            repeat(threads) { index ->
                executor.execute {
                    start.await(2, TimeUnit.SECONDS)
                    UserAnnotationsFile.add(
                        root,
                        UserAnnotation(
                            path = "Sample.kt",
                            span = Span(index, 0, index, 1),
                            intents = setOf("INTENT_$index"),
                        ),
                    )
                }
            }
            start.countDown()
            executor.shutdown()
            executor.awaitTermination(5, TimeUnit.SECONDS)

            val annotations = UserAnnotationsFile.load(root)
            assertEquals(threads, annotations.size)
            assertEquals((0 until threads).map { "INTENT_$it" }.toSet(), annotations.flatMap { it.intents }.toSet())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun encodedAnnotationsWithNotesAndTimestampsAreStrictJson() {
        val createdAt = "2026-01-01T00:00:00Z"
        val noteOnly = encodeAnnotation(note = "note")
        val createdAtOnly = encodeAnnotation(createdAt = createdAt)
        val both = encodeAnnotation(note = "note", createdAt = createdAt)
        val neither = encodeAnnotation()

        for ((label, encoded) in mapOf("note" to noteOnly, "createdAt" to createdAtOnly, "both" to both, "neither" to neither)) {
            assertFalse(Regex(",\\s*[}\\]]").containsMatchIn(encoded), "$label: $encoded")
            json.readTree(encoded)
        }

        val noteRecord = json.readTree(noteOnly)["annotations"][0]
        assertEquals("note", noteRecord["note"].asText())
        assertFalse(noteRecord.has("createdAt"))

        val createdAtRecord = json.readTree(createdAtOnly)["annotations"][0]
        assertEquals(createdAt, createdAtRecord["createdAt"].asText())
        assertFalse(createdAtRecord.has("note"))

        val bothRecord = json.readTree(both)["annotations"][0]
        assertEquals("note", bothRecord["note"].asText())
        assertEquals(createdAt, bothRecord["createdAt"].asText())

        val plainRecord = json.readTree(neither)["annotations"][0]
        assertFalse(plainRecord.has("note"))
        assertFalse(plainRecord.has("createdAt"))
    }
}
