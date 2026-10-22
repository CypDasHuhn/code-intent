package dev.cint.server.suggest

import dev.cint.api.CandidateEmit
import dev.cint.core.user.UserAnnotationsFile
import dev.cint.core.user.UserAnnotationsJson
import dev.cint.server.SuggestionSettings
import dev.cint.server.suggest.support.Peer
import dev.cint.server.suggest.support.candidate
import dev.cint.server.suggest.support.candidates
import dev.cint.server.suggest.support.gapSource
import dev.cint.server.suggest.support.harness
import dev.cint.server.suggest.support.twoGapSource
import dev.cint.suggest.CandidateContext
import dev.cint.suggest.IntentSuggestionProvider
import dev.cint.suggest.SuggestionProviderRegistry
import dev.cint.suggest.SuggestionService
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveSuggestionConcurrencyTest {
    @Test
    fun analysisRevisionOrderingMatchesConfigSnapshotDuringConcurrentPublish() {
        val root = Files.createTempDirectory("cint-live-suggest-tags-race")
        try {
            val harness = harness(root)
            val other = harness.newWriter()
            val uri = root.resolve("Sample.kt").toString()
            val text = """fun f() { log.audit("x") }"""
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            harness.server.analysisRevisionBarrierForTest = {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            val analysisThread = Thread { harness.open(uri, text, version = 1) }
            analysisThread.start()
            assertTrue(started.await(2, TimeUnit.SECONDS))

            val configThread =
                Thread {
                    harness.sendFrom(
                        other,
                        harness.configAppendMessage("logging", "kotlin", "methods", "audit", id = 50),
                    )
                }
            configThread.start()
            Thread.sleep(100)
            harness.server.analysisRevisionBarrierForTest = null
            release.countDown()
            analysisThread.join(5_000)
            configThread.join(5_000)

            val tags =
                harness
                    .messages()
                    .filter { it["method"]?.asText() == "cint/publishTags" && it.path("params").path("uri").asText() == uri }
                    .sortedBy { it.path("params").path("analysisRevision").asLong() }
            assertTrue(tags.size >= 2)
            val configRevisions = tags.map { it.path("params").path("revision").asLong() }
            assertEquals(configRevisions.sorted(), configRevisions)
            val last = tags.last()
            assertTrue(
                last.path("params").path("annotations").any { annotation ->
                    annotation.path("intents").any { it.asText() == "LOGGING" }
                },
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun successfulSkipIsAtomicWithThePublish() {
        val root = Files.createTempDirectory("cint-live-suggest-skip-atomic")
        try {
            val harness = harness(root)
            val peer = Peer(harness.server)
            val uri = root.resolve("Sample.kt").toString()
            peer.open(uri, gapSource, version = 1)
            val candidateId = candidate(peer.latest("cint/publishCandidates"), "VALIDATION")["candidateId"].asText()

            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            harness.server.candidatePublishBarrierForTest = {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            val skipThread =
                Thread {
                    peer.decide(uri, version = 1, id = 2, candidateId = candidateId, decision = "skip")
                }
            skipThread.start()
            assertTrue(started.await(2, TimeUnit.SECONDS))

            val changeThread = Thread { peer.change(uri, gapSource, version = 2) }
            changeThread.start()
            Thread.sleep(100)
            harness.server.candidatePublishBarrierForTest = null
            release.countDown()
            skipThread.join(5_000)
            changeThread.join(5_000)

            assertTrue(peer.lastResponse(2).path("accepted").asBoolean())
            assertTrue(
                peer
                    .latest("cint/publishCandidates")
                    .path("params")
                    .path("candidates")
                    .isEmpty,
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun staleSkipDoesNotHideTheCandidate() {
        val root = Files.createTempDirectory("cint-live-suggest-stale-skip")
        try {
            val harness = harness(root)
            val peer = Peer(harness.server)
            val uri = root.resolve("Sample.kt").toString()
            peer.open(uri, gapSource, version = 1)
            val candidateId = candidate(peer.latest("cint/publishCandidates"), "VALIDATION")["candidateId"].asText()

            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            harness.server.candidateDecisionBarrierForTest = {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            val skipThread =
                Thread {
                    peer.decide(uri, version = 1, id = 2, candidateId = candidateId, decision = "skip")
                }
            skipThread.start()
            assertTrue(started.await(2, TimeUnit.SECONDS))
            peer.change(uri, gapSource, version = 2)
            harness.server.candidateDecisionBarrierForTest = null
            release.countDown()
            skipThread.join(5_000)

            assertTrue(peer.lastResponse(2).path("stale").asBoolean())
            candidate(peer.latest("cint/publishCandidates"), "VALIDATION")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun concurrentConfirmsForDifferentAnchorsBothPersist() {
        val root = Files.createTempDirectory("cint-live-suggest-concurrent-confirms")
        try {
            val harness = harness(root)
            val first = Peer(harness.server)
            val second = Peer(harness.server)
            val uri = root.resolve("Sample.kt").toString()
            first.open(uri, twoGapSource, version = 1)
            second.open(uri, twoGapSource, version = 1)
            val firstCandidates = candidates(first.latest("cint/publishCandidates"), "VALIDATION")
            val secondCandidates = candidates(second.latest("cint/publishCandidates"), "VALIDATION")
            assertEquals(2, firstCandidates.size)
            assertEquals(2, secondCandidates.size)

            val start = CountDownLatch(1)
            val firstThread =
                Thread {
                    start.await(2, TimeUnit.SECONDS)
                    first.decide(uri, version = 1, id = 2, candidateId = firstCandidates[0]["candidateId"].asText(), decision = "confirm")
                }
            val secondThread =
                Thread {
                    start.await(2, TimeUnit.SECONDS)
                    second.decide(uri, version = 1, id = 3, candidateId = secondCandidates[1]["candidateId"].asText(), decision = "confirm")
                }
            firstThread.start()
            secondThread.start()
            start.countDown()
            firstThread.join(5_000)
            secondThread.join(5_000)

            val annotations = UserAnnotationsJson.parse(Files.readString(UserAnnotationsFile.path(root)))
            assertEquals(2, annotations.size)
            assertEquals(2, annotations.map { it.span.startLine }.toSet().size)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun secondClientCannotConfirmAnAlreadyResolvedSibling() {
        val root = Files.createTempDirectory("cint-live-suggest-cross-client-group")
        try {
            val provider =
                object : IntentSuggestionProvider {
                    override val id: String = "cross-stub"
                    override val model: String = "cross-stub-v1"

                    override fun suggest(context: CandidateContext): List<CandidateEmit> =
                        listOf(
                            context.candidate.copy(
                                intents = setOf("LOGGING"),
                                source = "ai:cross-stub",
                                provider = id,
                                model = model,
                                rationale = "cross-client alternative",
                            ),
                        )
                }
            val service = SuggestionService(SuggestionProviderRegistry(listOf(provider)))
            val harness =
                harness(
                    root,
                    suggestionSettings = SuggestionSettings(enabled = true, provider = provider.id, model = provider.model),
                    service = service,
                )
            val uri = root.resolve("Sample.kt").toString()
            val second = Peer(harness.server)
            harness.open(uri, gapSource, version = 1)
            second.open(uri, gapSource, version = 1)
            harness.suggest(uri, version = 1, id = 2, requestId = "first")
            second.suggest(uri, version = 1, id = 2, requestId = "second")
            assertTrue(harness.waitForResponse(2).path("accepted").asBoolean())
            assertTrue(second.waitForResponse(2).path("accepted").asBoolean())

            val firstAlternatives =
                harness
                    .latest("cint/publishCandidates")
                    .path("params")
                    .path("groups")[0]
                    .path("candidates")
            val secondAlternatives =
                second
                    .latest("cint/publishCandidates")
                    .path("params")
                    .path("groups")[0]
                    .path("candidates")
            val chosen = firstAlternatives.first { it["source"].asText() == "ai:cross-stub" }
            val sibling = secondAlternatives.first { it["source"].asText() == "rule:validation:validation" }

            harness.decide(uri, version = 1, id = 3, candidateId = chosen["candidateId"].asText(), decision = "confirm")
            assertTrue(
                second
                    .latest("cint/publishCandidates")
                    .path("params")
                    .path("groups")
                    .isEmpty,
            )
            second.decide(uri, version = 1, id = 4, candidateId = sibling["candidateId"].asText(), decision = "confirm")

            assertTrue(second.lastResponse(4).path("missing").asBoolean())
            val annotations = UserAnnotationsJson.parse(Files.readString(UserAnnotationsFile.path(root)))
            assertEquals(1, annotations.size)
            assertEquals(setOf("LOGGING"), annotations.single().intents)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectRepublishesCandidateStateToOtherOwners() {
        val root = Files.createTempDirectory("cint-live-suggest-cross-client-reject")
        try {
            val harness = harness(root)
            val first = Peer(harness.server)
            val second = Peer(harness.server)
            val uri = root.resolve("Sample.kt").toString()
            first.open(uri, twoGapSource, version = 1)
            second.open(uri, twoGapSource, version = 1)
            val firstCandidates = candidates(first.latest("cint/publishCandidates"), "VALIDATION")
            val secondCandidates = candidates(second.latest("cint/publishCandidates"), "VALIDATION")
            assertEquals(2, firstCandidates.size)
            assertEquals(2, secondCandidates.size)

            first.decide(uri, version = 1, id = 2, candidateId = firstCandidates[0]["candidateId"].asText(), decision = "reject")

            val republished = candidates(second.latest("cint/publishCandidates"), "VALIDATION")
            assertEquals(1, republished.size)
            assertEquals(secondCandidates[1]["candidateId"].asText(), republished.single()["candidateId"].asText())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun protocolShutdownDoesNotDisableSuggestionsForLaterClients() {
        val root = Files.createTempDirectory("cint-live-suggest-shutdown")
        try {
            val harness = harness(root)
            harness.send("""{"jsonrpc":"2.0","id":90,"method":"shutdown"}""")
            assertTrue(harness.lastResponse(90).asBoolean())

            val later = Peer(harness.server)
            val uri = root.resolve("Sample.kt").toString()
            later.open(uri, gapSource, version = 1)
            later.suggest(uri, version = 1, id = 2, requestId = "after-shutdown")
            assertTrue(later.waitForResponse(2).path("accepted").asBoolean())
            val enriched =
                later.waitForCandidate(uri, "VALIDATION") {
                    it["source"].asText() == "ai:fake"
                }
            assertEquals("ai:fake", enriched["source"].asText())
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
