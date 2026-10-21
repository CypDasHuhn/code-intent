package dev.cint.server

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import dev.cint.api.CandidateEmit
import dev.cint.api.CandidateReason
import dev.cint.api.Span
import dev.cint.core.user.UserAnnotationsFile
import dev.cint.core.user.UserAnnotationsJson
import dev.cint.host.HostConfig
import dev.cint.host.HostPipeline
import dev.cint.host.HostRuntime
import dev.cint.rulepacks.BundledRulePackConfigResolver
import dev.cint.suggest.CandidateContext
import dev.cint.suggest.DeepSeekIntentSuggestionProvider
import dev.cint.suggest.DeepSeekProviderConfig
import dev.cint.suggest.IntentSuggestionProvider
import dev.cint.suggest.SuggestionProviderRegistry
import dev.cint.suggest.SuggestionService
import java.io.BufferedWriter
import java.io.StringWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LiveSuggestionsTest {
    private val mapper = jacksonObjectMapper()

    private val gapSource =
        """
        fun f(): Boolean {
            // cint: validation

            if (true) {
                work()
            }
            return false
        }
        """.trimIndent()

    private val twoGapSource =
        """
        fun a(): Boolean {
            // cint: validation

            if (true) {
                first()
            }
            return false
        }

        fun b(): Boolean {
            // cint: validation

            if (true) {
                second()
            }
            return false
        }
        """.trimIndent()

    @Test
    fun publishSuggestConfirmEstablishesAndReProposesAfterAnnotationRemoval() {
        val root = Files.createTempDirectory("cint-live-suggest-confirm")
        try {
            val harness = harness(root)
            val uri = root.resolve("Sample.kt").toString()
            harness.open(uri, gapSource, version = 1)

            val rawCandidate = candidate(harness.latest("cint/publishCandidates"), "VALIDATION")
            val candidateId = rawCandidate["candidateId"].asText()
            val rawRevision =
                harness
                    .latest("cint/publishTags")
                    .path("params")
                    .path("analysisRevision")
                    .asLong()

            harness.suggest(uri, version = 1, id = 2, requestId = "r1")
            assertTrue(harness.waitForResponse(2).path("accepted").asBoolean())
            val enriched =
                harness.waitForCandidate(uri, "VALIDATION") {
                    it["source"].asText() == "ai:fake"
                }
            assertEquals(candidateId, enriched["candidateId"].asText())
            assertTrue(enriched["provider"].asText() == "fake")
            assertTrue(enriched["model"].asText().isNotBlank())
            assertTrue(enriched["promptVersion"].asText().isNotBlank())
            assertTrue(enriched["rationale"].asText().isNotBlank())

            harness.decide(uri, version = 1, id = 3, candidateId = candidateId, decision = "confirm")

            val annotations = UserAnnotationsJson.parse(Files.readString(UserAnnotationsFile.path(root)))
            assertEquals(1, annotations.size)
            assertEquals("Sample.kt", annotations.single().path)
            assertEquals(setOf("VALIDATION"), annotations.single().intents)

            val tags = harness.latest("cint/publishTags")
            assertTrue(
                tags.path("params").path("annotations").any { annotation ->
                    annotation.path("intents").any { it.asText() == "VALIDATION" } &&
                        annotation.path("provenance").any { it.path("source").asText() == "user" }
                },
            )
            assertTrue(tags.path("params").path("analysisRevision").asLong() > rawRevision)
            assertTrue(
                harness
                    .latest("cint/publishCandidates")
                    .path("params")
                    .path("candidates")
                    .isEmpty,
            )

            Files.deleteIfExists(UserAnnotationsFile.path(root))
            harness.analyze(uri, id = 4)
            assertTrue(harness.waitForResponse(4).asBoolean())
            val reProposed =
                harness.waitForCandidate(uri, "VALIDATION") {
                    it["candidateId"].asText() == candidateId
                }
            assertEquals("rule:validation:validation", reProposed["source"].asText())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectPersistsSuppressionAcrossFreshServer() {
        val root = Files.createTempDirectory("cint-live-suggest-reject")
        try {
            val uri = root.resolve("Sample.kt").toString()
            val harness = harness(root)
            harness.open(uri, gapSource, version = 1)
            val candidateId = candidate(harness.latest("cint/publishCandidates"), "VALIDATION")["candidateId"].asText()
            harness.decide(uri, version = 1, id = 2, candidateId = candidateId, decision = "reject")

            val decisions = Files.readString(root.resolve(".cint/suggestions.json"))
            assertTrue(decisions.contains("\"decision\" : \"reject\""))
            assertTrue(decisions.contains(candidateId))
            assertFalse(Files.exists(UserAnnotationsFile.path(root)))
            assertTrue(
                harness
                    .latest("cint/publishCandidates")
                    .path("params")
                    .path("candidates")
                    .isEmpty,
            )

            val fresh = harness(root)
            fresh.open(uri, gapSource, version = 1)
            assertTrue(
                fresh
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
    fun skipIsSessionOnlyAndStaleVersionsAreRejected() {
        val root = Files.createTempDirectory("cint-live-suggest-skip")
        try {
            val uri = root.resolve("Sample.kt").toString()
            val harness = harness(root)
            harness.open(uri, gapSource, version = 1)
            val candidateId = candidate(harness.latest("cint/publishCandidates"), "VALIDATION")["candidateId"].asText()
            harness.decide(uri, version = 1, id = 2, candidateId = candidateId, decision = "skip")
            assertTrue(
                harness
                    .latest("cint/publishCandidates")
                    .path("params")
                    .path("candidates")
                    .isEmpty,
            )
            assertFalse(Files.exists(root.resolve(".cint/suggestions.json")))

            val publishesBefore = harness.methodCount("cint/publishCandidates")
            harness.suggest(uri, version = 0, id = 3, requestId = "stale")
            assertTrue(harness.lastResponse(3).path("stale").asBoolean())
            assertEquals(publishesBefore, harness.methodCount("cint/publishCandidates"))

            val fresh = harness(root)
            fresh.open(uri, gapSource, version = 1)
            candidate(fresh.latest("cint/publishCandidates"), "VALIDATION")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun identicalSpansHaveDistinctIdentityAndIndependentSuppression() {
        val root = Files.createTempDirectory("cint-live-suggest-occurrences")
        try {
            val harness = harness(root)
            val uri = root.resolve("Sample.kt").toString()
            harness.open(uri, twoGapSource, version = 1)

            val candidates = candidates(harness.latest("cint/publishCandidates"), "VALIDATION")
            assertEquals(2, candidates.size)
            assertNotEquals(candidates[0]["candidateId"].asText(), candidates[1]["candidateId"].asText())
            assertEquals(
                2,
                harness
                    .latest("cint/publishCandidates")
                    .path("params")
                    .path("groups")
                    .size(),
            )

            harness.decide(uri, version = 1, id = 2, candidateId = candidates[0]["candidateId"].asText(), decision = "reject")
            val remaining = candidates(harness.latest("cint/publishCandidates"), "VALIDATION")
            assertEquals(1, remaining.size)
            assertEquals(candidates[1]["candidateId"].asText(), remaining.single()["candidateId"].asText())

            harness.decide(uri, version = 1, id = 3, candidateId = remaining.single()["candidateId"].asText(), decision = "reject")
            assertTrue(
                harness
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
    fun aiAlternativesAreMergedIntoOneGroupAndValidated() {
        val root = Files.createTempDirectory("cint-live-suggest-alternatives")
        try {
            val provider =
                object : IntentSuggestionProvider {
                    override val id: String = "stub"
                    override val model: String = "stub-v1"

                    override fun suggest(context: CandidateContext): List<CandidateEmit> {
                        val candidate = context.candidate
                        return listOf(
                            candidate.copy(
                                intents = setOf("LOGGING"),
                                source = "ai:stub",
                                provider = id,
                                model = model,
                                promptVersion = context.promptVersion,
                                rationale = "alternative",
                                aiEligible = false,
                            ),
                            candidate.copy(startByte = context.documentLength + 1, source = "ai:stub"),
                            candidate.copy(intents = setOf("UNKNOWN_INTENT"), source = "ai:stub"),
                        )
                    }
                }
            val service = SuggestionService(SuggestionProviderRegistry(listOf(provider)))
            val harness =
                harness(
                    root,
                    suggestionSettings = SuggestionSettings(enabled = true, provider = "stub", model = "stub-v1"),
                    service = service,
                )
            val uri = root.resolve("Sample.kt").toString()
            harness.open(uri, gapSource, version = 1)
            harness.suggest(uri, version = 1, id = 2, requestId = "alt")
            assertTrue(harness.waitForResponse(2).path("accepted").asBoolean())

            val params = harness.latest("cint/publishCandidates").path("params")
            val groups = params.path("groups")
            assertEquals(1, groups.size())
            val alternatives = groups[0].path("candidates")
            assertEquals(2, alternatives.size())
            assertEquals(setOf("LOGGING", "VALIDATION"), alternatives.map { it["intents"][0].asText() }.toSet())
            assertEquals(1, alternatives.count { it["source"].asText() == "rule:validation:validation" })
            assertEquals(1, alternatives.count { it["source"].asText() == "ai:stub" })
            val chosen = alternatives.first { it["source"].asText() == "ai:stub" }
            val sibling = alternatives.first { it["source"].asText() == "rule:validation:validation" }

            harness.decide(uri, version = 1, id = 3, candidateId = chosen["candidateId"].asText(), decision = "confirm")
            val resolved = harness.latest("cint/publishCandidates").path("params")
            assertTrue(resolved.path("candidates").isEmpty)
            assertTrue(resolved.path("groups").isEmpty)
            harness.decide(uri, version = 1, id = 4, candidateId = sibling["candidateId"].asText(), decision = "confirm")
            assertTrue(harness.lastResponse(4).path("missing").asBoolean())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun blockingProviderCanBeCancelledBeforePublish() {
        val root = Files.createTempDirectory("cint-live-suggest-cancel-inflight")
        try {
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val provider =
                object : IntentSuggestionProvider {
                    override val id: String = "blocking"
                    override val model: String = "blocking-v1"

                    override fun suggest(context: CandidateContext): List<CandidateEmit> {
                        started.countDown()
                        release.await(5, TimeUnit.SECONDS)
                        return listOf(context.candidate.copy(source = "ai:blocking", provider = id, model = model))
                    }
                }
            val service = SuggestionService(SuggestionProviderRegistry(listOf(provider)))
            val harness =
                harness(
                    root,
                    suggestionSettings = SuggestionSettings(enabled = true, provider = "blocking", model = "blocking-v1"),
                    service = service,
                )
            val uri = root.resolve("Sample.kt").toString()
            harness.open(uri, gapSource, version = 1)
            harness.suggest(uri, version = 1, id = 2, requestId = "blocking")
            assertTrue(started.await(2, TimeUnit.SECONDS))

            harness.cancel(uri, id = 3, requestId = "blocking")
            assertTrue(harness.lastResponse(3).asBoolean())

            val response = harness.waitForResponse(2)
            release.countDown()
            assertTrue(response.path("cancelled").asBoolean())
            assertTrue(
                harness.messages().none {
                    it["method"]?.asText() == "cint/publishCandidates" &&
                        it.path("params").path("candidates").any { candidate -> candidate["source"].asText() == "ai:blocking" }
                },
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun staleAnalysisCannotPublishAfterConfigMutation() {
        val root = Files.createTempDirectory("cint-live-suggest-race")
        try {
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val provider =
                object : IntentSuggestionProvider {
                    override val id: String = "race"
                    override val model: String = "race-v1"

                    override fun suggest(context: CandidateContext): List<CandidateEmit> {
                        started.countDown()
                        release.await(5, TimeUnit.SECONDS)
                        return listOf(context.candidate.copy(source = "ai:race", provider = id, model = model))
                    }
                }
            val service = SuggestionService(SuggestionProviderRegistry(listOf(provider)))
            val harness =
                harness(
                    root,
                    suggestionSettings = SuggestionSettings(enabled = true, provider = "race", model = "race-v1"),
                    service = service,
                )
            val uri = root.resolve("Sample.kt").toString()
            harness.open(uri, gapSource, version = 1)
            harness.suggest(uri, version = 1, id = 2, requestId = "race")
            assertTrue(started.await(2, TimeUnit.SECONDS))

            harness.configAppend("logging", "kotlin", "methods", "audit", id = 3)
            assertTrue(harness.lastResponse(3).has("packs"))
            release.countDown()

            val response = harness.waitForResponse(2)
            assertTrue(response.path("cancelled").asBoolean())
            assertTrue(
                harness.messages().none {
                    it["method"]?.asText() == "cint/publishCandidates" &&
                        it.path("params").path("candidates").any { candidate -> candidate["source"].asText() == "ai:race" }
                },
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun suggestionsCapabilityHonorsConfigGateAndHasNoSecrets() {
        val disabledRoot = Files.createTempDirectory("cint-live-suggest-disabled")
        try {
            val disabled = harness(disabledRoot, suggestionSettings = null)
            disabled.initialize()
            val descriptor = disabled.initializeResult().path("suggestions")
            assertFalse(descriptor.path("enabled").asBoolean())
            val keys = descriptor.fieldNames().asSequence().toSet()
            assertEquals(setOf("enabled", "trigger", "provider", "model", "knownIntents"), keys)
            assertTrue(keys.none { it.contains("key", ignoreCase = true) || it.contains("secret", ignoreCase = true) })

            val uri = disabledRoot.resolve("Sample.kt").toString()
            disabled.open(uri, gapSource, version = 1)
            val publishes = disabled.methodCount("cint/publishCandidates")
            disabled.suggest(uri, version = 1, id = 2, requestId = "off")
            assertTrue(disabled.lastResponse(2).path("disabled").asBoolean())
            assertEquals(publishes, disabled.methodCount("cint/publishCandidates"))
        } finally {
            disabledRoot.toFile().deleteRecursively()
        }

        val enabledRoot = Files.createTempDirectory("cint-live-suggest-enabled")
        try {
            val file = enabledRoot.resolve(".cint/suggestions.yml")
            Files.createDirectories(file.parent)
            Files.writeString(
                file,
                """
                suggestions:
                  enabled: true
                  trigger: manual
                  provider: fake
                  model: fake-intent-v1
                """.trimIndent(),
            )
            val enabled = harness(enabledRoot, suggestionSettings = null)
            enabled.initialize()
            val descriptor = enabled.initializeResult().path("suggestions")
            assertTrue(descriptor.path("enabled").asBoolean())
            assertEquals("fake", descriptor.path("provider").asText())
        } finally {
            enabledRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun clientsOnTheSameUriKeepIndependentCandidateState() {
        val root = Files.createTempDirectory("cint-live-suggest-owners")
        try {
            val uri = root.resolve("Sample.kt").toString()
            val first = harness(root)
            val second = harness(root)
            first.open(uri, gapSource, version = 1)
            second.open(uri, gapSource, version = 1)
            val firstId = candidate(first.latest("cint/publishCandidates"), "VALIDATION")["candidateId"].asText()
            val secondId = candidate(second.latest("cint/publishCandidates"), "VALIDATION")["candidateId"].asText()
            assertEquals(firstId, secondId)

            second.decide(uri, version = 1, id = 2, candidateId = secondId, decision = "skip")
            assertTrue(
                second
                    .latest("cint/publishCandidates")
                    .path("params")
                    .path("candidates")
                    .isEmpty,
            )
            candidate(first.latest("cint/publishCandidates"), "VALIDATION")

            first.decide(uri, version = 1, id = 3, candidateId = firstId, decision = "confirm")
            assertTrue(
                first
                    .latest("cint/publishTags")
                    .path("params")
                    .path("annotations")
                    .any { annotation -> annotation.path("source").asText() == "user" },
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun malformedUserAnnotationsDoNotBreakCandidatePublicationOrConfirm() {
        val root = Files.createTempDirectory("cint-live-suggest-malformed")
        try {
            val annotations = UserAnnotationsFile.path(root)
            Files.createDirectories(annotations.parent)
            Files.writeString(annotations, "{ this is not valid yaml")
            val harness = harness(root)
            val uri = root.resolve("Sample.kt").toString()
            harness.open(uri, gapSource, version = 1)
            val candidateId = candidate(harness.latest("cint/publishCandidates"), "VALIDATION")["candidateId"].asText()

            harness.decide(uri, version = 1, id = 2, candidateId = candidateId, decision = "confirm")

            val written = UserAnnotationsJson.parse(Files.readString(annotations))
            assertEquals(1, written.size)
            assertEquals(setOf("VALIDATION"), written.single().intents)
            assertTrue(
                harness
                    .latest("cint/publishTags")
                    .path("params")
                    .path("annotations")
                    .any { annotation -> annotation.path("source").asText() == "user" },
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun sameVersionDifferentHashDecisionIsStale() {
        val root = Files.createTempDirectory("cint-live-suggest-hash-stale")
        try {
            val harness = harness(root)
            val uri = root.resolve("Sample.kt").toString()
            harness.open(uri, gapSource, version = 1)
            val candidateId = candidate(harness.latest("cint/publishCandidates"), "VALIDATION")["candidateId"].asText()

            harness.rewriteDocumentTextForTest(uri, gapSource.replace("if (true)", "if (fals)"))
            harness.decide(uri, version = 1, id = 2, candidateId = candidateId, decision = "confirm")

            assertTrue(harness.lastResponse(2).path("stale").asBoolean())
            assertFalse(Files.exists(UserAnnotationsFile.path(root)))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun suggestionCannotOverwriteNewerAnalysisAfterItIsReleased() {
        val root = Files.createTempDirectory("cint-live-suggest-publish-race")
        try {
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val provider =
                object : IntentSuggestionProvider {
                    override val id: String = "race-publish"
                    override val model: String = "race-publish-v1"

                    override fun suggest(context: CandidateContext): List<CandidateEmit> {
                        started.countDown()
                        release.await(5, TimeUnit.SECONDS)
                        return listOf(context.candidate.copy(source = "ai:race-publish", provider = id, model = model))
                    }
                }
            val service = SuggestionService(SuggestionProviderRegistry(listOf(provider)))
            val harness =
                harness(
                    root,
                    suggestionSettings = SuggestionSettings(enabled = true, provider = provider.id, model = provider.model),
                    service = service,
                )
            val uri = root.resolve("Sample.kt").toString()
            harness.open(uri, gapSource, version = 1)
            harness.suggest(uri, version = 1, id = 2, requestId = "race-publish")
            assertTrue(started.await(2, TimeUnit.SECONDS))

            harness.change(uri, gapSource, version = 2)
            val newerRevision =
                harness
                    .latest("cint/publishTags")
                    .path("params")
                    .path("analysisRevision")
                    .asLong()
            release.countDown()

            val response = harness.waitForResponse(2)
            assertTrue(response.path("cancelled").asBoolean())
            assertTrue(
                harness.messages().none {
                    it["method"]?.asText() == "cint/publishCandidates" &&
                        it.path("params").path("candidates").any { candidate -> candidate["source"].asText() == "ai:race-publish" }
                },
            )
            assertEquals(
                newerRevision,
                harness
                    .latest("cint/publishCandidates")
                    .path("params")
                    .path("analysisRevision")
                    .asLong(),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun timeoutInterruptsBlockingProviderWithoutPublishing() {
        val root = Files.createTempDirectory("cint-live-suggest-timeout")
        try {
            val provider =
                object : IntentSuggestionProvider {
                    override val id: String = "slow"
                    override val model: String = "slow-v1"

                    override fun suggest(context: CandidateContext): List<CandidateEmit> {
                        Thread.sleep(5_000)
                        return listOf(context.candidate.copy(source = "ai:slow", provider = id, model = model))
                    }
                }
            val service = SuggestionService(SuggestionProviderRegistry(listOf(provider)))
            val harness =
                harness(
                    root,
                    suggestionSettings =
                        SuggestionSettings(
                            enabled = true,
                            provider = provider.id,
                            model = provider.model,
                            timeoutMs = 100,
                        ),
                    service = service,
                )
            val uri = root.resolve("Sample.kt").toString()
            harness.open(uri, gapSource, version = 1)
            harness.suggest(uri, version = 1, id = 2, requestId = "slow")

            val response = harness.waitForResponse(2)
            assertTrue(response.path("cancelled").asBoolean())
            assertTrue(response.path("timeout").asBoolean())
            assertTrue(
                harness.messages().none {
                    it["method"]?.asText() == "cint/publishCandidates" &&
                        it.path("params").path("candidates").any { candidate -> candidate["source"].asText() == "ai:slow" }
                },
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

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

    @Test
    fun suggestionSettingsDefaultToDeepSeekWhenEnabledWithoutProvider() {
        val root = Files.createTempDirectory("cint-suggestion-settings-deepseek")
        try {
            val file = root.resolve(".cint/suggestions.yml")
            Files.createDirectories(file.parent)
            Files.writeString(
                file,
                """
                suggestions:
                  enabled: true
                """.trimIndent(),
            )
            val settings = SuggestionSettingsLoader.load(root)
            assertEquals("deepseek", settings.provider)
            assertEquals("deepseek", settings.effectiveProvider)
            assertEquals("deepseek-chat", settings.effectiveModel)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun suggestionSettingsClampConfidenceAndLoadPrivacyOptions() {
        val root = Files.createTempDirectory("cint-suggestion-settings-privacy")
        try {
            val file = root.resolve(".cint/suggestions.yml")
            Files.createDirectories(file.parent)
            Files.writeString(
                file,
                """
                suggestions:
                  enabled: true
                  maxConfidence: 1.0
                  excerptRadiusBytes: 12
                  allowGlobs:
                    - "src/**/*.kt"
                  redactPatterns:
                    - "token=.*"
                """.trimIndent(),
            )
            val settings = SuggestionSettingsLoader.load(root)
            assertEquals(0.84, settings.maxConfidence)
            assertEquals(12, settings.excerptRadiusBytes)
            assertEquals(listOf("src/**/*.kt"), settings.allowGlobs)
            assertEquals(listOf("token=.*"), settings.redactPatterns)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun suggestionsCapabilityResolvesTheSelectedProviderModel() {
        val deepSeekRoot = Files.createTempDirectory("cint-live-suggest-cap-deepseek")
        try {
            val file = deepSeekRoot.resolve(".cint/suggestions.yml")
            Files.createDirectories(file.parent)
            Files.writeString(file, "suggestions:\n  enabled: true\n")
            val harness = harness(deepSeekRoot, suggestionSettings = null)
            harness.initialize()
            val descriptor = harness.initializeResult().path("suggestions")
            assertEquals("deepseek", descriptor.path("provider").asText())
            assertEquals("deepseek-chat", descriptor.path("model").asText())
        } finally {
            deepSeekRoot.toFile().deleteRecursively()
        }

        val fakeRoot = Files.createTempDirectory("cint-live-suggest-cap-fake")
        try {
            val file = fakeRoot.resolve(".cint/suggestions.yml")
            Files.createDirectories(file.parent)
            Files.writeString(file, "suggestions:\n  enabled: true\n  provider: fake\n")
            val harness = harness(fakeRoot, suggestionSettings = null)
            harness.initialize()
            val descriptor = harness.initializeResult().path("suggestions")
            assertEquals("fake", descriptor.path("provider").asText())
            assertEquals("fake-intent-v1", descriptor.path("model").asText())
        } finally {
            fakeRoot.toFile().deleteRecursively()
        }
    }

    private fun lineCol(
        text: String,
        offset: Int,
    ): Pair<Int, Int> {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        var line = 0
        var col = 0
        for (index in 0 until offset.coerceIn(0, bytes.size)) {
            if (bytes[index].toInt() == '\n'.code) {
                line += 1
                col = 0
            } else {
                col += 1
            }
        }
        return line to col
    }

    private fun candidate(
        message: JsonNode,
        intent: String,
    ): JsonNode = candidates(message, intent).single()

    private fun candidates(
        message: JsonNode,
        intent: String,
    ): List<JsonNode> = message["params"]["candidates"].filter { it["intents"].any { value -> value.asText() == intent } }

    private fun harness(
        root: Path,
        suggestionSettings: SuggestionSettings? =
            SuggestionSettings(
                enabled = true,
                trigger = "manual",
                provider = "fake",
                model = "fake-intent-v1",
            ),
        service: SuggestionService? = null,
    ): Harness {
        val settings = HostConfig.load(null)
        val pipeline = HostRuntime.pipeline(settings, BundledRulePackConfigResolver, root)
        val resolvedSettings = suggestionSettings ?: SuggestionSettingsLoader.load(root)
        return Harness(
            root,
            settings,
            pipeline,
            resolvedSettings,
            service ?: suggestionServiceFrom(resolvedSettings),
        )
    }

    private data class ChatResponse(
        val status: Int,
        val body: String,
    )

    private data class RecordedChatRequest(
        val method: String,
        val body: String,
    )

    private class ChatEndpoint : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        private val executor = Executors.newCachedThreadPool()
        val requests = CopyOnWriteArrayList<RecordedChatRequest>()

        @Volatile
        var responder: (RecordedChatRequest) -> ChatResponse = { ChatResponse(200, """{"choices":[]}""") }

        val url: String
            get() = "http://127.0.0.1:${server.address.port}/chat/completions"

        init {
            server.createContext("/chat/completions") { exchange ->
                val body =
                    exchange.requestBody.use {
                        String(it.readBytes(), StandardCharsets.UTF_8)
                    }
                val recorded = RecordedChatRequest(exchange.requestMethod, body)
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

    private class Harness(
        root: Path,
        settings: dev.cint.host.HostSettings,
        pipeline: HostPipeline,
        suggestionSettings: SuggestionSettings?,
        service: SuggestionService,
    ) {
        private val mapper = jacksonObjectMapper()
        private val output = StringWriter()
        private val writer = BufferedWriter(output)
        private val lock = Any()
        val server =
            LiveServer(
                root = root,
                socketPath = Path.of("/tmp/opencode/cint-036-test.sock"),
                useLsp = false,
                defaultLanguage = "kotlin",
                packs = pipeline,
                settings = settings,
                suggestionSettings = suggestionSettings ?: SuggestionSettingsLoader.load(root),
                suggestionService = service,
            )

        fun send(message: String) {
            synchronized(lock) {
                server.handleMessage(message, writer)
            }
        }

        fun sendFrom(
            target: BufferedWriter,
            message: String,
        ) {
            server.handleMessage(message, target)
        }

        fun newWriter(): BufferedWriter = BufferedWriter(StringWriter())

        fun rewriteDocumentTextForTest(
            uri: String,
            text: String,
        ) {
            server.rewriteDocumentTextForTest(writer, uri, text)
        }

        fun configAppendMessage(
            packId: String,
            language: String,
            field: String,
            value: String,
            id: Int,
        ): String =
            """{"jsonrpc":"2.0","id":$id,"method":"cint/config/append","params":{"packId":${quote(
                packId,
            )},"language":${quote(language)},"field":${quote(field)},"value":${quote(value)}}}"""

        fun initialize() {
            send("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocol":3}}""")
        }

        fun initializeResult(): JsonNode = lastResponse(1)

        fun open(
            uri: String,
            text: String,
            version: Int,
        ) {
            send(
                """{"jsonrpc":"2.0","id":11,"method":"textDocument/didOpen","params":{"uri":${quote(
                    uri,
                )},"text":${quote(text)},"version":$version,"languageId":"kotlin"}}""",
            )
        }

        fun change(
            uri: String,
            text: String,
            version: Int,
        ) {
            send(
                """{"jsonrpc":"2.0","method":"textDocument/didChange","params":{"uri":${quote(
                    uri,
                )},"text":${quote(text)},"version":$version,"languageId":"kotlin"}}""",
            )
        }

        fun analyze(
            uri: String,
            id: Int,
        ) {
            send("""{"jsonrpc":"2.0","id":$id,"method":"cint/analyze","params":{"uri":${quote(uri)}}}""")
        }

        fun suggest(
            uri: String,
            version: Int,
            id: Int,
            requestId: String,
        ) {
            send(
                """{"jsonrpc":"2.0","id":$id,"method":"cint/suggest","params":{"uri":${quote(
                    uri,
                )},"version":$version,"requestId":${quote(requestId)}}}""",
            )
        }

        fun cancel(
            uri: String,
            id: Int,
            requestId: String,
        ) {
            send(
                """{"jsonrpc":"2.0","id":$id,"method":"cint/suggest/cancel","params":{"uri":${quote(
                    uri,
                )},"requestId":${quote(requestId)}}}""",
            )
        }

        fun decide(
            uri: String,
            version: Int,
            id: Int,
            candidateId: String,
            decision: String,
        ) {
            send(
                """{"jsonrpc":"2.0","id":$id,"method":"cint/candidates/decide","params":{"uri":${quote(
                    uri,
                )},"version":$version,"candidateId":${quote(candidateId)},"decision":${quote(decision)}}}""",
            )
        }

        fun configAppend(
            packId: String,
            language: String,
            field: String,
            value: String,
            id: Int,
        ) {
            send(
                """{"jsonrpc":"2.0","id":$id,"method":"cint/config/append","params":{"packId":${quote(
                    packId,
                )},"language":${quote(language)},"field":${quote(field)},"value":${quote(value)}}}""",
            )
        }

        fun messages(): List<JsonNode> =
            synchronized(lock) {
                output
                    .toString()
                    .trim()
                    .lines()
                    .filter { it.isNotBlank() }
                    .mapNotNull { line -> runCatching { mapper.readTree(line) }.getOrNull() }
            }

        fun latest(method: String): JsonNode = messages().last { it["method"]?.asText() == method }

        fun methodCount(method: String): Int = messages().count { it["method"]?.asText() == method }

        fun lastResponse(id: Int): JsonNode =
            messages()
                .last {
                    it["id"]?.asInt() == id && it["method"] == null
                }.path("result")

        fun waitForResponse(id: Int): JsonNode = waitFor { lastResponseOrNull(id) }.path("result")

        fun waitForCandidate(
            uri: String,
            intent: String,
            predicate: (JsonNode) -> Boolean,
        ): JsonNode =
            waitFor {
                messages()
                    .filter { it["method"]?.asText() == "cint/publishCandidates" }
                    .flatMap { message ->
                        message.path("params").path("candidates").filter { candidate ->
                            candidate.path("intents").any { it.asText() == intent } &&
                                candidate.path("path").asText().isNotEmpty() &&
                                predicate(candidate)
                        }
                    }.lastOrNull()
            }

        fun waitFor(
            timeoutMs: Long = 3000,
            predicate: () -> JsonNode?,
        ): JsonNode {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (System.nanoTime() < deadline) {
                predicate()?.let { return it }
                Thread.sleep(10)
            }
            error("timed out waiting for condition; messages=${messages()}")
        }

        private fun lastResponseOrNull(id: Int): JsonNode? =
            messages().lastOrNull {
                it["id"]?.asInt() == id && it["method"] == null
            }

        private fun quote(value: String): String = mapper.writeValueAsString(value)
    }

    private class Peer(
        private val server: LiveServer,
    ) {
        private val mapper = jacksonObjectMapper()
        private val output = StringWriter()
        private val writer = BufferedWriter(output)

        fun send(message: String) {
            server.handleMessage(message, writer)
        }

        fun open(
            uri: String,
            text: String,
            version: Int,
        ) {
            send(
                """{"jsonrpc":"2.0","id":11,"method":"textDocument/didOpen","params":{"uri":${quote(
                    uri,
                )},"text":${quote(text)},"version":$version,"languageId":"kotlin"}}""",
            )
        }

        fun change(
            uri: String,
            text: String,
            version: Int,
        ) {
            send(
                """{"jsonrpc":"2.0","method":"textDocument/didChange","params":{"uri":${quote(
                    uri,
                )},"text":${quote(text)},"version":$version,"languageId":"kotlin"}}""",
            )
        }

        fun suggest(
            uri: String,
            version: Int,
            id: Int,
            requestId: String,
        ) {
            send(
                """{"jsonrpc":"2.0","id":$id,"method":"cint/suggest","params":{"uri":${quote(
                    uri,
                )},"version":$version,"requestId":${quote(requestId)}}}""",
            )
        }

        fun decide(
            uri: String,
            version: Int,
            id: Int,
            candidateId: String,
            decision: String,
        ) {
            send(
                """{"jsonrpc":"2.0","id":$id,"method":"cint/candidates/decide","params":{"uri":${quote(
                    uri,
                )},"version":$version,"candidateId":${quote(candidateId)},"decision":${quote(decision)}}}""",
            )
        }

        fun messages(): List<JsonNode> =
            output
                .toString()
                .trim()
                .lines()
                .filter { it.isNotBlank() }
                .mapNotNull { line -> runCatching { mapper.readTree(line) }.getOrNull() }

        fun latest(method: String): JsonNode = messages().last { it["method"]?.asText() == method }

        fun lastResponse(id: Int): JsonNode =
            messages()
                .last {
                    it["id"]?.asInt() == id && it["method"] == null
                }.path("result")

        fun waitForResponse(id: Int): JsonNode {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (System.nanoTime() < deadline) {
                val response =
                    messages().lastOrNull {
                        it["id"]?.asInt() == id && it["method"] == null
                    }
                if (response != null) return response.path("result")
                Thread.sleep(10)
            }
            error("timed out waiting for response $id; messages=${messages()}")
        }

        fun waitForCandidate(
            uri: String,
            intent: String,
            predicate: (JsonNode) -> Boolean,
        ): JsonNode {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (System.nanoTime() < deadline) {
                val candidate =
                    messages()
                        .filter { it["method"]?.asText() == "cint/publishCandidates" }
                        .flatMap { message ->
                            message.path("params").path("candidates").filter { candidate ->
                                candidate.path("intents").any { it.asText() == intent } &&
                                    candidate.path("path").asText().isNotEmpty() &&
                                    predicate(candidate)
                            }
                        }.lastOrNull()
                if (candidate != null) return candidate
                Thread.sleep(10)
            }
            error("timed out waiting for candidate on $uri; messages=${messages()}")
        }

        private fun quote(value: String): String = mapper.writeValueAsString(value)
    }
}
