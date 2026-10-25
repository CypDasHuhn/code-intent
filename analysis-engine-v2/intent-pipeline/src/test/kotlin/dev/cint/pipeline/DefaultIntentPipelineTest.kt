package dev.cint.pipeline

import dev.cint.ai.FallbackAiHook
import dev.cint.ai.FallbackAiPolicy
import dev.cint.contracts.data.CandidateReason
import dev.cint.contracts.data.Confidence
import dev.cint.contracts.data.Intents
import dev.cint.contracts.data.Provenance
import dev.cint.contracts.data.Status
import dev.cint.contracts.knowledge.AnnotationScope
import dev.cint.contracts.knowledge.FunctionAnnotation
import dev.cint.contracts.knowledge.FunctionSelector
import dev.cint.contracts.knowledge.KnowledgeChange
import dev.cint.contracts.knowledge.KnowledgeSnapshot
import dev.cint.contracts.knowledge.PackDefault
import dev.cint.contracts.ports.AnalysisRequest
import dev.cint.contracts.ports.CandidateEmission
import dev.cint.contracts.ports.KnowledgeStore
import dev.cint.contracts.ports.EstablishedEmission
import dev.cint.contracts.ports.PackDiagnostic
import dev.cint.contracts.ports.PackManifest
import dev.cint.contracts.ports.Playbook
import dev.cint.contracts.ports.RuleSpec
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory
import dev.cint.v2.testing.fakes.FakeClock
import dev.cint.v2.testing.fakes.FakeKnowledgeStore
import dev.cint.v2.testing.fakes.FakeRecorder
import dev.cint.v2.testing.fakes.FakeRuleEvaluator
import dev.cint.v2.testing.fakes.FakeRuleEvaluatorRegistry
import dev.cint.v2.testing.fakes.FakeRulePackSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultIntentPipelineTest {
    private val clock = FakeClock()
    private val spec = RuleSpec("demo-rule", Intents.LOGGING)

    @Test
    fun runsEndToEndWithFakesAndShapesTheResult() {
        val recorder = FakeRecorder()
        val source = sourceWithFunctions("findUser", "saveUser")
        val packSource = FakeRulePackSource(
            packManifests = listOf(PackManifest("demo", "Demo", "1", listOf("fake"))),
            playbooks = mapOf("demo" to Playbook("demo", listOf(spec))),
            defaultsByLanguage = mapOf(
                "fake" to listOf(packDefault("saveUser", Intents.DATA_MODIFY)),
            ),
            packDiagnostics = listOf(
                PackDiagnostic(
                    packId = "ghost",
                    code = "MISSING_PACK",
                    message = "ghost pack is unavailable",
                    error = dev.cint.contracts.util.ContractError.NotFound("pack", "ghost"),
                ),
            ),
            recorder = recorder,
        )
        val evaluator = FakeRuleEvaluator(
            spec,
            emissions = listOf(
                EstablishedEmission(
                    intents = setOf(Intents.LOGGING),
                    span = source.node("root").span,
                    startByte = source.node("root").startByte,
                    endByte = source.node("root").endByte,
                    source = "test",
                ),
                CandidateEmission(
                    intents = setOf(Intents.LOGGING),
                    span = source.node("fn-1").span,
                    startByte = source.node("fn-1").startByte,
                    endByte = source.node("fn-1").endByte,
                    reason = CandidateReason.GAP,
                    source = "test",
                ),
            ),
            recorder = recorder,
        )
        val knowledge = FakeKnowledgeStore(
            functionAnnotations = listOf(
                annotation("findUser", Intents.LOOKUP),
            ),
            clock = clock,
            recorder = recorder,
        )
        val pipeline = DefaultIntentPipeline(
            packSource,
            FakeRuleEvaluatorRegistry(mapOf(spec.id to evaluator), recorder),
        )

        val result = pipeline.analyze(
            AnalysisRequest(targetPaths = listOf("Sample.fake"), language = "fake"),
            source,
            knowledge.snapshot().getOrThrow(),
        ).getOrThrow()

        assertEquals(source.facts, result.facts)
        assertEquals(source.sourceEpoch, result.sourceEpoch)
        assertEquals(source.completeness, result.completeness)
        assertEquals(listOf("MISSING_PACK"), result.errors.map { it.code })
        assertTrue(result.annotations.any { it.intents == setOf(Intents.LOOKUP) })
        assertTrue(result.annotations.any { it.intents == setOf(Intents.DATA_MODIFY) })
        assertTrue(result.annotations.any { it.intents == setOf(Intents.LOGGING) })
        assertEquals(setOf(Intents.LOGGING), result.candidates.single().intents)
        assertTrue(recorder.called("packs.manifests"))
        assertTrue(recorder.called("packs.playbook"))
        assertTrue(recorder.called("packs.defaults"))
        assertTrue(recorder.called("rule.evaluate"))
    }

    @Test
    fun knowledgeInputsAreInjectedThroughAPort() {
        var calls = 0
        val pipeline = DefaultIntentPipeline(
            emptyPackSource(),
            knowledgeInputs = KnowledgeInputResolver { _, _, _, _ ->
                calls += 1
                ResultFactory.success(KnowledgeInputResolution(emptyList()))
            },
        )

        pipeline.analyze(
            AnalysisRequest(language = "fake"),
            sourceWithFunctions("findUser"),
            emptySnapshot(),
        ).getOrThrow()

        assertEquals(1, calls)
    }

    @Test
    fun unboundAndMismatchedRulesFail() {
        val packSource = FakeRulePackSource(
            packManifests = listOf(PackManifest("demo", "Demo", "1", listOf("fake"))),
            playbooks = mapOf("demo" to Playbook("demo", listOf(spec))),
        )
        val source = sourceWithFunctions("findUser")
        val request = AnalysisRequest(language = "fake")

        val unbound = DefaultIntentPipeline(packSource).analyze(
            request,
            source,
            emptySnapshot(),
        )
        assertTrue(unbound.isFailure)
        assertEquals(
            dev.cint.contracts.util.ContractError.NotFound("rule-evaluator", spec.id),
            unbound.errorOrNull,
        )

        val mismatchedEvaluator = FakeRuleEvaluator(spec.copy(owns = Intents.LOOKUP))
        val mismatched = DefaultIntentPipeline(
            packSource,
            FakeRuleEvaluatorRegistry(mapOf(spec.id to mismatchedEvaluator)),
        ).analyze(request, source, emptySnapshot())
        assertTrue(mismatched.isFailure)
        assertTrue(mismatched.errorOrNull is dev.cint.contracts.util.ContractError.ValidationError)
    }

    @Test
    fun fallbackAiIsNeverCalledByDefault() {
        val packSource = emptyPackSource()
        val store = FakeKnowledgeStore(clock = clock)
        val pipeline = DefaultIntentPipeline(
            packSource,
            fallbackAi = FallbackAiHook {
                calls += 1
                ResultFactory.success(noopChange())
            },
            knowledgeStore = store,
        )

        pipeline.analyze(
            AnalysisRequest(language = "fake", options = mapOf("fallbackAi" to "true")),
            sourceWithFunctions("findUser"),
            emptySnapshot(),
        ).getOrThrow()

        assertEquals(0, calls)
    }

    @Test
    fun enabledFallbackAiProposesThroughTheKnowledgeMutationApi() {
        val packSource = emptyPackSource()
        val store = dev.cint.knowledge.InMemoryKnowledgeStore.empty { clock.now }
        val proposal = annotation("findUser", Intents.LOOKUP).copy(status = Status.SUGGESTED)
        val pipeline = DefaultIntentPipeline(
            packSource,
            fallbackAi = FallbackAiHook {
                calls += 1
                ResultFactory.success(KnowledgeChange.SetFunctionAnnotation(proposal))
            },
            fallbackAiPolicy = FallbackAiPolicy.enabled(),
            knowledgeStore = store,
        )

        val result = pipeline.analyze(
            AnalysisRequest(language = "fake"),
            sourceWithFunctions("findUser"),
            store.snapshot().getOrThrow(),
        ).getOrThrow()

        assertEquals(1, calls)
        assertTrue(store.functionAnnotations().getOrThrow().any { it.selector == proposal.selector })
        assertTrue(result.annotations.any { it.intents == setOf(Intents.LOOKUP) })
    }

    @Test
    fun enabledFallbackAiWithoutStoreSurfacesSkipWarning() {
        val pipeline = DefaultIntentPipeline(
            emptyPackSource(),
            fallbackAi = FallbackAiHook {
                calls += 1
                ResultFactory.success(noopChange())
            },
            fallbackAiPolicy = FallbackAiPolicy.enabled(),
        )

        val result = pipeline.analyze(
            AnalysisRequest(language = "fake"),
            sourceWithFunctions("findUser"),
            emptySnapshot(),
        ).getOrThrow()

        assertEquals(0, calls)
        assertEquals(listOf("FALLBACK_AI_UNAVAILABLE"), result.errors.map { it.code })
        assertTrue(result.annotations.isEmpty())
    }

    @Test
    fun fallbackAiRefreshFailureSurfacesWarningAndKeepsDeterministicResult() {
        val delegate = dev.cint.knowledge.InMemoryKnowledgeStore.empty { clock.now }
        val store = FailingRefreshStore(delegate)
        val pipeline = DefaultIntentPipeline(
            emptyPackSource(),
            fallbackAi = FallbackAiHook {
                calls += 1
                ResultFactory.success(
                    KnowledgeChange.SetFunctionAnnotation(annotation("findUser", Intents.LOOKUP)),
                )
            },
            fallbackAiPolicy = FallbackAiPolicy.enabled(),
            knowledgeStore = store,
        )

        val result = pipeline.analyze(
            AnalysisRequest(language = "fake"),
            sourceWithFunctions("findUser"),
            emptySnapshot(),
        ).getOrThrow()

        assertEquals(1, calls)
        assertEquals(listOf("FALLBACK_AI_REFRESH_FAILED"), result.errors.map { it.code })
        assertTrue(result.annotations.isEmpty())
    }

    @Test
    fun annotatesFunctionsWithRealBundledPacksCoreAndKnowledge() {
        val catalog = dev.cint.packs.PackCatalog.of(
            dev.cint.packs.BundledPacks.all.filter { it.manifest.id == "logging" },
        )
        val packSource = dev.cint.packs.BundledRulePackSource(catalog)
        val bundledSpec = packSource.playbook("logging").getOrThrow().rules.single()
        val registry = MapRuleEvaluatorRegistry(
            listOf(
                object : dev.cint.contracts.ports.RuleEvaluator {
                    override val spec: RuleSpec = bundledSpec

                    override fun evaluate(
                        view: dev.cint.contracts.ports.RuleEvaluationView,
                    ): List<dev.cint.contracts.ports.RuleEmission> = emptyList()
                },
            ),
        )
        val pipeline = DefaultIntentPipeline(packSource, registry)
        val source = sourceWithFunctions("kotlin.io.println")
        val knowledge = dev.cint.knowledge.InMemoryKnowledgeStore.empty().snapshot().getOrThrow()

        val result = pipeline.analyze(
            AnalysisRequest(language = "kotlin"),
            source,
            knowledge,
        ).getOrThrow()

        assertFalse(result.annotations.isEmpty())
        assertTrue(result.annotations.all { Intents.LOGGING in it.intents })
        assertEquals(setOf("knowledge:pack-default"), result.annotations.map { it.rule }.toSet())
        assertEquals(listOf("KNOWLEDGE_SELECTOR_UNSUPPORTED"), result.errors.map { it.code })
    }

    private var calls = 0

    private fun noopChange(): KnowledgeChange =
        KnowledgeChange.Batch("noop", emptyList())

    private fun emptyPackSource(): FakeRulePackSource =
        FakeRulePackSource(
            packManifests = listOf(PackManifest("demo", "Demo", "1", listOf("fake"))),
            playbooks = mapOf("demo" to Playbook("demo", emptyList())),
        )

    private fun emptySnapshot() =
        dev.cint.contracts.knowledge.KnowledgeSnapshot(
            functionAnnotations = emptyList(),
            spanOverrides = emptyList(),
            patternHypotheses = emptyList(),
            timestamp = 1,
        )

    private fun annotation(name: String, intent: String): FunctionAnnotation =
        FunctionAnnotation(
            selector = FunctionSelector(qualifiedName = name),
            intents = setOf(intent),
            scope = AnnotationScope.GLOBAL,
            provenance = Provenance.USER,
            confidence = Confidence.HIGH,
            status = Status.ACCEPTED,
            createdAt = clock.now,
            updatedAt = clock.now,
        )

    private fun packDefault(name: String, intent: String): PackDefault =
        PackDefault(
            selector = FunctionSelector(qualifiedName = name),
            intents = setOf(intent),
            packId = "demo",
            packVersion = "1",
            confidence = Confidence.MEDIUM,
            createdAt = clock.now,
        )
}

private class FailingRefreshStore(
    private val delegate: KnowledgeStore,
) : KnowledgeStore by delegate {
    override fun snapshot(cancellation: CancellationToken): ContractResult<KnowledgeSnapshot> =
        ResultFactory.failure(ContractError.InternalError("refresh failed"))
}
