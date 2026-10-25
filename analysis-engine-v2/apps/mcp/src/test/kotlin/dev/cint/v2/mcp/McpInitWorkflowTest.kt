package dev.cint.v2.mcp

import dev.cint.contracts.data.Confidence
import dev.cint.contracts.data.Intents
import dev.cint.contracts.data.Status
import dev.cint.contracts.init.InitCheckpoint
import dev.cint.contracts.init.InitProposal
import dev.cint.contracts.init.InitStatus
import dev.cint.contracts.knowledge.AnnotationScope
import dev.cint.contracts.knowledge.KnowledgeChange
import dev.cint.contracts.knowledge.PackDefault
import dev.cint.contracts.knowledge.FunctionSelector
import dev.cint.contracts.ports.FallbackAi
import dev.cint.contracts.ports.FallbackAiPolicy
import dev.cint.contracts.ports.FallbackAiRequest
import dev.cint.contracts.ports.InitCheckpointStore
import dev.cint.contracts.ports.KnowledgeRepositoryLocation
import dev.cint.contracts.ports.PackManifest
import dev.cint.contracts.ports.Playbook
import dev.cint.contracts.ports.RuleSpec
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory
import dev.cint.knowledge.InMemoryKnowledgeStore
import dev.cint.knowledge.persistence.FileKnowledgeRepository
import dev.cint.knowledge.persistence.PersistentKnowledgeStores
import dev.cint.runtime.DefaultRuntime
import dev.cint.runtime.InitCheckpointStores
import dev.cint.runtime.RuntimeComponents
import dev.cint.runtime.RuntimeConfig
import dev.cint.runtime.RuntimeFactory
import dev.cint.runtime.RuntimeLanguageComposition
import dev.cint.v2.testing.fakes.FakeClock
import dev.cint.v2.testing.fakes.FakeIndex
import dev.cint.v2.testing.fakes.FakeIntentPipeline
import dev.cint.v2.testing.fakes.FakeLanguageSupport
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
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

class McpInitWorkflowTest {
    private val temporaryRoots = mutableListOf<Path>()
    private val now = Instant.parse("2025-02-01T00:00:00Z")

    @AfterTest
    fun cleanUp() {
        temporaryRoots.forEach { runCatching { it.toFile().deleteRecursively() } }
    }

    @Test
    fun scriptedAgentInitializesSampleRepoAcrossMultipleCalls() {
        val harness = harness(temporaryRoot())
        harness.client().use { client ->
            client.initialize()

            val begun = client.structured(client.tool(10, "init.begin"))
            assertEquals("IN_PROGRESS", begun.getValue("status").jsonPrimitive.content)
            assertFalse(begun.getValue("resumed").jsonPrimitive.content.toBoolean())

            val suggested = client.structured(
                client.tool(
                    11,
                    "knowledge.suggestDefaults",
                    buildJsonObject {
                        put("languages", buildJsonArray { add(JsonPrimitive("fake")) })
                    },
                ),
            )
            assertEquals(1, suggested.getValue("count").jsonPrimitive.content.toInt())
            assertFalse(suggested.getValue("knowledgeMaterialized").jsonPrimitive.content.toBoolean())

            val materialized = client.structured(
                client.tool(
                    12,
                    "knowledge.materializeDefaults",
                    buildJsonObject {
                        put("languages", buildJsonArray { add(JsonPrimitive("fake")) })
                    },
                ),
            )
            assertTrue(materialized.getValue("materialized").jsonPrimitive.content.toBoolean())
            assertEquals(1, harness.store.functionAnnotations().getOrThrow().size)

            val analyzed = client.structured(
                client.tool(13, "analysis.run", analysisArguments(listOf(FILE_A, FILE_B))),
            )
            assertEquals(
                listOf(FILE_A, FILE_B),
                analyzed.getValue("analyzedPaths").jsonArray.map { it.jsonPrimitive.content },
            )
            assertTrue(analyzed.getValue("skippedPaths").jsonArray.isEmpty())

            client.tool(14, "patterns.propose", patternArguments())
            val hypotheses = harness.store.patternHypotheses().getOrThrow()
            assertEquals(1, hypotheses.size)
            assertEquals(Status.SUGGESTED, hypotheses.single().status)

            client.tool(
                15,
                "patterns.validate",
                buildJsonObject {
                    put("id", hypotheses.single().selector.identityKey)
                    put("accept", true)
                },
            )
            val converted = client.structured(
                client.tool(16, "patterns.materialize", materializeArguments()),
            )
            assertEquals(1, converted.getValue("count").jsonPrimitive.content.toInt())
            assertEquals(
                setOf("logger", "seed"),
                harness.store.functionAnnotations().getOrThrow()
                    .map { it.selector.qualifiedName }
                    .toSet(),
            )

            val proposal = client.structured(
                client.tool(17, "changes.propose", proposalArguments("findUser")),
            )
            val proposalId = proposal.getValue("proposalId").jsonPrimitive.content
            client.tool(
                18,
                "changes.apply",
                buildJsonObject { put("proposalId", proposalId) },
            )
            assertEquals(
                setOf("findUser", "logger", "seed"),
                harness.store.functionAnnotations().getOrThrow()
                    .map { it.selector.qualifiedName }
                    .toSet(),
            )

            val status = client.structured(client.tool(19, "init.status"))
            val steps = status.getValue("completedSteps").jsonArray.map { it.jsonPrimitive.content }
            assertTrue(steps.contains("defaults.materialize"))
            assertTrue(steps.contains("analysis:$FILE_A"))
            assertTrue(steps.contains("analysis:$FILE_B"))
            assertTrue(steps.any { it.startsWith("pattern.materialize:") })
            assertTrue(steps.contains("proposal.apply:$proposalId"))

            client.tool(20, "init.complete")
            assertEquals(0, harness.fallback.calls)
        }
    }

