package dev.cint.server.suggest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.cint.api.CandidateEmit
import dev.cint.core.candidate.CandidateIdentity
import dev.cint.core.restriction.AiRestrictionRecord
import dev.cint.core.restriction.AiRestrictionResolver
import dev.cint.core.restriction.AiRestrictionsFile
import dev.cint.core.restriction.RestrictionOperation
import dev.cint.core.support.hashing.contentHash
import dev.cint.core.user.UserAnnotation
import dev.cint.core.user.UserAnnotationResolver
import dev.cint.core.user.UserAnnotationsFile
import dev.cint.server.SuggestionSettings
import dev.cint.server.live.DocumentKey
import dev.cint.server.live.PublishedProposal
import dev.cint.server.live.analyzeForTest
import dev.cint.server.suggest.support.Harness
import dev.cint.server.suggest.support.candidates
import dev.cint.server.suggest.support.harness
import dev.cint.suggest.SuggestionProviderRegistry
import dev.cint.suggest.SuggestionService
import dev.cint.suggest.model.CandidateContext
import dev.cint.suggest.model.CandidateProposal
import dev.cint.suggest.model.ProposalOperation
import dev.cint.suggest.model.ProposalOperationKind
import dev.cint.suggest.provider.IntentSuggestionProvider
import dev.cint.suggest.session.AgentRequest
import dev.cint.suggest.session.AgentTurn
import dev.cint.suggest.session.AnnotationSessionProvider
import dev.cint.suggest.session.ToolCall
import java.io.BufferedWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveRestrictionProposalTest {
    private val mapper = jacksonObjectMapper()
    private val source =
        """
        private val log = LoggerFactory.getLogger(Foo::class.java)
        fun f() {
            val message = "x"
            log.warn(message)
        }
        """.trimIndent()

    private val multiSource =
        """
        private val log = LoggerFactory.getLogger(Foo::class.java)
        fun a() {
            val message = "a"
            log.warn(message)
        }
        fun b() {
            val message = "b"
            log.warn(message)
        }
        """.trimIndent()

    private val candidateAndProposalSource =
        """
        private val log = LoggerFactory.getLogger(Foo::class.java)
        fun gap(): Boolean {
            // cint: validation

            if (true) {
                work()
            }
            return false
        }
        fun f() {
            log.warn(message)
        }
        """.trimIndent()

    private val addAndProposalSource =
        """
        private val log = LoggerFactory.getLogger(Foo::class.java)
        fun f() {
            val message = "x"
            log.warn(message)
        }
        fun g(text: String): String? {
            val match = Regex("cint").find(text) ?: return null
            return match.groupValues[1]
        }
        """.trimIndent()

    private val variantSource =
        """
        private val log = LoggerFactory.getLogger(Foo::class.java)
        fun g() {
            val other = "b"
            log.warn(other)
        }
        """.trimIndent()

    @Test
    fun confirmAppliesRemoveAndAddPersistsAndRemovalRestoresTheOriginal() {
        val root = Files.createTempDirectory("cint-live-restriction")
        try {
            val provider = RestrictionProvider()
            val harness = harness(root, suggestionSettings = settings(), service = service(provider))
            harness.initialize()
            assertTrue(
                harness
                    .initializeResult()
                    .path("suggestions")
                    .path("restrictions")
                    .asBoolean(),
            )
            val uri = root.resolve("Sample.kt").toString()
            harness.open(uri, source, version = 1)
            assertTrue(
                harness.server.analyzeForTest(uri, source, "kotlin").any { "LOGGING" in it.intents },
                "baseline has no LOGGING annotation",
            )
            harness.suggest(uri, version = 1, id = 2, requestId = "restriction")
            val suggestion = harness.waitForResponse(2)
            assertTrue(suggestion.path("accepted").asBoolean(), suggestion.toString())
            assertEquals(1, provider.calls.get())
            assertTrue(provider.sawLogging.get(), "provider never saw a LOGGING annotation")
            assertEquals(1, suggestion.path("restrictions").asInt())

            val proposal = proposalGroup(harness.latest("cint/publishCandidates"))
            val proposalId = proposal.path("proposalId").asText()
            assertTrue(proposalId.isNotBlank())
            assertEquals(
                listOf("REMOVE", "ADD"),
                proposal.path("operations").map { it.path("kind").asText() },
            )
            assertEquals(
                setOf("LOGGING"),
                proposal
                    .path("operations")
                    .filter {
                        it.path("kind").asText() == "REMOVE"
                    }.map { it.path("intent").asText() }
                    .toSet(),
            )
            assertEquals(
                setOf("SEARCH"),
                proposal
                    .path("operations")
                    .filter {
                        it.path("kind").asText() == "ADD"
                    }.map { it.path("intent").asText() }
                    .toSet(),
            )

            harness.send(
                """{"jsonrpc":"2.0","id":3,"method":"cint/candidates/decide","params":{"uri":${quote(
                    uri,
                )},"version":1,"proposalId":${quote(proposalId)},"decision":"confirm"}}""",
            )
            assertEquals("confirm", harness.waitForResponse(3).path("decision").asText())

            val applied = waitForTags(harness) { tags -> tags.sources().contains("ai-confirmed") }
            assertFalse(applied.anyAnnotation { "LOGGING" in it.intents() })
            val replacement = applied.annotationWithSource("ai-confirmed")
            assertTrue("SEARCH" in replacement.intents())
            assertTrue(
                replacement
                    .path("provenance")
                    .any {
                        it.path("rule").asText() == "core:ai-confirmed" &&
                            it.path("provider").asText() == "deepseek" &&
                            it.path("model").asText() == "deepseek-chat" &&
                            it.path("promptVersion").asText() == "test-prompt-v1"
                    },
            )
            assertTrue(
                applied
                    .annotations()
                    .none {
                        it.path("role").asText() == "derivative-use" &&
                            "LOGGING" in it.intents()
                    },
            )

            val record = AiRestrictionsFile.load(root).single()
            assertEquals(proposalId, record.id)
            assertEquals("Sample.kt", record.path)
            assertEquals("f", record.symbol)
            assertEquals("deepseek", record.provider)
            assertEquals("deepseek-chat", record.model)
            assertEquals("test-prompt-v1", record.promptVersion)
            assertEquals(setOf("LOGGING"), record.remove.map { it.intent }.toSet())
            assertEquals(setOf("SEARCH"), record.add.map { it.intent }.toSet())

            val restarted = harness(root, suggestionSettings = settings(), service = service(provider))
            restarted.initialize()
            restarted.open(uri, source, version = 1)
            val restored = waitForTags(restarted) { tags -> tags.sources().contains("ai-confirmed") }
            assertFalse(restored.anyAnnotation { "LOGGING" in it.intents() })
            assertEquals("ai-confirmed", restored.annotationWithSource("ai-confirmed").path("source").asText())
            assertTrue("SEARCH" in restored.annotationWithSource("ai-confirmed").intents())

            restarted.send(
                """{"jsonrpc":"2.0","id":4,"method":"cint/restrictions/list","params":{"uri":${quote(uri)}}}""",
            )
            val listed = restarted.waitForResponse(4).path("restrictions")
            assertEquals(1, listed.size())
            assertEquals("active", listed[0].path("status").asText())
            assertEquals("f", listed[0].path("symbol").asText())
            assertEquals("deepseek", listed[0].path("provider").asText())
            assertEquals("deepseek-chat", listed[0].path("model").asText())
            assertEquals("test-prompt-v1", listed[0].path("promptVersion").asText())
            assertTrue(listed[0].path("unitFingerprint").asText().isNotBlank())
            assertTrue(listed[0].path("unitFingerprint").asText() != listed[0].path("contentHash").asText())

            restarted.send(
                """{"jsonrpc":"2.0","id":5,"method":"cint/restrictions/remove","params":{"id":${quote(proposalId)}}}""",
            )
            assertTrue(restarted.waitForResponse(5).path("accepted").asBoolean())
            val reanalyzed = waitForTags(restarted) { tags -> tags.anyAnnotation { "LOGGING" in it.intents() } }
            assertTrue(reanalyzed.anyAnnotation { "LOGGING" in it.intents() })
            assertFalse(reanalyzed.sources().contains("ai-confirmed"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun deniedProposalIsSuppressedAcrossAnotherSuggestPass() {
        val root = Files.createTempDirectory("cint-live-restriction-deny")
        try {
            val provider = RestrictionProvider()
            val harness = harness(root, suggestionSettings = settings(), service = service(provider))
            harness.initialize()
            val uri = root.resolve("Sample.kt").toString()
            harness.open(uri, source, version = 1)
            harness.suggest(uri, version = 1, id = 2, requestId = "first")
            assertTrue(harness.waitForResponse(2).path("accepted").asBoolean())
            val proposalId = proposalGroup(harness.latest("cint/publishCandidates")).path("proposalId").asText()

            harness.send(
                """{"jsonrpc":"2.0","id":3,"method":"cint/candidates/decide","params":{"uri":${quote(
                    uri,
                )},"version":1,"proposalId":${quote(proposalId)},"decision":"reject"}}""",
            )
            assertEquals("reject", harness.waitForResponse(3).path("decision").asText())
            assertEquals(0, proposalGroups(harness.latest("cint/publishCandidates")).size)

            harness.suggest(uri, version = 1, id = 4, requestId = "second")
            val second = harness.waitForResponse(4)
            assertTrue(second.path("accepted").asBoolean())
            assertEquals(0, second.path("restrictions").asInt())
            assertEquals(0, proposalGroups(harness.latest("cint/publishCandidates")).size)
            assertFalse(Files.exists(AiRestrictionsFile.path(root)))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun confirmingOneProposalPreservesTheOtherPendingGroup() {
        val root = Files.createTempDirectory("cint-live-restriction-multi")
        try {
            val provider = MultiRestrictionProvider()
            val harness = harness(root, suggestionSettings = settings(), service = service(provider))
            harness.initialize()
            val uri = root.resolve("Sample.kt").toString()
            harness.open(uri, multiSource, version = 1)
            harness.suggest(uri, version = 1, id = 2, requestId = "multi")
            val response = harness.waitForResponse(2)
            assertTrue(response.path("accepted").asBoolean(), response.toString())
            assertEquals(2, response.path("restrictions").asInt())
            val ids = proposalGroups(harness.latest("cint/publishCandidates")).map { it.path("proposalId").asText() }
            assertEquals(2, ids.size)

            harness.send(
                """{"jsonrpc":"2.0","id":3,"method":"cint/candidates/decide","params":{"uri":${quote(
                    uri,
                )},"version":1,"proposalId":${quote(ids.first())},"decision":"confirm"}}""",
            )
            assertEquals("confirm", harness.waitForResponse(3).path("decision").asText())

            val remaining =
                harness.waitFor {
                    harness
                        .latestOrNull("cint/publishCandidates")
                        ?.let { message ->
                            proposalGroups(message).firstOrNull { it.path("proposalId").asText() != ids.first() }
                        }
                }
            assertEquals(ids.last(), remaining.path("proposalId").asText())

            harness.send(
                """{"jsonrpc":"2.0","id":4,"method":"cint/candidates/decide","params":{"uri":${quote(
                    uri,
                )},"version":1,"proposalId":${quote(ids.last())},"decision":"confirm"}}""",
            )
            assertEquals("confirm", harness.waitForResponse(4).path("decision").asText())
            assertEquals(2, AiRestrictionsFile.load(root).size)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun suppressedProposalIsNotCountedInTheNextSuggestResponse() {
        val root = Files.createTempDirectory("cint-live-restriction-count")
        try {
            val provider = RestrictionProvider()
            val harness = harness(root, suggestionSettings = settings(), service = service(provider))
            harness.initialize()
            val uri = root.resolve("Sample.kt").toString()
            harness.open(uri, source, version = 1)
            harness.suggest(uri, version = 1, id = 2, requestId = "first")
            assertTrue(harness.waitForResponse(2).path("accepted").asBoolean())
            val proposalId = proposalGroup(harness.latest("cint/publishCandidates")).path("proposalId").asText()

            harness.send(
                """{"jsonrpc":"2.0","id":3,"method":"cint/candidates/decide","params":{"uri":${quote(
                    uri,
                )},"version":1,"proposalId":${quote(proposalId)},"decision":"skip"}}""",
            )
            assertTrue(harness.waitForResponse(3).path("accepted").asBoolean())

            harness.suggest(uri, version = 1, id = 4, requestId = "second")
            val response = harness.waitForResponse(4)
            assertEquals(0, response.path("restrictions").asInt())
            assertEquals(0, proposalGroups(harness.latest("cint/publishCandidates")).size)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun restrictionsDisabledHidesProposalsAndTheInspector() {
        val root = Files.createTempDirectory("cint-live-restriction-disabled")
        try {
            val provider = RestrictionProvider()
            val harness =
                harness(
                    root,
                    suggestionSettings = settings().copy(restrictionsEnabled = false),
                    service = service(provider),
                )
            harness.initialize()
            assertFalse(
                harness
                    .initializeResult()
                    .path("suggestions")
                    .path("restrictions")
                    .asBoolean(),
            )
            val uri = root.resolve("Sample.kt").toString()
            harness.open(uri, source, version = 1)
            harness.suggest(uri, version = 1, id = 2, requestId = "disabled")
            val response = harness.waitForResponse(2)
            assertTrue(response.path("accepted").asBoolean())
            assertEquals(0, response.path("restrictions").asInt())
            assertEquals(0, proposalGroups(harness.latest("cint/publishCandidates")).size)
            assertFalse(Files.exists(AiRestrictionsFile.path(root)))

            harness.send(
                """{"jsonrpc":"2.0","id":3,"method":"cint/restrictions/list","params":{"uri":${quote(uri)}}}""",
            )
            val error =
                harness
                    .messages()
                    .last { it["id"]?.asInt() == 3 }
                    .path("error")
                    .path("message")
                    .asText()
            assertTrue(error.contains("disabled"), error)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun mixedUserAndPackSpanCannotBeProposedForRemoval() {
        val root = Files.createTempDirectory("cint-live-restriction-mixed")
        try {
            val callStart = source.indexOf("log.warn(message)")
            val callEnd = callStart + "log.warn(message)".length
            val span = UserAnnotationResolver.spanFor(source, callStart, callEnd) ?: error("no span")
            UserAnnotationsFile.save(
                root,
                listOf(
                    UserAnnotation(
                        path = "Sample.kt",
                        span = span,
                        intents = setOf("LOGGING"),
                    ),
                ),
            )
            val provider = RestrictionProvider()
            val harness = harness(root, suggestionSettings = settings(), service = service(provider))
            harness.initialize()
            val uri = root.resolve("Sample.kt").toString()
            harness.open(uri, source, version = 1)
            assertTrue(
                harness.server.analyzeForTest(uri, source, "kotlin").any {
                    "LOGGING" in it.intents && it.source == "mixed"
                },
            )
            harness.suggest(uri, version = 1, id = 2, requestId = "mixed")
            val response = harness.waitForResponse(2)
            assertTrue(response.path("accepted").asBoolean())
            assertEquals(0, response.path("restrictions").asInt())
            assertEquals(0, proposalGroups(harness.latest("cint/publishCandidates")).size)
            assertFalse(Files.exists(AiRestrictionsFile.path(root)))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun removeOnlyGroupPersistsAndReloads() {
        val root = Files.createTempDirectory("cint-live-restriction-remove-only")
        try {
            val provider = RemoveOnlyProvider()
            val harness = harness(root, suggestionSettings = settings(), service = service(provider))
            harness.initialize()
            val uri = root.resolve("Sample.kt").toString()
            harness.open(uri, source, version = 1)
            harness.suggest(uri, version = 1, id = 2, requestId = "remove")
            assertTrue(harness.waitForResponse(2).path("accepted").asBoolean())
            val proposal = proposalGroup(harness.latest("cint/publishCandidates"))
            assertEquals(
                listOf("REMOVE"),
                proposal.path("operations").map { it.path("kind").asText() },
            )
            val proposalId = proposal.path("proposalId").asText()
            harness.send(
                """{"jsonrpc":"2.0","id":3,"method":"cint/candidates/decide","params":{"uri":${quote(
                    uri,
                )},"version":1,"proposalId":${quote(proposalId)},"decision":"confirm"}}""",
            )
            assertEquals("confirm", harness.waitForResponse(3).path("decision").asText())
            val applied = waitForTags(harness) { tags -> tags.annotations().none { "LOGGING" in it.intents() } }
            assertFalse(applied.anyAnnotation { "LOGGING" in it.intents() })
            assertEquals(emptyList(), AiRestrictionsFile.load(root).single().add)

            val restarted = harness(root, suggestionSettings = settings(), service = service(provider))
            restarted.initialize()
            restarted.open(uri, source, version = 1)
            val restored = waitForTags(restarted) { tags -> tags.annotations().none { "LOGGING" in it.intents() } }
            assertFalse(restored.anyAnnotation { "LOGGING" in it.intents() })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun candidateConfirmDoesNotClearPendingProposalGroups() {
        val root = Files.createTempDirectory("cint-live-restriction-candidate")
        try {
            val provider = RestrictionProvider()
            val harness = harness(root, suggestionSettings = settings(), service = service(provider))
            harness.initialize()
            val uri = root.resolve("Sample.kt").toString()
            harness.open(uri, candidateAndProposalSource, version = 1)
            harness.suggest(uri, version = 1, id = 2, requestId = "candidate")
            assertTrue(harness.waitForResponse(2).path("accepted").asBoolean())
            val published = harness.latest("cint/publishCandidates")
            val proposalId = proposalGroup(published).path("proposalId").asText()
            val candidateId = candidates(published, "VALIDATION").single()["candidateId"].asText()

            harness.send(
                """{"jsonrpc":"2.0","id":3,"method":"cint/candidates/decide","params":{"uri":${quote(
                    uri,
                )},"version":1,"candidateId":${quote(candidateId)},"decision":"confirm"}}""",
            )
            assertEquals("confirm", harness.waitForResponse(3).path("decision").asText())

            val remaining =
                harness.waitFor {
                    harness
                        .latestOrNull("cint/publishCandidates")
                        ?.let { message -> proposalGroups(message).firstOrNull() }
                }
            assertEquals(proposalId, remaining.path("proposalId").asText())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun aiAddOnlyCandidateSurvivesProposalDecision() {
        val root = Files.createTempDirectory("cint-live-restriction-add-survives")
        try {
            val provider = AddAndProposalProvider()
            val harness = harness(root, suggestionSettings = settings(), service = service(provider))
            harness.initialize()
            val uri = root.resolve("Sample.kt").toString()
            harness.open(uri, addAndProposalSource, version = 1)
            harness.suggest(uri, version = 1, id = 2, requestId = "add-and-proposal")
            assertTrue(harness.waitForResponse(2).path("accepted").asBoolean())
            val published = harness.latest("cint/publishCandidates")
            val proposalId = proposalGroup(published).path("proposalId").asText()
            val candidateId =
                candidates(published, "SEARCH")
                    .first { it["source"].asText() == "ai:deepseek" }["candidateId"]
                    .asText()

            harness.send(
                """{"jsonrpc":"2.0","id":3,"method":"cint/candidates/decide","params":{"uri":${quote(
                    uri,
                )},"version":1,"proposalId":${quote(proposalId)},"decision":"confirm"}}""",
            )
            assertEquals("confirm", harness.waitForResponse(3).path("decision").asText())

            val republished =
                harness.waitFor {
                    harness.latestOrNull("cint/publishCandidates")?.let { message ->
                        candidates(message, "SEARCH")
                            .firstOrNull { it["candidateId"].asText() == candidateId }
                    }
                }
            assertEquals(candidateId, republished["candidateId"].asText())

            harness.send(
                """{"jsonrpc":"2.0","id":4,"method":"cint/candidates/decide","params":{"uri":${quote(
                    uri,
                )},"version":1,"candidateId":${quote(candidateId)},"decision":"confirm"}}""",
            )
            assertEquals("confirm", harness.waitForResponse(4).path("decision").asText())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun staleCarriedProposalIsDroppedAndCannotBeConfirmed() {
        val root = Files.createTempDirectory("cint-live-restriction-stale-carry")
        try {
            val provider = OverlappingRestrictionProvider()
            val harness = harness(root, suggestionSettings = settings(), service = service(provider))
            harness.initialize()
            val uri = root.resolve("Sample.kt").toString()
            harness.open(uri, source, version = 1)
            harness.suggest(uri, version = 1, id = 2, requestId = "overlap")
            assertTrue(harness.waitForResponse(2).path("accepted").asBoolean())
            val ids = proposalGroups(harness.latest("cint/publishCandidates")).map { it.path("proposalId").asText() }
            assertEquals(2, ids.size)

            harness.send(
                """{"jsonrpc":"2.0","id":3,"method":"cint/candidates/decide","params":{"uri":${quote(
                    uri,
                )},"version":1,"proposalId":${quote(ids.first())},"decision":"confirm"}}""",
            )
            assertEquals("confirm", harness.waitForResponse(3).path("decision").asText())
            assertEquals(0, proposalGroups(harness.latest("cint/publishCandidates")).size)

            harness.send(
                """{"jsonrpc":"2.0","id":4,"method":"cint/candidates/decide","params":{"uri":${quote(
                    uri,
                )},"version":1,"proposalId":${quote(ids.last())},"decision":"confirm"}}""",
            )
            assertTrue(harness.waitForResponse(4).path("missing").asBoolean())
            assertEquals(1, AiRestrictionsFile.load(root).size)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun differentClientsKeepTheirOwnProposalsAndProtocolThreeSeesNoGroups() {
        val root = Files.createTempDirectory("cint-live-restriction-clients")
        try {
            val provider = AnyLogRestrictionProvider()
            val harness = harness(root, suggestionSettings = settings(), service = service(provider))
            val uri = root.resolve("Sample.kt").toString()
            harness.initialize()
            harness.open(uri, source, version = 1)
            harness.suggest(uri, version = 1, id = 2, requestId = "a")
            assertTrue(harness.waitForResponse(2).path("accepted").asBoolean())
            val firstId =
                proposalGroups(harness.latest("cint/publishCandidates"))
                    .first()
                    .path("proposalId")
                    .asText()

            val second = TestClient()
            second.send(harness, initializeMessage(protocol = 4))
            second.send(harness, openMessage(uri, variantSource, version = 1))
            second.send(harness, suggestMessage(uri, version = 1, id = 6, requestId = "b"))
            assertTrue(second.waitForResult(mapper, 6).path("accepted").asBoolean())
            val secondIds =
                proposalGroups(second.latest(mapper, "cint/publishCandidates"))
                    .map { it.path("proposalId").asText() }
            assertTrue(secondIds.isNotEmpty())
            val secondId = secondIds.first()
            assertTrue(secondId != firstId)

            val third = TestClient()
            third.send(harness, initializeMessage(protocol = 3))
            third.send(harness, openMessage(uri, source, version = 1))

            harness.send(
                """{"jsonrpc":"2.0","id":3,"method":"cint/candidates/decide","params":{"uri":${quote(
                    uri,
                )},"version":1,"proposalId":${quote(firstId)},"decision":"confirm"}}""",
            )
            assertEquals("confirm", harness.waitForResponse(3).path("decision").asText())

            val secondAfter =
                second.waitFor {
                    second.latestOrNull(mapper, "cint/publishCandidates")?.let { message ->
                        proposalGroups(message).firstOrNull { it.path("proposalId").asText() == secondId }
                    }
                }
            assertEquals(secondId, secondAfter.path("proposalId").asText())

            val thirdAfter =
                third.waitFor {
                    third.latestOrNull(mapper, "cint/publishCandidates")?.takeIf {
                        it
                            .path("params")
                            .path("groups")
                            .isEmpty
                            .not()
                    }
                }
            assertTrue(proposalGroups(thirdAfter).isEmpty())
            assertTrue(thirdAfter.path("params").path("groups").all { it.path("operations").isMissingNode })

            second.send(harness, decideMessage(uri, version = 1, id = 7, proposalId = secondId, decision = "confirm"))
            assertEquals("confirm", second.waitForResult(mapper, 7).path("decision").asText())
            assertEquals(2, AiRestrictionsFile.load(root).size)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun disabledRestrictionsRejectAConfirmEvenWhenAProposalIsAlreadyCached() {
        val root = Files.createTempDirectory("cint-live-restriction-confirm-disabled")
        try {
            val provider = RestrictionProvider()
            val harness =
                harness(
                    root,
                    suggestionSettings = settings().copy(restrictionsEnabled = false),
                    service = service(provider),
                )
            val client = TestClient()
            client.send(harness, initializeMessage(protocol = 4))
            val uri = root.resolve("Sample.kt").toString()
            client.send(harness, openMessage(uri, source, version = 1))
            val start = source.indexOf("log.warn(message)")
            val end = start + "log.warn(message)".length
            val span = UserAnnotationResolver.spanFor(source, start, end) ?: error("no span")
            val proposal =
                CandidateProposal(
                    proposalId = "cached-proposal",
                    anchorId = "cached-anchor",
                    path = "Sample.kt",
                    contentHash = CandidateIdentity.contentHash("log.warn(message)"),
                    unitId = "u0",
                    span = span,
                    startByte = start,
                    endByte = end,
                    operations =
                        listOf(
                            ProposalOperation(ProposalOperationKind.REMOVE, "LOGGING", start, end, span, 0.7),
                            ProposalOperation(ProposalOperationKind.ADD, "SEARCH", start, end, span, 0.7),
                        ),
                    provider = "deepseek",
                    model = "deepseek-chat",
                    promptVersion = "test-prompt-v1",
                )
            harness.server.publishedProposals[DocumentKey(client.writer, uri)] =
                listOf(
                    PublishedProposal(
                        proposalId = proposal.proposalId,
                        anchorId = proposal.anchorId,
                        path = proposal.path,
                        contentHash = proposal.contentHash,
                        version = 1,
                        analysisRevision = 1,
                        proposal = proposal,
                    ),
                )
            client.send(harness, decideMessage(uri, version = 1, id = 8, proposalId = proposal.proposalId, decision = "confirm"))
            val response = client.waitForResult(mapper, 8)
            assertTrue(response.path("disabled").asBoolean(), response.toString())
            assertFalse(Files.exists(AiRestrictionsFile.path(root)))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun restrictionWithNoApplicableRemovalIsListedDormant() {
        val root = Files.createTempDirectory("cint-live-restriction-dormant-status")
        try {
            val harness = harness(root, suggestionSettings = settings())
            harness.initialize()
            val uri = root.resolve("Sample.kt").toString()
            harness.open(uri, source, version = 1)
            val start = source.indexOf("log.warn(message)")
            val end = start + "log.warn(message)".length
            val span = UserAnnotationResolver.spanFor(source, start, end) ?: error("no span")
            val record =
                AiRestrictionRecord(
                    id = "inert",
                    path = "Sample.kt",
                    span = span,
                    contentHash = contentHash("log.warn(message)"),
                    remove = listOf(RestrictionOperation("LOGGING", 0, "log.warn".length)),
                    add = listOf(RestrictionOperation("SEARCH", 0, "log.warn".length)),
                    symbol = "f",
                    unitFingerprint = null,
                    provider = "deepseek",
                    model = "deepseek-chat",
                    promptVersion = "test-prompt-v1",
                )
            AiRestrictionsFile.add(root, record)

            harness.send(
                """{"jsonrpc":"2.0","id":3,"method":"cint/restrictions/list","params":{"uri":${quote(uri)}}}""",
            )
            val listed = harness.waitForResponse(3).path("restrictions")
            assertEquals("dormant", listed[0].path("status").asText())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun concurrentDecisionsConfirmAtMostOnce() {
        val root = Files.createTempDirectory("cint-live-restriction-concurrent")
        try {
            val provider = RestrictionProvider()
            val harness = harness(root, suggestionSettings = settings(), service = service(provider))
            val uri = root.resolve("Sample.kt").toString()
            harness.initialize()
            harness.open(uri, source, version = 1)
            harness.suggest(uri, version = 1, id = 2, requestId = "a")
            assertTrue(harness.waitForResponse(2).path("accepted").asBoolean())
            val proposalId = proposalGroup(harness.latest("cint/publishCandidates")).path("proposalId").asText()

            val second = TestClient()
            second.send(harness, initializeMessage(protocol = 4))
            second.send(harness, openMessage(uri, source, version = 1))
            second.send(harness, suggestMessage(uri, version = 1, id = 3, requestId = "b"))
            assertTrue(second.waitForResult(mapper, 3).path("accepted").asBoolean())

            val start = CountDownLatch(1)
            val firstThread =
                Thread {
                    start.await(2, TimeUnit.SECONDS)
                    harness.send(
                        """{"jsonrpc":"2.0","id":10,"method":"cint/candidates/decide","params":{"uri":${quote(
                            uri,
                        )},"version":1,"proposalId":${quote(proposalId)},"decision":"confirm"}}""",
                    )
                }
            val secondThread =
                Thread {
                    start.await(2, TimeUnit.SECONDS)
                    second.send(
                        harness,
                        decideMessage(uri, version = 1, id = 11, proposalId = proposalId, decision = "confirm"),
                    )
                }
            firstThread.start()
            secondThread.start()
            start.countDown()
            firstThread.join(5_000)
            secondThread.join(5_000)

            val firstResult = harness.waitForResponse(10)
            val secondResult = second.waitForResult(mapper, 11)
            val accepted = listOf(firstResult, secondResult).count { it.path("accepted").asBoolean() }
            assertEquals(1, accepted)
            assertTrue(
                listOf(firstResult, secondResult).any {
                    it.path("missing").asBoolean() || it.path("stale").asBoolean()
                },
            )
            assertEquals(1, AiRestrictionsFile.load(root).size)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun restrictionRemovalPreservesPendingProposalGroups() {
        val root = Files.createTempDirectory("cint-live-restriction-remove-carry")
        try {
            val provider = RestrictionProvider()
            val harness = harness(root, suggestionSettings = settings(), service = service(provider))
            harness.initialize()
            val uri = root.resolve("Sample.kt").toString()
            harness.open(uri, source, version = 1)
            harness.suggest(uri, version = 1, id = 2, requestId = "pending")
            assertTrue(harness.waitForResponse(2).path("accepted").asBoolean())
            val proposalId = proposalGroup(harness.latest("cint/publishCandidates")).path("proposalId").asText()

            AiRestrictionsFile.add(
                root,
                AiRestrictionRecord(
                    id = "other",
                    path = "Other.kt",
                    span = dev.cint.api.Span(0, 0, 0, 1),
                    contentHash = "abc",
                    remove = listOf(RestrictionOperation("LOGGING", 0, 1)),
                    add = emptyList(),
                ),
            )
            harness.send(
                """{"jsonrpc":"2.0","id":3,"method":"cint/restrictions/remove","params":{"id":"other"}}""",
            )
            assertTrue(harness.waitForResponse(3).path("accepted").asBoolean())
            val republished =
                harness.waitFor {
                    harness.latestOrNull("cint/publishCandidates")?.let { message ->
                        proposalGroups(message).firstOrNull { it.path("proposalId").asText() == proposalId }
                    }
                }
            assertEquals(proposalId, republished.path("proposalId").asText())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun futureVersionFileErrorIsCorrelatedToTheRequestId() {
        val root = Files.createTempDirectory("cint-live-restriction-future-id")
        try {
            val harness = harness(root, suggestionSettings = settings())
            harness.initialize()
            val path = AiRestrictionsFile.path(root)
            Files.createDirectories(path.parent)
            Files.writeString(
                path,
                """
                {"version":2,"restrictions":[{"id":"future","path":"Sample.kt","contentHash":"abc","anchor":{"startLine":0,"startCol":0,"endLine":0,"endCol":1},"remove":[{"intent":"LOGGING","startOffset":0,"endOffset":1}],"add":[]}]}
                """.trimIndent(),
            )
            harness.send(
                """{"jsonrpc":"2.0","id":9,"method":"cint/restrictions/remove","params":{"id":"future"}}""",
            )
            val response = harness.messages().last { it["id"]?.asInt() == 9 }
            assertTrue(
                response
                    .path("error")
                    .path("message")
                    .asText()
                    .contains("unsupported"),
            )
            assertEquals(9, response.path("id").asInt())
            assertTrue(Files.readString(path).contains("\"version\":2"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun settings(): SuggestionSettings =
        SuggestionSettings(
            enabled = true,
            provider = "deepseek",
            model = "deepseek-chat",
            discoveryEnabled = true,
            restrictionsEnabled = true,
            promptVersion = "test-prompt-v1",
            sessionTimeoutMs = 5_000,
            maxTokensPerRequest = 16_384,
        )

    private fun service(provider: IntentSuggestionProvider): SuggestionService =
        SuggestionService(SuggestionProviderRegistry(listOf(provider)))

    private fun quote(value: String): String = mapper.writeValueAsString(value)

    private fun proposalGroup(message: JsonNode): JsonNode = proposalGroups(message).single()

    private fun proposalGroups(message: JsonNode): List<JsonNode> = message.path("params").path("groups").filter { it.has("proposalId") }

    private fun waitForTags(
        harness: dev.cint.server.suggest.support.Harness,
        predicate: (JsonNode) -> Boolean,
    ): JsonNode =
        harness.waitFor {
            harness
                .messages()
                .filter { it["method"]?.asText() == "cint/publishTags" }
                .map { it.path("params") }
                .lastOrNull(predicate)
        }

    private fun JsonNode.annotations(): List<JsonNode> = path("annotations").toList()

    private fun JsonNode.intents(): Set<String> = path("intents").map { it.asText() }.toSet()

    private fun JsonNode.sources(): Set<String> = annotations().map { it.path("source").asText() }.toSet()

    private fun JsonNode.anyAnnotation(predicate: (JsonNode) -> Boolean): Boolean = annotations().any(predicate)

    private fun JsonNode.annotationWithSource(source: String): JsonNode = annotations().first { it.path("source").asText() == source }

    private class MultiRestrictionProvider :
        IntentSuggestionProvider,
        AnnotationSessionProvider {
        override val id: String = "deepseek"
        override val model: String = "deepseek-chat"
        override val defaultPromptVersion: String = "test-prompt-v1"
        private val mapper = jacksonObjectMapper()

        override fun suggest(context: CandidateContext): List<CandidateEmit> = emptyList()

        override fun complete(request: AgentRequest): AgentTurn {
            val verdicts =
                unitsOf(request).map { unit ->
                    val logging =
                        unit.path("existingAnnotations").firstOrNull { annotation ->
                            annotation.path("intents").any { it.asText() == "LOGGING" } &&
                                annotation.path("startByte").asInt() == unit.path("startByte").asInt() &&
                                annotation.path("endByte").asInt() == unit.path("endByte").asInt() &&
                                unit.path("text").asText() == "log.warn(message)"
                        }
                    if (logging == null) {
                        linkedMapOf("unitId" to unit.path("unitId").asText(), "verdict" to "none")
                    } else {
                        verdict(unit, logging, add = true)
                    }
                }
            return submit(mapper, verdicts)
        }
    }

    private class RemoveOnlyProvider :
        IntentSuggestionProvider,
        AnnotationSessionProvider {
        override val id: String = "deepseek"
        override val model: String = "deepseek-chat"
        override val defaultPromptVersion: String = "test-prompt-v1"
        private val mapper = jacksonObjectMapper()

        override fun suggest(context: CandidateContext): List<CandidateEmit> = emptyList()

        override fun complete(request: AgentRequest): AgentTurn {
            val verdicts =
                unitsOf(request).map { unit ->
                    val logging =
                        unit.path("existingAnnotations").firstOrNull { annotation ->
                            annotation.path("intents").any { it.asText() == "LOGGING" } &&
                                annotation.path("startByte").asInt() == unit.path("startByte").asInt() &&
                                annotation.path("endByte").asInt() == unit.path("endByte").asInt() &&
                                unit.path("text").asText() == "log.warn(message)"
                        }
                    if (logging == null) {
                        linkedMapOf("unitId" to unit.path("unitId").asText(), "verdict" to "none")
                    } else {
                        verdict(unit, logging, add = false)
                    }
                }
            return submit(mapper, verdicts)
        }
    }

    private class AddAndProposalProvider :
        IntentSuggestionProvider,
        AnnotationSessionProvider {
        override val id: String = "deepseek"
        override val model: String = "deepseek-chat"
        override val defaultPromptVersion: String = "test-prompt-v1"
        private val mapper = jacksonObjectMapper()

        override fun suggest(context: CandidateContext): List<CandidateEmit> = emptyList()

        override fun complete(request: AgentRequest): AgentTurn {
            val units = unitsOf(request)
            val findTarget =
                units
                    .filter { it.path("text").asText().contains("find(text)") }
                    .minByOrNull { it.path("text").asText().length }
            val verdicts =
                units.map { unit ->
                    val text = unit.path("text").asText()
                    val logging = loggingAnnotation(unit).takeIf { text == "log.warn(message)" }
                    when {
                        logging != null -> {
                            verdict(unit, logging, add = true)
                        }

                        unit === findTarget -> {
                            linkedMapOf(
                                "unitId" to unit.path("unitId").asText(),
                                "add" to
                                    listOf(
                                        linkedMapOf(
                                            "intent" to "SEARCH",
                                            "startByte" to unit.path("startByte").asInt(),
                                            "endByte" to unit.path("endByte").asInt(),
                                            "confidence" to 0.62,
                                            "rationale" to "regex find searches the text",
                                        ),
                                    ),
                            )
                        }

                        else -> {
                            linkedMapOf("unitId" to unit.path("unitId").asText(), "verdict" to "none")
                        }
                    }
                }
            return submit(mapper, verdicts)
        }
    }

    private class OverlappingRestrictionProvider :
        IntentSuggestionProvider,
        AnnotationSessionProvider {
        override val id: String = "deepseek"
        override val model: String = "deepseek-chat"
        override val defaultPromptVersion: String = "test-prompt-v1"
        private val mapper = jacksonObjectMapper()

        override fun suggest(context: CandidateContext): List<CandidateEmit> = emptyList()

        override fun complete(request: AgentRequest): AgentTurn {
            val verdicts =
                unitsOf(request).map { unit ->
                    val text = unit.path("text").asText()
                    val logging = loggingAnnotation(unit)
                    if (logging == null || (text != "log.warn(message)" && text != "message")) {
                        linkedMapOf("unitId" to unit.path("unitId").asText(), "verdict" to "none")
                    } else {
                        verdict(unit, logging, add = true)
                    }
                }
            return submit(mapper, verdicts)
        }
    }

    private class AnyLogRestrictionProvider :
        IntentSuggestionProvider,
        AnnotationSessionProvider {
        override val id: String = "deepseek"
        override val model: String = "deepseek-chat"
        override val defaultPromptVersion: String = "test-prompt-v1"
        private val mapper = jacksonObjectMapper()

        override fun suggest(context: CandidateContext): List<CandidateEmit> = emptyList()

        override fun complete(request: AgentRequest): AgentTurn {
            val verdicts =
                unitsOf(request).map { unit ->
                    val logging = loggingAnnotation(unit).takeIf { unit.path("text").asText().startsWith("log.warn") }
                    if (logging == null) {
                        linkedMapOf("unitId" to unit.path("unitId").asText(), "verdict" to "none")
                    } else {
                        verdict(unit, logging, add = true)
                    }
                }
            return submit(mapper, verdicts)
        }
    }

    private class RestrictionProvider :
        IntentSuggestionProvider,
        AnnotationSessionProvider {
        override val id: String = "deepseek"
        override val model: String = "deepseek-chat"
        override val defaultPromptVersion: String = "test-prompt-v1"
        private val mapper = jacksonObjectMapper()
        val calls: AtomicInteger = AtomicInteger(0)
        val sawLogging: AtomicBoolean = AtomicBoolean(false)

        override fun suggest(context: CandidateContext): List<CandidateEmit> = emptyList()

        override fun complete(request: AgentRequest): AgentTurn {
            calls.incrementAndGet()
            val units = unitsOf(request)
            val target =
                units.firstOrNull { unit ->
                    unit.path("text").asText() == "log.warn(message)" &&
                        unit.path("existingAnnotations").any { annotation ->
                            annotation.path("intents").any { it.asText() == "LOGGING" } &&
                                annotation.path("startByte").asInt() == unit.path("startByte").asInt() &&
                                annotation.path("endByte").asInt() == unit.path("endByte").asInt()
                        }
                }
            if (target != null) sawLogging.set(true)
            val verdicts =
                units.map { unit ->
                    val unitId = unit.path("unitId").asText()
                    if (unit !== target) {
                        linkedMapOf("unitId" to unitId, "verdict" to "none")
                    } else {
                        val logging =
                            unit.path("existingAnnotations").first { annotation ->
                                annotation.path("intents").any { it.asText() == "LOGGING" } &&
                                    annotation.path("startByte").asInt() == unit.path("startByte").asInt() &&
                                    annotation.path("endByte").asInt() == unit.path("endByte").asInt()
                            }
                        val start = logging.path("startByte").asInt()
                        val end = logging.path("endByte").asInt()
                        linkedMapOf(
                            "unitId" to unitId,
                            "remove" to
                                listOf(
                                    linkedMapOf(
                                        "intent" to "LOGGING",
                                        "startByte" to start,
                                        "endByte" to end,
                                        "rationale" to "not a log call",
                                    ),
                                ),
                            "add" to
                                listOf(
                                    linkedMapOf(
                                        "intent" to "SEARCH",
                                        "startByte" to start,
                                        "endByte" to end,
                                        "confidence" to 0.68,
                                        "rationale" to "regex find searches the text",
                                    ),
                                ),
                        )
                    }
                }
            return AgentTurn.Calls(
                listOf(
                    ToolCall(
                        id = "restriction",
                        name = "submit_verdicts",
                        arguments = mapper.writeValueAsString(linkedMapOf("verdicts" to verdicts)),
                    ),
                ),
            )
        }
    }
}

private fun unitsOf(request: AgentRequest): List<JsonNode> {
    val mapper = jacksonObjectMapper()
    val user = request.messages.lastOrNull { it.role == "user" }?.content ?: return emptyList()
    return mapper.readTree(user).path("units").toList()
}

private fun loggingAnnotation(unit: JsonNode): JsonNode? {
    val start = unit.path("startByte").asInt()
    val end = unit.path("endByte").asInt()
    return unit
        .path("existingAnnotations")
        .filter { annotation ->
            annotation.path("intents").any { it.asText() == "LOGGING" } &&
                annotation.path("endByte").asInt() > start &&
                annotation.path("startByte").asInt() < end
        }.minByOrNull { annotation ->
            annotation.path("endByte").asInt() - annotation.path("startByte").asInt()
        }
}

private val wireMapper = jacksonObjectMapper()

private fun initializeMessage(protocol: Int): String = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocol":$protocol}}"""

private fun openMessage(
    uri: String,
    text: String,
    version: Int,
): String =
    """{"jsonrpc":"2.0","id":2,"method":"textDocument/didOpen","params":{"uri":${wireMapper.writeValueAsString(
        uri,
    )},"text":${wireMapper.writeValueAsString(text)},"version":$version,"languageId":"kotlin"}}"""

private fun suggestMessage(
    uri: String,
    version: Int,
    id: Int,
    requestId: String,
): String =
    """{"jsonrpc":"2.0","id":$id,"method":"cint/suggest","params":{"uri":${wireMapper.writeValueAsString(
        uri,
    )},"version":$version,"requestId":${wireMapper.writeValueAsString(requestId)}}}"""

private fun decideMessage(
    uri: String,
    version: Int,
    id: Int,
    proposalId: String,
    decision: String,
): String =
    """{"jsonrpc":"2.0","id":$id,"method":"cint/candidates/decide","params":{"uri":${wireMapper.writeValueAsString(
        uri,
    )},"version":$version,"proposalId":${wireMapper.writeValueAsString(proposalId)},"decision":${wireMapper.writeValueAsString(
        decision,
    )}}}"""

private class TestClient {
    private val output = StringWriter()
    val writer = BufferedWriter(output)

    fun send(
        harness: Harness,
        message: String,
    ) {
        harness.sendFrom(writer, message)
    }

    fun messages(mapper: com.fasterxml.jackson.databind.ObjectMapper): List<JsonNode> =
        output
            .toString()
            .trim()
            .lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line -> runCatching { mapper.readTree(line) }.getOrNull() }

    fun latestOrNull(
        mapper: com.fasterxml.jackson.databind.ObjectMapper,
        method: String,
    ): JsonNode? = messages(mapper).lastOrNull { it["method"]?.asText() == method }

    fun latest(
        mapper: com.fasterxml.jackson.databind.ObjectMapper,
        method: String,
    ): JsonNode = latestOrNull(mapper, method) ?: error("missing $method")

    fun waitForResult(
        mapper: com.fasterxml.jackson.databind.ObjectMapper,
        id: Int,
    ): JsonNode =
        waitFor {
            messages(mapper).lastOrNull { it["id"]?.asInt() == id && it["method"] == null }?.path("result")
        }

    fun waitFor(
        timeoutMs: Long = 3_000,
        predicate: () -> JsonNode?,
    ): JsonNode {
        val deadline =
            System.nanoTime() +
                java.util.concurrent.TimeUnit.MILLISECONDS
                    .toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            predicate()?.let { return it }
            Thread.sleep(10)
        }
        error("timed out waiting for client condition")
    }
}

private fun verdict(
    unit: JsonNode,
    logging: JsonNode,
    add: Boolean,
): Map<String, Any?> {
    val start = logging.path("startByte").asInt()
    val end = logging.path("endByte").asInt()
    return linkedMapOf<String, Any?>(
        "unitId" to unit.path("unitId").asText(),
        "remove" to
            listOf(
                linkedMapOf(
                    "intent" to "LOGGING",
                    "startByte" to start,
                    "endByte" to end,
                    "rationale" to "not a log call",
                ),
            ),
    ).apply {
        if (add) {
            put(
                "add",
                listOf(
                    linkedMapOf(
                        "intent" to "SEARCH",
                        "startByte" to start,
                        "endByte" to end,
                        "confidence" to 0.68,
                        "rationale" to "regex find searches the text",
                    ),
                ),
            )
        }
    }
}

private fun submit(
    mapper: com.fasterxml.jackson.databind.ObjectMapper,
    verdicts: List<Map<String, Any?>>,
): AgentTurn =
    AgentTurn.Calls(
        listOf(
            ToolCall(
                id = "restriction",
                name = "submit_verdicts",
                arguments = mapper.writeValueAsString(linkedMapOf("verdicts" to verdicts)),
            ),
        ),
    )

private fun dev.cint.server.suggest.support.Harness.latestOrNull(method: String): JsonNode? =
    messages().lastOrNull { it["method"]?.asText() == method }
