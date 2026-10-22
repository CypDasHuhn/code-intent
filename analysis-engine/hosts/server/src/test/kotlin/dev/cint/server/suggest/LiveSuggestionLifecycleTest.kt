package dev.cint.server.suggest

import dev.cint.api.CandidateEmit
import dev.cint.core.user.UserAnnotationsFile
import dev.cint.core.user.UserAnnotationsJson
import dev.cint.server.SuggestionSettings
import dev.cint.server.suggest.support.candidate
import dev.cint.server.suggest.support.candidates
import dev.cint.server.suggest.support.gapSource
import dev.cint.server.suggest.support.harness
import dev.cint.server.suggest.support.twoGapSource
import dev.cint.suggest.CandidateContext
import dev.cint.suggest.IntentSuggestionProvider
import dev.cint.suggest.SuggestionProviderRegistry
import dev.cint.suggest.SuggestionService
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LiveSuggestionLifecycleTest {
    @Test
    fun publishSuggestConfirmEstablishesAndReProposesAfterAnnotationRemoval() {
        val root = Files.createTempDirectory("cint-live-suggest-confirm")
        try {
            val harness = harness(root)
            val uri = root.resolve("Sample.kt").toString()
            harness.open(uri, gapSource, version = 1)

            val rawCandidate = candidate(harness.latest("cint/publishCandidates"), "VALIDATION")
            val candidateId = rawCandidate["candidateId"].asText()
            val rawRevision =
                harness
                    .latest("cint/publishTags")
                    .path("params")
                    .path("analysisRevision")
                    .asLong()

            harness.suggest(uri, version = 1, id = 2, requestId = "r1")
            assertTrue(harness.waitForResponse(2).path("accepted").asBoolean())
            val enriched =
                harness.waitForCandidate(uri, "VALIDATION") {
                    it["source"].asText() == "ai:fake"
                }
            assertEquals(candidateId, enriched["candidateId"].asText())
            assertEquals("fake", enriched["provider"].asText())
            assertTrue(enriched["model"].asText().isNotBlank())
            assertTrue(enriched["promptVersion"].asText().isNotBlank())
            assertTrue(enriched["rationale"].asText().isNotBlank())

            harness.decide(uri, version = 1, id = 3, candidateId = candidateId, decision = "confirm")

            val annotations = UserAnnotationsJson.parse(Files.readString(UserAnnotationsFile.path(root)))
            assertEquals(1, annotations.size)
            assertEquals("Sample.kt", annotations.single().path)
            assertEquals(setOf("VALIDATION"), annotations.single().intents)

            val tags = harness.latest("cint/publishTags")
            assertTrue(
                tags.path("params").path("annotations").any { annotation ->
                    annotation.path("intents").any { it.asText() == "VALIDATION" } &&
                        annotation.path("provenance").any { it.path("source").asText() == "user" }
                },
            )
            assertTrue(tags.path("params").path("analysisRevision").asLong() > rawRevision)
            assertTrue(
                harness
                    .latest("cint/publishCandidates")
                    .path("params")
                    .path("candidates")
                    .isEmpty,
            )

            Files.deleteIfExists(UserAnnotationsFile.path(root))
            harness.analyze(uri, id = 4)
            assertTrue(harness.waitForResponse(4).asBoolean())
            val reProposed =
                harness.waitForCandidate(uri, "VALIDATION") {
                    it["candidateId"].asText() == candidateId
                }
            assertEquals("rule:validation:validation", reProposed["source"].asText())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectPersistsSuppressionAcrossFreshServer() {
        val root = Files.createTempDirectory("cint-live-suggest-reject")
        try {
            val uri = root.resolve("Sample.kt").toString()
            val harness = harness(root)
            harness.open(uri, gapSource, version = 1)
            val candidateId = candidate(harness.latest("cint/publishCandidates"), "VALIDATION")["candidateId"].asText()
            harness.decide(uri, version = 1, id = 2, candidateId = candidateId, decision = "reject")

            val decisions = Files.readString(root.resolve(".cint/suggestions.json"))
            assertTrue(decisions.contains("\"decision\" : \"reject\""))
            assertTrue(decisions.contains(candidateId))
            assertFalse(Files.exists(UserAnnotationsFile.path(root)))
            assertTrue(
                harness
                    .latest("cint/publishCandidates")
                    .path("params")
                    .path("candidates")
                    .isEmpty,
            )

            val fresh = harness(root)
            fresh.open(uri, gapSource, version = 1)
            assertTrue(
                fresh
                    .latest("cint/publishCandidates")
                    .path("params")
                    .path("candidates")
                    .isEmpty,
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun skipIsSessionOnlyAndStaleVersionsAreRejected() {
        val root = Files.createTempDirectory("cint-live-suggest-skip")
        try {
            val uri = root.resolve("Sample.kt").toString()
            val harness = harness(root)
            harness.open(uri, gapSource, version = 1)
            val candidateId = candidate(harness.latest("cint/publishCandidates"), "VALIDATION")["candidateId"].asText()
            harness.decide(uri, version = 1, id = 2, candidateId = candidateId, decision = "skip")
            assertTrue(
                harness
                    .latest("cint/publishCandidates")
                    .path("params")
                    .path("candidates")
                    .isEmpty,
            )
            assertFalse(Files.exists(root.resolve(".cint/suggestions.json")))

            val publishesBefore = harness.methodCount("cint/publishCandidates")
            harness.suggest(uri, version = 0, id = 3, requestId = "stale")
            assertTrue(harness.lastResponse(3).path("stale").asBoolean())
            assertEquals(publishesBefore, harness.methodCount("cint/publishCandidates"))

            val fresh = harness(root)
            fresh.open(uri, gapSource, version = 1)
            candidate(fresh.latest("cint/publishCandidates"), "VALIDATION")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun identicalSpansHaveDistinctIdentityAndIndependentSuppression() {
        val root = Files.createTempDirectory("cint-live-suggest-occurrences")
        try {
            val harness = harness(root)
            val uri = root.resolve("Sample.kt").toString()
            harness.open(uri, twoGapSource, version = 1)

            val candidates = candidates(harness.latest("cint/publishCandidates"), "VALIDATION")
            assertEquals(2, candidates.size)
            assertNotEquals(candidates[0]["candidateId"].asText(), candidates[1]["candidateId"].asText())
            assertEquals(
                2,
                harness
                    .latest("cint/publishCandidates")
                    .path("params")
                    .path("groups")
                    .size(),
            )

            harness.decide(uri, version = 1, id = 2, candidateId = candidates[0]["candidateId"].asText(), decision = "reject")
            val remaining = candidates(harness.latest("cint/publishCandidates"), "VALIDATION")
            assertEquals(1, remaining.size)
            assertEquals(candidates[1]["candidateId"].asText(), remaining.single()["candidateId"].asText())

            harness.decide(uri, version = 1, id = 3, candidateId = remaining.single()["candidateId"].asText(), decision = "reject")
            assertTrue(
                harness
                    .latest("cint/publishCandidates")
                    .path("params")
                    .path("candidates")
                    .isEmpty,
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun aiAlternativesAreMergedIntoOneGroupAndValidated() {
        val root = Files.createTempDirectory("cint-live-suggest-alternatives")
        try {
            val provider =
                object : IntentSuggestionProvider {
                    override val id: String = "stub"
                    override val model: String = "stub-v1"

                    override fun suggest(context: CandidateContext): List<CandidateEmit> {
                        val candidate = context.candidate
                        return listOf(
                            candidate.copy(
                                intents = setOf("LOGGING"),
                                source = "ai:stub",
                                provider = id,
                                model = model,
                                promptVersion = context.promptVersion,
                                rationale = "alternative",
                                aiEligible = false,
                            ),
                            candidate.copy(startByte = context.documentLength + 1, source = "ai:stub"),
                            candidate.copy(intents = setOf("UNKNOWN_INTENT"), source = "ai:stub"),
                        )
                    }
                }
            val service = SuggestionService(SuggestionProviderRegistry(listOf(provider)))
            val harness =
                harness(
                    root,
                    suggestionSettings = SuggestionSettings(enabled = true, provider = "stub", model = "stub-v1"),
                    service = service,
                )
            val uri = root.resolve("Sample.kt").toString()
            harness.open(uri, gapSource, version = 1)
            harness.suggest(uri, version = 1, id = 2, requestId = "alt")
            assertTrue(harness.waitForResponse(2).path("accepted").asBoolean())

            val params = harness.latest("cint/publishCandidates").path("params")
            val groups = params.path("groups")
            assertEquals(1, groups.size())
            val alternatives = groups[0].path("candidates")
            assertEquals(2, alternatives.size())
            assertEquals(setOf("LOGGING", "VALIDATION"), alternatives.map { it["intents"][0].asText() }.toSet())
            assertEquals(1, alternatives.count { it["source"].asText() == "rule:validation:validation" })
            assertEquals(1, alternatives.count { it["source"].asText() == "ai:stub" })
            val chosen = alternatives.first { it["source"].asText() == "ai:stub" }
            val sibling = alternatives.first { it["source"].asText() == "rule:validation:validation" }

            harness.decide(uri, version = 1, id = 3, candidateId = chosen["candidateId"].asText(), decision = "confirm")
            val resolved = harness.latest("cint/publishCandidates").path("params")
            assertTrue(resolved.path("candidates").isEmpty)
            assertTrue(resolved.path("groups").isEmpty)
            harness.decide(uri, version = 1, id = 4, candidateId = sibling["candidateId"].asText(), decision = "confirm")
            assertTrue(harness.lastResponse(4).path("missing").asBoolean())
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