    @Test
    fun interruptedInitResumesWithoutDuplicatingKnowledgeOrRedoingAnalysis() {
        val root = temporaryRoot()
        val first = harness(root)
        var proposalId: String
        first.client().use { client ->
            client.initialize()
            client.tool(20, "init.begin")
            client.tool(
                21,
                "knowledge.materializeDefaults",
                buildJsonObject {
                    put("languages", buildJsonArray { add(JsonPrimitive("fake")) })
                },
            )
            client.tool(22, "analysis.run", analysisArguments(listOf(FILE_A, FILE_B)))
            client.tool(23, "patterns.propose", patternArguments())
            val hypothesis = first.store.patternHypotheses().getOrThrow().single()
            client.tool(
                24,
                "patterns.validate",
                buildJsonObject {
                    put("id", hypothesis.selector.identityKey)
                    put("accept", true)
                },
            )
            client.tool(25, "patterns.materialize", materializeArguments())
            proposalId = client.structured(
                client.tool(26, "changes.propose", proposalArguments("findUser")),
            ).getValue("proposalId").jsonPrimitive.content
            client.tool(
                27,
                "changes.apply",
                buildJsonObject { put("proposalId", proposalId) },
            )
        }
        assertEquals(0, first.fallback.calls)
        val countBefore = first.store.functionAnnotations().getOrThrow().size
        assertEquals(3, countBefore)
        assertTrue(Files.isRegularFile(root.resolve(".cint/init-checkpoint.json")))

        val second = harness(root)
        second.client().use { client ->
            client.initialize()
            val resumed = client.structured(client.tool(30, "init.begin"))
            assertTrue(resumed.getValue("resumed").jsonPrimitive.content.toBoolean())
            val resumedSteps =
                resumed.getValue("completedSteps").jsonArray.map { it.jsonPrimitive.content }
            assertTrue(resumedSteps.contains("analysis:$FILE_A"))
            assertTrue(resumedSteps.contains("analysis:$FILE_B"))
            assertTrue(resumedSteps.contains("proposal.apply:$proposalId"))

            val rematerialized = client.structured(
                client.tool(
                    31,
                    "knowledge.materializeDefaults",
                    buildJsonObject {
                        put("languages", buildJsonArray { add(JsonPrimitive("fake")) })
                    },
                ),
            )
            assertFalse(rematerialized.getValue("materialized").jsonPrimitive.content.toBoolean())
            assertTrue(
                rematerialized.getValue("alreadyMaterialized").jsonPrimitive.content.toBoolean(),
            )

            val reanalyzed = client.structured(
                client.tool(32, "analysis.run", analysisArguments(listOf(FILE_A))),
            )
            assertEquals(
                listOf(FILE_A),
                reanalyzed.getValue("skippedPaths").jsonArray.map { it.jsonPrimitive.content },
            )
            assertTrue(reanalyzed.getValue("result") is JsonNull)

            val reconverted = client.structured(
                client.tool(33, "patterns.materialize", materializeArguments()),
            )
            assertEquals(1, reconverted.getValue("count").jsonPrimitive.content.toInt())

            val reproposed = client.structured(
                client.tool(34, "changes.propose", proposalArguments("findUser")),
            )
            assertEquals(
                proposalId,
                reproposed.getValue("proposalId").jsonPrimitive.content,
            )
            assertTrue(reproposed.getValue("idempotent").jsonPrimitive.content.toBoolean())
            val reapplied = client.structured(
                client.tool(
                    35,
                    "changes.apply",
                    buildJsonObject { put("proposalId", proposalId) },
                ),
            )
            assertTrue(reapplied.getValue("alreadyApplied").jsonPrimitive.content.toBoolean())

            val completed = client.structured(client.tool(36, "init.complete"))
            assertEquals("COMPLETE", completed.getValue("status").jsonPrimitive.content)
            assertEquals(
                countBefore,
                second.store.functionAnnotations().getOrThrow().size,
            )
            assertEquals(
                setOf("findUser", "logger", "seed"),
                second.store.functionAnnotations().getOrThrow()
                    .map { it.selector.qualifiedName }
                    .toSet(),
            )
            assertEquals(0, second.fallback.calls)
        }
    }

