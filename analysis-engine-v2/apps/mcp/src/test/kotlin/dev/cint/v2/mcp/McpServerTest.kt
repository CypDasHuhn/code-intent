package dev.cint.v2.mcp

import dev.cint.contracts.data.AnalysisError
import dev.cint.contracts.data.AnalysisResult
import dev.cint.contracts.data.Completeness
import dev.cint.contracts.data.Intents
import dev.cint.contracts.facts.FactBatch
import dev.cint.contracts.facts.IndexedSource
import dev.cint.contracts.knowledge.AnnotationScope
import dev.cint.contracts.knowledge.KnowledgeSnapshot
import dev.cint.contracts.ports.AnalysisRequest
import dev.cint.contracts.ports.IntentPipeline
import dev.cint.contracts.ports.KnowledgeRepositoryLocation
import dev.cint.contracts.ports.PackManifest
import dev.cint.contracts.ports.Playbook
import dev.cint.contracts.ports.RuleSpec
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory
import dev.cint.knowledge.InMemoryKnowledgeStore
import dev.cint.knowledge.persistence.FileKnowledgeRepository
import dev.cint.knowledge.persistence.PersistentKnowledgeStores
import dev.cint.runtime.DefaultRuntime
import dev.cint.runtime.RuntimeComponents
import dev.cint.runtime.RuntimeConfig
import dev.cint.runtime.RuntimeFactory
import dev.cint.runtime.RuntimeLanguageComposition
import dev.cint.v2.testing.fakes.FakeClock
import dev.cint.v2.testing.fakes.FakeIndex
import dev.cint.v2.testing.fakes.FakeLanguageSupport
import dev.cint.v2.testing.fakes.FakeRuleEvaluator
import dev.cint.v2.testing.fakes.FakeRuleEvaluatorRegistry
import dev.cint.v2.testing.fakes.FakeRulePackSource
import dev.cint.v2.testing.fakes.fakeDescriptor
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class McpServerTest {
    private val temporaryRoots = mutableListOf<Path>()
    private val now = Instant.parse("2025-01-01T00:00:00Z")

    @AfterTest
    fun cleanUp() {
        temporaryRoots.forEach { runCatching { it.toFile().deleteRecursively() } }
    }

    @Test
    fun scriptedClientUsesRealRuntimeStoreValidationAndPersistence() {
        val clock = TestClock(now)
        val first = harness(temporaryRoot(), clock)
        first.client().use { client ->
            client.initialize()
            assertTrue(client.toolNames().containsAll(REQUIRED_TOOLS))

            val resources = client.request(2, "resources/list")
                .getValue("result").jsonObject.getValue("resources").jsonArray
            assertTrue(resources.size >= 5)
            val snapshotRead = client.request(
                3,
                "resources/read",
                buildJsonObject { put("uri", McpResourceUris.SNAPSHOT) },
            ).getValue("result").jsonObject
                .getValue("contents").jsonArray
                .single().jsonObject
            assertTrue(snapshotRead.getValue("text").jsonPrimitive.content.contains("functionAnnotations"))

            val analysis = client.structured(
                client.tool(
                    4,
                    "analysis.run",
                    buildJsonObject {
                        put("targetPaths", buildJsonArray { add(JsonPrimitive(SAMPLE_PATH)) })
                        put("language", "kotlin")
                    },
                ),
            )
            val result = analysis.getValue("result").jsonObject
            assertEquals(
                "text",
                client.toolContentType(
                    client.tool(
                        4,
                        "analysis.run",
                        buildJsonObject {
                            put("targetPaths", buildJsonArray { add(JsonPrimitive(SAMPLE_PATH)) })
                            put("language", "kotlin")
                        },
                    ),
                ),
            )
            assertEquals("COMPLETE", result.getValue("completeness").jsonPrimitive.content)
            assertTrue(
                result.getValue("errors").jsonArray.none {
                    it.jsonObject["severity"]?.jsonPrimitive?.content == "ERROR"
                },
                result.toString(),
            )

            val future = now.plus(kotlin.time.Duration.parse("365d"))
            client.tool(
                5,
                "function_annotations.set",
                annotationArguments("findUser", Intents.LOOKUP, future),
            )
            val stored = first.store.functionAnnotations().getOrThrow().single()
            assertEquals(clock.instant, stored.updatedAt)

            clock.instant = now.plus(kotlin.time.Duration.parse("1s"))
            client.tool(
                6,
                "function_annotations.set",
                annotationArguments("findUser", Intents.SEARCH, future),
            )
            assertEquals(clock.instant, first.store.functionAnnotations().getOrThrow().single().updatedAt)

            assertEquals(
                1,
                client.structured(client.tool(7, "function_annotations.list"))
                    .getValue("annotations").jsonArray.size,
            )
            client.tool(
                8,
                "function_annotations.remove",
                buildJsonObject {
                    put("selector", buildJsonObject { put("qualifiedName", "findUser") })
                },
            )
            assertEquals(0, first.store.functionAnnotations().getOrThrow().size)

            val spanSelector = spanSelector()
            client.tool(
                9,
                "span_overrides.set",
                buildJsonObject {
                    put(
                        "override",
                        buildJsonObject {
                            put("selector", spanSelector)
                            put("intents", buildJsonArray { add(JsonPrimitive(Intents.VALIDATION)) })
                            put("createdAt", JsonPrimitive(future.toString()))
                            put("updatedAt", JsonPrimitive(future.toString()))
                        },
                    )
                },
            )
            assertEquals(clock.instant, first.store.spanOverrides().getOrThrow().single().updatedAt)
            client.tool(
                10,
                "span_overrides.remove",
                buildJsonObject { put("selector", spanSelector) },
            )
            assertEquals(0, first.store.spanOverrides().getOrThrow().size)

            val invalid = client.tool(
                11,
                "function_annotations.set",
                annotationArguments("bad", "NOT_AN_INTENT", future),
            )
            assertTrue(invalid.getValue("isError").jsonPrimitive.content.toBoolean())
            assertEquals(
                "ValidationError",
                client.structured(invalid).getValue("error").jsonObject
                    .getValue("code").jsonPrimitive.content,
            )

            val proposal = client.structured(
                client.tool(
                    12,
                    "changes.propose",
                    buildJsonObject {
                        put("description", "set persisted annotation")
                        put(
                            "changes",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("type", "SetFunctionAnnotation")
                                        put(
                                            "annotation",
                                            buildJsonObject {
                                                put(
                                                    "selector",
                                                    buildJsonObject { put("qualifiedName", "persisted") },
                                                )
                                                put(
                                                    "intents",
                                                    buildJsonArray { add(JsonPrimitive(Intents.LOOKUP)) },
                                                )
                                                put("scope", JsonPrimitive(AnnotationScope.GLOBAL.name))
                                                put("provenance", JsonPrimitive("MCP_AGENT"))
                                                put("confidence", buildJsonObject { put("value", 0.7) })
                                                put("status", JsonPrimitive("SUGGESTED"))
                                                put("createdAt", JsonPrimitive(future.toString()))
                                                put("updatedAt", JsonPrimitive(future.toString()))
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    },
                ),
            )
            val proposalId = proposal.getValue("proposalId").jsonPrimitive.content
            client.structured(
                client.tool(
                    13,
                    "changes.apply",
                    buildJsonObject { put("proposalId", proposalId) },
                ),
            )
            val applied = first.store.functionAnnotations().getOrThrow().single()
            assertEquals("persisted", applied.selector.qualifiedName)
            assertEquals(clock.instant, applied.updatedAt)

            val invalidProposal = client.structured(
                client.tool(
                    14,
                    "changes.propose",
                    buildJsonObject {
                        put(
                            "changes",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("type", "SetFunctionAnnotation")
                                        put(
                                            "annotation",
                                            buildJsonObject {
                                                put(
                                                    "selector",
                                                    buildJsonObject { put("qualifiedName", "bad2") },
                                                )
                                                put("intents", buildJsonArray { add(JsonPrimitive("BOGUS")) })
                                                put("scope", JsonPrimitive(AnnotationScope.GLOBAL.name))
                                                put("provenance", JsonPrimitive("MCP_AGENT"))
                                                put("confidence", buildJsonObject { put("value", 0.7) })
                                                put("status", JsonPrimitive("SUGGESTED"))
                                                put("createdAt", JsonPrimitive(future.toString()))
                                                put("updatedAt", JsonPrimitive(future.toString()))
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    },
                ),
            )
            val invalidApply = client.tool(
                15,
                "changes.apply",
                buildJsonObject {
                    put("proposalId", invalidProposal.getValue("proposalId").jsonPrimitive.content)
                },
            )
            assertTrue(invalidApply.getValue("isError").jsonPrimitive.content.toBoolean())

            client.tool(
                16,
                "patterns.propose",
                patternArguments("logger.*", "one.kt"),
            )
            client.tool(
                17,
                "patterns.propose",
                patternArguments("logger.*", "two.kt"),
            )
            val hypotheses = first.store.patternHypotheses().getOrThrow()
            assertEquals(2, hypotheses.size)
            val ambiguous = client.tool(
                18,
                "patterns.validate",
                buildJsonObject {
                    put("id", "logger.*")
                    put("accept", true)
                },
            )
            assertTrue(ambiguous.getValue("isError").jsonPrimitive.content.toBoolean())
            val accepted = client.tool(
                19,
                "patterns.validate",
                buildJsonObject {
                    put(
                        "id",
                        hypotheses.first { it.selector.path == "one.kt" }.selector.identityKey,
                    )
                    put("accept", true)
                },
            )
            assertFalse(accepted["isError"]?.jsonPrimitive?.content?.toBoolean() ?: false)

            val status = client.structured(client.tool(20, "repo.status"))
            assertTrue(status.getValue("status").jsonObject.containsKey("indexedFiles"))
            val index = client.structured(client.tool(21, "repo.index"))
            assertTrue(index.getValue("indexedFiles").jsonPrimitive.content.toInt() >= 1)

            val unknownTool = client.request(
                22,
                "tools/call",
                buildJsonObject {
                    put("name", "nope")
                    put("arguments", buildJsonObject {})
                },
            )
            assertEquals(
                -32602,
                unknownTool.getValue("error").jsonObject
                    .getValue("code").jsonPrimitive.content.toInt(),
            )
            val unknownResource = client.request(
                23,
                "resources/read",
                buildJsonObject { put("uri", "cint://nope") },
            )
            assertEquals(
                -32002,
                unknownResource.getValue("error").jsonObject
                    .getValue("code").jsonPrimitive.content.toInt(),
            )

            assertEquals(JsonNull, client.request(24, "shutdown")["result"])
        }
        assertTrue(first.server.closed)
        assertTrue(
            first.runtime.analyze(AnalysisRequest(listOf(SAMPLE_PATH), language = "kotlin")).isFailure,
        )

        val restarted = harness(first.root, clock)
        assertEquals(
            listOf("persisted"),
            restarted.store.functionAnnotations().getOrThrow().map { it.selector.qualifiedName },
        )
        assertEquals(2, restarted.store.patternHypotheses().getOrThrow().size)
        restarted.runtime.shutdown()
    }

    @Test
    fun cancellationKeepsControlRequestsResponsive() {
        val clock = TestClock(now)
        val root = temporaryRoot()
        val harness = harness(root, clock, BlockingPipeline())
        harness.client().use { client ->
            client.initialize()
            client.send(
                30,
                "tools/call",
                buildJsonObject {
                    put("name", "analysis.run")
                    put(
                        "arguments",
                        buildJsonObject {
                            put("targetPaths", buildJsonArray { add(JsonPrimitive(SAMPLE_PATH)) })
                            put("language", "kotlin")
                        },
                    )
                },
            )
            val ping = client.request(31, "ping")
            assertEquals("31", ping.getValue("id").jsonPrimitive.content)
            assertTrue(ping.getValue("result").jsonObject.isEmpty())

            client.notify(
                "notifications/cancelled",
                buildJsonObject { put("requestId", JsonPrimitive(30)) },
            )
            val cancelled = client.read()
            assertEquals("30", cancelled.getValue("id").jsonPrimitive.content)
            val result = cancelled.getValue("result").jsonObject
            assertTrue(result.getValue("isError").jsonPrimitive.content.toBoolean())
            assertEquals(
                "Cancelled",
                result.getValue("structuredContent").jsonObject
                    .getValue("error").jsonObject.getValue("code").jsonPrimitive.content,
            )
            assertEquals(JsonNull, client.request(32, "shutdown")["result"])
        }
    }

    @Test
    fun toolResultWireIncludesTextDiscriminator() {
        val wire =
            encodeMcp(
                dev.cint.contracts.mcp.McpToolResult(
                    content = listOf(dev.cint.contracts.mcp.McpToolContent(text = "{}")),
                ),
            )
        assertEquals(
            buildJsonObject {
                put(
                    "content",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", "{}")
                            },
                        )
                    },
                )
                put("isError", false)
            },
            wire,
        )
    }

    @Test
    fun parallelToolCallsSerializeRuntimeAccess() {
        val clock = TestClock(now)
        val harness = harness(temporaryRoot(), clock)
        harness.client().use { client ->
            client.initialize()
            val future = now.plus(kotlin.time.Duration.parse("365d"))
            val ids = (100..106).toList()
            ids.forEachIndexed { index, id ->
                when (index) {
                    5 -> client.send(
                        id,
                        "tools/call",
                        buildJsonObject {
                            put("name", "analysis.run")
                            put(
                                "arguments",
                                buildJsonObject {
                                    put(
                                        "targetPaths",
                                        buildJsonArray { add(JsonPrimitive(SAMPLE_PATH)) },
                                    )
                                    put("language", "kotlin")
                                },
                            )
                        },
                    )
                    6 -> client.send(
                        id,
                        "tools/call",
                        buildJsonObject {
                            put("name", "repo.status")
                            put("arguments", buildJsonObject {})
                        },
                    )
                    else -> client.send(
                        id,
                        "tools/call",
                        buildJsonObject {
                            put("name", "function_annotations.set")
                            put("arguments", annotationArguments("parallel-$index", Intents.LOOKUP, future))
                        },
                    )
                }
            }
            val responses =
                ids.map { client.read() }
                    .associateBy { it.getValue("id").jsonPrimitive.content.toInt() }
            assertEquals(ids.toSet(), responses.keys)
            responses.values.forEach { response ->
                assertFalse(response.containsKey("error"), response.toString())
                val result = response.getValue("result").jsonObject
                assertFalse(result["isError"]?.jsonPrimitive?.content?.toBoolean() ?: false)
                assertEquals("text", result.getValue("content").jsonArray.single().jsonObject
                    .getValue("type").jsonPrimitive.content)
            }
            assertEquals(
                (0..4).map { "parallel-$it" },
                harness.store.functionAnnotations().getOrThrow().map { it.selector.qualifiedName },
            )
            assertEquals(JsonNull, client.request(107, "shutdown")["result"])
        }
    }

    @Test
    fun deferredMaterializationDoesNotCreateKnowledgeOnStartup() {
        val root = temporaryRoot()
        val clock = TestClock(now)
        val spec = RuleSpec("demo:lookup", Intents.LOOKUP)
        val runtime = RuntimeFactory.create(
            RuntimeConfig(
                rootPath = root.toString(),
                initializeLanguages = setOf("fake"),
                detectLanguages = false,
                materializeDefaultsOnOpen = false,
            ),
            RuntimeComponents(
                packSourceFactory = {
                    FakeRulePackSource(
                        packManifests = listOf(PackManifest("demo", "Demo", "1", listOf("fake"))),
                        playbooks = mapOf("demo" to Playbook("demo", listOf(spec))),
                        defaultsByLanguage = mapOf(
                            "fake" to listOf(
                                dev.cint.contracts.knowledge.PackDefault(
                                    selector = dev.cint.contracts.knowledge.FunctionSelector(
                                        qualifiedName = "seed",
                                    ),
                                    intents = setOf(Intents.LOGGING),
                                    packId = "demo",
                                    packVersion = "1",
                                    createdAt = clock.instant,
                                ),
                            ),
                        ),
                    )
                },
                ruleEvaluators =
                    FakeRuleEvaluatorRegistry(mapOf(spec.id to FakeRuleEvaluator(spec))),
            ),
        ).getOrThrow()

        runtime.shutdown().getOrThrow()
        assertFalse(Files.exists(root.resolve(".cint/knowledge.json")))
    }

    private fun harness(
        root: Path,
        clock: TestClock,
        pipeline: IntentPipeline? = null,
    ): Harness {
        val knowledgeFile = root.resolve(".cint/knowledge.json")
        val repository =
            FileKnowledgeRepository.open(
                KnowledgeRepositoryLocation(path = knowledgeFile.toString()),
            ).getOrThrow()
        val loaded = repository.load().getOrThrow()
        val memory =
            if (loaded == null) {
                InMemoryKnowledgeStore.empty { clock.instant }
            } else {
                InMemoryKnowledgeStore.fromSnapshot(
                    loaded,
                    exists = loaded.materialized,
                    now = { clock.instant },
                ).getOrThrow()
            }
        val store = PersistentKnowledgeStores.persistent(repository, memory)
        val composition =
            RuntimeLanguageComposition { _, _ ->
                ResultFactory.success(
                    FakeLanguageSupport(fakeDescriptor(id = "kotlin", extensions = listOf("kt"))),
                )
            }
        val runtime =
            RuntimeFactory.create(
                RuntimeConfig(
                    rootPath = root.toString(),
                    materializeDefaultsOnOpen = false,
                ),
                RuntimeComponents(
                    languageComposition = composition,
                    index = FakeIndex(mutableMapOf(SAMPLE_PATH to "fun findUser() {}"), FakeClock()),
                    pipeline = pipeline,
                    knowledgeStore = store,
                ),
            ).getOrThrow()
        return Harness(root, knowledgeFile, clock, runtime, store)
    }

    private fun annotationArguments(
        name: String,
        intent: String,
        timestamp: Instant,
    ): JsonObject =
        buildJsonObject {
            put(
                "annotation",
                buildJsonObject {
                    put("selector", buildJsonObject { put("qualifiedName", name) })
                    put("intents", buildJsonArray { add(JsonPrimitive(intent)) })
                    put("createdAt", JsonPrimitive(timestamp.toString()))
                    put("updatedAt", JsonPrimitive(timestamp.toString()))
                },
            )
        }

    private fun patternArguments(
        pattern: String,
        path: String,
    ): JsonObject =
        buildJsonObject {
            put(
                "hypothesis",
                buildJsonObject {
                    put(
                        "selector",
                        buildJsonObject {
                            put("pattern", pattern)
                            put("path", path)
                        },
                    )
                    put("proposedIntents", buildJsonArray { add(JsonPrimitive(Intents.LOGGING)) })
                },
            )
        }

    private fun spanSelector(): JsonObject =
        buildJsonObject {
            put("path", SAMPLE_PATH)
            put(
                "span",
                buildJsonObject {
                    put("startLine", 1)
                    put("startCol", 0)
                    put("endLine", 1)
                    put("endCol", 5)
                },
            )
            put("contentHash", "hash-1")
        }

    private fun temporaryRoot(): Path {
        val root = Files.createTempDirectory("cint-mcp-082")
        temporaryRoots.add(root)
        return root
    }

    private class TestClock(
        var instant: Instant,
    )

    private class Harness(
        val root: Path,
        val knowledgeFile: Path,
        val clock: TestClock,
        val runtime: DefaultRuntime,
        val store: dev.cint.contracts.ports.KnowledgeStore,
    ) {
        private val toServer = PipedOutputStream()
        private val fromServer = PipedOutputStream()
        val server =
            McpServer(
                runtime,
                input = PipedInputStream(toServer, BUFFER_SIZE),
                output = fromServer,
                now = { clock.instant },
            )

        fun client(): ScriptedClient = ScriptedClient(toServer, fromServer, server)
    }

    private class ScriptedClient(
        private val toServer: PipedOutputStream,
        fromServer: PipedOutputStream,
        private val server: McpServer,
    ) : AutoCloseable {
        private val writer = BufferedWriter(OutputStreamWriter(toServer, Charsets.UTF_8))
        private val reader =
            BufferedReader(
                InputStreamReader(PipedInputStream(fromServer, BUFFER_SIZE), Charsets.UTF_8),
            )
        private val worker = thread(start = true, isDaemon = true) { server.run() }

        fun initialize() {
            request(1, "initialize")
            notify("notifications/initialized", JsonObject(emptyMap()))
        }

        fun toolNames(): List<String> =
            request(2, "tools/list").getValue("result").jsonObject
                .getValue("tools").jsonArray
                .map { it.jsonObject.getValue("name").jsonPrimitive.content }

        fun tool(
            id: Int,
            name: String,
            arguments: JsonObject = JsonObject(emptyMap()),
        ): JsonObject =
            request(
                id,
                "tools/call",
                buildJsonObject {
                    put("name", name)
                    put("arguments", arguments)
                },
            ).getValue("result").jsonObject

        fun structured(result: JsonObject): JsonObject =
            result.getValue("structuredContent").jsonObject

        fun toolContentType(result: JsonObject): String =
            result.getValue("content").jsonArray.single().jsonObject
                .getValue("type").jsonPrimitive.content

        fun request(
            id: Int,
            method: String,
            params: JsonObject = JsonObject(emptyMap()),
        ): JsonObject {
            send(id, method, params)
            return read()
        }

        fun send(
            id: Int,
            method: String,
            params: JsonObject = JsonObject(emptyMap()),
        ) {
            val message =
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("method", method)
                    put("params", params)
                }
            writer.write(message.toString())
            writer.newLine()
            writer.flush()
        }

        fun notify(
            method: String,
            params: JsonObject,
        ) {
            val message =
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("method", method)
                    put("params", params)
                }
            writer.write(message.toString())
            writer.newLine()
            writer.flush()
        }

        fun read(): JsonObject = Json.parseToJsonElement(reader.readLine()).jsonObject

        override fun close() {
            runCatching { writer.close() }
            worker.join(3_000)
        }
    }

    private class BlockingPipeline : IntentPipeline {
        override fun analyze(
            request: AnalysisRequest,
            source: IndexedSource,
            knowledge: KnowledgeSnapshot,
            cancellation: CancellationToken,
        ): ContractResult<AnalysisResult> {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!cancellation.isCancelled && System.nanoTime() < deadline) {
                Thread.sleep(5)
            }
            if (cancellation.isCancelled) {
                return ResultFactory.failure(ContractError.Cancelled("analysis"))
            }
            return ResultFactory.success(
                AnalysisResult(
                    annotations = emptyList(),
                    candidates = emptyList(),
                    facts = FactBatch(syntaxTree = null),
                    sourceEpoch = 1,
                    completeness = Completeness.COMPLETE,
                    errors = listOf(AnalysisError("timeout", "blocking pipeline timed out")),
                ),
            )
        }
    }

    private companion object {
        const val SAMPLE_PATH = "Sample.kt"
        const val BUFFER_SIZE = 1 shl 16
        val REQUIRED_TOOLS =
            listOf(
                "analysis.run",
                "knowledge.snapshot",
                "function_annotations.list",
                "function_annotations.set",
                "function_annotations.remove",
                "span_overrides.list",
                "span_overrides.set",
                "span_overrides.remove",
                "patterns.list",
                "patterns.propose",
                "patterns.validate",
                "changes.propose",
                "changes.apply",
                "repo.status",
                "repo.index",
            )
    }
}
