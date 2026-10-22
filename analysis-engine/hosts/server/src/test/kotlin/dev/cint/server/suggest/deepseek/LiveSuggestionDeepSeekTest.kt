package dev.cint.server.suggest.deepseek

import dev.cint.core.user.UserAnnotationsFile
import dev.cint.core.user.UserAnnotationsJson
import dev.cint.server.SuggestionSettings
import dev.cint.server.suggest.support.ChatEndpoint
import dev.cint.server.suggest.support.ChatResponse
import dev.cint.server.suggest.support.gapSource
import dev.cint.server.suggest.support.harness
import dev.cint.server.suggest.support.lineCol
import dev.cint.server.suggest.support.mapper
import dev.cint.server.suggestionServiceFrom
import dev.cint.suggest.DeepSeekIntentSuggestionProvider
import dev.cint.suggest.DeepSeekProviderConfig
import dev.cint.suggest.SuggestionProviderRegistry
import dev.cint.suggest.SuggestionService
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveSuggestionDeepSeekTest {
    @Test
    fun deepSeekProviderEndToEndUsesFakeEndpointAndPublishesConfirmableCandidates() {
        val root = Files.createTempDirectory("cint-live-suggest-deepseek")
        val endpoint = ChatEndpoint()
        try {
            endpoint.responder = { request ->
                val body = mapper.readTree(request.body)
                val user = mapper.readTree(body.path("messages")[1].path("content").asText())
                val startByte = user.path("candidate").path("startByte").asInt()
                val endByte = user.path("candidate").path("endByte").asInt()
                val content =
                    mapper.writeValueAsString(
                        listOf(
                            linkedMapOf(
                                "startByte" to startByte,
                                "endByte" to endByte,
                                "intent" to "LOGGING",
                                "confidence" to 0.99,
                                "rationale" to "deepseek says logging",
                            ),
                        ),
                    )
                val response =
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
                ChatResponse(200, response)
            }
            val suggestionSettings =
                SuggestionSettings(
                    enabled = true,
                    provider = "deepseek",
                    model = "deepseek-chat",
                    apiKeyEnv = "CINT_TEST_DEEPSEEK_KEY",
                    baseUrl = endpoint.url,
                    timeoutMs = 3_000,
                )
            val service = suggestionServiceFrom(suggestionSettings) { "fake-key" }
            val harness = harness(root, suggestionSettings = suggestionSettings, service = service)
            val uri = root.resolve("Sample.kt").toString()
            harness.open(uri, gapSource, version = 1)
            harness.suggest(uri, version = 1, id = 2, requestId = "deepseek")

            assertTrue(harness.waitForResponse(2).path("accepted").asBoolean())
            val enriched =
                harness.waitForCandidate(uri, "LOGGING") {
                    it["source"].asText() == "ai:deepseek"
                }
            assertEquals("deepseek", enriched["provider"].asText())
            assertEquals("deepseek-chat", enriched["model"].asText())
            assertEquals("deepseek-intent-v1", enriched["promptVersion"].asText())
            assertEquals(0.84, enriched["confidence"].asDouble())
            assertFalse(enriched["aiEligible"].asBoolean())
            assertEquals("deepseek says logging", enriched["rationale"].asText())
            assertFalse(
                endpoint.requests
                    .single()
                    .body
                    .contains("fake-key"),
            )

            harness.decide(uri, version = 1, id = 3, candidateId = enriched["candidateId"].asText(), decision = "confirm")
            val annotations = UserAnnotationsJson.parse(Files.readString(UserAnnotationsFile.path(root)))
            assertEquals(setOf("LOGGING"), annotations.single().intents)
        } finally {
            endpoint.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun deepSeekSubSpanPublishesAbsoluteBytesLinesAndColumns() {
        val root = Files.createTempDirectory("cint-live-suggest-deepseek-subspan")
        val endpoint = ChatEndpoint()
        val source =
            """
            fun f(): Boolean {
                // cint: validation

                if (true) {
                    work("é")
                }
                return false
            }
            """.trimIndent()
        try {
            endpoint.responder = { request ->
                val body = mapper.readTree(request.body)
                val user = mapper.readTree(body.path("messages")[1].path("content").asText())
                val startByte = user.path("candidate").path("startByte").asInt() + 1
                val endByte = user.path("candidate").path("endByte").asInt() - 1
                val content =
                    mapper.writeValueAsString(
                        listOf(
                            linkedMapOf(
                                "startByte" to startByte,
                                "endByte" to endByte,
                                "intent" to "LOGGING",
                                "confidence" to 0.7,
                                "rationale" to "sub span",
                            ),
                        ),
                    )
                val response =
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
                ChatResponse(200, response)
            }
            val suggestionSettings =
                SuggestionSettings(
                    enabled = true,
                    provider = "deepseek",
                    model = "deepseek-chat",
                    apiKeyEnv = "CINT_TEST_DEEPSEEK_KEY",
                    baseUrl = endpoint.url,
                    timeoutMs = 3_000,
                )
            val service = suggestionServiceFrom(suggestionSettings) { "fake-key" }
            val harness = harness(root, suggestionSettings = suggestionSettings, service = service)
            val uri = root.resolve("Sample.kt").toString()
            harness.open(uri, source, version = 1)
            harness.suggest(uri, version = 1, id = 2, requestId = "deepseek-subspan")

            assertTrue(harness.waitForResponse(2).path("accepted").asBoolean())
            val enriched =
                harness.waitForCandidate(uri, "LOGGING") {
                    it["source"].asText() == "ai:deepseek"
                }
            val body = mapper.readTree(endpoint.requests.single().body)
            val user = mapper.readTree(body.path("messages")[1].path("content").asText())
            val excerptText = user.path("excerpt").path("text").asText()
            assertTrue(excerptText.contains("é"))
            val startByte = user.path("candidate").path("startByte").asInt() + 1
            val endByte = user.path("candidate").path("endByte").asInt() - 1
            assertEquals(startByte, enriched["startByte"].asInt())
            assertEquals(endByte, enriched["endByte"].asInt())
            val start = lineCol(source, startByte)
            val end = lineCol(source, endByte)
            assertEquals(start.first, enriched["startLine"].asInt())
            assertEquals(start.second, enriched["startCol"].asInt())
            assertEquals(end.first, enriched["endLine"].asInt())
            assertEquals(end.second, enriched["endCol"].asInt())
        } finally {
            endpoint.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun deepSeekBlockingEndpointTimesOutWithoutPublishingPartialCandidates() {
        val root = Files.createTempDirectory("cint-live-suggest-deepseek-timeout")
        val endpoint = ChatEndpoint()
        try {
            endpoint.responder = {
                Thread.sleep(2_000)
                ChatResponse(200, """{"choices":[]}""")
            }
            val provider =
                DeepSeekIntentSuggestionProvider(
                    config =
                        DeepSeekProviderConfig(
                            apiKeyEnv = "CINT_TEST_DEEPSEEK_KEY",
                            baseUrl = endpoint.url,
                            timeoutMs = 5_000,
                        ),
                    apiKey = { "fake-key" },
                )
            val service = SuggestionService(SuggestionProviderRegistry(listOf(provider)))
            val suggestionSettings =
                SuggestionSettings(
                    enabled = true,
                    provider = "deepseek",
                    model = "deepseek-chat",
                    timeoutMs = 100,
                )
            val harness = harness(root, suggestionSettings = suggestionSettings, service = service)
            val uri = root.resolve("Sample.kt").toString()
            harness.open(uri, gapSource, version = 1)
            harness.suggest(uri, version = 1, id = 2, requestId = "deepseek-timeout")

            val response = harness.waitForResponse(2)
            assertTrue(response.path("cancelled").asBoolean())
            assertTrue(response.path("timeout").asBoolean())
            assertTrue(
                harness.messages().none {
                    it["method"]?.asText() == "cint/publishCandidates" &&
                        it.path("params").path("candidates").any { candidate -> candidate["source"].asText() == "ai:deepseek" }
                },
            )
        } finally {
            endpoint.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun deepSeekProviderWithoutKeyDegradesToDeterministicCandidates() {
        val root = Files.createTempDirectory("cint-live-suggest-deepseek-nokey")
        val endpoint = ChatEndpoint()
        try {
            val suggestionSettings =
                SuggestionSettings(
                    enabled = true,
                    provider = "deepseek",
                    apiKeyEnv = "CINT_TEST_MISSING_DEEPSEEK_KEY",
                    baseUrl = endpoint.url,
                    timeoutMs = 100,
                )
            val service = suggestionServiceFrom(suggestionSettings) { null }
            val harness = harness(root, suggestionSettings = suggestionSettings, service = service)
            val uri = root.resolve("Sample.kt").toString()
            harness.open(uri, gapSource, version = 1)
            harness.suggest(uri, version = 1, id = 2, requestId = "missing-key")

            assertTrue(harness.waitForResponse(2).path("accepted").asBoolean())
            val candidates = harness.latest("cint/publishCandidates").path("params").path("candidates")
            assertEquals(1, candidates.size())
            assertEquals("rule:validation:validation", candidates.single()["source"].asText())
            assertTrue(endpoint.requests.isEmpty())
        } finally {
            endpoint.close()
            root.toFile().deleteRecursively()
        }
    }
}
