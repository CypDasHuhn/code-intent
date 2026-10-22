package dev.cint.server.suggest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.cint.api.CandidateEmit
import dev.cint.server.SuggestionSettings
import dev.cint.server.suggest.support.candidates
import dev.cint.server.suggest.support.gapSource
import dev.cint.server.suggest.support.harness
import dev.cint.suggest.SuggestionProviderRegistry
import dev.cint.suggest.SuggestionService
import dev.cint.suggest.model.CandidateContext
import dev.cint.suggest.provider.IntentSuggestionProvider
import dev.cint.suggest.session.AgentRequest
import dev.cint.suggest.session.AgentTurn
import dev.cint.suggest.session.AnnotationSessionProvider
import dev.cint.suggest.session.ToolCall
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveSuggestionDiscoveryTest {
    private val mapper = jacksonObjectMapper()
    private val source =
        "fun magicCommentIn(text: String): String? {\n" +
            "    val match = Regex(\"\"\"cint\\\\s*:\\\\s*([A-Za-z0-9_.-]+)\"\"\", RegexOption.IGNORE_CASE).find(text) ?: return null\n" +
            "    return match.groupValues[1]\n" +
            "}"

    @Test
    fun discoveryGradesUnitsWithoutPackCandidatesAndReportsProgressCoverageAndLog() {
        val root = Files.createTempDirectory("cint-live-discovery")
        try {
            val provider = DiscoveryProvider()
            val harness = harness(root, suggestionSettings = discoverySettings(), service = service(provider))
            harness.initialize()
            val descriptor = harness.initializeResult().path("suggestions")
            assertEquals(4, harness.initializeResult().path("protocol").asInt())
            assertTrue(descriptor.path("discovery").asBoolean())

            val uri = root.resolve("Facts.kt").toString()
            harness.open(uri, source, version = 1)
            harness.suggest(uri, version = 1, id = 2, requestId = "discovery")
            val response = harness.waitForResponse(2)

            assertTrue(response.path("accepted").asBoolean())
            assertTrue(response.path("total").asInt() > 0)
            assertEquals(response.path("total").asInt(), response.path("graded").asInt())
            assertTrue(response.path("additions").asInt() >= 1)
            assertTrue(response.path("toolCalls").asInt() >= 1)

            val discovered =
                candidates(harness.latest("cint/publishCandidates"), "SEARCH")
                    .firstOrNull { it["source"].asText() == "ai:discovery" }
                    ?: error("missing discovered SEARCH candidate: ${harness.latest("cint/publishCandidates")}")
            assertEquals("discovery", discovered["provider"].asText())
            assertEquals("discovery-v1", discovered["model"].asText())
            assertEquals("discovery-prompt-v1", discovered["promptVersion"].asText())

            val progress = harness.messages().filter { it["method"]?.asText() == "cint/suggest/progress" }
            assertTrue(progress.isNotEmpty())
            assertTrue(progress.any { it.path("params").path("total").asInt() > 0 })
            assertTrue(progress.any { it.path("params").path("graded").asInt() == response.path("graded").asInt() })

            harness.send("""{"jsonrpc":"2.0","id":9,"method":"cint/suggest/log","params":{"uri":${mapper.writeValueAsString(uri)}}}""")
            val log = harness.waitForResponse(9)
            assertEquals(response.path("total").asInt(), log.path("total").asInt())
            assertTrue(log.path("events").any { it.path("type").asText() == "tool" })
            assertTrue(log.path("events").any { it.path("type").asText() == "verdict" })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun protocolThreeKeepsLegacyEnrichmentAndGetsNoDiscoveryProgressOrLog() {
        val root = Files.createTempDirectory("cint-live-discovery-protocol3")
        try {
            val provider = LegacyProvider()
            val harness =
                harness(
                    root,
                    suggestionSettings = discoverySettings().copy(provider = provider.id, model = provider.model),
                    service = service(provider),
                )
            harness.initialize(protocol = 3)
            assertFalse(
                harness
                    .initializeResult()
                    .path("suggestions")
                    .path("discovery")
                    .asBoolean(),
            )

            val uri = root.resolve("Sample.kt").toString()
            harness.open(uri, gapSource, version = 1)
            harness.suggest(uri, version = 1, id = 2, requestId = "legacy")
            assertTrue(harness.waitForResponse(2).path("accepted").asBoolean())

            assertEquals(0, provider.sessionCalls.get())
            assertEquals(
                "ai:legacy",
                harness.waitForCandidate(uri, "VALIDATION") { it["source"].asText() == "ai:legacy" }["source"].asText(),
            )
            assertTrue(harness.messages().none { it["method"]?.asText() == "cint/suggest/progress" })

            harness.send("""{"jsonrpc":"2.0","id":9,"method":"cint/suggest/log","params":{"uri":${mapper.writeValueAsString(uri)}}}""")
            val error =
                harness
                    .messages()
                    .last { it["id"]?.asInt() == 9 }
                    .path("error")
                    .path("message")
                    .asText()
            assertTrue(error.contains("protocol 4"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun staleCachedAnalysisIsRejectedBeforeDiscoveryStarts() {
        val root = Files.createTempDirectory("cint-live-discovery-stale-cache")
        try {
            val provider = DiscoveryProvider()
            val harness = harness(root, suggestionSettings = discoverySettings(), service = service(provider))
            harness.initialize()
            val uri = root.resolve("Facts.kt").toString()
            harness.open(uri, source, version = 1)

            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            harness.server.analysisRevisionBarrierForTest = {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            val change = Thread { harness.change(uri, source, version = 2) }
            change.start()
            assertTrue(started.await(2, TimeUnit.SECONDS))

            val suggestMessage =
                """{"jsonrpc":"2.0","id":2,"method":"cint/suggest","params":{"uri":${mapper.writeValueAsString(
                    uri,
                )},"version":2,"requestId":"stale-cache"}}"""
            harness.sendDirect(suggestMessage)
            val response = harness.waitForResponseDirect(2)
            release.countDown()
            change.join(5_000)
            harness.server.analysisRevisionBarrierForTest = null

            assertTrue(response.path("stale").asBoolean(), "expected stale response, got $response")
            assertEquals(0, provider.calls.get())
            assertTrue(
                harness.messages().none {
                    it["method"]?.asText() == "cint/publishCandidates" &&
                        it.path("params").path("candidates").any { candidate -> candidate["source"].asText() == "ai:discovery" }
                },
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun editCancelsOldSessionAndDoesNotBlockTheNewVersion() {
        val root = Files.createTempDirectory("cint-live-discovery-edit")
        try {
            val provider = BlockingDiscoveryProvider()
            val harness = harness(root, suggestionSettings = discoverySettings(), service = service(provider))
            harness.initialize()
            val uri = root.resolve("Facts.kt").toString()
            harness.open(uri, source, version = 1)
            harness.suggest(uri, version = 1, id = 2, requestId = "before-edit")
            assertTrue(provider.started.await(2, TimeUnit.SECONDS))

            harness.change(uri, source + "\n// edited", version = 2)
            harness.suggest(uri, version = 2, id = 3, requestId = "after-edit")
            val fresh = harness.waitForResponse(3)
            val old = harness.waitForResponse(2)

            assertTrue(old.path("cancelled").asBoolean())
            assertTrue(fresh.path("accepted").asBoolean())
            assertFalse(fresh.path("busy").asBoolean())
            assertEquals(0, fresh.path("graded").asInt())
            assertTrue(fresh.path("uncovered").size() > 0)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun editCancelsAQueuedSuggestionWithoutDroppingItsResponse() {
        val root = Files.createTempDirectory("cint-live-discovery-queued-edit")
        try {
            val provider = GatedDiscoveryProvider()
            val harness =
                harness(
                    root,
                    suggestionSettings = discoverySettings().copy(workerThreads = 1),
                    service = service(provider),
                )
            harness.initialize()
            val first = root.resolve("First.kt").toString()
            val second = root.resolve("Second.kt").toString()
            harness.open(first, source, version = 1)
            harness.open(second, source, version = 1)

            harness.suggest(first, version = 1, id = 2, requestId = "running")
            assertTrue(provider.started.await(2, TimeUnit.SECONDS))
            harness.suggest(second, version = 1, id = 3, requestId = "queued")
            harness.change(second, source + "\n// edited", version = 2)
            provider.release.countDown()

            assertTrue(harness.waitForResponse(2).path("accepted").asBoolean())
            assertTrue(harness.waitForResponse(3).path("cancelled").asBoolean())

            harness.suggest(second, version = 2, id = 4, requestId = "replacement")
            val replacement = harness.waitForResponse(4)
            assertTrue(replacement.path("accepted").asBoolean())
            assertFalse(replacement.path("busy").asBoolean())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun budgetExhaustionPublishesGradedAdditionsAndUncoveredUnits() {
        val root = Files.createTempDirectory("cint-live-discovery-budget")
        try {
            val provider = DiscoveryProvider()
            val harness =
                harness(
                    root,
                    suggestionSettings = discoverySettings().copy(discoveryMaxUnitsPerRequest = 5, maxToolCalls = 1),
                    service = service(provider),
                )
            harness.initialize()
            val uri = root.resolve("Facts.kt").toString()
            harness.open(uri, source, version = 1)
            harness.suggest(uri, version = 1, id = 2, requestId = "budget")
            val response = harness.waitForResponse(2)

            assertTrue(response.path("accepted").asBoolean())
            assertTrue(response.path("budgetExhausted").asBoolean())
            assertTrue(response.path("graded").asInt() > 0)
            assertTrue(response.path("uncovered").size() > 0)
            candidates(harness.latest("cint/publishCandidates"), "SEARCH")
                .first { it["source"].asText() == "ai:discovery" }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun allowGlobsSkipSurfacesAllUnitsAsUncovered() {
        val root = Files.createTempDirectory("cint-live-discovery-skip")
        try {
            val provider = DiscoveryProvider()
            val harness =
                harness(
                    root,
                    suggestionSettings = discoverySettings().copy(allowGlobs = listOf("**/Other.kt")),
                    service = service(provider),
                )
            harness.initialize()
            val uri = root.resolve("Facts.kt").toString()
            harness.open(uri, source, version = 1)
            harness.suggest(uri, version = 1, id = 2, requestId = "skip")
            val response = harness.waitForResponse(2)

            assertTrue(response.path("accepted").asBoolean())
            assertTrue(response.path("skipped").asBoolean())
            assertEquals(0, response.path("graded").asInt())
            assertEquals(response.path("total").asInt(), response.path("uncovered").size())
            assertEquals(0, provider.calls.get())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun responseProseIsAbsentFromTheSessionLog() {
        val root = Files.createTempDirectory("cint-live-discovery-secret")
        try {
            val secret = "hunter2-secret"
            val provider =
                object : IntentSuggestionProvider, AnnotationSessionProvider {
                    override val id: String = "secret"
                    override val model: String = "secret-v1"
                    override val defaultPromptVersion: String = "secret-prompt-v1"

                    override fun suggest(context: CandidateContext): List<CandidateEmit> = emptyList()

                    override fun complete(request: AgentRequest): AgentTurn = AgentTurn.Message("here is $secret")
                }
            val harness =
                harness(
                    root,
                    suggestionSettings = discoverySettings().copy(provider = "secret"),
                    service = service(provider),
                )
            harness.initialize()
            val uri = root.resolve("Facts.kt").toString()
            harness.open(uri, source, version = 1)
            harness.suggest(uri, version = 1, id = 2, requestId = "secret")
            assertTrue(harness.waitForResponse(2).path("accepted").asBoolean())

            harness.send("""{"jsonrpc":"2.0","id":9,"method":"cint/suggest/log","params":{"uri":${mapper.writeValueAsString(uri)}}}""")
            val serialized = harness.lastResponse(9).toString()
            assertFalse(serialized.contains(secret))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun discoverySettings(): SuggestionSettings =
        SuggestionSettings(
            enabled = true,
            provider = "discovery",
            model = "discovery-v1",
            discoveryEnabled = true,
            sessionTimeoutMs = 5_000,
        )

    private fun service(provider: IntentSuggestionProvider): SuggestionService =
        SuggestionService(SuggestionProviderRegistry(listOf(provider)))

    private class DiscoveryProvider :
        IntentSuggestionProvider,
        AnnotationSessionProvider {
        override val id: String = "discovery"
        override val model: String = "discovery-v1"
        override val defaultPromptVersion: String = "discovery-prompt-v1"
        val calls: AtomicInteger = AtomicInteger(0)
        private val mapper = jacksonObjectMapper()

        override fun suggest(context: CandidateContext): List<CandidateEmit> = emptyList()

        override fun complete(request: AgentRequest): AgentTurn {
            calls.incrementAndGet()
            val units = unitsOf(request)
            val target =
                units
                    .filter { it.path("text").asText().contains("Regex") && it.path("text").asText().contains("find(text)") }
                    .minByOrNull { it.path("text").asText().length }
            val verdicts =
                units.map { unit ->
                    val unitId = unit.path("unitId").asText()
                    if (unit === target) {
                        val text = unit.path("text").asText()
                        val unitStart = unit.path("startByte").asInt()
                        val start = unitStart + text.indexOf("find(text)")
                        val end = start + "find(text)".length
                        linkedMapOf(
                            "unitId" to unitId,
                            "add" to
                                listOf(
                                    linkedMapOf(
                                        "intent" to "SEARCH",
                                        "startByte" to start,
                                        "endByte" to end,
                                        "confidence" to 0.62,
                                        "rationale" to "Regex find searches the text",
                                    ),
                                ),
                        )
                    } else {
                        linkedMapOf("unitId" to unitId, "verdict" to "none")
                    }
                }
            return AgentTurn.Calls(
                listOf(
                    ToolCall(
                        id = "d1",
                        name = "submit_verdicts",
                        arguments = mapper.writeValueAsString(linkedMapOf("verdicts" to verdicts)),
                    ),
                ),
            )
        }
    }

    private class BlockingDiscoveryProvider :
        IntentSuggestionProvider,
        AnnotationSessionProvider {
        override val id: String = "discovery"
        override val model: String = "discovery-v1"
        override val defaultPromptVersion: String = "discovery-prompt-v1"
        val started: CountDownLatch = CountDownLatch(1)
        private val calls = AtomicInteger(0)

        override fun suggest(context: CandidateContext): List<CandidateEmit> = emptyList()

        override fun complete(request: AgentRequest): AgentTurn {
            if (calls.incrementAndGet() == 1) {
                started.countDown()
                try {
                    Thread.sleep(5_000)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return AgentTurn.Failure("cancelled")
                }
            }
            return AgentTurn.Message("no verdicts")
        }
    }

    private class GatedDiscoveryProvider :
        IntentSuggestionProvider,
        AnnotationSessionProvider {
        override val id: String = "discovery"
        override val model: String = "discovery-v1"
        override val defaultPromptVersion: String = "discovery-prompt-v1"
        val started: CountDownLatch = CountDownLatch(1)
        val release: CountDownLatch = CountDownLatch(1)
        private val calls = AtomicInteger(0)

        override fun suggest(context: CandidateContext): List<CandidateEmit> = emptyList()

        override fun complete(request: AgentRequest): AgentTurn {
            if (calls.incrementAndGet() == 1) {
                started.countDown()
                try {
                    release.await(5, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return AgentTurn.Failure("cancelled")
                }
            }
            return AgentTurn.Message("no verdicts")
        }
    }

    private class LegacyProvider :
        IntentSuggestionProvider,
        AnnotationSessionProvider {
        override val id: String = "legacy"
        override val model: String = "legacy-v1"
        override val defaultPromptVersion: String = "legacy-prompt-v1"
        val sessionCalls: AtomicInteger = AtomicInteger(0)

        override fun suggest(context: CandidateContext): List<CandidateEmit> =
            listOf(
                context.candidate.copy(
                    source = "ai:legacy",
                    provider = id,
                    model = model,
                    aiEligible = false,
                ),
            )

        override fun complete(request: AgentRequest): AgentTurn {
            sessionCalls.incrementAndGet()
            return AgentTurn.Message("unexpected")
        }
    }
}

private fun unitsOf(request: AgentRequest): List<JsonNode> {
    val mapper = jacksonObjectMapper()
    val user = request.messages.lastOrNull { it.role == "user" }?.content ?: return emptyList()
    return mapper.readTree(user).path("units").toList()
}
