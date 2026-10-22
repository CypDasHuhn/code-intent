package dev.cint.suggest

import dev.cint.api.CandidateEmit
import dev.cint.api.CandidateReason
import dev.cint.api.Span
import dev.cint.suggest.model.CandidateContext
import dev.cint.suggest.model.CandidateDocument
import dev.cint.suggest.model.SuggestionOptions
import dev.cint.suggest.provider.IntentSuggestionProvider
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SuggestionServicePrivacyTest {
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
    fun allowGlobsGateTheAiPass() {
        val provider = RecordingProvider()
        val service = SuggestionService(SuggestionProviderRegistry(listOf(provider)))
        val document =
            CandidateDocument(
                path = "src/Sample.kt",
                version = 1,
                text = "abcd",
                knownIntents = setOf("VALIDATION", "LOGGING"),
            )

        service.enrich(
            candidates = listOf(candidate),
            document = document,
            options = SuggestionOptions(providerId = provider.id, allowGlobs = listOf("**/*.kt")),
        )
        assertEquals(1, provider.calls)

        provider.calls = 0
        val skipped =
            service.enrich(
                candidates = listOf(candidate),
                document = document,
                options = SuggestionOptions(providerId = provider.id, allowGlobs = listOf("**/*.ts")),
            )
        assertEquals(0, provider.calls)
        assertEquals(listOf(candidate), skipped)
    }

    @Test
    fun redactionAppliesToTheExcerptWithoutChangingOffsets() {
        val provider = RecordingProvider()
        val service = SuggestionService(SuggestionProviderRegistry(listOf(provider)))
        val text = "before\npassword = \"hunter2\"\nafter"
        val localCandidate =
            candidate.copy(
                span = Span(0, 0, 2, 5),
                startByte = 0,
                endByte = text.toByteArray(StandardCharsets.UTF_8).size,
            )

        service.enrich(
            candidates = listOf(localCandidate),
            document =
                CandidateDocument(
                    path = "src/Sample.kt",
                    version = 1,
                    text = text,
                    knownIntents = setOf("VALIDATION", "LOGGING"),
                ),
            options =
                SuggestionOptions(
                    providerId = provider.id,
                    excerptRadiusBytes = 1_000,
                    redactPatterns = listOf("hunter2"),
                ),
        )

        val excerpt = provider.lastContext?.sourceExcerpt ?: error("provider did not receive an excerpt")
        assertFalse(excerpt.text.contains("hunter2"))
        assertTrue(excerpt.text.contains("*******"))
        assertEquals(text.toByteArray(StandardCharsets.UTF_8).size, excerpt.text.toByteArray(StandardCharsets.UTF_8).size)
        assertEquals(text.lines().size, excerpt.text.lines().size)
        assertEquals(localCandidate.startByte, excerpt.startByte)
        assertEquals(localCandidate.endByte, excerpt.endByte)
    }

    @Test
    fun excerptRadiusBoundsTheContextWindow() {
        val provider = RecordingProvider()
        val service = SuggestionService(SuggestionProviderRegistry(listOf(provider)))
        val prefix = "prefix ".repeat(100)
        val text = prefix + "abcd" + " suffix".repeat(100)
        val start = prefix.toByteArray(StandardCharsets.UTF_8).size
        val localCandidate =
            candidate.copy(
                span = Span(0, start, 0, start + 4),
                startByte = start,
                endByte = start + 4,
            )

        service.enrich(
            candidates = listOf(localCandidate),
            document =
                CandidateDocument(
                    path = "src/Sample.kt",
                    version = 1,
                    text = text,
                    knownIntents = setOf("VALIDATION", "LOGGING"),
                ),
            options = SuggestionOptions(providerId = provider.id, excerptRadiusBytes = 5),
        )

        val excerpt = provider.lastContext?.sourceExcerpt ?: error("provider did not receive an excerpt")
        assertEquals(14, excerpt.text.toByteArray(StandardCharsets.UTF_8).size)
        assertTrue(excerpt.text.contains("abcd"))
        assertTrue(excerpt.startByte > 0)
        assertTrue(excerpt.endByte < text.toByteArray(StandardCharsets.UTF_8).size)
    }

    private class RecordingProvider : IntentSuggestionProvider {
        override val id: String = "recording"
        override val model: String = "recording-v1"
        var calls: Int = 0
        var lastContext: CandidateContext? = null

        override fun suggest(context: CandidateContext): List<CandidateEmit> {
            calls += 1
            lastContext = context
            return emptyList()
        }
    }
}
