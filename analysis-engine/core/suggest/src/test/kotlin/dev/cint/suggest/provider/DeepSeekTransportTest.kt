package dev.cint.suggest.provider

import dev.cint.api.Span
import dev.cint.suggest.model.SourceExcerpt
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeepSeekTransportTest {
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
            val body = deepSeekMapper.readTree(recorded.body)
            assertEquals("deepseek-chat", body.path("model").asText())
            assertEquals(2, body.path("messages").size())
            assertTrue(
                body
                    .path("messages")[0]
                    .path("content")
                    .asText()
                    .isNotBlank(),
            )
            val user = deepSeekMapper.readTree(body.path("messages")[1].path("content").asText())
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
}
