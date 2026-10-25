package dev.cint.ai

import com.sun.net.httpserver.HttpServer
import dev.cint.contracts.data.Confidence
import dev.cint.contracts.data.Intents
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractError
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HttpFallbackAiProviderTest {
    private var server: HttpServer? = null

    @AfterTest
    fun stop() {
        server?.stop(0)
    }

    @Test
    fun realTransportPostsToAFakeServerAndParsesProposals() {
        var received = ""
        val server =
            startServer { body ->
                received = body
                """
                {"choices":[{"message":{"content":"```json\n[{\"kind\":\"function\",\"name\":\"findUser\",\"intents\":[\"LOOKUP\"],\"confidence\":0.8,\"rationale\":\"reads\"}]\n```"}}]}
                """.trimIndent()
            }
        val provider =
            HttpFallbackAiProvider(
                config(server),
                HttpAiTransport(URI("http://127.0.0.1:${server.address.port}/chat")),
            )

        val result = provider.propose(providerRequest()).getOrThrow()

        assertEquals(1, result.size)
        val proposal = result.single() as AiProposal.Function
        assertEquals("findUser", proposal.name)
        assertEquals(setOf(Intents.LOOKUP), proposal.intents)
        assertEquals(Confidence.fromDouble(0.8), proposal.confidence)
        assertTrue(received.contains("findUser"))
    }

    @Test
    fun nonSuccessStatusFailsWithoutProposals() {
        val server = startServer(status = 503, response = "{\"error\":\"unavailable\"}")
        val provider =
            HttpFallbackAiProvider(
                config(server),
                HttpAiTransport(URI("http://127.0.0.1:${server.address.port}/chat")),
            )

        val result = provider.propose(providerRequest())

        assertTrue(result.isFailure)
        assertTrue(result.errorOrNull is ContractError.DependencyUnavailable)
    }

    @Test
    fun malformedResponseFailsHermetically() {
        val server = startServer { "{\"choices\":[]}" }
        val provider =
            HttpFallbackAiProvider(
                config(server),
                HttpAiTransport(URI("http://127.0.0.1:${server.address.port}/chat")),
            )

        val result = provider.propose(providerRequest())

        assertTrue(result.isFailure)
        assertTrue(result.errorOrNull is ContractError.SerializationError)
    }

    @Test
    fun nonArrayProposalContentFailsInsteadOfLookingEmpty() {
        val server = startServer { """{"choices":[{"message":{"content":"{\"kind\":\"function\"}"}}]}""" }
        val provider =
            HttpFallbackAiProvider(
                config(server),
                HttpAiTransport(URI("http://127.0.0.1:${server.address.port}/chat")),
            )

        val result = provider.propose(providerRequest())

        assertTrue(result.isFailure)
        assertIs<ContractError.SerializationError>(result.errorOrNull)
    }

    @Test
    fun oversizedResponseIsRejected() {
        val server = startServer { "x".repeat(500) }
        val transport =
            HttpAiTransport(
                URI("http://127.0.0.1:${server.address.port}/chat"),
                maxResponseBytes = 50,
            )

        val result = transport.post("{}", CancellationToken.NONE)

        assertTrue(result.isFailure)
        assertIs<ContractError.SerializationError>(result.errorOrNull)
    }

    @Test
    fun cancellationDuringASlowRequestIsObserved() {
        val server = startServer(delayMs = 500) { "{}" }
        val transport = HttpAiTransport(URI("http://127.0.0.1:${server.address.port}/chat"), timeoutMs = 2_000)
        val started = System.nanoTime()

        val result =
            transport.post(
                "{}",
                CancellationToken.from {
                    (System.nanoTime() - started) / 1_000_000 >= 30
                },
            )

        assertTrue(result.isFailure)
        assertEquals(ContractError.Cancelled("fallback-ai"), result.errorOrNull)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsedMs < 400, "cancellation took ${elapsedMs}ms")
    }

    @Test
    fun anyInvalidProposalElementFailsTheWholeResponse() {
        val server =
            startServer {
                """{"choices":[{"message":{"content":"[{\"kind\":\"function\",\"name\":\"findUser\",\"intents\":[\"LOOKUP\"],\"confidence\":0.8},{\"kind\":\"function\"}]"}}]}"""
            }
        val provider =
            HttpFallbackAiProvider(
                config(server),
                HttpAiTransport(URI("http://127.0.0.1:${server.address.port}/chat")),
            )

        val result = provider.propose(providerRequest())

        assertTrue(result.isFailure)
        assertIs<ContractError.SerializationError>(result.errorOrNull)
    }

    @Test
    fun bodyReadFailureIsTranslatedToAContractError() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/chat") { exchange ->
            exchange.requestBody.use { it.readAllBytes() }
            exchange.sendResponseHeaders(200, 1_024)
            exchange.responseBody.use { it.write("x".repeat(8).toByteArray(StandardCharsets.UTF_8)) }
        }
        server.start()
        this.server = server
        val transport = HttpAiTransport(URI("http://127.0.0.1:${server.address.port}/chat"), timeoutMs = 2_000)

        val result = transport.post("{}", CancellationToken.NONE)

        assertTrue(result.isFailure)
        assertIs<ContractError.DependencyUnavailable>(result.errorOrNull)
    }

    private fun config(server: HttpServer): HttpFallbackAiProviderConfig =
        HttpFallbackAiProviderConfig(
            endpoint = URI("http://127.0.0.1:${server.address.port}/chat"),
            model = "fake-model",
            timeoutMs = 2_000,
        )

    private fun providerRequest(): AiProviderRequest =
        AiProviderRequest(
            path = "Sample.kt",
            language = "kotlin",
            contentHash = "hash",
            source = "fun findUser() {}",
            functions = listOf(AiFunctionPrompt("findUser", "fun findUser() {}", 0, 0)),
            knownIntents = Intents.allKnown,
        )

    private fun startServer(
        status: Int = 200,
        delayMs: Long = 0,
        response: (String) -> String,
    ): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/chat") { exchange ->
            val body =
                exchange.requestBody.use { input ->
                    String(input.readAllBytes(), StandardCharsets.UTF_8)
                }
            if (delayMs > 0) Thread.sleep(delayMs)
            val payload = response(body).toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()
        this.server = server
        return server
    }

    private fun startServer(
        status: Int,
        response: String,
    ): HttpServer = startServer(status) { response }
}
