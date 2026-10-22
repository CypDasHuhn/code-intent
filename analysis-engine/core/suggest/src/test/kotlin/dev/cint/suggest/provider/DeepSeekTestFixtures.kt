package dev.cint.suggest.provider

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import dev.cint.api.CandidateEmit
import dev.cint.api.CandidateReason
import dev.cint.api.Span
import dev.cint.suggest.model.CandidateContext
import dev.cint.suggest.model.SourceExcerpt
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

internal val deepSeekMapper = jacksonObjectMapper()

internal val candidate =
    CandidateEmit(
        intents = setOf("VALIDATION"),
        span = Span(0, 0, 0, 4),
        startByte = 0,
        endByte = 4,
        source = "rule:validation:validation",
        confidence = 0.4,
        reason = CandidateReason.GAP,
    )

internal fun context(text: String = "abcd"): CandidateContext =
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

internal fun provider(
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

internal fun completion(content: String): Response {
    val body =
        deepSeekMapper.writeValueAsString(
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

internal data class Response(
    val status: Int,
    val body: String,
)

internal data class RecordedRequest(
    val method: String,
    val headers: Map<String, List<String>>,
    val body: String,
)

internal class FakeEndpoint : AutoCloseable {
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