    @Test
    fun invalidHypothesesAndProposalsReturnStructuredErrors() {
        val harness = harness(temporaryRoot())
        harness.client().use { client ->
            client.initialize()
            client.tool(40, "init.begin")

            val emptyAnalysis = client.tool(
                401,
                "analysis.run",
                buildJsonObject { put("language", "fake") },
            )
            assertTrue(emptyAnalysis.getValue("isError").jsonPrimitive.content.toBoolean())

            val invalidIntent = client.tool(
                41,
                "patterns.propose",
                patternArguments(intents = listOf("NOT_AN_INTENT")),
            )
            assertTrue(invalidIntent.getValue("isError").jsonPrimitive.content.toBoolean())
            assertEquals(
                "ValidationError",
                client.structured(invalidIntent).getValue("error").jsonObject
                    .getValue("code").jsonPrimitive.content,
            )

            client.tool(42, "patterns.propose", patternArguments())
            val hypothesis = harness.store.patternHypotheses().getOrThrow().single()
            val unaccepted = client.tool(43, "patterns.materialize", materializeArguments())
            assertTrue(unaccepted.getValue("isError").jsonPrimitive.content.toBoolean())
            assertEquals(
                "PreconditionFailed",
                client.structured(unaccepted).getValue("error").jsonObject
                    .getValue("code").jsonPrimitive.content,
            )

            val noTargets = client.tool(
                44,
                "patterns.materialize",
                buildJsonObject { put("id", hypothesis.selector.identityKey) },
            )
            assertTrue(noTargets.getValue("isError").jsonPrimitive.content.toBoolean())

            val missingProposal = client.tool(
                45,
                "changes.apply",
                buildJsonObject { put("proposalId", "missing") },
            )
            assertTrue(missingProposal.getValue("isError").jsonPrimitive.content.toBoolean())
            assertEquals(
                "NotFound",
                client.structured(missingProposal).getValue("error").jsonObject
                    .getValue("code").jsonPrimitive.content,
            )

            assertEquals(0, harness.store.functionAnnotations().getOrThrow().size)
            assertEquals(0, harness.fallback.calls)
        }
    }

    @Test
    fun materializeDefaultsDoesNotOverwriteExistingProjectKnowledge() {
        val harness = harness(temporaryRoot())
        harness.client().use { client ->
            client.initialize()
            client.tool(50, "init.begin")
            val before = client.structured(client.tool(501, "repo.status"))
            assertFalse(before.getValue("repositoryInitialized").jsonPrimitive.content.toBoolean())
            client.tool(51, "function_annotations.set", annotationArguments("edited"))
            val materialized = client.structured(
                client.tool(
                    52,
                    "knowledge.materializeDefaults",
                    buildJsonObject {
                        put("languages", buildJsonArray { add(JsonPrimitive("fake")) })
                    },
                ),
            )
            assertFalse(materialized.getValue("materialized").jsonPrimitive.content.toBoolean())
            assertTrue(
                materialized.getValue("alreadyMaterialized").jsonPrimitive.content.toBoolean(),
            )
            val annotations = harness.store.functionAnnotations().getOrThrow()
            assertEquals(listOf("edited"), annotations.map { it.selector.qualifiedName })
            assertEquals(0, harness.fallback.calls)
        }
    }

