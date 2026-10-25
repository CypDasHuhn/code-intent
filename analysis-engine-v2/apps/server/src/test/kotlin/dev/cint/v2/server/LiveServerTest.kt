package dev.cint.v2.server

import dev.cint.contracts.data.AnalysisResult
import dev.cint.contracts.data.Annotation
import dev.cint.contracts.data.Candidate
import dev.cint.contracts.data.CandidateReason
import dev.cint.contracts.data.Completeness
import dev.cint.contracts.data.Confidence
import dev.cint.contracts.data.Contribution
import dev.cint.contracts.data.Intents
import dev.cint.contracts.data.Role
import dev.cint.contracts.data.Span
import dev.cint.contracts.facts.FactBatch
import dev.cint.contracts.facts.SourceInput
import dev.cint.contracts.knowledge.KnowledgeChange
import dev.cint.contracts.ports.AnalysisRequest
import dev.cint.contracts.ports.KnowledgeStore
import dev.cint.contracts.ports.PackManifest
import dev.cint.contracts.ports.Playbook
import dev.cint.contracts.ports.RuleSpec
import dev.cint.contracts.ports.Runtime
import dev.cint.contracts.ports.RuntimePack
import dev.cint.contracts.ports.RuntimeStatus
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContentHash
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory
import dev.cint.ipc.AnalyzeResult
import dev.cint.ipc.MetricsResult
import dev.cint.ipc.ClientInfo
import dev.cint.ipc.DocumentParams
import dev.cint.ipc.InitializeParams
import dev.cint.ipc.InitializeResult
import dev.cint.ipc.IpcJson
import dev.cint.ipc.IpcMethods
import dev.cint.ipc.JsonRpcId
import dev.cint.ipc.JsonRpcMessage
import dev.cint.ipc.NdjsonCodec
import dev.cint.ipc.ProtocolFingerprint
import dev.cint.ipc.StatusResult
import dev.cint.runtime.RuntimeComponents
import dev.cint.runtime.RuntimeConfig
import dev.cint.runtime.RuntimeFactory
import dev.cint.runtime.RuntimeLanguageComposition
import dev.cint.v2.index.InMemoryIndex
import dev.cint.v2.testing.fakes.FakeKnowledgeStore
import dev.cint.v2.testing.fakes.FakeLanguageSupport
import dev.cint.v2.testing.fakes.fakeDescriptor
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.BufferedWriter
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveServerTest {
    private val codec = NdjsonCodec()
    private val running = mutableListOf<RunningServer>()
    private val clients = mutableListOf<TestClient>()

    @AfterTest
    fun cleanUp() {
        clients.forEach { runCatching { it.close() } }
        running.forEach { runCatching { it.close() } }
    }

    @Test
    fun scriptedClientAnalyzesAndPublishesOverUnixSocket() {
        val server = start()
        val client = client(server)
        val initialized = client.initialize()
        assertEquals(5, initialized.protocol)
        assertEquals("cint-server-v2", initialized.server.name)
        assertNotNull(initialized.fingerprint)

        client.didOpen("file:///test/Sample.kt", "fun sample() {}", 1)
        client.request(2, IpcMethods.ANALYZE, IpcJson.encode(dev.cint.ipc.AnalyzeParams(uri = URI)).jsonObject)

        val tags = client.awaitNotification(IpcMethods.PUBLISH_TAGS)
        val tagParams = tags.params
        assertEquals("1", tagParams.getValue("version").toString())
        assertEquals("1", tagParams.getValue("analysisVersion").toString())
        assertTrue(tagParams.containsKey("annotations"))
        assertTrue(tagParams.containsKey("configRevision"))

        val candidates = client.awaitNotification(IpcMethods.PUBLISH_CANDIDATES)
        assertTrue(candidates.params.containsKey("proposals"))
        assertTrue(candidates.params.containsKey("proposalRevision"))
        val proposal = candidates.params.getValue("proposals").jsonArray.single().jsonObject
        assertEquals(
            "fake-prompt",
            proposal.getValue("origin").jsonObject.getValue("promptVersion").jsonPrimitive.content,
        )
        assertEquals("annotation", tags.params.getValue("annotations").jsonArray.single().jsonObject.getValue("source").jsonPrimitive.content)

        val response = client.awaitResponse(2)
        val result = IpcJson.json.decodeFromJsonElement(AnalyzeResult.serializer(), response.result)
        assertEquals(1, result.files.size)
        assertEquals("file:///test/Sample.kt", result.files.single().path)

        assertEquals(true, client.shutdown())
        server.awaitStopped()
        assertFalse(Files.exists(server.socketPath))
    }

    @Test
    fun staleVersionsAreIgnoredAndSupersededAnalysisIsCancelled() {
        val runtime = TestRuntime()
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        runtime.onCancelled = { cancelled.countDown() }
        runtime.onAnalyze = { source, cancellation ->
            if ((source as SourceInput.InMemory).version == 1L) {
                started.countDown()
                while (!cancellation.isCancelled) {
                    Thread.sleep(2)
                }
            }
        }
        val server = start(runtime)
        val client = client(server)
        client.initialize()
        client.didOpen("file:///test/Stale.kt", "version one", 1)
        assertTrue(started.await(5, TimeUnit.SECONDS))

        client.didChange("file:///test/Stale.kt", "version two", 2)
        val tags = client.awaitNotification(IpcMethods.PUBLISH_TAGS)
        assertEquals("2", tags.params.getValue("version").toString())
        client.awaitNotification(IpcMethods.PUBLISH_CANDIDATES)
        assertTrue(cancelled.await(5, TimeUnit.SECONDS))

        client.didChange("file:///test/Stale.kt", "stale version", 1)
        client.status()
        assertTrue(runtime.sources.none { it is SourceInput.InMemory && it.version == 1L && it.text == "stale version" })
        assertNull(client.next(100))
        assertTrue(runtime.cancellations.get() >= 1)
    }

    @Test
    fun didCloseCancelsInflightWorkAndSuppressesPublish() {
        val runtime = TestRuntime()
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        runtime.onCancelled = { cancelled.countDown() }
        runtime.onAnalyze = { _, cancellation ->
            started.countDown()
            while (!cancellation.isCancelled) {
                Thread.sleep(2)
            }
        }
        val server = start(runtime)
        val client = client(server)
        client.initialize()
        client.didOpen("file:///test/Close.kt", "class Close", 1)
        assertTrue(started.await(5, TimeUnit.SECONDS))
        client.didClose("file:///test/Close.kt")
        assertTrue(cancelled.await(5, TimeUnit.SECONDS))
        assertNull(client.next(100))
        assertTrue(runtime.published.isEmpty())
    }

    @Test
    fun closeAndReopenSameDocumentStartsAFreshRevision() {
        val server = start()
        val client = client(server)
        client.initialize()
        client.didOpen("file:///test/Reopen.kt", "class Reopen", 1)
        assertEquals("1", client.awaitNotification(IpcMethods.PUBLISH_TAGS).params.getValue("analysisVersion").toString())
        client.didClose("file:///test/Reopen.kt")
        client.status()
        client.didOpen("file:///test/Reopen.kt", "class ReopenTwo", 2)
        val tags = client.awaitNotification(IpcMethods.PUBLISH_TAGS)
        assertEquals("2", tags.params.getValue("version").toString())
        assertEquals("2", tags.params.getValue("analysisVersion").toString())
    }

    @Test
    fun sameUriAcrossSessionsPublishesBothSessions() {
        val server = start()
        val first = client(server)
        first.initialize()
        val second = client(server)
        second.initialize()
        first.didOpen("file:///test/Shared.kt", "class One", 1)
        second.didOpen("file:///test/Shared.kt", "class Two", 1)
        assertEquals("1", first.awaitNotification(IpcMethods.PUBLISH_TAGS).params.getValue("version").toString())
        assertEquals("1", second.awaitNotification(IpcMethods.PUBLISH_TAGS).params.getValue("version").toString())
        val observer = client(server)
        observer.initialize()
        assertEquals(2, awaitActiveDocuments(observer, 2))
    }

    @Test
    fun disconnectCancelsInflightWorkAndIsSafe() {
        val runtime = TestRuntime()
        val started = CountDownLatch(1)
        runtime.onAnalyze = { _, cancellation ->
            started.countDown()
            try {
                while (!cancellation.isCancelled) {
                    Thread.sleep(2)
                }
            } catch (_: InterruptedException) {
            }
        }
        val server = start(runtime)
        val client = client(server)
        client.initialize()
        client.didOpen("file:///test/Disconnect.kt", "class Disconnect", 1)
        assertTrue(started.await(5, TimeUnit.SECONDS))
        client.close()
        clients.remove(client)

        val observer = client(server)
        observer.initialize()
        assertEquals(0, awaitActiveDocuments(observer, 0))
        assertTrue(runtime.cancellations.get() >= 1)
    }

    @Test
    fun multipleSessionsTrackDocumentsIndependently() {
        val server = start()
        val first = client(server)
        first.initialize()
        first.didOpen("file:///test/One.kt", "class One", 1)
        first.awaitNotification(IpcMethods.PUBLISH_TAGS)

        val second = client(server)
        second.initialize()
        second.didOpen("file:///test/Two.kt", "class Two", 1)
        second.awaitNotification(IpcMethods.PUBLISH_TAGS)

        val observer = client(server)
        observer.initialize()
        assertEquals(2, awaitActiveDocuments(observer, 2))

        first.didClose("file:///test/One.kt")
        second.didClose("file:///test/Two.kt")
        assertEquals(0, awaitActiveDocuments(observer, 0))
    }

    private fun awaitActiveDocuments(
        client: TestClient,
        expected: Int,
    ): Int {
        val deadline = System.currentTimeMillis() + 5_000
        var active = client.status().activeDocuments
        while (active != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            active = client.status().activeDocuments
        }
        return active
    }

    @Test
    fun malformedInputAndUnsupportedHandlersFailExplicitly() {
        val server = start()
        val client = client(server)
        client.initialize()

        client.sendRaw("not json\n")
        val parseError = assertIs<JsonRpcMessage.ErrorResponse>(client.next())
        assertEquals(-32700, parseError.error.code)

        client.request(2, IpcMethods.SUGGEST, IpcJson.encode(dev.cint.ipc.SuggestParams(uri = URI)).jsonObject)
        val suggestError = assertIs<JsonRpcMessage.ErrorResponse>(client.next())
        assertEquals(-32000, suggestError.error.code)
        assertTrue(suggestError.error.data.toString().contains("unsupported-operation"))

        client.request(3, IpcMethods.CONFIG_READ, IpcJson.encode(dev.cint.ipc.ConfigParams()).jsonObject)
        val configError = assertIs<JsonRpcMessage.ErrorResponse>(client.next())
        assertTrue(configError.error.data.toString().contains("unsupported-operation"))

        client.request(4, IpcMethods.RESTRICTIONS, IpcJson.encode(dev.cint.ipc.RestrictionsListParams()).jsonObject)
        val restrictionsError = assertIs<JsonRpcMessage.ErrorResponse>(client.next())
        assertTrue(restrictionsError.error.data.toString().contains("unsupported-operation"))

        client.request(
            5,
            IpcMethods.ANALYZE,
            buildJsonObject {
                put("uri", URI)
                put("unexpected", true)
            },
        )
        val invalidParams = assertIs<JsonRpcMessage.ErrorResponse>(client.next())
        assertEquals(-32602, invalidParams.error.code)
        assertTrue(invalidParams.error.data.toString().contains("invalid-params"))
    }

    @Test
    fun legacyProtocolGetsV1PublishShapesAndAnalyzeResult() {
        val server = start()
        val client = client(server)
        val initialized = client.initialize(protocol = 4)
        assertEquals(4, initialized.protocol)
        val config = assertNotNull(initialized.config)
        val packs = config.getValue("packs").jsonArray
        assertEquals("logging", packs.single().jsonObject.getValue("packId").jsonPrimitive.content)
        assertEquals("logging:logging", packs.single().jsonObject.getValue("rules").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
        val suggestions = assertNotNull(initialized.suggestions)
        assertEquals(15, suggestions.getValue("knownIntents").jsonArray.size)
        assertIs<JsonNull>(suggestions.getValue("provider"))
        assertIs<JsonNull>(suggestions.getValue("model"))
        assertEquals("manual", suggestions.getValue("trigger").jsonPrimitive.content)
        assertTrue(suggestions.getValue("restrictions").jsonPrimitive.boolean)
        assertTrue(suggestions.getValue("cache").jsonPrimitive.boolean)
        assertEquals(24, suggestions.getValue("maxUnitsPerRequest").jsonPrimitive.int)

        client.didOpen("file:///test/Legacy.kt", "class Legacy", 1)
        val tags = client.awaitNotification(IpcMethods.PUBLISH_TAGS)
        assertTrue(tags.params.containsKey("revision"))
        assertTrue(tags.params.containsKey("analysisRevision"))
        assertFalse(tags.params.containsKey("configRevision"))
        assertFalse(tags.params.containsKey("analysisVersion"))
        assertFalse(tags.params.containsKey("diagnostics"))
        val annotation = tags.params.getValue("annotations").jsonArray.single().jsonObject
        assertEquals("annotation", annotation.getValue("source").jsonPrimitive.content)
        assertEquals("treesitter", annotation.getValue("provenance").jsonArray.single().jsonObject.getValue("source").jsonPrimitive.content)
        assertTrue(annotation.containsKey("rule") || annotation.getValue("provenance").jsonArray.single().jsonObject.containsKey("rule"))
        assertFalse(annotation.getValue("provenance").jsonArray.single().jsonObject.containsKey("ruleId"))

        val candidates = client.awaitNotification(IpcMethods.PUBLISH_CANDIDATES)
        assertTrue(candidates.params.containsKey("groups"))
        assertTrue(candidates.params.containsKey("candidates"))
        assertTrue(candidates.params.containsKey("suggestionRevision"))
        assertFalse(candidates.params.containsKey("proposals"))
        val candidate = candidates.params.getValue("candidates").jsonArray.single().jsonObject
        assertEquals("rule:test:rule", candidate.getValue("source").jsonPrimitive.content)
        assertEquals("because", candidate.getValue("rationale").jsonPrimitive.content)
        assertEquals("fake", candidate.getValue("provider").jsonPrimitive.content)
        assertEquals("fake-model", candidate.getValue("model").jsonPrimitive.content)
        assertEquals("fake-prompt", candidate.getValue("promptVersion").jsonPrimitive.content)

        client.request(
            2,
            IpcMethods.ANALYZE,
            IpcJson.encode(dev.cint.ipc.AnalyzeParams(uri = "file:///test/Legacy.kt")).jsonObject,
        )
        val republishedTags = client.awaitNotification(IpcMethods.PUBLISH_TAGS)
        assertEquals("0", republishedTags.params.getValue("revision").toString())
        assertEquals("2", republishedTags.params.getValue("analysisRevision").toString())
        client.awaitNotification(IpcMethods.PUBLISH_CANDIDATES)
        val response = client.awaitResponse(2)
        assertEquals("true", response.result.toString())
    }

    @Test
    fun analysisFailuresUseStructuredStableErrors() {
        val runtime = TestRuntime()
        runtime.failure = ContractError.NotFound("source", "missing.kt")
        val server = start(runtime)
        val file = server.directory.resolve("root/Missing.kt")
        Files.writeString(file, "class Missing")
        val client = client(server)
        client.initialize()
        client.request(
            2,
            IpcMethods.ANALYZE,
            IpcJson.encode(dev.cint.ipc.AnalyzeParams(uri = file.toUri().toString())).jsonObject,
        )
        val error = assertIs<JsonRpcMessage.ErrorResponse>(client.next())
        assertEquals(-32000, error.error.code)
        assertEquals("analysis failed: not-found", error.error.message)
        val data = assertNotNull(error.error.data).jsonObject
        assertEquals("not-found", data.getValue("kind").jsonPrimitive.content)
        assertEquals("source", data.getValue("details").jsonObject.getValue("resource").jsonPrimitive.content)
        assertEquals("missing.kt", data.getValue("details").jsonObject.getValue("identifier").jsonPrimitive.content)
    }

    @Test
    fun nonOpenAnalyzeUsesRealContentHashAndKeepsRevisionsMonotonicPerUri() {
        val server = start()
        val file = server.directory.resolve("root/NonOpen.kt")
        val text = "class NonOpen"
        Files.writeString(file, text)
        val uri = file.toUri().toString()
        val hash = ContentHash.compute(text).value
        val client = client(server)
        client.initialize()
        client.request(2, IpcMethods.ANALYZE, IpcJson.encode(dev.cint.ipc.AnalyzeParams(uri = uri)).jsonObject)
        val first = client.awaitNotification(IpcMethods.PUBLISH_TAGS)
        assertEquals(hash, first.params.getValue("hash").jsonPrimitive.content)
        assertEquals("1", first.params.getValue("analysisVersion").toString())
        client.awaitNotification(IpcMethods.PUBLISH_CANDIDATES)
        client.awaitResponse(2)

        client.request(3, IpcMethods.ANALYZE, IpcJson.encode(dev.cint.ipc.AnalyzeParams(uri = uri)).jsonObject)
        val second = client.awaitNotification(IpcMethods.PUBLISH_TAGS)
        assertEquals("2", second.params.getValue("analysisVersion").toString())
        client.awaitNotification(IpcMethods.PUBLISH_CANDIDATES)
        client.awaitResponse(3)

        client.didOpen(uri, text, 1)
        val opened = client.awaitNotification(IpcMethods.PUBLISH_TAGS)
        assertEquals("3", opened.params.getValue("analysisVersion").toString())
    }

    @Test
    fun nonOpenAnalyzeTimeoutIsBounded() {
        val runtime = TestRuntime()
        runtime.onAnalyze = { _, _ -> Thread.sleep(2_000) }
        val server = start(runtime, analysisTimeoutMs = 200, closeGraceMs = 200)
        val file = server.directory.resolve("root/Slow.kt")
        Files.writeString(file, "class Slow")
        val client = client(server)
        client.initialize()
        val began = System.currentTimeMillis()
        client.request(
            2,
            IpcMethods.ANALYZE,
            IpcJson.encode(dev.cint.ipc.AnalyzeParams(uri = file.toUri().toString())).jsonObject,
        )
        val error = assertIs<JsonRpcMessage.ErrorResponse>(client.next(5_000))
        assertEquals("timeout", assertNotNull(error.error.data).jsonObject.getValue("kind").jsonPrimitive.content)
        assertTrue(System.currentTimeMillis() - began < 1_500)
    }

    @Test
    fun nonOpenAnalyzeFailsExplicitlyWhenTheTargetCannotBeRead() {
        val server = start()
        val client = client(server)
        client.initialize()
        client.request(
            2,
            IpcMethods.ANALYZE,
            IpcJson.encode(dev.cint.ipc.AnalyzeParams(uri = "file:///test/does-not-exist.kt")).jsonObject,
        )
        val error = assertIs<JsonRpcMessage.ErrorResponse>(client.next())
        assertEquals(-32000, error.error.code)
        assertEquals("not-found", assertNotNull(error.error.data).jsonObject.getValue("kind").jsonPrimitive.content)
    }

    @Test
    fun legacyOmitsAbsentCandidateMetadata() {
        val runtime = TestRuntime()
        runtime.candidateRationale = null
        runtime.candidateProvider = null
        runtime.candidateModel = null
        runtime.candidatePromptVersion = null
        val server = start(runtime)
        val client = client(server)
        client.initialize(protocol = 4)
        client.didOpen("file:///test/Omitted.kt", "class Omitted", 1)
        client.awaitNotification(IpcMethods.PUBLISH_TAGS)
        val candidates = client.awaitNotification(IpcMethods.PUBLISH_CANDIDATES)
        val candidate = candidates.params.getValue("candidates").jsonArray.single().jsonObject
        assertEquals(
            setOf(
                "candidateId", "path", "contentHash", "intents", "source", "confidence",
                "startLine", "startCol", "endLine", "endCol", "startByte", "endByte",
                "reason", "aiEligible",
            ),
            candidate.keys,
        )
    }

    @Test
    fun documentMutationsWaitForThePerUriPublishLock() {
        val store = DocumentStore("kotlin")
        val uri = "file:///test/Ordering.kt"
        val first = assertNotNull(store.open(DocumentParams(uri, "one", 1, "kotlin")))
        val publishEntered = CountDownLatch(1)
        val releasePublish = CountDownLatch(1)
        val publisher =
            Thread {
                store.publishIfCurrent(uri, first.generation) { revision ->
                    assertTrue(revision > 0)
                    publishEntered.countDown()
                    releasePublish.await(5, TimeUnit.SECONDS)
                }
            }.apply { start() }
        assertTrue(publishEntered.await(5, TimeUnit.SECONDS))

        val changeCompleted = CountDownLatch(1)
        val changer =
            Thread {
                store.change(DocumentParams(uri, "two", 2, "kotlin"))
                changeCompleted.countDown()
            }.apply { start() }
        assertFalse(changeCompleted.await(200, TimeUnit.MILLISECONDS))
        releasePublish.countDown()
        assertTrue(changeCompleted.await(5, TimeUnit.SECONDS))
        publisher.join(5_000)
        assertFalse(publisher.isAlive)

        val current = assertNotNull(store.current(uri))
        val closeEntered = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val closerPublisher =
            Thread {
                store.publishIfCurrent(uri, current.generation) {
                    closeEntered.countDown()
                    releaseClose.await(5, TimeUnit.SECONDS)
                }
            }.apply { start() }
        assertTrue(closeEntered.await(5, TimeUnit.SECONDS))
        val closed = CountDownLatch(1)
        val closer =
            Thread {
                store.close(uri)
                closed.countDown()
            }.apply { start() }
        assertFalse(closed.await(200, TimeUnit.MILLISECONDS))
        releaseClose.countDown()
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        closerPublisher.join(5_000)
        assertFalse(closerPublisher.isAlive)
        assertNull(store.current(uri))
    }

    @Test
    fun runtimeShutdownFailureIsExposedAfterSocketCleanup() {
        val runtime = TestRuntime()
        runtime.shutdownError = ContractError.PreconditionFailed("save failed")
        val server = start(runtime)
        val client = client(server)
        client.initialize()
        assertEquals(true, client.shutdown())
        server.awaitStopped()
        assertFalse(Files.exists(server.socketPath))
        assertNotNull(server.server.runtimeShutdownFailure())
    }

    @Test
    fun shutdownStopsTheServer() {
        val server = start()
        val client = client(server)
        client.initialize()
        assertEquals(true, client.shutdown())
        server.awaitStopped()
        assertFalse(Files.exists(server.socketPath))
    }

    @Test
    fun realRuntimeStartsAndServesStatusAndMetrics() {
        val runtimeRoot = Files.createTempDirectory("cint-v2-real-runtime")
        try {
            val runtime = assertNotNull(createRuntime(runtimeRoot))
            val server = start(runtime)
            val client = client(server)
            client.initialize()
            assertEquals(0, client.status().activeDocuments)
            val metrics = client.metrics()
            assertTrue(metrics.text.contains("cint_active_documents 0"))
            assertTrue(metrics.text.contains("cint_indexed_files"))
            assertEquals(true, client.shutdown())
            server.awaitStopped()
        } finally {
            runtimeRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun realRuntimeAnalyzesThroughSocketWithSharedRegistry() {
        val runtime =
            RuntimeFactory.create(
                RuntimeConfig(),
                RuntimeComponents(
                    languageComposition =
                        RuntimeLanguageComposition { _, _ ->
                            ResultFactory.success(FakeLanguageSupport(fakeDescriptor()))
                        },
                    index = InMemoryIndex(RuntimeConfig.defaultIndexPlan(), semanticTimeoutMs = 1_000),
                    knowledgeStore = FakeKnowledgeStore(),
                ),
            ).getOrThrow()
        val server = start(runtime)
        val client = client(server)
        client.initialize()
        client.didOpen("file:///test/Real.kt", "fun work() { call println }", 1)
        val tags = client.awaitNotification(IpcMethods.PUBLISH_TAGS)
        val annotations = tags.params.getValue("annotations").jsonArray
        assertTrue(annotations.isNotEmpty())
        assertTrue(
            annotations.any { annotation ->
                annotation.jsonObject.getValue("intents").jsonArray.any { it.jsonPrimitive.content == Intents.LOGGING }
            },
        )
    }

    @Test
    fun refusesNonSocketPathsAndLiveSockets() {
        val directory = Files.createTempDirectory("cint-v2-socket-test")
        val root = Files.createDirectories(directory.resolve("root"))
        try {
            val regular = Files.writeString(directory.resolve("regular.txt"), "not a socket")
            assertFailsWith<IllegalStateException> {
                LiveServer(TestRuntime(), ServerOptions(socketPath = regular, root = root)).run()
            }

            val running = start()
            assertFailsWith<IllegalStateException> {
                LiveServer(TestRuntime(), ServerOptions(socketPath = running.socketPath, root = root)).run()
            }

            val shared = Files.createTempDirectory("cint-v2-shared")
            Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rwxr-xr-x"))
            val before = PosixFilePermissions.toString(Files.getPosixFilePermissions(shared))
            val ownSocket = shared.resolve("own.sock")
            val own = LiveServer(TestRuntime(), ServerOptions(socketPath = ownSocket, root = root))
            val thread = Thread { own.run() }.apply {
                isDaemon = true
                start()
            }
            own.awaitStarted()
            assertEquals(before, PosixFilePermissions.toString(Files.getPosixFilePermissions(shared)))
            own.requestShutdown()
            own.awaitStopped()
            thread.join(5_000)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun shutdownDoesNotWaitForInterruptionIgnoringAnalysis() {
        val runtime = TestRuntime()
        val started = CountDownLatch(1)
        runtime.onAnalyze = { _, _ ->
            started.countDown()
            try {
                Thread.sleep(2_000)
            } catch (_: InterruptedException) {
                runCatching { Thread.sleep(1_000) }
            }
        }
        val server = start(runtime, analysisTimeoutMs = 200, closeGraceMs = 200)
        val client = client(server)
        client.initialize()
        client.didOpen("file:///test/Slow.kt", "class Slow", 1)
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val began = System.currentTimeMillis()
        assertEquals(true, client.shutdown())
        server.awaitStopped()
        assertTrue(System.currentTimeMillis() - began < 3_000)
    }

    private fun start(
        runtime: Runtime = TestRuntime(),
        analysisTimeoutMs: Long = 30_000,
        closeGraceMs: Long = 5_000,
    ): RunningServer {
        val directory = Files.createTempDirectory("cint-v2-server-test")
        val root = Files.createDirectories(directory.resolve("root"))
        val socket = directory.resolve("cint.sock")
        val server =
            LiveServer(
                runtime,
                ServerOptions(
                    socketPath = socket,
                    root = root,
                    analysisTimeoutMs = analysisTimeoutMs,
                    closeGraceMs = closeGraceMs,
                ),
            )
        val thread = Thread { server.run() }.apply {
            isDaemon = true
            name = "cint-v2-server-test"
            start()
        }
        server.awaitStarted()
        val runningServer = RunningServer(server, thread, directory, socket)
        running += runningServer
        return runningServer
    }

    private fun client(server: RunningServer): TestClient {
        val client = TestClient(server.socketPath)
        clients += client
        return client
    }

    private val URI = "file:///test/Sample.kt"

    private class RunningServer(
        val server: LiveServer,
        val thread: Thread,
        val directory: Path,
        val socketPath: Path,
    ) {
        fun awaitStopped() {
            server.awaitStopped()
            thread.join(5_000)
        }

        fun close() {
            server.requestShutdown()
            server.awaitStopped()
            thread.join(5_000)
            directory.toFile().deleteRecursively()
        }
    }

    private class TestClient(socketPath: Path) : AutoCloseable {
        private val codec = NdjsonCodec()
        private val channel =
            SocketChannel.open(StandardProtocolFamily.UNIX).apply {
                connect(UnixDomainSocketAddress.of(socketPath))
            }
        private val reader = BufferedReader(Channels.newReader(channel, StandardCharsets.UTF_8))
        private val writer = BufferedWriter(Channels.newWriter(channel, StandardCharsets.UTF_8))
        private val lines = LinkedBlockingQueue<String>()
        private var statusSequence = 50L
        private val readerThread =
            Thread {
                try {
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isNotBlank()) lines.put(line)
                    }
                } catch (_: Exception) {
                }
            }.apply {
                isDaemon = true
                start()
            }

        fun initialize(protocol: Int = 5): InitializeResult {
            val params =
                InitializeParams(
                    protocol = protocol,
                    fingerprint = if (protocol >= 5) ProtocolFingerprint.CURRENT else null,
                    client = ClientInfo("test", "1"),
                )
            request(1, IpcMethods.INITIALIZE, IpcJson.encode(params).jsonObject)
            val response = awaitResponse(1)
            return IpcJson.json.decodeFromJsonElement(InitializeResult.serializer(), response.result)
        }

        fun didOpen(
            uri: String,
            text: String,
            version: Int,
        ) {
            send(JsonRpcMessage.Notification(IpcMethods.DID_OPEN, IpcJson.encode(DocumentParams(uri, text, version, "kotlin")).jsonObject))
        }

        fun didChange(
            uri: String,
            text: String,
            version: Int,
        ) {
            send(JsonRpcMessage.Notification(IpcMethods.DID_CHANGE, IpcJson.encode(DocumentParams(uri, text, version, "kotlin")).jsonObject))
        }

        fun didClose(uri: String) {
            send(JsonRpcMessage.Notification(IpcMethods.DID_CLOSE, IpcJson.encode(dev.cint.ipc.DocumentCloseParams(uri)).jsonObject))
        }

        fun request(
            id: Long,
            method: String,
            params: kotlinx.serialization.json.JsonObject,
        ) {
            send(JsonRpcMessage.Request(JsonRpcId.NumberId(id), method, params))
        }

        fun sendRaw(line: String) {
            synchronized(writer) {
                writer.write(line)
                writer.flush()
            }
        }

        fun send(message: JsonRpcMessage) {
            sendRaw(codec.encodeLine(message))
        }

        fun next(timeoutMs: Long = 5_000): JsonRpcMessage? =
            lines.poll(timeoutMs, TimeUnit.MILLISECONDS)?.let(codec::decode)

        fun awaitResponse(id: Long): JsonRpcMessage.Response {
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                val message = next(deadline - System.currentTimeMillis())
                if (message is JsonRpcMessage.Response && message.id == JsonRpcId.NumberId(id)) return message
            }
            error("timed out waiting for response $id")
        }

        fun awaitNotification(method: String): JsonRpcMessage.Notification {
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                val message = next(deadline - System.currentTimeMillis())
                if (message is JsonRpcMessage.Notification && message.method == method) return message
            }
            error("timed out waiting for notification $method")
        }

        fun status(): StatusResult {
            val id = statusSequence
            statusSequence += 1
            request(id, IpcMethods.STATUS, IpcJson.encode(dev.cint.ipc.NoParams).jsonObject)
            val response = awaitResponse(id)
            return IpcJson.json.decodeFromJsonElement(StatusResult.serializer(), response.result)
        }

        fun metrics(): MetricsResult {
            request(70, IpcMethods.METRICS, IpcJson.encode(dev.cint.ipc.NoParams).jsonObject)
            val response = awaitResponse(70)
            return IpcJson.json.decodeFromJsonElement(MetricsResult.serializer(), response.result)
        }

        fun shutdown(): Boolean {
            request(60, IpcMethods.SHUTDOWN, IpcJson.encode(dev.cint.ipc.NoParams).jsonObject)
            val response = awaitResponse(60)
            return response.result.toString() == "true"
        }

        override fun close() {
            runCatching { readerThread.interrupt() }
            runCatching { channel.close() }
        }
    }

    private class TestRuntime : Runtime {
        val sources = mutableListOf<SourceInput>()
        val published = mutableListOf<AnalysisResult>()
        val cancellations = AtomicInteger(0)
        val shutdown = AtomicBoolean(false)

        @Volatile
        var onAnalyze: ((SourceInput, CancellationToken) -> Unit)? = null

        @Volatile
        var onCancelled: (() -> Unit)? = null

        @Volatile
        var failure: ContractError? = null

        @Volatile
        var candidateRationale: String? = "because"

        @Volatile
        var candidateProvider: String? = "fake"

        @Volatile
        var candidateModel: String? = "fake-model"

        @Volatile
        var candidatePromptVersion: String? = "fake-prompt"

        @Volatile
        var shutdownError: ContractError? = null

        override fun packs(cancellation: CancellationToken): ContractResult<List<RuntimePack>> =
            ResultFactory.success(
                listOf(
                    RuntimePack(
                        manifest =
                            PackManifest(
                                id = "logging",
                                name = "Logging",
                                version = "1",
                                languages = listOf("kotlin"),
                                providesIntents =
                                    listOf(
                                        Intents.BOOKKEEPING,
                                        Intents.DATA_MODIFY,
                                        Intents.DATA_TRANSFORMATION,
                                        Intents.ERROR_HANDLING,
                                        Intents.LOCAL_COPY,
                                        Intents.LOCAL_VALUE_MODIFY,
                                        Intents.LOGGING,
                                        Intents.LOOKUP,
                                        Intents.NORMALIZATION,
                                        Intents.RETURN_VALUE,
                                        Intents.SEARCH,
                                        Intents.SERVICE_DELEGATION,
                                        Intents.SERVICE_INIT,
                                        Intents.VALIDATION,
                                    ),
                            ),
                        playbook =
                            Playbook(
                                packId = "logging",
                                rules = listOf(RuleSpec("logging:logging", Intents.LOGGING)),
                            ),
                    ),
                ),
            )

        override fun analyze(
            request: AnalysisRequest,
            cancellation: CancellationToken,
        ): ContractResult<AnalysisResult> =
            analyzeSource(
                SourceInput.FilePath(request.targetPaths.first(), request.language),
                request,
                cancellation,
            )

        override fun analyzeSource(
            source: SourceInput,
            request: AnalysisRequest,
            cancellation: CancellationToken,
        ): ContractResult<AnalysisResult> {
            synchronized(sources) { sources += source }
            onAnalyze?.invoke(source, cancellation)
            if (cancellation.isCancelled) {
                cancellations.incrementAndGet()
                onCancelled?.invoke()
                return ResultFactory.failure(ContractError.Cancelled("analysis"))
            }
            failure?.let { return ResultFactory.failure(it) }
            val contribution =
                Contribution(
                    rule = "test:rule",
                    role = Role.SOURCE_INTENT,
                    intents = setOf(Intents.LOGGING),
                    span = Span(0, 0, 0, 1),
                    source = "rule",
                    confidence = Confidence.HIGH,
                    startByte = 0,
                    endByte = 1,
                )
            val analysis =
                AnalysisResult(
                    annotations =
                        listOf(
                            Annotation(
                                role = Role.SOURCE_INTENT,
                                intents = setOf(Intents.LOGGING),
                                span = Span(0, 0, 0, 1),
                                source = "knowledge",
                                confidence = Confidence.HIGH,
                                startByte = 0,
                                endByte = 1,
                                provenance = listOf(contribution),
                            ),
                        ),
                    candidates =
                        listOf(
                            Candidate(
                                intents = setOf(Intents.LOGGING),
                                span = Span(0, 0, 0, 1),
                                startByte = 0,
                                endByte = 1,
                                source = "rule:test:rule",
                                confidence = Confidence.LOW,
                                reason = CandidateReason.PACK_DOMAIN,
                                rationale = candidateRationale,
                                provider = candidateProvider,
                                model = candidateModel,
                                promptVersion = candidatePromptVersion,
                            ),
                        ),
                    facts = FactBatch(syntaxTree = null),
                    sourceEpoch = source.version,
                    completeness = Completeness.COMPLETE,
                    errors = emptyList(),
                )
            return ResultFactory.success(analysis)
        }

        override fun knowledge(): KnowledgeStore = error("knowledge is not used by the live server tests")

        override fun materializeDefaults(
            changes: KnowledgeChange.MaterializeDefaults,
            cancellation: CancellationToken,
        ): ContractResult<Unit> = ResultFactory.success(Unit)

        override fun reload(cancellation: CancellationToken): ContractResult<Unit> = ResultFactory.success(Unit)

        override fun publish(
            result: AnalysisResult,
            cancellation: CancellationToken,
        ): ContractResult<Unit> {
            if (cancellation.isCancelled) {
                return ResultFactory.failure(ContractError.Cancelled("publish"))
            }
            synchronized(published) { published += result }
            return ResultFactory.success(Unit)
        }

        override fun status(cancellation: CancellationToken): ContractResult<RuntimeStatus> =
            ResultFactory.success(
                RuntimeStatus(
                    isInitialized = !shutdown.get(),
                    indexedFiles = synchronized(sources) { sources.size },
                    knowledgeEntries = 0,
                ),
            )

        override fun shutdown(): ContractResult<Unit> {
            shutdown.set(true)
            shutdownError?.let { return ResultFactory.failure(it) }
            return ResultFactory.success(Unit)
        }
    }
}
