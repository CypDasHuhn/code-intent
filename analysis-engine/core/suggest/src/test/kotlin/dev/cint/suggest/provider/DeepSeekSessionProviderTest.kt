package dev.cint.suggest.provider

import dev.cint.api.Span
import dev.cint.suggest.session.AgentMessage
import dev.cint.suggest.session.AgentRequest
import dev.cint.suggest.session.AgentTurn
import dev.cint.suggest.session.AnnotationSession
import dev.cint.suggest.session.SessionDocument
import dev.cint.suggest.session.SessionLimits
import dev.cint.suggest.session.SessionUnit
import dev.cint.suggest.session.ToolDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeepSeekSessionProviderTest {
    private val text = "val match = Regex(\"cint\").find(text)"
    private val findStart = text.indexOf("find(text)")
    private val findEnd = findStart + "find(text)".length

    @Test
    fun parsesToolCallsAndSendsTheMcpShapedToolSurface() {
        val endpoint = FakeEndpoint()
        endpoint.use { endpoint ->
            endpoint.responder = {
                toolCompletion(
                    name = "submit_verdicts",
                    arguments =
                        """{"verdicts":[{"unitId":"u0","add":[{"intent":"SEARCH","startByte":$findStart,"endByte":$findEnd,"confidence":0.6,"rationale":"find searches"}]}]}""",
                )
            }
            val provider = provider(endpoint)
            val turn =
                provider.complete(
                    AgentRequest(
                        messages =
                            listOf(
                                AgentMessage("system", "system instructions"),
                                AgentMessage("user", "grade"),
                            ),
                        tools = listOf(ToolDefinition("submit_verdicts", "submit", mapOf("type" to "object"))),
                        promptVersion = DEEPSEEK_PROMPT_VERSION,
                        maxTokens = 512,
                        timeoutMs = 2_000,
                    ),
                )

            val calls = (turn as AgentTurn.Calls).calls
            assertEquals("submit_verdicts", calls.single().name)
            assertTrue(calls.single().arguments.contains("SEARCH"))
            val body = deepSeekMapper.readTree(endpoint.requests.single().body)
            assertEquals(DEEPSEEK_PROMPT_VERSION, provider.defaultPromptVersion)
            assertEquals(512, body.path("max_tokens").asInt())
            assertEquals(
                "submit_verdicts",
                body
                    .path("tools")[0]
                    .path("function")
                    .path("name")
                    .asText(),
            )
            assertTrue(body.path("messages").any { it.path("role").asText() == "user" })
            assertTrue(
                body.path("messages").any {
                    it.path("role").asText() == "system" && it.path("content").asText() == "system instructions"
                },
            )
        }
    }

    @Test
    fun deepSeekSessionPublishesValidatedCandidatesFromAToolLoop() {
        val endpoint = FakeEndpoint()
        endpoint.use { endpoint ->
            endpoint.responder = {
                toolCompletion(
                    name = "submit_verdicts",
                    arguments =
                        """{"verdicts":[{"unitId":"u0","add":[{"intent":"SEARCH","startByte":$findStart,"endByte":$findEnd,"confidence":0.6,"rationale":"find searches"}]}]}""",
                )
            }
            val provider = provider(endpoint)
            val unit =
                SessionUnit(
                    unitId = "u0",
                    path = "Facts.kt",
                    language = "kotlin",
                    startByte = 0,
                    endByte = text.length,
                    span = Span(0, 0, 0, text.length),
                    text = text,
                    enclosingStatement = Span(0, 0, 0, text.length),
                )
            val document =
                SessionDocument(
                    path = "Facts.kt",
                    version = 1,
                    text = text,
                    language = "kotlin",
                    units = listOf(unit),
                    annotations = emptyList(),
                    knownIntents = setOf("SEARCH", "LOGGING"),
                )

            val result = AnnotationSession(provider).run(document, SessionLimits())

            assertEquals(1, result.graded)
            assertEquals(1, result.additions)
            val candidate = result.candidates.single()
            assertEquals(setOf("SEARCH"), candidate.intents)
            assertEquals("ai:deepseek", candidate.source)
            assertEquals(findStart, candidate.startByte)
            assertEquals(findEnd, candidate.endByte)
            assertEquals(DEEPSEEK_PROMPT_VERSION, candidate.promptVersion)
            val body = deepSeekMapper.readTree(endpoint.requests.single().body)
            assertEquals("system", body.path("messages")[0].path("role").asText())
            assertTrue(
                body
                    .path("messages")[0]
                    .path("content")
                    .asText()
                    .contains("annotation discovery agent"),
            )
        }
    }

    private fun toolCompletion(
        name: String,
        arguments: String,
    ): Response {
        val body =
            deepSeekMapper.writeValueAsString(
                linkedMapOf(
                    "choices" to
                        listOf(
                            linkedMapOf(
                                "message" to
                                    linkedMapOf(
                                        "role" to "assistant",
                                        "content" to "",
                                        "tool_calls" to
                                            listOf(
                                                linkedMapOf(
                                                    "id" to "call-1",
                                                    "type" to "function",
                                                    "function" to
                                                        linkedMapOf(
                                                            "name" to name,
                                                            "arguments" to arguments,
                                                        ),
                                                ),
                                            ),
                                    ),
                            ),
                        ),
                ),
            )
        return Response(200, body)
    }
}