    @Test
    fun checkpointStoreKeepsWorkflowsAndMergesUpdates() {
        val root = temporaryRoot()
        val first = InitCheckpointStores.open(root.toString(), null).getOrThrow()
        val second = InitCheckpointStores.open(root.toString(), null).getOrThrow()
        val later = now.plus(kotlin.time.Duration.parse("1s"))

        first.update("a") {
            ResultFactory.success(
                InitCheckpoint(
                    workflowId = "a",
                    status = InitStatus.IN_PROGRESS,
                    completedSteps = listOf("one"),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }.getOrThrow()
        second.update("b") {
            ResultFactory.success(
                InitCheckpoint(
                    workflowId = "b",
                    status = InitStatus.IN_PROGRESS,
                    completedSteps = listOf("three"),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }.getOrThrow()
        first.update("a") { existing ->
            ResultFactory.success(
                existing!!.copy(
                    completedSteps = (existing.completedSteps + "two").sorted(),
                    updatedAt = later,
                ),
            )
        }.getOrThrow()

        assertEquals(listOf("one", "two"), first.load("a").getOrThrow()!!.completedSteps)
        assertEquals(listOf("three"), second.load("b").getOrThrow()!!.completedSteps)
    }

    @Test
    fun checkpointUpdatesAreConcurrentSafe() {
        val root = temporaryRoot()
        val initial = InitCheckpointStores.open(root.toString(), null).getOrThrow()
        initial.update("concurrent") {
            ResultFactory.success(
                InitCheckpoint(
                    workflowId = "concurrent",
                    status = InitStatus.IN_PROGRESS,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }.getOrThrow()
        val later = now.plus(kotlin.time.Duration.parse("1s"))
        val start = CountDownLatch(1)
        val workers =
            (0 until 8).map { worker ->
                thread {
                    start.await()
                    repeat(10) { iteration ->
                        val store = InitCheckpointStores.open(root.toString(), null).getOrThrow()
                        store.update("concurrent") { existing ->
                            val checkpoint =
                                existing
                                    ?: return@update ResultFactory.failure(
                                        dev.cint.contracts.util.ContractError.NotFound(
                                            "init-checkpoint",
                                            "concurrent",
                                        ),
                                    )
                            ResultFactory.success(
                                checkpoint.copy(
                                    completedSteps =
                                        (checkpoint.completedSteps + "w$worker-$iteration").sorted(),
                                    updatedAt = later,
                                ),
                            )
                        }.getOrThrow()
                    }
                }
            }
        start.countDown()
        workers.forEach { it.join(10_000) }
        assertTrue(workers.none { it.isAlive })
        val checkpoint = initial.load("concurrent").getOrThrow()!!
        assertEquals(80, checkpoint.completedSteps.size)
        assertEquals(80, checkpoint.completedSteps.toSet().size)
    }

    @Test
    fun preExistingKnowledgeAllowsSkippedDefaultsCompletion() {
        val harness = harness(temporaryRoot())
        harness.client().use { client ->
            client.initialize()
            client.tool(55, "init.begin")
            client.tool(56, "function_annotations.set", annotationArguments("existing"))
            val materialized = client.structured(
                client.tool(
                    57,
                    "knowledge.materializeDefaults",
                    buildJsonObject { put("languages", buildJsonArray { add(JsonPrimitive("fake")) }) },
                ),
            )
            assertFalse(materialized.getValue("materialized").jsonPrimitive.content.toBoolean())
            client.tool(58, "analysis.run", analysisArguments(listOf(FILE_A)))
            val status = client.structured(client.tool(59, "init.status"))
            val steps = status.getValue("completedSteps").jsonArray.map { it.jsonPrimitive.content }
            assertTrue(steps.contains("defaults.skipped"))
            val complete = client.structured(client.tool(591, "init.complete"))
            assertEquals("COMPLETE", complete.getValue("status").jsonPrimitive.content)
        }
    }

    @Test
    fun noOpDefaultsDoesNotSatisfyCompletionWhenMaterializationWasNeeded() {
        val rejected =
            PackDefault(
                selector = FunctionSelector(qualifiedName = "blocked"),
                intents = setOf(Intents.LOGGING),
                packId = "demo",
                packVersion = "1",
                status = Status.REJECTED,
                createdAt = now,
            )
        val harness = harness(temporaryRoot(), packDefaults = listOf(rejected))
        harness.client().use { client ->
            client.initialize()
            client.tool(55, "init.begin")
            val materialized = client.structured(
                client.tool(
                    57,
                    "knowledge.materializeDefaults",
                    buildJsonObject { put("languages", buildJsonArray { add(JsonPrimitive("fake")) }) },
                ),
            )
            assertFalse(materialized.getValue("materialized").jsonPrimitive.content.toBoolean())
            client.tool(58, "analysis.run", analysisArguments(listOf(FILE_A)))
            val status = client.structured(client.tool(59, "init.status"))
            val steps = status.getValue("completedSteps").jsonArray.map { it.jsonPrimitive.content }
            assertTrue(steps.contains("defaults.unmet"))
            assertFalse(steps.contains("defaults.materialize"))
            val complete = client.tool(591, "init.complete")
            assertTrue(complete.getValue("isError").jsonPrimitive.content.toBoolean())
        }
    }

    @Test
    fun changesRejectPolicyBypasses() {
        val harness = harness(temporaryRoot())
        harness.client().use { client ->
            client.initialize()
            client.tool(140, "init.begin")
            val accepted =
                client.tool(141, "changes.propose", patternProposalArguments("ACCEPTED"))
            assertTrue(accepted.getValue("isError").jsonPrimitive.content.toBoolean())
            val defaults =
                client.tool(142, "changes.propose", materializeProposalArguments())
            assertTrue(defaults.getValue("isError").jsonPrimitive.content.toBoolean())

            val forbiddenPattern =
                InitProposal(
                    id = "forbidden-pattern",
                    description = "forbidden",
                    changes =
                        KnowledgeChange.Batch(
                            "forbidden",
                            listOf(
                                KnowledgeChange.SetPatternHypothesis(
                                    dev.cint.contracts.knowledge.PatternHypothesis.create(
                                        selector =
                                            dev.cint.contracts.knowledge.PatternSelector(
                                                pattern = "bypass",
                                            ),
                                        scope = AnnotationScope.GLOBAL,
                                        evidence = listOf("evidence"),
                                        examples = emptyList(),
                                        counterexamples = emptyList(),
                                        proposedIntents = setOf(Intents.LOGGING),
                                        status = Status.ACCEPTED,
                                        createdAt = now,
                                    ),
                                ),
                            ),
                        ),
                    createdAt = now,
                )
            harness.checkpoints.update(WORKFLOW_ID) { checkpoint ->
                ResultFactory.success(
                    checkpoint!!.copy(proposals = checkpoint.proposals + forbiddenPattern),
                )
            }.getOrThrow()
            val applyPattern = client.tool(
                143,
                "changes.apply",
                buildJsonObject { put("proposalId", "forbidden-pattern") },
            )
            assertTrue(applyPattern.getValue("isError").jsonPrimitive.content.toBoolean())
            assertTrue(harness.store.patternHypotheses().getOrThrow().isEmpty())
        }
    }

    @Test
    fun applyComparesMaterialFieldsAndUsesAppliedTimestamps() {
        val harness = harness(temporaryRoot())
        harness.client().use { client ->
            client.initialize()
            client.tool(150, "init.begin")
            val proposalId =
                client.structured(
                    client.tool(
                        151,
                        "changes.propose",
                        proposalArguments("partial", confidence = 0.9),
                    ),
                ).getValue("proposalId").jsonPrimitive.content
            client.tool(152, "changes.apply", buildJsonObject { put("proposalId", proposalId) })
            val original =
                harness.store.functionAnnotations().getOrThrow()
                    .single { it.selector.qualifiedName == "partial" }
            harness.store.setFunctionAnnotation(
                original.copy(confidence = Confidence(0.1)),
            ).getOrThrow()
            val replay = client.structured(
                client.tool(153, "changes.apply", buildJsonObject { put("proposalId", proposalId) }),
            )
            assertFalse(replay.getValue("alreadyApplied").jsonPrimitive.content.toBoolean())
            assertEquals(
                Confidence(0.9),
                harness.store.functionAnnotations().getOrThrow()
                    .single { it.selector.qualifiedName == "partial" }
                    .confidence,
            )

            val patternId =
                client.structured(
                    client.tool(154, "changes.propose", patternProposalArguments("SUGGESTED")),
                ).getValue("proposalId").jsonPrimitive.content
            client.tool(155, "changes.apply", buildJsonObject { put("proposalId", patternId) })
            val storedPattern = harness.store.patternHypotheses().getOrThrow().single()
            harness.store.setPatternHypothesis(
                storedPattern.copy(
                    confidence = Confidence(0.1),
                    evidence = listOf("changed"),
                ),
            ).getOrThrow()
            val patternReplay = client.structured(
                client.tool(156, "changes.apply", buildJsonObject { put("proposalId", patternId) }),
            )
            assertFalse(patternReplay.getValue("alreadyApplied").jsonPrimitive.content.toBoolean())
            val restored = harness.store.patternHypotheses().getOrThrow().single()
            assertEquals(Confidence.LOW, restored.confidence)
            assertEquals(listOf("call sites log"), restored.evidence)

            val timestampId =
                client.structured(
                    client.tool(157, "changes.propose", proposalArguments("timestamped")),
                ).getValue("proposalId").jsonPrimitive.content
            client.tool(158, "changes.apply", buildJsonObject { put("proposalId", timestampId) })
            harness.checkpoints.update(WORKFLOW_ID) { checkpoint ->
                ResultFactory.success(
                    checkpoint!!.copy(
                        proposals =
                            checkpoint.proposals.map {
                                if (it.id == timestampId) {
                                    it.copy(appliedKnowledgeTimestamp = Long.MAX_VALUE)
                                } else {
                                    it
                                }
                            },
                    ),
                )
            }.getOrThrow()
            val timestampReplay = client.structured(
                client.tool(
                    159,
                    "changes.apply",
                    buildJsonObject { put("proposalId", timestampId) },
                ),
            )
            assertFalse(
                timestampReplay.getValue("alreadyApplied").jsonPrimitive.content.toBoolean(),
            )
        }
    }

    @Test
    fun hypothesesCannotSelfAcceptAndReconversionIsIdempotent() {
        val harness = harness(temporaryRoot())
        harness.client().use { client ->
            client.initialize()
            client.tool(60, "init.begin")
            client.tool(61, "patterns.propose", patternArguments(status = "ACCEPTED"))
            val hypothesis = harness.store.patternHypotheses().getOrThrow().single()
            assertEquals(Status.SUGGESTED, hypothesis.status)

            val premature = client.tool(62, "patterns.materialize", materializeArguments())
            assertTrue(premature.getValue("isError").jsonPrimitive.content.toBoolean())

            client.tool(
                63,
                "patterns.validate",
                buildJsonObject {
                    put("id", hypothesis.selector.identityKey)
                    put("accept", true)
                },
            )
            val first =
                client.structured(client.tool(64, "patterns.materialize", materializeArguments()))
            assertEquals(1, first.getValue("created").jsonPrimitive.content.toInt())
            val annotation =
                harness.store.functionAnnotations().getOrThrow()
                    .single { it.selector.qualifiedName == "logger" }
            assertEquals(hypothesis.selector.identityKey, annotation.patternId)
            assertTrue(annotation.evidence.isNotEmpty())
            val updatedAt = annotation.updatedAt

            harness.clock.instant = harness.clock.instant.plus(kotlin.time.Duration.parse("1s"))
            val second =
                client.structured(client.tool(65, "patterns.materialize", materializeArguments()))
            assertEquals(0, second.getValue("created").jsonPrimitive.content.toInt())
            assertEquals(1, second.getValue("unchanged").jsonPrimitive.content.toInt())
            assertEquals(
                updatedAt,
                harness.store.functionAnnotations().getOrThrow()
                    .single { it.selector.qualifiedName == "logger" }
                    .updatedAt,
            )

            client.tool(
                66,
                "patterns.propose",
                patternArguments(scope = AnnotationScope.FILE.name, path = FILE_A),
            )
            val fileHypothesis =
                harness.store.patternHypotheses().getOrThrow()
                    .first { it.scope == AnnotationScope.FILE }
            client.tool(
                67,
                "patterns.validate",
                buildJsonObject {
                    put("id", fileHypothesis.selector.identityKey)
                    put("accept", true)
                },
            )
            val mismatch = client.tool(
                68,
                "patterns.materialize",
                buildJsonObject {
                    put("id", fileHypothesis.selector.identityKey)
                    put(
                        "targets",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("selector", buildJsonObject { put("qualifiedName", "outside") })
                                },
                            )
                        },
                    )
                },
            )
            assertTrue(mismatch.getValue("isError").jsonPrimitive.content.toBoolean())
        }
    }

    @Test
    fun initCompleteRequiresLifecycleAndPendingProposals() {
        val harness = harness(temporaryRoot())
        harness.client().use { client ->
            client.initialize()
            val beforeBegin = client.tool(70, "init.complete")
            assertTrue(beforeBegin.getValue("isError").jsonPrimitive.content.toBoolean())

            client.tool(71, "init.begin")
            val tooEarly = client.tool(72, "init.complete")
            assertTrue(tooEarly.getValue("isError").jsonPrimitive.content.toBoolean())

            client.tool(
                73,
                "knowledge.materializeDefaults",
                buildJsonObject { put("languages", buildJsonArray { add(JsonPrimitive("fake")) }) },
            )
            client.tool(74, "analysis.run", analysisArguments(listOf(FILE_A)))
            val proposalId =
                client.structured(client.tool(75, "changes.propose", proposalArguments("lifecycle")))
                    .getValue("proposalId").jsonPrimitive.content
            val pending = client.tool(76, "init.complete")
            assertTrue(pending.getValue("isError").jsonPrimitive.content.toBoolean())

            client.tool(
                77,
                "changes.apply",
                buildJsonObject { put("proposalId", proposalId) },
            )
            val completed = client.structured(client.tool(78, "init.complete"))
            assertEquals("COMPLETE", completed.getValue("status").jsonPrimitive.content)
            val again = client.tool(79, "init.complete")
            assertTrue(again.getValue("isError").jsonPrimitive.content.toBoolean())

            client.tool(80, "changes.propose", proposalArguments("after-complete"))
            val reopened = client.structured(client.tool(81, "init.status"))
            assertEquals("IN_PROGRESS", reopened.getValue("status").jsonPrimitive.content)
            val status = client.structured(client.tool(82, "repo.status"))
            assertTrue(status.getValue("repositoryInitialized").jsonPrimitive.content.toBoolean())
        }
    }

    @Test
    fun proposalIdsCanonicalizeTimestampsOrderAndRejectConflicts() {
        val harness = harness(temporaryRoot())
        harness.client().use { client ->
            client.initialize()
            client.tool(90, "init.begin")
            val firstTimestamp = now
            val secondTimestamp = now.plus(kotlin.time.Duration.parse("5s"))
            val first =
                client.structured(
                    client.tool(
                        91,
                        "changes.propose",
                        proposalArguments(listOf("alpha", "beta"), firstTimestamp),
                    ),
                )
            val second =
                client.structured(
                    client.tool(
                        92,
                        "changes.propose",
                        proposalArguments(listOf("beta", "alpha"), secondTimestamp),
                    ),
                )
            assertEquals(
                first.getValue("proposalId").jsonPrimitive.content,
                second.getValue("proposalId").jsonPrimitive.content,
            )
            assertTrue(second.getValue("idempotent").jsonPrimitive.content.toBoolean())

            client.tool(
                93,
                "changes.propose",
                proposalArguments("explicit", now, explicitId = "fixed-proposal"),
            )
            val conflict = client.tool(
                94,
                "changes.propose",
                proposalArguments("different", now, explicitId = "fixed-proposal"),
            )
            assertTrue(conflict.getValue("isError").jsonPrimitive.content.toBoolean())
            assertEquals(
                "AlreadyExists",
                client.structured(conflict).getValue("error").jsonObject
                    .getValue("code").jsonPrimitive.content,
            )
        }
    }

    @Test
    fun applyReconcilesKnowledgeAndRejectsStaleRemovals() {
        val harness = harness(temporaryRoot())
        harness.client().use { client ->
            client.initialize()
            client.tool(100, "init.begin")
            val crashProposal =
                client.structured(client.tool(101, "changes.propose", proposalArguments("crashy")))
            val crashId = crashProposal.getValue("proposalId").jsonPrimitive.content
            val stored =
                harness.checkpoints.load(WORKFLOW_ID).getOrThrow()!!.proposal(crashId)!!
            harness.store.proposeChange(stored.changes).getOrThrow()
            val reconciled = client.structured(
                client.tool(
                    102,
                    "changes.apply",
                    buildJsonObject { put("proposalId", crashId) },
                ),
            )
            assertTrue(reconciled.getValue("reconciled").jsonPrimitive.content.toBoolean())

            val replayId =
                client.structured(client.tool(103, "changes.propose", proposalArguments("replay")))
                    .getValue("proposalId").jsonPrimitive.content
            client.tool(104, "changes.apply", buildJsonObject { put("proposalId", replayId) })
            client.tool(
                105,
                "function_annotations.remove",
                buildJsonObject { put("selector", buildJsonObject { put("qualifiedName", "replay") }) },
            )
            client.tool(106, "changes.apply", buildJsonObject { put("proposalId", replayId) })
            assertTrue(
                harness.store.functionAnnotations().getOrThrow()
                    .any { it.selector.qualifiedName == "replay" },
            )

            client.tool(107, "function_annotations.set", annotationArguments("removeMe"))
            val removeId =
                client.structured(
                    client.tool(
                        108,
                        "changes.propose",
                        removeProposalArguments("removeMe"),
                    ),
                ).getValue("proposalId").jsonPrimitive.content
            harness.clock.instant = harness.clock.instant.plus(kotlin.time.Duration.parse("1s"))
            client.tool(109, "function_annotations.set", annotationArguments("removeMe"))
            val stale =
                client.tool(110, "changes.apply", buildJsonObject { put("proposalId", removeId) })
            assertTrue(stale.getValue("isError").jsonPrimitive.content.toBoolean())
            assertTrue(
                harness.store.functionAnnotations().getOrThrow()
                    .any { it.selector.qualifiedName == "removeMe" },
            )
        }
    }

    @Test
    fun repoIndexUsesCheckpointAfterRestartAndReanalyzesEditedFiles() {
        val root = temporaryRoot()
        val first = harness(root)
        first.client().use { client ->
            client.initialize()
            client.tool(120, "init.begin")
            client.tool(
                121,
                "knowledge.materializeDefaults",
                buildJsonObject { put("languages", buildJsonArray { add(JsonPrimitive("fake")) }) },
            )
            client.tool(122, "analysis.run", analysisArguments(listOf(FILE_A, FILE_B)))
        }

        val second = harness(root)
        second.client().use { client ->
            client.initialize()
            client.tool(123, "init.begin")
            val index = client.structured(client.tool(124, "repo.index"))
            assertEquals("checkpoint", index.getValue("source").jsonPrimitive.content)
            assertEquals(2, index.getValue("indexedFiles").jsonPrimitive.content.toInt())

            second.index.setContent(FILE_A, "fun loggerChanged() {}")
            val reanalyzed =
                client.structured(client.tool(125, "analysis.run", analysisArguments(listOf(FILE_A))))
            assertTrue(reanalyzed.getValue("skippedPaths").jsonArray.isEmpty())
            assertEquals(
                listOf(FILE_A),
                reanalyzed.getValue("analyzedPaths").jsonArray.map { it.jsonPrimitive.content },
            )

            val differentOptions =
                client.structured(
                    client.tool(
                        126,
                        "analysis.run",
                        analysisArguments(listOf(FILE_B), options = mapOf("mode" to "strict")),
                    ),
                )
            assertTrue(differentOptions.getValue("skippedPaths").jsonArray.isEmpty())
            assertEquals(
                listOf(FILE_B),
                differentOptions.getValue("analyzedPaths").jsonArray.map { it.jsonPrimitive.content },
            )
        }
    }

    @Test
    fun materializeDefaultsIgnoresClientSuppliedDefaults() {
        val harness = harness(temporaryRoot())
        harness.client().use { client ->
            client.initialize()
            client.tool(130, "init.begin")
            client.tool(
                131,
                "knowledge.materializeDefaults",
                buildJsonObject {
                    put("languages", buildJsonArray { add(JsonPrimitive("fake")) })
                    put(
                        "defaults",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("selector", buildJsonObject { put("qualifiedName", "forged") })
                                    put("intents", buildJsonArray { add(JsonPrimitive(Intents.LOGGING)) })
                                    put("packId", "evil")
                                    put("packVersion", "1")
                                    put("createdAt", now.toString())
                                },
                            )
                        },
                    )
                },
            )
            val annotations = harness.store.functionAnnotations().getOrThrow()
            assertTrue(annotations.any { it.selector.qualifiedName == "seed" })
            assertFalse(annotations.any { it.selector.qualifiedName == "forged" })
        }
    }

