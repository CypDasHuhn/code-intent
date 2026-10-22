package dev.cint.server.suggest

import dev.cint.core.user.UserAnnotationsFile
import dev.cint.core.user.UserAnnotationsJson
import dev.cint.server.suggest.support.candidate
import dev.cint.server.suggest.support.gapSource
import dev.cint.server.suggest.support.harness
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveSuggestionOwnershipTest {
    @Test
    fun clientsOnTheSameUriKeepIndependentCandidateState() {
        val root = Files.createTempDirectory("cint-live-suggest-owners")
        try {
            val uri = root.resolve("Sample.kt").toString()
            val first = harness(root)
            val second = harness(root)
            first.open(uri, gapSource, version = 1)
            second.open(uri, gapSource, version = 1)
            val firstId = candidate(first.latest("cint/publishCandidates"), "VALIDATION")["candidateId"].asText()
            val secondId = candidate(second.latest("cint/publishCandidates"), "VALIDATION")["candidateId"].asText()
            assertEquals(firstId, secondId)

            second.decide(uri, version = 1, id = 2, candidateId = secondId, decision = "skip")
            assertTrue(
                second
                    .latest("cint/publishCandidates")
                    .path("params")
                    .path("candidates")
                    .isEmpty,
            )
            candidate(first.latest("cint/publishCandidates"), "VALIDATION")

            first.decide(uri, version = 1, id = 3, candidateId = firstId, decision = "confirm")
            assertTrue(
                first
                    .latest("cint/publishTags")
                    .path("params")
                    .path("annotations")
                    .any { annotation -> annotation.path("source").asText() == "user" },
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun malformedUserAnnotationsDoNotBreakCandidatePublicationOrConfirm() {
        val root = Files.createTempDirectory("cint-live-suggest-malformed")
        try {
            val annotations = UserAnnotationsFile.path(root)
            Files.createDirectories(annotations.parent)
            Files.writeString(annotations, "{ this is not valid yaml")
            val harness = harness(root)
            val uri = root.resolve("Sample.kt").toString()
            harness.open(uri, gapSource, version = 1)
            val candidateId = candidate(harness.latest("cint/publishCandidates"), "VALIDATION")["candidateId"].asText()

            harness.decide(uri, version = 1, id = 2, candidateId = candidateId, decision = "confirm")

            val written = UserAnnotationsJson.parse(Files.readString(annotations))
            assertEquals(1, written.size)
            assertEquals(setOf("VALIDATION"), written.single().intents)
            assertTrue(
                harness
                    .latest("cint/publishTags")
                    .path("params")
                    .path("annotations")
                    .any { annotation -> annotation.path("source").asText() == "user" },
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun sameVersionDifferentHashDecisionIsStale() {
        val root = Files.createTempDirectory("cint-live-suggest-hash-stale")
        try {
            val harness = harness(root)
            val uri = root.resolve("Sample.kt").toString()
            harness.open(uri, gapSource, version = 1)
            val candidateId = candidate(harness.latest("cint/publishCandidates"), "VALIDATION")["candidateId"].asText()

            harness.rewriteDocumentTextForTest(uri, gapSource.replace("if (true)", "if (fals)"))
            harness.decide(uri, version = 1, id = 2, candidateId = candidateId, decision = "confirm")

            assertTrue(harness.lastResponse(2).path("stale").asBoolean())
            assertFalse(Files.exists(UserAnnotationsFile.path(root)))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
