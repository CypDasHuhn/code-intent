package dev.cint.server.suggest

import dev.cint.api.CandidateEmit
import dev.cint.server.SuggestionSettings
import dev.cint.server.suggest.support.gapSource
import dev.cint.server.suggest.support.harness
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

class LiveSuggestionCancelTest {
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
}