    private fun harness(
        root: Path,
        packDefaults: List<PackDefault>? = null,
    ): Harness {
        val clock = TestClock(now)
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
        val fallback = RecordingFallbackAi()
        val index =
            FakeIndex(
                mutableMapOf(
                    FILE_A to "fun logger() {}",
                    FILE_B to "fun findUser() {}",
                ),
            )
        val spec = RuleSpec("demo:logging", Intents.LOGGING)
        val composition =
            RuntimeLanguageComposition { _, _ ->
                ResultFactory.success(FakeLanguageSupport(fakeDescriptor(id = "fake")))
            }
        val runtime =
            RuntimeFactory.create(
                RuntimeConfig(
                    rootPath = root.toString(),
                    materializeDefaultsOnOpen = false,
                ),
                RuntimeComponents(
                    languageComposition = composition,
                    index = index,
                    packSourceFactory = {
                        FakeRulePackSource(
                            packManifests = listOf(PackManifest("demo", "Demo", "1", listOf("fake"))),
                            playbooks = mapOf("demo" to Playbook("demo", listOf(spec))),
                            defaultsByLanguage =
                                mapOf(
                                    "fake" to
                                        (packDefaults
                                            ?: listOf(
                                                PackDefault(
                                                    selector = FunctionSelector(qualifiedName = "seed"),
                                                    intents = setOf(Intents.LOGGING),
                                                    packId = "demo",
                                                    packVersion = "1",
                                                    createdAt = clock.instant,
                                                ),
                                            )),
                                ),
                        )
                    },
                    pipeline = FakeIntentPipeline(),
                    knowledgeStore = store,
                    fallbackAi = fallback,
                    fallbackAiPolicy = FallbackAiPolicy.DISABLED,
                ),
            ).getOrThrow()
        val checkpoints =
            InitCheckpointStores.open(root.toString(), null).getOrThrow()
        return Harness(root, runtime, store, checkpoints, clock, fallback, index)
    }

