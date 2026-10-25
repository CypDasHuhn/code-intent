package dev.cint.integration.ai

import com.sun.net.httpserver.HttpServer
import dev.cint.ai.DefaultFallbackAi
import dev.cint.ai.FallbackAiBudget
import dev.cint.ai.HttpAiTransport
import dev.cint.ai.HttpFallbackAiProvider
import dev.cint.ai.HttpFallbackAiProviderConfig
import dev.cint.contracts.data.Completeness
import dev.cint.contracts.data.Intents
import dev.cint.contracts.data.Provenance
import dev.cint.contracts.data.Status
import dev.cint.contracts.facts.FactBatch
import dev.cint.contracts.facts.FunctionFact
import dev.cint.contracts.facts.IndexedSource
import dev.cint.contracts.facts.SourceInput
import dev.cint.contracts.facts.SyntaxNode
import dev.cint.contracts.facts.SyntaxTree
import dev.cint.contracts.ports.AnalysisRequest
import dev.cint.contracts.ports.FallbackAiPolicy
import dev.cint.contracts.ports.PackManifest
import dev.cint.contracts.ports.Playbook
import dev.cint.knowledge.InMemoryKnowledgeStore
import dev.cint.pipeline.DefaultIntentPipeline
import dev.cint.v2.testing.fakes.FakeRulePackSource
import kotlinx.datetime.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets

class FallbackAiIntegrationTest {
    private var server: HttpServer? = null
    private val now = Instant.parse("2025-06-01T00:00:00Z")

    @AfterTest
    fun stop() {
        server?.stop(0)
    }

    @Test
    fun realHttpProposalsMergeAndApplyThroughTheSharedStoreWithoutLeakingIntoTheRun() {
        val server = startServer()
        val provider = HttpFallbackAiProvider(
            HttpFallbackAiProviderConfig(
                endpoint = URI("http://127.0.0.1:${server.address.port}/chat"),
                timeoutMs = 2_000,
            ),
            HttpAiTransport(URI("http://127.0.0.1:${server.address.port}/chat")),
        )
        val ai = DefaultFallbackAi(
            provider,
            budget = FallbackAiBudget(maxConfidence = 0.7),
            clock = { now },
        )
        val store = InMemoryKnowledgeStore.empty { now }
        val pipeline = DefaultIntentPipeline(
            FakeRulePackSource(
                packManifests = listOf(PackManifest("demo", "Demo", "1", listOf("kotlin"))),
                playbooks = mapOf("demo" to Playbook("demo", emptyList())),
            ),
            fallbackAi = ai,
            fallbackAiPolicy = FallbackAiPolicy.selective(),
            knowledgeStore = store,
        )
        val source = source()

        val first = pipeline.analyze(
            AnalysisRequest(language = "kotlin"),
            source,
            store.snapshot().getOrThrow(),
        ).getOrThrow()

        val annotation = store.functionAnnotations().getOrThrow().single()
        assertEquals("findUser", annotation.selector.qualifiedName)
        assertEquals(setOf(Intents.LOOKUP, Intents.SEARCH), annotation.intents)
        assertEquals(Provenance.AI_FALLBACK, annotation.provenance)
        assertEquals(Status.SUGGESTED, annotation.status)
        assertEquals(0.7, annotation.confidence.value)
        assertTrue(first.annotations.isEmpty())

        val clean = source.copy(
            input = SourceInput.InMemory("fun findUser() {}", "kotlin", 0, "Sample.kt"),
            completeness = Completeness.COMPLETE,
        )
        val second = pipeline.analyze(
            AnalysisRequest(language = "kotlin"),
            clean,
            store.snapshot().getOrThrow(),
        ).getOrThrow()

        assertTrue(second.annotations.isEmpty())
    }

    private fun startServer(): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/chat") { exchange ->
            exchange.requestBody.use { it.readAllBytes() }
            val content =
                """
                [{"kind":"function","name":"findUser","intents":["LOOKUP"],"confidence":0.4,"rationale":"a"},
                 {"kind":"function","name":"findUser","intents":["SEARCH"],"confidence":0.8,"rationale":"b"}]
                """.trimIndent()
            val escaped = content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
            val payload =
                """{"choices":[{"message":{"content":"$escaped"}}]}""".toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()
        this.server = server
        return server
    }

    private fun source(): IndexedSource {
        val text = "fun findUser() { return lookup() }"
        val root = SyntaxNode(
            id = "root",
            kind = "file",
            startByte = 0,
            endByte = text.length,
            startLine = 0,
            startCol = 0,
            endLine = 0,
            endCol = text.length,
            text = text,
            childIds = listOf("fn-0"),
        )
        val function = SyntaxNode(
            id = "fn-0",
            kind = "function_declaration",
            startByte = 0,
            endByte = text.length,
            startLine = 0,
            startCol = 0,
            endLine = 0,
            endCol = text.length,
            text = text,
            parentId = root.id,
        )
        val tree = SyntaxTree(
            rootId = root.id,
            nodes = mapOf(root.id to root, function.id to function),
            language = "kotlin",
            version = "1",
            contentHash = "hash-1",
        )
        return IndexedSource(
            input = SourceInput.InMemory(text, "kotlin", 1, "Sample.kt"),
            facts = FactBatch(
                syntaxTree = tree,
                functions = listOf(FunctionFact("fn-0", "findUser", null, null)),
            ),
            sourceEpoch = 1,
            completeness = Completeness.COMPLETE,
            contentHash = "hash-1",
        )
    }
}
