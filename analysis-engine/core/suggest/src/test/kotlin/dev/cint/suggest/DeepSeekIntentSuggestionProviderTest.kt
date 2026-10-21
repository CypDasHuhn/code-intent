package dev.cint.suggest

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import dev.cint.api.CandidateEmit
import dev.cint.api.CandidateReason
import dev.cint.api.Span
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeepSeekIntentSuggestionProviderTest {
    private val mapper = jacksonObjectMapper()

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

    private fun context(text: String = "abcd"): CandidateContext =
        CandidateContext(
            path = "Sample.kt",
            version = 1,
            candidate = candidate,
            documentLength = text.toByteArray(StandardCharsets.UTF_8).size,
            knownIntents = setOf("VALIDATION", "LOGGING"),
            promptVersion = "test-prompt-v1",
            language = "kotlin",
            sourceExcerpt = SourceExcerpt.around(text, candidate.startByte, candidate.endByte),
        )

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
    fun unsetPromptVersionUsesTheDeepSeekDefault() {
        val endpoint = FakeEndpoint()
        endpoint.use { endpoint ->
            endpoint.responder = {
                completion("""[{"startByte":0,"endByte":4,"intent":"LOGGING","confidence":0.6,"rationale":"logging"}]""")
            }
            val provider = provider(endpoint)
            val enriched = provider.suggest(context().copy(promptVersion = null)).single()

            assertEquals(DEEPSEEK_PROMPT_VERSION, enriched.promptVersion)
            val body = mapper.readTree(endpoint.requests.single().body)
            val user = mapper.readTree(body.path("messages")[1].path("content").asText())
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
    fun requestShapeIsBoundedAndDoesNotLeakTheKey() {
        val endpoint = FakeEndpoint()
        endpoint.use { endpoint ->
            endpoint.responder = { completion("[]") }
            val prefix = "large surrounding text ".repeat(200)
            val text = prefix + "abcd" + " trailing".repeat(200)
            val start = prefix.toByteArray(StandardCharsets.UTF_8).size
            val localCandidate =
                candidate.copy(
                    span = Span(0, start, 0, start + 4),
                    startByte = start,
                    endByte = start + 4,
                )
            val localContext =
                context(text).copy(
                    candidate = localCandidate,
                    sourceExcerpt = SourceExcerpt.around(text, start, start + 4),
                )
            val provider = provider(endpoint, key = "unit-test-secret")
            provider.suggest(localContext)

            val recorded = endpoint.requests.single()
            assertEquals("POST", recorded.method)
            assertEquals("Bearer unit-test-secret", recorded.headers["Authorization"]?.single())
            assertFalse(recorded.body.contains("unit-test-secret"))
            val body = mapper.readTree(recorded.body)
            assertEquals("deepseek-chat", body.path("model").asText())
            assertEquals(2, body.path("messages").size())
            assertTrue(
                body
                    .path("messages")[0]
                    .path("content")
                    .asText()
                    .isNotBlank(),
            )
            val user = mapper.readTree(body.path("messages")[1].path("content").asText())
            assertEquals("Sample.kt", user.path("path").asText())
            assertEquals("kotlin", user.path("language").asText())
            assertEquals("GAP", user.path("candidate").path("reason").asText())
            assertEquals(0.4, user.path("candidate").path("confidence").asDouble())
            assertEquals(listOf("LOGGING", "VALIDATION"), user.path("knownIntents").map { it.asText() })
            assertEquals("test-prompt-v1", user.path("promptVersion").asText())
            val excerpt = user.path("excerpt")
            val excerptText = excerpt.path("text").asText()
            assertTrue(excerptText.contains("abcd"))
            assertTrue(excerptText.length < 1_500)
            assertTrue(excerpt.path("startByte").asInt() > 0)
            assertTrue(excerpt.path("endByte").asInt() < text.toByteArray(StandardCharsets.UTF_8).size)
        }
    }

    @Test
    fun missingKeyReturnsNoProposalsWithoutCallingTheEndpoint() {
        val endpoint = FakeEndpoint()
        endpoint.use { endpoint ->
            val provider = provider(endpoint, key = null)
            assertTrue(provider.suggest(context()).isEmpty())
            assertTrue(endpoint.requests.isEmpty())
        }
    }

    @Test
    fun non200MalformedContentAndWrongShapeReturnNoProposals() {
        val responses =
            listOf(
                Response(500, """{"error":"nope"}"""),
                completion("this is not json"),
                completion("""{"candidates":[]}"""),
                completion("""[{"startByte":0,"endByte":4,"intent":"LOGGING"}]"""),
            )
        for (response in responses) {
            val endpoint = FakeEndpoint()
            endpoint.use { endpoint ->
                endpoint.responder = { response }
                assertTrue(provider(endpoint).suggest(context()).isEmpty(), response.body)
            }
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
    fun httpTimeoutReturnsNoProposals() {
        val endpoint = FakeEndpoint()
        endpoint.use { endpoint ->
            endpoint.responder = {
                Thread.sleep(1_000)
                completion("""[]""")
            }
            val provider = provider(endpoint, timeoutMs = 100)
            assertTrue(provider.suggest(context()).isEmpty())
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

    private fun provider(
        endpoint: FakeEndpoint,
        key: String? = "test-key",
        timeoutMs: Long = 2_000L,
        maxConfidence: Double = DEEPSEEK_MAX_CONFIDENCE,
    ): DeepSeekIntentSuggestionProvider =
        DeepSeekIntentSuggestionProvider(
            config =
                DeepSeekProviderConfig(
                    apiKeyEnv = "CINT_TEST_DEEPSEEK_KEY",
                    baseUrl = endpoint.url,
                    timeoutMs = timeoutMs,
                    maxConfidence = maxConfidence,
                ),
            apiKey = { if (it == "CINT_TEST_DEEPSEEK_KEY") key else null },
        )

    private fun completion(content: String): Response {
        val body =
            mapper.writeValueAsString(
                linkedMapOf(
                    "choices" to
                        listOf(
                            linkedMapOf(
                                "message" to linkedMapOf("role" to "assistant", "content" to content),
                            ),
                        ),
                ),
            )
        return Response(200, body)
    }

    private data class Response(
        val status: Int,
        val body: String,
    )

    private data class RecordedRequest(
        val method: String,
        val headers: Map<String, List<String>>,
        val body: String,
    )

    private class FakeEndpoint : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        private val executor = Executors.newCachedThreadPool()
        val requests = CopyOnWriteArrayList<RecordedRequest>()

        @Volatile
        var responder: (RecordedRequest) -> Response = { Response(200, """{"choices":[]}""") }

        val url: String
            get() = "http://127.0.0.1:${server.address.port}/chat/completions"

        init {
            server.createContext("/chat/completions") { exchange ->
                val body =
                    exchange.requestBody.use {
                        String(it.readBytes(), StandardCharsets.UTF_8)
                    }
                val headers =
                    exchange.requestHeaders.entries.associate { entry ->
                        entry.key to entry.value.toList()
                    }
                val recorded = RecordedRequest(exchange.requestMethod, headers, body)
                requests += recorded
                val response = responder(recorded)
                val bytes = response.body.toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(response.status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.executor = executor
            server.start()
        }

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
