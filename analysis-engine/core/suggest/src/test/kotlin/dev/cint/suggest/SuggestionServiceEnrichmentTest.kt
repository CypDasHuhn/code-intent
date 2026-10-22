package dev.cint.suggest

import dev.cint.api.CandidateEmit
import dev.cint.api.CandidateReason
import dev.cint.api.Span
import dev.cint.suggest.model.CandidateContext
import dev.cint.suggest.model.CandidateDocument
import dev.cint.suggest.model.SuggestionOptions
import dev.cint.suggest.provider.IntentSuggestionProvider
import kotlin.test.Test
import kotlin.test.assertEquals

class SuggestionServiceEnrichmentTest {
    @Test
    fun defaultEnrichmentAndValidationMaximumsAreUnlimited() {
        val provider = RecordingProvider()
        val service = SuggestionService(SuggestionProviderRegistry(listOf(provider)))
        val candidates = candidates(15)
        val defaults = SuggestionOptions()

        assertEquals(0, defaults.maxCandidates)
        assertEquals(0, defaults.maxEnrichmentCandidates)

        val enriched =
            service.enrich(
                candidates = candidates,
                document = document(),
                options = defaults.copy(providerId = provider.id),
            )

        assertEquals(15, provider.calls)
        assertEquals(15, enriched.count { it.source == provider.source })
        assertEquals(15, enriched.count { it.source.startsWith("rule:") })
    }

    @Test
    fun positiveEnrichmentMaximumStillLimitsProviderCalls() {
        val provider = RecordingProvider()
        val service = SuggestionService(SuggestionProviderRegistry(listOf(provider)))
        val candidates = candidates(15)

        val enriched =
            service.enrich(
                candidates = candidates,
                document = document(),
                options =
                    SuggestionOptions(
                        providerId = provider.id,
                        maxCandidates = 0,
                        maxEnrichmentCandidates = 3,
                    ),
            )

        assertEquals(3, provider.calls)
        assertEquals(3, enriched.count { it.source == provider.source })
        assertEquals(15, enriched.count { it.source.startsWith("rule:") })
    }

    private fun candidates(count: Int): List<CandidateEmit> =
        (1..count).map { index ->
            CandidateEmit(
                intents = setOf("VALIDATION"),
                span = Span(0, index, 0, index + 1),
                startByte = index,
                endByte = index + 1,
                source = "rule:validation:validation",
                confidence = 0.4,
                reason = CandidateReason.GAP,
            )
        }

    private fun document(): CandidateDocument =
        CandidateDocument(
            path = "Sample.kt",
            version = 1,
            text = "x".repeat(16),
            knownIntents = setOf("VALIDATION", "LOGGING"),
        )

    private class RecordingProvider : IntentSuggestionProvider {
        override val id: String = "recording"
        override val model: String = "recording-v1"
        val source: String = "ai:recording"
        var calls: Int = 0

        override fun suggest(context: CandidateContext): List<CandidateEmit> {
            calls += 1
            return listOf(
                context.candidate.copy(
                    intents = setOf("LOGGING"),
                    source = source,
                    provider = id,
                    model = model,
                ),
            )
        }
    }
}
