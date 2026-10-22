package dev.cint.suggest.provider

import dev.cint.api.CandidateReason
import dev.cint.api.Span
import dev.cint.suggest.model.SourceExcerpt
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeepSeekIntentSuggestionProviderTest {
    @Test
    fun validResponseIsValidatedCappedAndCarriesProvenance() {
        val endpoint = FakeEndpoint()
        endpoint.use { endpoint ->
            endpoint.responder = {
                completion(
                    """
                    ```json
                    [{"startByte":0,"endByte":4,"intent":"LOGGING","confidence":0.99,"rationale":"looks like logging"}]
                    ```
                    """.trimIndent(),
                )
            }
            val provider = provider(endpoint)
            val enriched = provider.suggest(context()).single()

            assertEquals("ai:deepseek", enriched.source)
            assertEquals("deepseek", enriched.provider)
            assertEquals("deepseek-chat", enriched.model)
            assertEquals("test-prompt-v1", enriched.promptVersion)
            assertEquals(setOf("LOGGING"), enriched.intents)
            assertEquals(CandidateReason.GAP, enriched.reason)
            assertEquals(0.84, enriched.confidence)
            assertEquals("looks like logging", enriched.rationale)
            assertFalse(enriched.aiEligible)
        }
    }

    @Test
    fun requestUsesRecallDefaultsAndAsksForAlternatives() {
        val endpoint = FakeEndpoint()
        endpoint.use { endpoint ->
            endpoint.responder = {
                completion("""[{"startByte":0,"endByte":4,"intent":"LOGGING","confidence":0.6,"rationale":"logging"}]""")
            }
            provider(endpoint).suggest(context())

            val body = deepSeekMapper.readTree(endpoint.requests.single().body)
            assertEquals(0.3, body.path("temperature").asDouble())
            assertEquals(1024, body.path("max_tokens").asInt())
            val system =
                body
                    .path("messages")
                    .first { it.path("role").asText() == "system" }
                    .path("content")
                    .asText()
            assertTrue(system.contains("alternatives"))
            assertTrue(system.contains("lower-confidence"))
        }
    }

    @Test
    fun unsetPromptVersionUsesTheDeepSeekDefault() {
        val endpoint = FakeEndpoint()
        endpoint.use { endpoint ->
            endpoint.responder = {
                completion("""[{"startByte":0,"endByte":4,"intent":"LOGGING","confidence":0.6,"rationale":"logging"}]""")
            }
            val provider = provider(endpoint)
            val enriched = provider.suggest(context().copy(promptVersion = null)).single()

            assertEquals("deepseek-intent-v3", DEEPSEEK_PROMPT_VERSION)
            assertEquals(DEEPSEEK_PROMPT_VERSION, enriched.promptVersion)
            val body = deepSeekMapper.readTree(endpoint.requests.single().body)
            val user = deepSeekMapper.readTree(body.path("messages")[1].path("content").asText())
            assertEquals(DEEPSEEK_PROMPT_VERSION, user.path("promptVersion").asText())
        }
    }

    @Test
    fun configuredConfidenceAboveTheSafeCeilingIsClamped() {
        val endpoint = FakeEndpoint()
        endpoint.use { endpoint ->
            endpoint.responder = {
                completion("""[{"startByte":0,"endByte":4,"intent":"LOGGING","confidence":0.99,"rationale":"logging"}]""")
            }
            val enriched = provider(endpoint, maxConfidence = 1.0).suggest(context()).single()
            assertEquals(DEEPSEEK_MAX_CONFIDENCE, enriched.confidence)
        }
    }

    @Test
    fun outOfRangeAndNonFiniteConfidenceIsDropped() {
        val values = listOf("1.1", "-0.1", "1e999", "-1e999", "NaN")
        for (value in values) {
            val endpoint = FakeEndpoint()
            endpoint.use { endpoint ->
                endpoint.responder = {
                    completion(
                        """[{"startByte":0,"endByte":4,"intent":"LOGGING","confidence":$value,"rationale":"bad"}]""",
                    )
                }
                assertTrue(provider(endpoint).suggest(context()).isEmpty(), value)
            }
        }
    }

    @Test
    fun modelSubSpanInMultilineExcerptMapsToAbsoluteBytesAndLines() {
        val endpoint = FakeEndpoint()
        endpoint.use { endpoint ->
            val text = "first line\nsecond target line\nthird line"
            val modelStart = text.indexOf("target")
            val modelEnd = modelStart + "target".length
            val inputStart = text.indexOf("second")
            val inputEnd = text.indexOf('\n', modelEnd)
            val localCandidate =
                candidate.copy(
                    span = Span(1, 0, 1, 0),
                    startByte = inputStart,
                    endByte = inputEnd,
                )
            val localContext =
                context(text).copy(
                    candidate = localCandidate,
                    sourceExcerpt = SourceExcerpt.around(text, inputStart, inputEnd),
                )
            endpoint.responder = {
                completion(
                    """[{"startByte":$modelStart,"endByte":$modelEnd,"intent":"LOGGING","confidence":0.6,"rationale":"sub span"}]""",
                )
            }
            val enriched = provider(endpoint).suggest(localContext).single()

            assertEquals(modelStart, enriched.startByte)
            assertEquals(modelEnd, enriched.endByte)
            assertEquals(Span(1, 7, 1, 13), enriched.span)
        }
    }

    @Test
    fun nonAsciiExcerptBoundaryKeepsByteOffsetsAndPositions() {
        val endpoint = FakeEndpoint()
        endpoint.use { endpoint ->
            val prefix = "é".repeat(500)
            val text = prefix + "TARGET tail"
            val inputStart = prefix.toByteArray(StandardCharsets.UTF_8).size
            val inputEnd = inputStart + "TARGET".length
            val excerpt = SourceExcerpt.around(text, inputStart, inputEnd, radiusBytes = 401)
            assertEquals(0, excerpt.startByte % 2)
            assertFalse(excerpt.text.contains('\uFFFD'))
            val localCandidate =
                candidate.copy(
                    span = Span(0, inputStart, 0, inputEnd),
                    startByte = inputStart,
                    endByte = inputEnd,
                )
            val localContext =
                context(text).copy(
                    candidate = localCandidate,
                    sourceExcerpt = excerpt,
                )
            val modelStart = inputStart + 1
            val modelEnd = inputEnd - 1
            endpoint.responder = {
                completion(
                    """[{"startByte":$modelStart,"endByte":$modelEnd,"intent":"LOGGING","confidence":0.6,"rationale":"utf8"}]""",
                )
            }
            val enriched = provider(endpoint).suggest(localContext).single()

            assertEquals(modelStart, enriched.startByte)
            assertEquals(modelEnd, enriched.endByte)
            assertEquals(Span(0, modelStart, 0, modelEnd), enriched.span)
        }
    }

    @Test
    fun candidateAtDocumentEdgeMapsExactSpan() {
        val endpoint = FakeEndpoint()
        endpoint.use { endpoint ->
            val provider = provider(endpoint)

            fun check(
                text: String,
                startByte: Int,
                endByte: Int,
                expected: Span,
            ) {
                val localCandidate =
                    candidate.copy(
                        span = Span(0, 0, 0, 0),
                        startByte = startByte,
                        endByte = endByte,
                    )
                val localContext =
                    context(text).copy(
                        candidate = localCandidate,
                        sourceExcerpt = SourceExcerpt.around(text, startByte, endByte),
                    )
                endpoint.responder = {
                    completion(
                        """[{"startByte":$startByte,"endByte":$endByte,"intent":"LOGGING","confidence":0.6,"rationale":"edge"}]""",
                    )
                }
                val enriched = provider.suggest(localContext).single()
                assertEquals(startByte, enriched.startByte)
                assertEquals(endByte, enriched.endByte)
                assertEquals(expected, enriched.span)
            }

            check("edge", 0, 4, Span(0, 0, 0, 4))
            check("head\ntail", 5, 9, Span(1, 0, 1, 4))
        }
    }

    @Test
    fun outOfBoundsUnknownAndOutsideSpanCandidatesAreRejected() {
        val endpoint = FakeEndpoint()
        endpoint.use { endpoint ->
            endpoint.responder = {
                completion(
                    """
                    [
                      {"startByte":0,"endByte":4,"intent":"UNKNOWN","confidence":0.7,"rationale":"unknown"},
                      {"startByte":0,"endByte":10,"intent":"LOGGING","confidence":0.7,"rationale":"out of bounds"},
                      {"startByte":50,"endByte":60,"intent":"VALIDATION","confidence":0.7,"rationale":"outside"},
                      {"startByte":0,"endByte":4,"intent":"LOGGING","confidence":0.7,"rationale":"valid"}
                    ]
                    """.trimIndent(),
                )
            }
            val enriched = provider(endpoint).suggest(context()).single()
            assertEquals(setOf("LOGGING"), enriched.intents)
            assertEquals("valid", enriched.rationale)
        }
    }

    @Test
    fun ineligibleCandidateIsIgnoredWithoutARequest() {
        val endpoint = FakeEndpoint()
        endpoint.use { endpoint ->
            val provider = provider(endpoint)
            assertTrue(provider.suggest(context().copy(candidate = candidate.copy(aiEligible = false))).isEmpty())
            assertTrue(endpoint.requests.isEmpty())
        }
    }
}
