package dev.cint.suggest.provider

import dev.cint.api.CandidateEmit
import dev.cint.api.CandidateReason
import dev.cint.api.Span
import dev.cint.core.candidate.CandidateIdentity
import dev.cint.suggest.CandidateValidator
import dev.cint.suggest.SuggestionProviderRegistry
import dev.cint.suggest.SuggestionService
import dev.cint.suggest.model.CandidateContext
import dev.cint.suggest.model.CandidateDocument
import dev.cint.suggest.model.SuggestionOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FakeIntentSuggestionProviderTest {
    private val candidate =
        CandidateEmit(
            intents = setOf("VALIDATION"),
            span = Span(0, 0, 0, 4),
            startByte = 0,
            endByte = 4,
            source = "rule:validation:validation",
            confidence = 0.4,
            reason = CandidateReason.GAP,
        )

    @Test
    fun identityIncludesOccurrencePositionAndIsStableAcrossIntentOrdering() {
        val first = CandidateIdentity.of("Sample.kt", "hash", 0, 4, setOf("A", "B"), "GAP")
        val second = CandidateIdentity.of("Sample.kt", "hash", 0, 4, setOf("B", "A"), "GAP")
        val differentReason = CandidateIdentity.of("Sample.kt", "hash", 0, 4, setOf("A", "B"), "LOW_CONFIDENCE")
        val secondOccurrence = CandidateIdentity.of("Sample.kt", "hash", 40, 44, setOf("A", "B"), "GAP")

        assertEquals(first, second)
        assertNotEquals(first, differentReason)
        assertNotEquals(first, secondOccurrence)
    }

    @Test
    fun fakeProviderEnrichesWithStructuredProvenance() {
        val provider = FakeIntentSuggestionProvider()
        val context =
            CandidateContext(
                path = "Sample.kt",
                version = 1,
                candidate = candidate,
                documentLength = 100,
                knownIntents = setOf("VALIDATION"),
            )

        val enriched = provider.suggest(context).single()

        assertEquals("ai:fake", enriched.source)
        assertEquals(CandidateReason.GAP, enriched.reason)
        assertEquals(0.75, enriched.confidence)
        assertEquals("fake", enriched.provider)
        assertEquals("fake-intent-v1", enriched.model)
        assertEquals(FAKE_PROMPT_VERSION, enriched.promptVersion)
        assertTrue(enriched.rationale != null)
        assertTrue(!enriched.aiEligible)
        assertTrue(provider.suggest(context.copy(candidate = candidate.copy(aiEligible = false))).isEmpty())
    }

    @Test
    fun validatorEnforcesContainmentVocabularyConfidenceAndMaxCount() {
        val known = setOf("VALIDATION", "LOGGING")
        val inside = candidate.copy(intents = setOf("LOGGING"), source = "ai:test")
        val outOfBounds = inside.copy(startByte = 50, endByte = 60)
        val outsideInput = inside.copy(startByte = 2, endByte = 10)
        val unknown = inside.copy(intents = setOf("UNKNOWN"), startByte = 0, endByte = 4)
        val lowConfidence = inside.copy(confidence = 2.0, startByte = 0, endByte = 4)

        val accepted =
            CandidateValidator.validate(
                input = candidate,
                candidates = listOf(outOfBounds, outsideInput, unknown, lowConfidence, inside),
                documentLength = 10,
                knownIntents = known,
                maxCandidates = 5,
            )

        assertEquals(listOf(inside), accepted)
    }

    @Test
    fun validatorAppliesRealMaximumAndKeepsValidAlternatives() {
        val known = setOf("VALIDATION", "LOGGING", "ERROR_HANDLING")
        val alternatives =
            listOf(
                candidate.copy(intents = setOf("LOGGING"), source = "ai:test"),
                candidate.copy(intents = setOf("ERROR_HANDLING"), source = "ai:test"),
                candidate.copy(intents = setOf("VALIDATION"), source = "ai:test"),
            )

        val accepted =
            CandidateValidator.validate(
                input = candidate,
                candidates = alternatives,
                documentLength = 10,
                knownIntents = known,
                maxCandidates = 2,
            )

        assertEquals(2, accepted.size)
        assertEquals(alternatives.take(2), accepted)
    }

    @Test
    fun validatorTreatsNonPositiveMaximumsAsUnlimited() {
        val known = (1..15).map { "INTENT_$it" }.toSet() + "VALIDATION"
        val input = candidate.copy(span = Span(0, 0, 0, 100), startByte = 0, endByte = 100)
        val proposals =
            (1..15).map {
                candidate.copy(
                    intents = setOf("INTENT_$it"),
                    startByte = it,
                    endByte = it + 1,
                    source = "ai:test",
                )
            }

        val unlimited =
            CandidateValidator.validate(
                input = input,
                candidates = proposals,
                documentLength = 100,
                knownIntents = known,
                maxCandidates = 0,
            )
        val negative =
            CandidateValidator.validate(
                input = input,
                candidates = proposals,
                documentLength = 100,
                knownIntents = known,
                maxCandidates = -3,
            )

        assertEquals(15, unlimited.size)
        assertEquals(proposals, unlimited)
        assertEquals(proposals, negative)
    }

    @Test
    fun serviceMergesAiAlternativeWithDeterministicCandidate() {
        val provider =
            object : IntentSuggestionProvider {
                override val id: String = "stub"
                override val model: String = "stub-v1"

                override fun suggest(context: CandidateContext): List<CandidateEmit> =
                    listOf(
                        context.candidate.copy(
                            intents = setOf("LOGGING"),
                            source = "ai:stub",
                            provider = id,
                            model = model,
                        ),
                    )
            }
        val service = SuggestionService(SuggestionProviderRegistry(listOf(provider)))
        val merged =
            service.enrich(
                candidates = listOf(candidate),
                document =
                    CandidateDocument(
                        path = "Sample.kt",
                        version = 1,
                        text = "abcd",
                        knownIntents = setOf("VALIDATION", "LOGGING"),
                    ),
                options = SuggestionOptions(providerId = "stub", maxCandidates = 5),
            )

        assertEquals(2, merged.size)
        assertEquals(setOf("LOGGING"), merged.first { it.source == "ai:stub" }.intents)
        assertEquals("rule:validation:validation", merged.first { it.source.startsWith("rule:") }.source)
    }
}
