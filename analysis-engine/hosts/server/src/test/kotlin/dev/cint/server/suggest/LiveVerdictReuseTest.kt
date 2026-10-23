package dev.cint.server.suggest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.cint.api.Span
import dev.cint.core.restriction.AiRestrictionRecord
import dev.cint.core.restriction.AiRestrictionsFile
import dev.cint.core.restriction.RestrictionOperation
import dev.cint.core.support.hashing.contentHash
import dev.cint.core.syntax.expressionUnits
import dev.cint.core.user.UserAnnotation
import dev.cint.core.user.UserAnnotationResolver
import dev.cint.core.user.UserAnnotationsFile
import dev.cint.server.SuggestionSettings
import dev.cint.server.cache.AiVerdictCacheStore
import dev.cint.server.suggest.support.Harness
import dev.cint.server.suggest.support.candidates
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
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveVerdictReuseTest {
    private val mapper = jacksonObjectMapper()
    private val source =
        """
        fun a(text: String): String? {
            val match = Regex("cint").find(text) ?: return null
            return match.groupValues[1]
        }

        fun b(text: String): String? {
            val other = Regex("other").find(text) ?: return null
            return other.groupValues[1]
        }
        """.trimIndent()

    private val decisionSource =
        """
        fun a(text: String): String? {
            val match = Regex("cint").find(text) ?: return null
            return match.groupValues[1]
        }
        """.trimIndent()

    private val declarationSource =
        """
        fun use(text: String): String? {
            val pattern = Regex("cint")
            return pattern.find(text)?.value
        }
        """.trimIndent()

    private val duplicateSource =
        """
        fun f(x: Int) {
            println(x)
            println(x)
        }
        """.trimIndent()

    private val proposalSource =
        """
        private val log = LoggerFactory.getLogger(Foo::class.java)
        fun f() {
            val message = "x"
            log.warn(message)
        }
        """.trimIndent()

    private val siblingSource =
        """
        fun f() {
            foo()
            bar()
        }
        """.trimIndent()

    @Test
    fun identicalTextDoesNotReanalyzeButRepublishesTheNewVersion() {
        val root = Files.createTempDirectory("cint-reuse-identical")
        try {
            val harness = harness(root, suggestionSettings = settings())
            harness.initialize()
            val uri = root.resolve("Facts.kt").toString()
            harness.open(uri, source, version = 1)
            assertEquals(1, harness.analysisRuns(uri))
            val parsesBefore = harness.parseRuns()

            harness.change(uri, source, version = 2)
            val tags = harness.latest("cint/publishTags").path("params")
            assertEquals(2, tags.path("version").asInt())
            assertEquals(1, harness.analysisRuns(uri))
            assertTrue(tags.path("annotations").size() >= 0)
            assertEquals(parsesBefore, harness.parseRuns())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun reformatReusesEveryUnitWithoutCallingTheProvider() {
        val root = Files.createTempDirectory("cint-reuse-reformat")
        try {
            val provider = ReuseProvider()
            val harness = harness(root, suggestionSettings = settings(), service = service(provider))
            harness.initialize()
            val uri = root.resolve("Facts.kt").toString()
            harness.open(uri, source, version = 1)
            harness.suggest(uri, version = 1, id = 2, requestId = "first")
            val first = harness.waitForResponse(2)
            val total = first.path("total").asInt()
            val callsAfterFirst = provider.calls.get()
            assertTrue(callsAfterFirst >= 1)
            assertTrue(first.path("overhead").asLong() >= 0)

            val reformatted = source.replace("    ", "  ")
            harness.change(uri, reformatted, version = 2)
            harness.suggest(uri, version = 2, id = 3, requestId = "reformat")
            val second = harness.waitForResponse(3)

            assertEquals(total, second.path("reused").asInt())
            assertEquals(0, second.path("graded").asInt())
            assertEquals(total, second.path("total").asInt())
            assertEquals(callsAfterFirst, provider.calls.get())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun lintEditReusesTheUnaffectedFunctionAndGradesOnlyChangedUnits() {
        val root = Files.createTempDirectory("cint-reuse-lint")
        try {
            val provider = ReuseProvider()
            val harness = harness(root, suggestionSettings = settings(), service = service(provider))
            harness.initialize()
            val uri = root.resolve("Facts.kt").toString()
            harness.open(uri, source, version = 1)
            harness.suggest(uri, version = 1, id = 2, requestId = "first")
            val first = harness.waitForResponse(2)
            val total = first.path("total").asInt()
            val callsAfterFirst = provider.calls.get()
            assertTrue(callsAfterFirst >= 1)

            val edited = source.replace("Regex(\"cint\")", "Regex(\"cint2\")")
            harness.change(uri, edited, version = 2)
            harness.suggest(uri, version = 2, id = 3, requestId = "lint")
            val second = harness.waitForResponse(3)

            assertTrue(second.path("reused").asInt() > 0)
            assertTrue(second.path("graded").asInt() > 0)
            assertTrue(second.path("graded").asInt() < total)
            assertEquals(
                total,
                second.path("reused").asInt() + second.path("graded").asInt(),
            )
            assertTrue(provider.calls.get() > callsAfterFirst)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun diskCacheSurvivesRestartAndOmitsSourceAndRationale() {
        val root = Files.createTempDirectory("cint-reuse-restart")
        try {
            val firstProvider = ReuseProvider()
            val firstHarness = harness(root, suggestionSettings = settings(), service = service(firstProvider))
            firstHarness.initialize()
            val uri = root.resolve("Facts.kt").toString()
            firstHarness.open(uri, source, version = 1)
            firstHarness.suggest(uri, version = 1, id = 2, requestId = "first")
            val first = firstHarness.waitForResponse(2)

            val cacheFile = AiVerdictCacheStore.path(root)
            assertTrue(Files.isRegularFile(cacheFile))
            val payload = Files.readString(cacheFile)
            assertFalse(payload.contains("Regex"), payload)
            assertFalse(payload.contains("find(text)"), payload)
            assertFalse(payload.contains("rationale"), payload)
            assertFalse(payload.contains("normalizedText"), payload)

            val secondProvider = ReuseProvider()
            val restarted = harness(root, suggestionSettings = settings(), service = service(secondProvider))
            restarted.initialize()
            restarted.open(uri, source, version = 1)
            restarted.suggest(uri, version = 1, id = 3, requestId = "restart")
            val second = restarted.waitForResponse(3)

            assertEquals(first.path("total").asInt(), second.path("reused").asInt())
            assertEquals(0, second.path("graded").asInt())
            assertEquals(0, secondProvider.calls.get())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun promptChangeInvalidatesThePersistedCache() {
        val root = Files.createTempDirectory("cint-reuse-prompt")
        try {
            val firstProvider = ReuseProvider()
            val firstHarness =
                harness(root, suggestionSettings = settings(promptVersion = "prompt-v1"), service = service(firstProvider))
            firstHarness.initialize()
            val uri = root.resolve("Facts.kt").toString()
            firstHarness.open(uri, source, version = 1)
            firstHarness.suggest(uri, version = 1, id = 2, requestId = "first")
            firstHarness.waitForResponse(2)

            val secondProvider = ReuseProvider()
            val changed =
                harness(root, suggestionSettings = settings(promptVersion = "prompt-v2"), service = service(secondProvider))
            changed.initialize()
            changed.open(uri, source, version = 1)
            changed.suggest(uri, version = 1, id = 3, requestId = "changed")
            val second = changed.waitForResponse(3)

            assertEquals(0, second.path("reused").asInt())
            assertTrue(second.path("graded").asInt() > 0)
            assertTrue(secondProvider.calls.get() > 0)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun userAnnotationsAndRestrictionsInvalidateTheCacheLayer() {
        val root = Files.createTempDirectory("cint-reuse-env")
        try {
            val provider = ReuseProvider()
            val harness = harness(root, suggestionSettings = settings(), service = service(provider))
            harness.initialize()
            val uri = root.resolve("Facts.kt").toString()
            harness.open(uri, source, version = 1)
            harness.suggest(uri, version = 1, id = 2, requestId = "first")
            harness.waitForResponse(2)
            val callsAfterFirst = provider.calls.get()
            assertTrue(callsAfterFirst >= 1)

            val start = source.indexOf("match")
            val span = UserAnnotationResolver.spanFor(source, start, start + "match".length) ?: error("span")
            UserAnnotationsFile.save(
                root,
                listOf(UserAnnotation(path = "Facts.kt", span = span, intents = setOf("READONLY"))),
            )
            harness.analyze(uri, id = 3)
            harness.suggest(uri, version = 1, id = 4, requestId = "annotation")
            val afterAnnotation = harness.waitForResponse(4)
            assertEquals(0, afterAnnotation.path("reused").asInt())
            val callsAfterAnnotation = provider.calls.get()
            assertTrue(callsAfterAnnotation > callsAfterFirst)

            val callStart = source.indexOf("Regex(\"cint\").find(text)")
            val callEnd = callStart + "Regex(\"cint\").find(text)".length
            val callSpan = UserAnnotationResolver.spanFor(source, callStart, callEnd) ?: error("call span")
            AiRestrictionsFile.add(
                root,
                AiRestrictionRecord(
                    id = "restriction",
                    path = "Facts.kt",
                    span = callSpan,
                    contentHash = contentHash("Regex(\"cint\").find(text)"),
                    remove = listOf(RestrictionOperation("LOGGING", 0, callEnd - callStart)),
                    add = emptyList(),
                ),
            )
            harness.analyze(uri, id = 5)
            harness.suggest(uri, version = 1, id = 6, requestId = "restriction")
            val afterRestriction = harness.waitForResponse(6)
            assertEquals(0, afterRestriction.path("reused").asInt())
            assertTrue(provider.calls.get() > callsAfterAnnotation)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectedCandidateDoesNotReappearAfterAFormattingEdit() {
        val root = Files.createTempDirectory("cint-reuse-decision")
        try {
            val provider = ReuseProvider()
            val harness =
                harness(
                    root,
                    suggestionSettings = settings(cacheEnabled = false),
                    service = service(provider),
                )
            harness.initialize()
            val uri = root.resolve("Facts.kt").toString()
            harness.open(uri, decisionSource, version = 1)
            harness.suggest(uri, version = 1, id = 2, requestId = "first")
            harness.waitForResponse(2)
            val candidate =
                candidates(harness.latest("cint/publishCandidates"), "SEARCH")
                    .first { it["source"].asText() == "ai:reuse" }
            harness.send(
                """{"jsonrpc":"2.0","id":3,"method":"cint/candidates/decide","params":{"uri":${quote(
                    uri,
                )},"version":1,"candidateId":${quote(candidate["candidateId"].asText())},"decision":"reject"}}""",
            )
            assertEquals("reject", harness.waitForResponse(3).path("decision").asText())
            val rejectedLine = candidate["startLine"].asInt()
            val rejectedText = UserAnnotationResolver.selectionText(decisionSource, spanOf(candidate)).orEmpty()
            assertTrue(
                candidates(harness.latest("cint/publishCandidates"), "SEARCH")
                    .none {
                        it["startLine"].asInt() == rejectedLine &&
                            UserAnnotationResolver.selectionText(decisionSource, spanOf(it)) == rejectedText
                    },
            )

            val reformatted = decisionSource.replace("val match = Regex", "val  match = Regex")
            harness.change(uri, reformatted, version = 2)
            harness.suggest(uri, version = 2, id = 4, requestId = "format")
            val response = harness.waitForResponse(4)
            assertTrue(response.path("accepted").asBoolean())
            assertTrue(
                candidates(harness.latest("cint/publishCandidates"), "SEARCH")
                    .none {
                        it["startLine"].asInt() == rejectedLine &&
                            UserAnnotationResolver.selectionText(reformatted, spanOf(it)) == rejectedText
                    },
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun declarationChangeRegradesUnitsThatReferenceIt() {
        val root = Files.createTempDirectory("cint-reuse-declaration")
        try {
            val provider = ReuseProvider()
            val harness = harness(root, suggestionSettings = settings(), service = service(provider))
            harness.initialize()
            val uri = root.resolve("Facts.kt").toString()
            harness.open(uri, declarationSource, version = 1)
            harness.suggest(uri, version = 1, id = 2, requestId = "first")
            val first = harness.waitForResponse(2)
            provider.seenTexts.clear()

            val edited = declarationSource.replace("Regex(\"cint\")", "\"cint\"")
            harness.change(uri, edited, version = 2)
            harness.suggest(uri, version = 2, id = 3, requestId = "declaration")
            val second = harness.waitForResponse(3)

            assertTrue(second.path("reused").asInt() < first.path("total").asInt())
            assertTrue(
                provider.seenTexts.any { it == "pattern.find(text)" },
                "referencing unit was not regraded: ${provider.seenTexts}",
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun confirmedCandidateSurvivesIntraSpanFormatting() {
        val root = Files.createTempDirectory("cint-reuse-confirm-format")
        try {
            val provider = ReuseProvider()
            val harness = harness(root, suggestionSettings = settings(cacheEnabled = false), service = service(provider))
            harness.initialize()
            val uri = root.resolve("Facts.kt").toString()
            harness.open(uri, decisionSource, version = 1)
            harness.suggest(uri, version = 1, id = 2, requestId = "first")
            harness.waitForResponse(2)
            val candidate = candidates(harness.latest("cint/publishCandidates"), "SEARCH").first()
            val confirmedLine = candidate["startLine"].asInt()
            val confirmedText = UserAnnotationResolver.selectionText(decisionSource, spanOf(candidate)).orEmpty()
            harness.send(
                """{"jsonrpc":"2.0","id":3,"method":"cint/candidates/decide","params":{"uri":${quote(
                    uri,
                )},"version":1,"candidateId":${quote(candidate["candidateId"].asText())},"decision":"confirm"}}""",
            )
            assertEquals("confirm", harness.waitForResponse(3).path("decision").asText())
            harness.waitFor {
                harness
                    .latestOrNull("cint/publishTags")
                    ?.path("params")
                    ?.path("annotations")
                    ?.takeIf { annotations -> annotations.any { it.path("source").asText() == "user" } }
            }

            val reformatted = decisionSource.replace("find(text)", "find( text )")
            harness.change(uri, reformatted, version = 2)
            val tags = harness.latest("cint/publishTags").path("params")
            assertEquals(2, tags.path("version").asInt())
            assertTrue(
                tags.path("annotations").any {
                    it.path("source").asText() == "user" &&
                        it.path("intents").any { intent -> intent.asText() == "SEARCH" }
                },
                tags.toString(),
            )

            harness.suggest(uri, version = 2, id = 4, requestId = "after-format")
            assertTrue(harness.waitForResponse(4).path("accepted").asBoolean())
            assertTrue(
                candidates(harness.latest("cint/publishCandidates"), "SEARCH")
                    .none {
                        it["startLine"].asInt() == confirmedLine &&
                            UserAnnotationResolver.selectionText(reformatted, spanOf(it)) == confirmedText
                    },
                harness.latest("cint/publishCandidates").toString(),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun partialTokenConfirmedCandidateSurvivesReformat() {
        val root = Files.createTempDirectory("cint-reuse-confirm-partial")
        try {
            val provider = PartialTokenProvider()
            val harness = harness(root, suggestionSettings = settings(), service = service(provider))
            harness.initialize()
            val uri = root.resolve("Facts.kt").toString()
            harness.open(uri, decisionSource, version = 1)
            harness.suggest(uri, version = 1, id = 2, requestId = "first")
            harness.waitForResponse(2)
            val candidate = candidates(harness.latest("cint/publishCandidates"), "SEARCH").first()
            harness.send(
                """{"jsonrpc":"2.0","id":3,"method":"cint/candidates/decide","params":{"uri":${quote(
                    uri,
                )},"version":1,"candidateId":${quote(candidate["candidateId"].asText())},"decision":"confirm"}}""",
            )
            assertEquals("confirm", harness.waitForResponse(3).path("decision").asText())
            harness.waitFor {
                harness
                    .latestOrNull("cint/publishTags")
                    ?.path("params")
                    ?.path("annotations")
                    ?.takeIf { annotations -> annotations.any { it.path("source").asText() == "user" } }
            }

            val reformatted = decisionSource.replace("find(text)", "find( text )")
            harness.change(uri, reformatted, version = 2)
            val tags = harness.latest("cint/publishTags").path("params")
            assertTrue(
                tags.path("annotations").any {
                    it.path("source").asText() == "user" &&
                        it.path("intents").any { intent -> intent.asText() == "SEARCH" }
                },
                tags.toString(),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun identicalTextWithAStoredDecisionDoesNotParse() {
        val root = Files.createTempDirectory("cint-reuse-decision-short-circuit")
        try {
            val provider = ReuseProvider()
            val harness = harness(root, suggestionSettings = settings(), service = service(provider))
            harness.initialize()
            val uri = root.resolve("Facts.kt").toString()
            harness.open(uri, decisionSource, version = 1)
            harness.suggest(uri, version = 1, id = 2, requestId = "first")
            harness.waitForResponse(2)
            val candidate = candidates(harness.latest("cint/publishCandidates"), "SEARCH").first()
            harness.send(
                """{"jsonrpc":"2.0","id":3,"method":"cint/candidates/decide","params":{"uri":${quote(
                    uri,
                )},"version":1,"candidateId":${quote(candidate["candidateId"].asText())},"decision":"reject"}}""",
            )
            assertEquals("reject", harness.waitForResponse(3).path("decision").asText())
            val analysesBefore = harness.analysisRuns(uri)
            val parsesBefore = harness.parseRuns()

            harness.change(uri, decisionSource, version = 2)
            assertEquals(analysesBefore, harness.analysisRuns(uri))
            assertEquals(parsesBefore, harness.parseRuns())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun confirmedProposalDoesNotReappearAfterFormatting() {
        val root = Files.createTempDirectory("cint-reuse-proposal-rekey")
        try {
            val provider = DuplicateProposalProvider()
            val harness =
                harness(
                    root,
                    suggestionSettings = settings(cacheEnabled = false),
                    service = service(provider),
                )
            harness.initialize()
            val uri = root.resolve("Facts.kt").toString()
            harness.open(uri, proposalSource, version = 1)
            harness.suggest(uri, version = 1, id = 2, requestId = "first")
            harness.waitForResponse(2)
            val proposal = proposalGroups(harness.latest("cint/publishCandidates")).single()
            harness.send(
                """{"jsonrpc":"2.0","id":3,"method":"cint/candidates/decide","params":{"uri":${quote(
                    uri,
                )},"version":1,"proposalId":${quote(proposal["proposalId"].asText())},"decision":"confirm"}}""",
            )
            assertEquals("confirm", harness.waitForResponse(3).path("decision").asText())

            val reformatted = proposalSource.replace("log.warn(message)", "log.warn( message )")
            harness.change(uri, reformatted, version = 2)
            harness.suggest(uri, version = 2, id = 4, requestId = "after-format")
            val response = harness.waitForResponse(4)
            assertEquals(0, response.path("restrictions").asInt())
            assertTrue(proposalGroups(harness.latest("cint/publishCandidates")).isEmpty())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun confirmedExpressionDoesNotExpandToTheEnclosingFunctionAfterSiblingEdit() {
        val root = Files.createTempDirectory("cint-reuse-confirm-sibling")
        try {
            val provider = SiblingProvider()
            val harness = harness(root, suggestionSettings = settings(), service = service(provider))
            harness.initialize()
            val uri = root.resolve("Facts.kt").toString()
            harness.open(uri, siblingSource, version = 1)
            harness.suggest(uri, version = 1, id = 2, requestId = "first")
            harness.waitForResponse(2)
            val candidate = candidates(harness.latest("cint/publishCandidates"), "SEARCH").single()
            harness.send(
                """{"jsonrpc":"2.0","id":3,"method":"cint/candidates/decide","params":{"uri":${quote(
                    uri,
                )},"version":1,"candidateId":${quote(candidate["candidateId"].asText())},"decision":"confirm"}}""",
            )
            assertEquals("confirm", harness.waitForResponse(3).path("decision").asText())

            val edited = siblingSource.replace("bar()", "baz()")
            harness.change(uri, edited, version = 2)
            val annotations = harness.latest("cint/publishTags").path("params").path("annotations")
            assertTrue(
                annotations.none {
                    it.path("source").asText() == "user" &&
                        it.path("intents").any { intent -> intent.asText() == "SEARCH" } &&
                        (it.path("endLine").asInt() > it.path("startLine").asInt())
                },
                annotations.toString(),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun confirmedExpressionDoesNotRebindToADuplicateAfterInsertion() {
        val root = Files.createTempDirectory("cint-reuse-confirm-duplicate")
        try {
            val provider = SiblingProvider()
            val harness = harness(root, suggestionSettings = settings(), service = service(provider))
            harness.initialize()
            val uri = root.resolve("Facts.kt").toString()
            harness.open(uri, siblingSource, version = 1)
            harness.suggest(uri, version = 1, id = 2, requestId = "first")
            harness.waitForResponse(2)
            val candidate = candidates(harness.latest("cint/publishCandidates"), "SEARCH").single()
            harness.send(
                """{"jsonrpc":"2.0","id":3,"method":"cint/candidates/decide","params":{"uri":${quote(
                    uri,
                )},"version":1,"candidateId":${quote(candidate["candidateId"].asText())},"decision":"confirm"}}""",
            )
            assertEquals("confirm", harness.waitForResponse(3).path("decision").asText())

            val edited = siblingSource.replace("            foo()\n", "            foo()\n            foo()\n")
            harness.change(uri, edited, version = 2)
            val annotations = harness.latest("cint/publishTags").path("params").path("annotations")
            val user =
                annotations.firstOrNull {
                    it.path("source").asText() == "user" &&
                        it.path("intents").any { intent -> intent.asText() == "SEARCH" }
                }
            assertTrue(
                user == null ||
                    (
                        user.path("startLine").asInt() == 1 &&
                            user.path("startCol").asInt() == 4 &&
                            user.path("endLine").asInt() == 1 &&
                            user.path("endCol").asInt() == 9
                    ),
                annotations.toString(),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun restrictionFromAnotherFileDoesNotSuppressAProposal() {
        val root = Files.createTempDirectory("cint-reuse-cross-file")
        try {
            val provider = DuplicateProposalProvider()
            val harness =
                harness(
                    root,
                    suggestionSettings = settings(cacheEnabled = false),
                    service = service(provider),
                )
            harness.initialize()
            val firstUri = root.resolve("First.kt").toString()
            val secondUri = root.resolve("Second.kt").toString()
            harness.open(firstUri, proposalSource, version = 1)
            harness.send(
                """{"jsonrpc":"2.0","id":11,"method":"textDocument/didOpen","params":{"uri":${quote(
                    secondUri,
                )},"text":${quote(proposalSource)},"version":1,"languageId":"kotlin"}}""",
            )
            harness.suggest(firstUri, version = 1, id = 2, requestId = "first")
            harness.waitForResponse(2)
            val proposal = proposalGroups(harness.latest("cint/publishCandidates")).single()
            harness.send(
                """{"jsonrpc":"2.0","id":3,"method":"cint/candidates/decide","params":{"uri":${quote(
                    firstUri,
                )},"version":1,"proposalId":${quote(proposal["proposalId"].asText())},"decision":"confirm"}}""",
            )
            assertEquals("confirm", harness.waitForResponse(3).path("decision").asText())

            harness.suggest(secondUri, version = 1, id = 4, requestId = "second")
            val second = harness.waitForResponse(4)
            assertEquals(1, second.path("restrictions").asInt())
            assertEquals(1, proposalGroups(harness.latest("cint/publishCandidates")).size)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun dormantOrDifferentlyShapedRestrictionDoesNotSuppressAProposal() {
        val root = Files.createTempDirectory("cint-reuse-restriction-shape")
        try {
            val provider = DuplicateProposalProvider()
            val harness =
                harness(
                    root,
                    suggestionSettings = settings(cacheEnabled = false),
                    service = service(provider),
                )
            harness.initialize()
            val uri = root.resolve("Facts.kt").toString()
            harness.open(uri, proposalSource, version = 1)
            val source =
                dev.cint.core.facts.model
                    .SourceFile(uri, proposalSource)
            val frontend =
                dev.cint.frontend.Frontends
                    .require("kotlin")
            val tree = frontend.parse(source)
            val syntax = frontend.syntax(tree)
            val unit =
                syntax
                    .expressionUnits(tree)
                    .first { it.text == "log.warn(message)" }
            AiRestrictionsFile.add(
                root,
                AiRestrictionRecord(
                    id = "remove-only",
                    path = "Facts.kt",
                    span = unit.span,
                    contentHash = contentHash(unit.text),
                    remove = listOf(RestrictionOperation("LOGGING", 0, unit.text.length)),
                    add = emptyList(),
                    unitFingerprint =
                        dev.cint.core.syntax.UnitIdentity.unitFingerprint(
                            source,
                            tree,
                            syntax,
                            unit.startByte,
                            unit.endByte,
                        ),
                    fingerprintVersion = dev.cint.core.syntax.UnitIdentity.CURRENT_FINGERPRINT_VERSION,
                ),
            )

            harness.suggest(uri, version = 1, id = 2, requestId = "shape")
            val response = harness.waitForResponse(2)
            assertEquals(1, response.path("restrictions").asInt())
            assertEquals(1, proposalGroups(harness.latest("cint/publishCandidates")).size)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun dormantRestrictionDoesNotSuppressAProposal() {
        val root = Files.createTempDirectory("cint-reuse-restriction-dormant")
        try {
            val provider = DuplicateProposalProvider()
            val harness =
                harness(
                    root,
                    suggestionSettings = settings(cacheEnabled = false),
                    service = service(provider),
                )
            harness.initialize()
            val uri = root.resolve("Facts.kt").toString()
            harness.open(uri, proposalSource, version = 1)
            AiRestrictionsFile.add(
                root,
                AiRestrictionRecord(
                    id = "dormant",
                    path = "Facts.kt",
                    span = dev.cint.api.Span(0, 0, 0, 1),
                    contentHash = "deadbeef",
                    remove = listOf(RestrictionOperation("LOGGING", 0, 1)),
                    add = listOf(RestrictionOperation("SEARCH", 0, 1)),
                    unitFingerprint = "stale",
                    fingerprintVersion = dev.cint.core.syntax.UnitIdentity.CURRENT_FINGERPRINT_VERSION,
                ),
            )

            harness.suggest(uri, version = 1, id = 2, requestId = "dormant")
            val response = harness.waitForResponse(2)
            assertEquals(1, response.path("restrictions").asInt())
            assertEquals(1, proposalGroups(harness.latest("cint/publishCandidates")).size)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun differentlyShapedSubSpanProposalIsNotSuppressed() {
        val root = Files.createTempDirectory("cint-reuse-restriction-subspan")
        try {
            val provider = NarrowProposalProvider()
            val harness =
                harness(
                    root,
                    suggestionSettings = settings(cacheEnabled = false),
                    service = service(provider),
                )
            harness.initialize()
            val uri = root.resolve("Facts.kt").toString()
            harness.open(uri, proposalSource, version = 1)
            val source =
                dev.cint.core.facts.model
                    .SourceFile(uri, proposalSource)
            val frontend =
                dev.cint.frontend.Frontends
                    .require("kotlin")
            val tree = frontend.parse(source)
            val syntax = frontend.syntax(tree)
            val unit = syntax.expressionUnits(tree).first { it.text == "log.warn(message)" }
            AiRestrictionsFile.add(
                root,
                AiRestrictionRecord(
                    id = "whole-unit",
                    path = "Facts.kt",
                    span = unit.span,
                    contentHash = contentHash(unit.text),
                    remove = listOf(RestrictionOperation("LOGGING", 0, unit.text.length)),
                    add = listOf(RestrictionOperation("SEARCH", 0, unit.text.length)),
                    unitFingerprint =
                        dev.cint.core.syntax.UnitIdentity.unitFingerprint(
                            source,
                            tree,
                            syntax,
                            unit.startByte,
                            unit.endByte,
                        ),
                    fingerprintVersion = dev.cint.core.syntax.UnitIdentity.CURRENT_FINGERPRINT_VERSION,
                ),
            )

            harness.suggest(uri, version = 1, id = 2, requestId = "subspan")
            val response = harness.waitForResponse(2)
            assertEquals(1, response.path("restrictions").asInt())
            assertEquals(1, proposalGroups(harness.latest("cint/publishCandidates")).size)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun duplicateIdenticalExpressionsKeepIndependentDecisions() {
        val root = Files.createTempDirectory("cint-reuse-duplicates")
        try {
            val provider = DuplicateProvider()
            val harness =
                harness(
                    root,
                    suggestionSettings = settings(cacheEnabled = false),
                    service = service(provider),
                )
            harness.initialize()
            val uri = root.resolve("Facts.kt").toString()
            harness.open(uri, duplicateSource, version = 1)
            harness.suggest(uri, version = 1, id = 2, requestId = "first")
            harness.waitForResponse(2)
            val candidates = candidates(harness.latest("cint/publishCandidates"), "SEARCH")
            assertEquals(2, candidates.size)

            harness.send(
                """{"jsonrpc":"2.0","id":3,"method":"cint/candidates/decide","params":{"uri":${quote(
                    uri,
                )},"version":1,"candidateId":${quote(candidates[0]["candidateId"].asText())},"decision":"reject"}}""",
            )
            assertEquals("reject", harness.waitForResponse(3).path("decision").asText())
            val remaining = candidates(harness.latest("cint/publishCandidates"), "SEARCH")
            assertEquals(1, remaining.size)
            assertEquals(candidates[1]["candidateId"].asText(), remaining.single()["candidateId"].asText())

            harness.send(
                """{"jsonrpc":"2.0","id":4,"method":"cint/candidates/decide","params":{"uri":${quote(
                    uri,
                )},"version":1,"candidateId":${quote(remaining.single()["candidateId"].asText())},"decision":"confirm"}}""",
            )
            assertEquals("confirm", harness.waitForResponse(4).path("decision").asText())
            assertTrue(candidates(harness.latest("cint/publishCandidates"), "SEARCH").isEmpty())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun duplicateIdenticalProposalsKeepIndependentDecisions() {
        val root = Files.createTempDirectory("cint-reuse-duplicate-proposals")
        try {
            val provider = DuplicateProposalProvider()
            val harness =
                harness(
                    root,
                    suggestionSettings = settings(cacheEnabled = false),
                    service = service(provider),
                )
            harness.initialize()
            val uri = root.resolve("Facts.kt").toString()
            harness.open(uri, duplicateSource, version = 1)
            harness.suggest(uri, version = 1, id = 2, requestId = "first")
            harness.waitForResponse(2)
            val proposals = proposalGroups(harness.latest("cint/publishCandidates"))
            assertEquals(2, proposals.size)

            harness.send(
                """{"jsonrpc":"2.0","id":3,"method":"cint/candidates/decide","params":{"uri":${quote(
                    uri,
                )},"version":1,"proposalId":${quote(proposals[0]["proposalId"].asText())},"decision":"reject"}}""",
            )
            assertEquals("reject", harness.waitForResponse(3).path("decision").asText())
            val remaining = proposalGroups(harness.latest("cint/publishCandidates"))
            assertEquals(1, remaining.size)
            assertEquals(proposals[1]["proposalId"].asText(), remaining.single()["proposalId"].asText())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun cacheFileHeaderTracksTheFullEnvironment() {
        val root = Files.createTempDirectory("cint-reuse-header")
        try {
            val provider = ReuseProvider()
            val harness = harness(root, suggestionSettings = settings(), service = service(provider))
            harness.initialize()
            val uri = root.resolve("Facts.kt").toString()
            harness.open(uri, source, version = 1)
            harness.suggest(uri, version = 1, id = 2, requestId = "first")
            harness.waitForResponse(2)
            val cacheFile = AiVerdictCacheStore.path(root)
            val oldPayload = Files.readString(cacheFile)
            val oldKey = Regex("\"key\"\\s*:\\s*\"([a-f0-9]+)\"").find(oldPayload)?.groupValues?.get(1)
            assertTrue(oldKey != null)
            assertTrue(oldPayload.contains("\"aiEnv\""))
            assertTrue(oldPayload.contains("\"deterministicHash\""))

            val start = source.indexOf("match")
            val span = UserAnnotationResolver.spanFor(source, start, start + "match".length) ?: error("span")
            UserAnnotationsFile.save(
                root,
                listOf(UserAnnotation(path = "Facts.kt", span = span, intents = setOf("READONLY"))),
            )
            harness.analyze(uri, id = 3)
            harness.suggest(uri, version = 1, id = 4, requestId = "after-annotation")
            harness.waitForResponse(4)

            val newPayload = Files.readString(cacheFile)
            assertFalse(newPayload.contains(oldKey!!))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun configMutationRacingSameTextPublishEndsWithTheNewConfig() {
        val root = Files.createTempDirectory("cint-reuse-config-race")
        try {
            val harness = harness(root, suggestionSettings = settings())
            harness.initialize()
            val uri = root.resolve("Facts.kt").toString()
            val raceSource = "fun f() {\n    audit(\"x\")\n}"
            harness.open(uri, raceSource, version = 1)
            val started = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            harness.server.analysisEnvironmentBarrierForTest = {
                started.countDown()
                release.await(5, java.util.concurrent.TimeUnit.SECONDS)
            }
            val changeThread = Thread { harness.change(uri, raceSource, version = 2) }
            val appendThread =
                Thread {
                    harness.configAppend(
                        packId = "logging",
                        language = "kotlin",
                        field = "printCalls",
                        value = "audit",
                        id = 20,
                    )
                }
            try {
                changeThread.start()
                assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS))
                appendThread.start()
                release.countDown()
                changeThread.join(5_000)
                appendThread.join(5_000)
            } finally {
                release.countDown()
                harness.server.analysisEnvironmentBarrierForTest = null
            }
            val tags =
                harness.waitFor {
                    harness
                        .latestOrNull("cint/publishTags")
                        ?.path("params")
                        ?.path("annotations")
                        ?.takeIf { annotations ->
                            annotations.any { annotation ->
                                annotation.path("intents").any { it.asText() == "LOGGING" }
                            }
                        }
                }
            assertTrue(tags.size() > 0)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun cachePersistFalseKeepsReuseInMemoryOnly() {
        val root = Files.createTempDirectory("cint-reuse-memory-only")
        try {
            val provider = ReuseProvider()
            val harness =
                harness(
                    root,
                    suggestionSettings = settings(cachePersist = false),
                    service = service(provider),
                )
            harness.initialize()
            val uri = root.resolve("Facts.kt").toString()
            harness.open(uri, source, version = 1)
            harness.suggest(uri, version = 1, id = 2, requestId = "first")
            val first = harness.waitForResponse(2)
            assertFalse(Files.exists(AiVerdictCacheStore.path(root)))

            harness.suggest(uri, version = 1, id = 3, requestId = "second")
            val second = harness.waitForResponse(3)
            assertEquals(first.path("total").asInt(), second.path("reused").asInt())
            assertEquals(0, second.path("graded").asInt())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun corruptCacheFileFallsBackToGrading() {
        val root = Files.createTempDirectory("cint-reuse-corrupt")
        try {
            val cacheFile = AiVerdictCacheStore.path(root)
            Files.createDirectories(cacheFile.parent)
            Files.writeString(cacheFile, "{ not valid json")
            val provider = ReuseProvider()
            val harness = harness(root, suggestionSettings = settings(), service = service(provider))
            harness.initialize()
            val uri = root.resolve("Facts.kt").toString()
            harness.open(uri, source, version = 1)
            harness.suggest(uri, version = 1, id = 2, requestId = "corrupt")
            val response = harness.waitForResponse(2)

            assertEquals(0, response.path("reused").asInt())
            assertTrue(response.path("graded").asInt() > 0)
            assertTrue(provider.calls.get() > 0)
            assertTrue(Files.readString(cacheFile).contains("\"entries\""))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun legacySuggestionDecisionReadsAsUnversionedIdentity() {
        val root = Files.createTempDirectory("cint-reuse-decision-version")
        try {
            val file = root.resolve(".cint").resolve("suggestions.json")
            Files.createDirectories(file.parent)
            Files.writeString(
                file,
                """
                {"version":1,"decisions":[{"candidateId":"legacy","path":"Facts.kt","contentHash":"x","intents":["SEARCH"],"reason":"GAP","decision":"reject","createdAt":"2026-10-23T00:00:00Z","anchorFingerprint":"fingerprint"}]}
                """.trimIndent(),
            )
            val decision =
                dev.cint.server
                    .SuggestionStore(root)
                    .load()
                    .single()
            assertEquals(
                dev.cint.core.syntax.UnitIdentity.UNVERSIONED_FINGERPRINT_VERSION,
                dev.cint.core.syntax.UnitIdentity
                    .versionOf(decision.anchorFingerprintVersion),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun splitRestrictionConfirmAndSuggestUseTheRegionIdentity() {
        val root = Files.createTempDirectory("cint-reuse-split-e2e")
        try {
            val oldScript = "function make() {\n  console.log(\"x\")\n}\n"
            val formattedScript = "function make() {\n  console.log( \"x\" )\n}\n"
            val oldHost = vueHost(oldScript)
            val formattedHost = "<!-- inserted -->\n\n" + vueHost(formattedScript)
            val uri = root.resolve("Facts.vue").toString()
            val harness =
                harness(
                    root,
                    suggestionSettings = settings(cacheEnabled = false),
                    service = service(DuplicateProposalProvider()),
                )
            harness.initialize()
            harness.open(uri, oldHost, version = 1, language = "vue")
            harness.suggest(uri, version = 1, id = 2, requestId = "first")
            val first = harness.waitForResponse(2)
            assertTrue(first.path("accepted").asBoolean(), first.toString())
            val proposal = proposalGroups(harness.latest("cint/publishCandidates")).single()
            harness.send(
                """{"jsonrpc":"2.0","id":3,"method":"cint/candidates/decide","params":{"uri":${quote(
                    uri,
                )},"version":1,"proposalId":${quote(proposal["proposalId"].asText())},"decision":"confirm"}}""",
            )
            assertEquals("confirm", harness.waitForResponse(3).path("decision").asText())
            harness.send("""{"jsonrpc":"2.0","id":4,"method":"cint/restrictions/list","params":{"uri":${quote(uri)}}}""")
            assertEquals(
                "active",
                harness
                    .waitForResponse(4)
                    .path("restrictions")[0]
                    .path("status")
                    .asText(),
            )

            harness.change(uri, formattedHost, version = 2, language = "vue")
            harness.send("""{"jsonrpc":"2.0","id":5,"method":"cint/restrictions/list","params":{"uri":${quote(uri)}}}""")
            assertEquals(
                "active",
                harness
                    .waitForResponse(5)
                    .path("restrictions")[0]
                    .path("status")
                    .asText(),
            )
            harness.suggest(uri, version = 2, id = 6, requestId = "after-format")
            val second = harness.waitForResponse(6)
            assertTrue(second.path("accepted").asBoolean(), second.toString())
            assertTrue(proposalGroups(harness.latest("cint/publishCandidates")).isEmpty())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun identicalUnitsInDifferentScriptRegionsDoNotCrossSuppress() {
        val root = Files.createTempDirectory("cint-reuse-two-regions")
        try {
            val script = "function make() {\n  console.log(\"x\")\n}\n"
            val host = vueHosts(script, script)
            val uri = root.resolve("Facts.vue").toString()
            val harness =
                harness(
                    root,
                    suggestionSettings = settings(cacheEnabled = false),
                    service = service(DuplicateProposalProvider()),
                )
            harness.initialize()
            harness.open(uri, host, version = 1, language = "vue")
            harness.suggest(uri, version = 1, id = 2, requestId = "first")
            harness.waitForResponse(2)
            val firstProposals = proposalGroups(harness.latest("cint/publishCandidates"))
            assertEquals(2, firstProposals.size)
            harness.send(
                """{"jsonrpc":"2.0","id":3,"method":"cint/candidates/decide","params":{"uri":${quote(
                    uri,
                )},"version":1,"proposalId":${quote(
                    firstProposals.sortedBy { it["startByte"].asInt() }.first()["proposalId"].asText(),
                )},"decision":"confirm"}}""",
            )
            assertEquals("confirm", harness.waitForResponse(3).path("decision").asText())
            harness.send("""{"jsonrpc":"2.0","id":5,"method":"cint/restrictions/list","params":{"uri":${quote(uri)}}}""")
            assertEquals(
                "dormant",
                harness
                    .waitForResponse(5)
                    .path("restrictions")[0]
                    .path("status")
                    .asText(),
            )

            harness.suggest(uri, version = 1, id = 4, requestId = "second")
            harness.waitForResponse(4)
            val remaining = proposalGroups(harness.latest("cint/publishCandidates"))
            assertEquals(1, remaining.size)
            val regions =
                dev.cint.frontend.VueFrontend
                    .regions(
                        dev.cint.core.facts.model
                            .SourceFile(uri, host),
                    )
            assertTrue(remaining.single()["startByte"].asInt() >= regions[1].startByte)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun splitDeclarationEditInvalidatesAReferencingUnit() {
        val root = Files.createTempDirectory("cint-reuse-split-declaration")
        try {
            val script = "function helper() {\n  return 1\n}\nfunction use() {\n  return helper()\n}\n"
            val host = vueHost(script)
            val provider = ReuseProvider()
            val harness =
                harness(
                    root,
                    suggestionSettings = settings(cacheEnabled = false),
                    service = service(provider),
                )
            harness.initialize()
            val uri = root.resolve("Facts.vue").toString()
            harness.open(uri, host, version = 1, language = "vue")
            harness.suggest(uri, version = 1, id = 2, requestId = "first")
            harness.waitForResponse(2)
            provider.seenTexts.clear()

            val edited = vueHost(script.replace("return 1", "return 2"))
            harness.change(uri, edited, version = 2, language = "vue")
            harness.suggest(uri, version = 2, id = 3, requestId = "declaration")
            harness.waitForResponse(3)

            assertTrue("helper()" in provider.seenTexts, provider.seenTexts.toString())
            assertTrue("javascript" in provider.seenLanguages, provider.seenLanguages.toString())
            assertTrue("javascript" in provider.seenPromptLanguages, provider.seenPromptLanguages.toString())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun splitTypeLookupUsesTheRegionFrontendAndOracle() {
        val root = Files.createTempDirectory("cint-reuse-split-type-lookup")
        try {
            val script = "function make() {\n  return f(a, b)\n}\n"
            val host = vueHost(script)
            val harness =
                harness(
                    root,
                    suggestionSettings = settings(cacheEnabled = false),
                    service = service(ReuseProvider()),
                    useLsp = true,
                )
            harness.server.oracles["javascript"] =
                dev.cint.core.facts.oracle.MapOracle(
                    (0 until host.toByteArray(Charsets.UTF_8).size).associateWith {
                        dev.cint.core.facts.oracle
                            .TypeFact("region.Type")
                    },
                )
            harness.initialize()
            val uri = root.resolve("Facts.vue").toString()
            harness.open(uri, host, version = 1, language = "vue")
            val document = harness.sessionDocumentForTest(uri, setOf("SEARCH")) ?: error("no document")
            val unit = document.units.first { it.text == "f(a, b)" }
            val fact = harness.typeLookupForTest(uri, document)?.invoke(unit.startByte, unit.endByte)

            assertEquals("region.Type", fact?.get("fqName"))
            assertEquals("javascript", unit.language)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun splitDiscoveryIncludesHostAndRegionLanguages() {
        val root = Files.createTempDirectory("cint-reuse-split-host-units")
        try {
            val host =
                "<template>{{ foo() }}</template>\n\n<script>\nfunction make() {\n  return f(a, b)\n}\n</script>\n"
            val harness = harness(root, suggestionSettings = settings(cacheEnabled = false))
            harness.initialize()
            val uri = root.resolve("Facts.vue").toString()
            harness.open(uri, host, version = 1, language = "vue")
            val document = harness.sessionDocumentForTest(uri, setOf("SEARCH")) ?: error("no document")

            assertTrue(document.units.any { it.language == "vue" })
            assertTrue(document.units.any { it.language == "javascript" })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun hostLevelSplitRestrictionStatusResolvesAgainstTheHost() {
        val root = Files.createTempDirectory("cint-reuse-split-host-status")
        try {
            val host = "<template>{{ foo() }}</template>\n\n<script>\nfunction make() {\n  return f(a, b)\n}\n</script>\n"
            val harness = harness(root, suggestionSettings = settings(cacheEnabled = false))
            harness.initialize()
            val uri = root.resolve("Facts.vue").toString()
            harness.open(uri, host, version = 1, language = "vue")
            val source =
                dev.cint.core.facts.model
                    .SourceFile(uri, host)
            val tree =
                dev.cint.frontend.VueFrontend
                    .parse(source)
            val syntax =
                dev.cint.frontend.VueFrontend
                    .syntax(tree)
            val unit = syntax.expressionUnits(tree).first { "foo()" in it.text }
            val record =
                AiRestrictionRecord(
                    id = "host-level",
                    path = "Facts.vue",
                    span = unit.span,
                    contentHash = contentHash(unit.text),
                    remove = listOf(RestrictionOperation("LOGGING", 0, unit.text.length)),
                    add = listOf(RestrictionOperation("SEARCH", 0, unit.text.length)),
                    unitFingerprint =
                        dev.cint.core.syntax.UnitIdentity.unitFingerprint(
                            source,
                            tree,
                            syntax,
                            unit.startByte,
                            unit.endByte,
                        ),
                )
            AiRestrictionsFile.add(root, record)

            assertTrue(harness.resolveRestrictionForStatus(uri, record).isNotEmpty())
            harness.send("""{"jsonrpc":"2.0","id":2,"method":"cint/restrictions/list","params":{"uri":${quote(uri)}}}""")
            assertEquals(
                "dormant",
                harness
                    .waitForResponse(2)
                    .path("restrictions")[0]
                    .path("status")
                    .asText(),
            )

            val proposal =
                dev.cint.suggest.model.CandidateProposal(
                    proposalId = "host-proposal",
                    anchorId = "host-anchor",
                    path = "Facts.vue",
                    contentHash = contentHash(unit.text),
                    unitId = "u0",
                    span = unit.span,
                    startByte = unit.startByte,
                    endByte = unit.endByte,
                    operations =
                        listOf(
                            dev.cint.suggest.model.ProposalOperation(
                                dev.cint.suggest.model.ProposalOperationKind.REMOVE,
                                "LOGGING",
                                unit.startByte,
                                unit.endByte,
                                unit.span,
                                0.6,
                            ),
                            dev.cint.suggest.model.ProposalOperation(
                                dev.cint.suggest.model.ProposalOperationKind.ADD,
                                "SEARCH",
                                unit.startByte,
                                unit.endByte,
                                unit.span,
                                0.6,
                            ),
                        ),
                )
            harness.publishCandidatesForTest(uri, listOf(proposal))
            assertEquals(1, proposalGroups(harness.latest("cint/publishCandidates")).size)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun hostLevelSplitUserAnnotationConfirmIsPublished() {
        val root = Files.createTempDirectory("cint-reuse-split-host-confirm")
        try {
            val host =
                "<template>{{ foo() }}</template>\n\n<script>\nfunction make() {\n  return f(a, b)\n}\n</script>\n"
            val provider = HostUnitProvider()
            val harness =
                harness(
                    root,
                    suggestionSettings = settings(cacheEnabled = false),
                    service = service(provider),
                )
            harness.initialize()
            val uri = root.resolve("Facts.vue").toString()
            harness.open(uri, host, version = 1, language = "vue")
            harness.suggest(uri, version = 1, id = 2, requestId = "host")
            harness.waitForResponse(2)
            val candidate = candidates(harness.latest("cint/publishCandidates"), "SEARCH").first()
            harness.send(
                """{"jsonrpc":"2.0","id":3,"method":"cint/candidates/decide","params":{"uri":${quote(
                    uri,
                )},"version":1,"candidateId":${quote(candidate["candidateId"].asText())},"decision":"confirm"}}""",
            )
            assertEquals("confirm", harness.waitForResponse(3).path("decision").asText())
            val tags = harness.latest("cint/publishTags").path("params")
            assertTrue(
                tags.path("annotations").any {
                    it.path("source").asText() == "user" &&
                        it.path("intents").any { intent -> intent.asText() == "SEARCH" } &&
                        it.path("startLine").asInt() == candidate["startLine"].asInt()
                },
                tags.toString(),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun hostLevelRestrictionDoesNotSuppressARegionProposal() {
        val root = Files.createTempDirectory("cint-reuse-host-restriction-region-proposal")
        try {
            val host = "<template>{{ foo() }}</template>\n\n<script>\nfunction make() {\n  return f(a, b)\n}\n</script>\n"
            val harness = harness(root, suggestionSettings = settings(cacheEnabled = false))
            harness.initialize()
            val uri = root.resolve("Facts.vue").toString()
            harness.open(uri, host, version = 1, language = "vue")
            val source =
                dev.cint.core.facts.model
                    .SourceFile(uri, host)
            val tree =
                dev.cint.frontend.VueFrontend
                    .parse(source)
            val syntax =
                dev.cint.frontend.VueFrontend
                    .syntax(tree)
            val hostUnit = syntax.expressionUnits(tree).first { "foo()" in it.text }
            AiRestrictionsFile.add(
                root,
                AiRestrictionRecord(
                    id = "host-record",
                    path = "Facts.vue",
                    span = hostUnit.span,
                    contentHash = contentHash(hostUnit.text),
                    remove = listOf(RestrictionOperation("LOGGING", 0, hostUnit.text.length)),
                    add = listOf(RestrictionOperation("SEARCH", 0, hostUnit.text.length)),
                    unitFingerprint =
                        dev.cint.core.syntax.UnitIdentity.unitFingerprint(
                            source,
                            tree,
                            syntax,
                            hostUnit.startByte,
                            hostUnit.endByte,
                        ),
                ),
            )
            val region =
                dev.cint.frontend.VueFrontend
                    .regions(source)
                    .single()
            val localStart = region.text.indexOf("f(a, b)")
            val startByte = region.startByte + localStart
            val endByte = startByte + "f(a, b)".length
            val span = UserAnnotationResolver.spanFor(host, startByte, endByte) ?: error("span")
            val proposal =
                dev.cint.suggest.model.CandidateProposal(
                    proposalId = "region-proposal",
                    anchorId = "region-anchor",
                    path = "Facts.vue",
                    contentHash = contentHash("f(a, b)"),
                    unitId = "u0",
                    span = span,
                    startByte = startByte,
                    endByte = endByte,
                    operations =
                        listOf(
                            dev.cint.suggest.model.ProposalOperation(
                                dev.cint.suggest.model.ProposalOperationKind.REMOVE,
                                "LOGGING",
                                startByte,
                                endByte,
                                span,
                                0.6,
                            ),
                            dev.cint.suggest.model.ProposalOperation(
                                dev.cint.suggest.model.ProposalOperationKind.ADD,
                                "SEARCH",
                                startByte,
                                endByte,
                                span,
                                0.6,
                            ),
                        ),
                )
            harness.publishCandidatesForTest(uri, listOf(proposal))

            assertEquals(1, proposalGroups(harness.latest("cint/publishCandidates")).size)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun vueHost(script: String): String = "<template></template>\n\n<script>\n$script</script>\n"

    private fun vueHosts(vararg scripts: String): String =
        "<template></template>\n\n" +
            scripts.joinToString("\n") { script -> "<script>\n$script</script>\n" }

    private fun settings(
        promptVersion: String = "reuse-prompt-v1",
        cacheEnabled: Boolean = true,
        cachePersist: Boolean = true,
    ): SuggestionSettings =
        SuggestionSettings(
            enabled = true,
            provider = "reuse",
            model = "reuse-v1",
            discoveryEnabled = true,
            restrictionsEnabled = true,
            promptVersion = promptVersion,
            cacheEnabled = cacheEnabled,
            cachePersist = cachePersist,
            cacheMaxEntries = 100,
            cacheTtlHours = 720,
            sessionTimeoutMs = 5_000,
            maxTokensPerRequest = 16_384,
        )

    private fun service(provider: IntentSuggestionProvider): SuggestionService =
        SuggestionService(SuggestionProviderRegistry(listOf(provider)))

    private fun quote(value: String): String = mapper.writeValueAsString(value)

    private fun spanOf(candidate: JsonNode): Span =
        Span(
            candidate["startLine"].asInt(),
            candidate["startCol"].asInt(),
            candidate["endLine"].asInt(),
            candidate["endCol"].asInt(),
        )

    private inner class ReuseProvider :
        IntentSuggestionProvider,
        AnnotationSessionProvider {
        override val id: String = "reuse"
        override val model: String = "reuse-v1"
        override val defaultPromptVersion: String = "reuse-prompt-v1"
        val calls: AtomicInteger = AtomicInteger(0)
        val seenTexts: MutableSet<String> = linkedSetOf()
        val seenLanguages: MutableSet<String> = linkedSetOf()
        val seenPromptLanguages: MutableSet<String> = linkedSetOf()

        override fun suggest(context: CandidateContext): List<dev.cint.api.CandidateEmit> = emptyList()

        override fun complete(request: AgentRequest): AgentTurn {
            calls.incrementAndGet()
            val user =
                request.messages
                    .lastOrNull { it.role == "user" }
                    ?.content
                    .orEmpty()
            val root = mapper.readTree(user)
            val units = root.path("units")
            root.path("languages").forEach {
                seenLanguages += it.asText()
                seenPromptLanguages += it.asText()
            }
            units.forEach { seenLanguages += it.path("language").asText() }
            val verdicts =
                units.map { unit ->
                    val unitText = unit.path("text").asText()
                    seenTexts += unitText
                    val start = unit.path("startByte").asInt()
                    if (unitText.contains("find(")) {
                        val index = unitText.indexOf("find(")
                        linkedMapOf(
                            "unitId" to unit.path("unitId").asText(),
                            "add" to
                                listOf(
                                    linkedMapOf(
                                        "intent" to "SEARCH",
                                        "startByte" to start + index,
                                        "endByte" to start + index + "find(text)".length,
                                        "confidence" to 0.62,
                                        "rationale" to "regex find searches the text",
                                    ),
                                ),
                        )
                    } else {
                        linkedMapOf("unitId" to unit.path("unitId").asText(), "verdict" to "none")
                    }
                }
            return AgentTurn.Calls(
                listOf(
                    ToolCall(
                        id = "call-${calls.get()}",
                        name = "submit_verdicts",
                        arguments = mapper.writeValueAsString(linkedMapOf("verdicts" to verdicts)),
                    ),
                ),
            )
        }
    }

    private inner class DuplicateProvider :
        IntentSuggestionProvider,
        AnnotationSessionProvider {
        override val id: String = "reuse"
        override val model: String = "reuse-v1"
        override val defaultPromptVersion: String = "reuse-prompt-v1"

        override fun suggest(context: CandidateContext): List<dev.cint.api.CandidateEmit> = emptyList()

        override fun complete(request: AgentRequest): AgentTurn {
            val user =
                request.messages
                    .lastOrNull { it.role == "user" }
                    ?.content
                    .orEmpty()
            val units = mapper.readTree(user).path("units")
            val verdicts =
                units.map { unit ->
                    val text = unit.path("text").asText()
                    val start = unit.path("startByte").asInt()
                    if (text == "println(x)") {
                        linkedMapOf(
                            "unitId" to unit.path("unitId").asText(),
                            "add" to
                                listOf(
                                    linkedMapOf(
                                        "intent" to "SEARCH",
                                        "startByte" to start,
                                        "endByte" to start + text.length,
                                        "confidence" to 0.6,
                                    ),
                                ),
                        )
                    } else {
                        linkedMapOf("unitId" to unit.path("unitId").asText(), "verdict" to "none")
                    }
                }
            return AgentTurn.Calls(
                listOf(
                    ToolCall(
                        id = "duplicate",
                        name = "submit_verdicts",
                        arguments = mapper.writeValueAsString(linkedMapOf("verdicts" to verdicts)),
                    ),
                ),
            )
        }
    }

    private inner class HostUnitProvider :
        IntentSuggestionProvider,
        AnnotationSessionProvider {
        override val id: String = "reuse"
        override val model: String = "reuse-v1"
        override val defaultPromptVersion: String = "reuse-prompt-v1"

        override fun suggest(context: CandidateContext): List<dev.cint.api.CandidateEmit> = emptyList()

        override fun complete(request: AgentRequest): AgentTurn {
            val user =
                request.messages
                    .lastOrNull { it.role == "user" }
                    ?.content
                    .orEmpty()
            val units = mapper.readTree(user).path("units")
            val verdicts =
                units.map { unit ->
                    val text = unit.path("text").asText()
                    val start = unit.path("startByte").asInt()
                    if ("foo()" in text) {
                        linkedMapOf(
                            "unitId" to unit.path("unitId").asText(),
                            "add" to
                                listOf(
                                    linkedMapOf(
                                        "intent" to "SEARCH",
                                        "startByte" to start,
                                        "endByte" to start + text.length,
                                        "confidence" to 0.6,
                                    ),
                                ),
                        )
                    } else {
                        linkedMapOf("unitId" to unit.path("unitId").asText(), "verdict" to "none")
                    }
                }
            return AgentTurn.Calls(
                listOf(
                    ToolCall(
                        id = "host-unit",
                        name = "submit_verdicts",
                        arguments = mapper.writeValueAsString(linkedMapOf("verdicts" to verdicts)),
                    ),
                ),
            )
        }
    }

    private inner class SiblingProvider :
        IntentSuggestionProvider,
        AnnotationSessionProvider {
        override val id: String = "reuse"
        override val model: String = "reuse-v1"
        override val defaultPromptVersion: String = "reuse-prompt-v1"

        override fun suggest(context: CandidateContext): List<dev.cint.api.CandidateEmit> = emptyList()

        override fun complete(request: AgentRequest): AgentTurn {
            val user =
                request.messages
                    .lastOrNull { it.role == "user" }
                    ?.content
                    .orEmpty()
            val units = mapper.readTree(user).path("units")
            val verdicts =
                units.map { unit ->
                    val text = unit.path("text").asText()
                    val start = unit.path("startByte").asInt()
                    if (text == "foo()") {
                        linkedMapOf(
                            "unitId" to unit.path("unitId").asText(),
                            "add" to
                                listOf(
                                    linkedMapOf(
                                        "intent" to "SEARCH",
                                        "startByte" to start,
                                        "endByte" to start + text.length,
                                        "confidence" to 0.6,
                                    ),
                                ),
                        )
                    } else {
                        linkedMapOf("unitId" to unit.path("unitId").asText(), "verdict" to "none")
                    }
                }
            return AgentTurn.Calls(
                listOf(
                    ToolCall(
                        id = "sibling",
                        name = "submit_verdicts",
                        arguments = mapper.writeValueAsString(linkedMapOf("verdicts" to verdicts)),
                    ),
                ),
            )
        }
    }

    private inner class PartialTokenProvider :
        IntentSuggestionProvider,
        AnnotationSessionProvider {
        override val id: String = "reuse"
        override val model: String = "reuse-v1"
        override val defaultPromptVersion: String = "reuse-prompt-v1"

        override fun suggest(context: CandidateContext): List<dev.cint.api.CandidateEmit> = emptyList()

        override fun complete(request: AgentRequest): AgentTurn {
            val user =
                request.messages
                    .lastOrNull { it.role == "user" }
                    ?.content
                    .orEmpty()
            val units = mapper.readTree(user).path("units")
            val verdicts =
                units.map { unit ->
                    val text = unit.path("text").asText()
                    val start = unit.path("startByte").asInt()
                    val index = text.indexOf("find(")
                    if (index >= 0) {
                        linkedMapOf(
                            "unitId" to unit.path("unitId").asText(),
                            "add" to
                                listOf(
                                    linkedMapOf(
                                        "intent" to "SEARCH",
                                        "startByte" to start + index,
                                        "endByte" to start + index + 2,
                                        "confidence" to 0.6,
                                    ),
                                ),
                        )
                    } else {
                        linkedMapOf("unitId" to unit.path("unitId").asText(), "verdict" to "none")
                    }
                }
            return AgentTurn.Calls(
                listOf(
                    ToolCall(
                        id = "partial-token",
                        name = "submit_verdicts",
                        arguments = mapper.writeValueAsString(linkedMapOf("verdicts" to verdicts)),
                    ),
                ),
            )
        }
    }

    private inner class NarrowProposalProvider :
        IntentSuggestionProvider,
        AnnotationSessionProvider {
        override val id: String = "reuse"
        override val model: String = "reuse-v1"
        override val defaultPromptVersion: String = "reuse-prompt-v1"

        override fun suggest(context: CandidateContext): List<dev.cint.api.CandidateEmit> = emptyList()

        override fun complete(request: AgentRequest): AgentTurn {
            val user =
                request.messages
                    .lastOrNull { it.role == "user" }
                    ?.content
                    .orEmpty()
            val units = mapper.readTree(user).path("units")
            val verdicts =
                units.map { unit ->
                    val text = unit.path("text").asText()
                    val start = unit.path("startByte").asInt()
                    val logging =
                        unit.path("existingAnnotations").firstOrNull { annotation ->
                            annotation.path("intents").any { it.asText() == "LOGGING" } &&
                                annotation.path("startByte").asInt() == start &&
                                annotation.path("endByte").asInt() == start + text.length
                        }
                    val argument = text.indexOf("message")
                    if (text == "log.warn(message)" && logging != null && argument >= 0) {
                        linkedMapOf(
                            "unitId" to unit.path("unitId").asText(),
                            "remove" to
                                listOf(
                                    linkedMapOf(
                                        "intent" to "LOGGING",
                                        "startByte" to start,
                                        "endByte" to start + text.length,
                                    ),
                                ),
                            "add" to
                                listOf(
                                    linkedMapOf(
                                        "intent" to "SEARCH",
                                        "startByte" to start + argument,
                                        "endByte" to start + argument + "message".length,
                                        "confidence" to 0.6,
                                    ),
                                ),
                        )
                    } else {
                        linkedMapOf("unitId" to unit.path("unitId").asText(), "verdict" to "none")
                    }
                }
            return AgentTurn.Calls(
                listOf(
                    ToolCall(
                        id = "narrow-proposal",
                        name = "submit_verdicts",
                        arguments = mapper.writeValueAsString(linkedMapOf("verdicts" to verdicts)),
                    ),
                ),
            )
        }
    }

    private inner class DuplicateProposalProvider :
        IntentSuggestionProvider,
        AnnotationSessionProvider {
        override val id: String = "reuse"
        override val model: String = "reuse-v1"
        override val defaultPromptVersion: String = "reuse-prompt-v1"
        val seenLanguages: MutableSet<String> = linkedSetOf()

        override fun suggest(context: CandidateContext): List<dev.cint.api.CandidateEmit> = emptyList()

        override fun complete(request: AgentRequest): AgentTurn {
            val user =
                request.messages
                    .lastOrNull { it.role == "user" }
                    ?.content
                    .orEmpty()
            val root = mapper.readTree(user)
            val units = root.path("units")
            root.path("languages").forEach { seenLanguages += it.asText() }
            units.forEach { seenLanguages += it.path("language").asText() }
            val verdicts =
                units.map { unit ->
                    val logging =
                        unit.path("existingAnnotations").firstOrNull { annotation ->
                            annotation.path("intents").any { it.asText() == "LOGGING" } &&
                                annotation.path("startByte").asInt() == unit.path("startByte").asInt() &&
                                annotation.path("endByte").asInt() == unit.path("endByte").asInt()
                        }
                    if (logging != null) {
                        linkedMapOf(
                            "unitId" to unit.path("unitId").asText(),
                            "remove" to
                                listOf(
                                    linkedMapOf(
                                        "intent" to "LOGGING",
                                        "startByte" to logging.path("startByte").asInt(),
                                        "endByte" to logging.path("endByte").asInt(),
                                    ),
                                ),
                            "add" to
                                listOf(
                                    linkedMapOf(
                                        "intent" to "SEARCH",
                                        "startByte" to unit.path("startByte").asInt(),
                                        "endByte" to unit.path("endByte").asInt(),
                                        "confidence" to 0.6,
                                    ),
                                ),
                        )
                    } else {
                        linkedMapOf("unitId" to unit.path("unitId").asText(), "verdict" to "none")
                    }
                }
            return AgentTurn.Calls(
                listOf(
                    ToolCall(
                        id = "duplicate-proposal",
                        name = "submit_verdicts",
                        arguments = mapper.writeValueAsString(linkedMapOf("verdicts" to verdicts)),
                    ),
                ),
            )
        }
    }

    private fun proposalGroups(message: JsonNode): List<JsonNode> = message.path("params").path("groups").filter { it.has("proposalId") }
}

private fun Harness.latestOrNull(method: String): JsonNode? = messages().lastOrNull { it["method"]?.asText() == method }