    private fun analysisArguments(
        paths: List<String>,
        language: String = "fake",
        options: Map<String, String> = emptyMap(),
    ): JsonObject =
        buildJsonObject {
            put("targetPaths", buildJsonArray { paths.forEach { add(JsonPrimitive(it)) } })
            put("language", language)
            if (options.isNotEmpty()) {
                put("options", buildJsonObject { options.forEach { (key, value) -> put(key, value) } })
            }
        }

    private fun patternArguments(
        intents: List<String> = listOf(Intents.LOGGING),
        status: String? = null,
        scope: String? = null,
        path: String? = null,
    ): JsonObject =
        buildJsonObject {
            put(
                "hypothesis",
                buildJsonObject {
                    put(
                        "selector",
                        buildJsonObject {
                            put("pattern", "logger.*")
                            path?.let { put("path", it) }
                        },
                    )
                    put("evidence", buildJsonArray { add(JsonPrimitive("call sites log")) })
                    put("examples", buildJsonArray { add(JsonPrimitive("$FILE_A:1")) })
                    put(
                        "proposedIntents",
                        buildJsonArray { intents.forEach { add(JsonPrimitive(it)) } },
                    )
                    status?.let { put("status", it) }
                    scope?.let { put("scope", it) }
                },
            )
        }

    private fun materializeArguments(): JsonObject =
        buildJsonObject {
            put("id", "logger.*")
            put(
                "targets",
                buildJsonArray {
                    add(buildJsonObject { put("selector", buildJsonObject { put("qualifiedName", "logger") }) })
                },
            )
        }

    private fun proposalArguments(
        name: String,
        timestamp: Instant = now,
        explicitId: String? = null,
        confidence: Double = 0.7,
    ): JsonObject = proposalArguments(listOf(name), timestamp, explicitId, confidence)

    private fun proposalArguments(
        names: List<String>,
        timestamp: Instant = now,
        explicitId: String? = null,
        confidence: Double = 0.7,
    ): JsonObject =
        buildJsonObject {
            explicitId?.let { put("proposalId", it) }
            put("description", "annotate ${names.sorted().joinToString(",")}")
            put(
                "changes",
                buildJsonArray {
                    names.forEach { name ->
                        add(
                            buildJsonObject {
                                put("type", "SetFunctionAnnotation")
                                put(
                                    "annotation",
                                    buildJsonObject {
                                        put("selector", buildJsonObject { put("qualifiedName", name) })
                                        put(
                                            "intents",
                                            buildJsonArray { add(JsonPrimitive(Intents.LOOKUP)) },
                                        )
                                        put("scope", "GLOBAL")
                                        put("provenance", "MCP_AGENT")
                                        put("confidence", buildJsonObject { put("value", confidence) })
                                        put("status", "SUGGESTED")
                                        put("createdAt", timestamp.toString())
                                        put("updatedAt", timestamp.toString())
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }

    private fun removeProposalArguments(name: String): JsonObject =
        buildJsonObject {
            put("description", "remove $name")
            put(
                "changes",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "RemoveFunctionAnnotation")
                            put("selector", buildJsonObject { put("qualifiedName", name) })
                        },
                    )
                },
            )
        }

    private fun patternProposalArguments(status: String): JsonObject =
        buildJsonObject {
            put(
                "changes",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "SetPatternHypothesis")
                            put(
                                "hypothesis",
                                buildJsonObject {
                                    put("selector", buildJsonObject { put("pattern", "logger.*") })
                                    put("scope", "GLOBAL")
                                    put("evidence", buildJsonArray { add(JsonPrimitive("call sites log")) })
                                    put("examples", buildJsonArray { add(JsonPrimitive("$FILE_A:1")) })
                                    put("counterexamples", buildJsonArray {})
                                    put(
                                        "proposedIntents",
                                        buildJsonArray { add(JsonPrimitive(Intents.LOGGING)) },
                                    )
                                    put("provenance", "MCP_AGENT")
                                    put("confidence", buildJsonObject { put("value", 0.3) })
                                    put("status", status)
                                    put("createdAt", now.toString())
                                    put("updatedAt", now.toString())
                                },
                            )
                        },
                    )
                },
            )
        }

    private fun materializeProposalArguments(): JsonObject =
        buildJsonObject {
            put(
                "changes",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "MaterializeDefaults")
                            put(
                                "defaults",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("selector", buildJsonObject { put("qualifiedName", "forged") })
                                            put(
                                                "intents",
                                                buildJsonArray { add(JsonPrimitive(Intents.LOGGING)) },
                                            )
                                            put("packId", "evil")
                                            put("packVersion", "1")
                                            put("createdAt", now.toString())
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
        }

    private fun annotationArguments(name: String): JsonObject =
        buildJsonObject {
            put(
                "annotation",
                buildJsonObject {
                    put("selector", buildJsonObject { put("qualifiedName", name) })
                    put("intents", buildJsonArray { add(JsonPrimitive(Intents.SEARCH)) })
                },
            )
        }

    private fun temporaryRoot(): Path {
        val root = Files.createTempDirectory("cint-mcp-083")
        temporaryRoots.add(root)
        return root
    }

    private class TestClock(
        var instant: Instant,
    )

    private class RecordingFallbackAi : FallbackAi {
        var calls: Int = 0
            private set

        override fun propose(
            request: FallbackAiRequest,
            cancellation: CancellationToken,
        ): ContractResult<dev.cint.contracts.knowledge.KnowledgeChange> {
            calls += 1
            return ResultFactory.failure(
                dev.cint.contracts.util.ContractError.PreconditionFailed("fallback AI must not run"),
            )
        }
    }

    private class Harness(
        val root: Path,
        val runtime: DefaultRuntime,
        val store: dev.cint.contracts.ports.KnowledgeStore,
        val checkpoints: InitCheckpointStore,
        val clock: TestClock,
        val fallback: RecordingFallbackAi,
        val index: FakeIndex,
    ) {
        fun client(): ScriptedClient {
            val toServer = PipedOutputStream()
            val fromServer = PipedOutputStream()
            val server =
                McpServer(
                    runtime = runtime,
                    input = PipedInputStream(toServer, BUFFER_SIZE),
                    output = fromServer,
                    now = { clock.instant },
                    checkpointStore = checkpoints,
                    workflowId = WORKFLOW_ID,
                )
            return ScriptedClient(toServer, fromServer, server)
        }
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

    private companion object {
        const val FILE_A = "src/Logger.kt"
        const val FILE_B = "src/UserRepository.kt"
        const val WORKFLOW_ID = "repo-init"
        const val BUFFER_SIZE = 1 shl 16
    }
}
