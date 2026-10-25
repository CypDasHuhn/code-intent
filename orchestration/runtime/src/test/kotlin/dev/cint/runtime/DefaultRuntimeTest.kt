package dev.cint.runtime

import dev.cint.contracts.data.AnalysisResult
import dev.cint.contracts.data.Annotation
import dev.cint.contracts.data.Completeness
import dev.cint.contracts.data.Confidence
import dev.cint.contracts.data.Contribution
import dev.cint.contracts.data.Intents
import dev.cint.contracts.data.Provenance
import dev.cint.contracts.data.Role
import dev.cint.contracts.data.Span
import dev.cint.contracts.data.Status
import dev.cint.contracts.facts.FactBatch
import dev.cint.contracts.facts.IndexedSource
import dev.cint.contracts.facts.LanguageDescriptor
import dev.cint.contracts.facts.SourceInput
import dev.cint.contracts.knowledge.AnnotationScope
import dev.cint.contracts.knowledge.FunctionAnnotation
import dev.cint.contracts.knowledge.FunctionSelector
import dev.cint.contracts.knowledge.KnowledgeChange
import dev.cint.contracts.knowledge.KnowledgeSnapshot
import dev.cint.contracts.knowledge.PackDefault
import dev.cint.contracts.ports.AnalysisRequest
import dev.cint.contracts.ports.Index
import dev.cint.contracts.ports.IndexInput
import dev.cint.contracts.ports.KnowledgeRepository
import dev.cint.contracts.ports.KnowledgeRepositoryFactory
import dev.cint.contracts.ports.KnowledgeRepositoryLocation
import dev.cint.contracts.ports.LanguageSupport
import dev.cint.contracts.ports.PackManifest
import dev.cint.contracts.ports.ProviderMode
import dev.cint.contracts.ports.RescanResult
import dev.cint.contracts.ports.RuleEvaluatorRegistry
import dev.cint.contracts.ports.SemanticProvider
import dev.cint.contracts.ports.SemanticProviderConfig
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContentHash
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory
import dev.cint.knowledge.InMemoryKnowledgeStore
import dev.cint.knowledge.persistence.KnowledgeStoreFiles
import dev.cint.packs.BundledRulePackSource
import dev.cint.pipeline.RuleEvaluatorCatalog
import dev.cint.v2.index.InMemoryIndex
import dev.cint.v2.languages.LanguageCatalog
import dev.cint.v2.languages.LanguageOptions
import dev.cint.v2.languages.LanguageProviderDefaults
import dev.cint.v2.languages.LanguageProviderFactory
import dev.cint.v2.languages.LanguageSupportFactory
import dev.cint.v2.testing.fakes.FakeClock
import dev.cint.v2.testing.fakes.FakeIndex
import dev.cint.v2.testing.fakes.FakeIntentPipeline
import dev.cint.v2.testing.fakes.FakeKnowledgeStore
import dev.cint.v2.testing.fakes.FakeLanguageSupport
import dev.cint.v2.testing.fakes.FakeRecorder
import dev.cint.v2.testing.fakes.FakeRulePackSource
import dev.cint.v2.testing.fakes.FakeSemanticProvider
import dev.cint.v2.testing.fakes.fakeDescriptor
import kotlinx.datetime.Instant
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class DefaultRuntimeTest {
    private val temporaryRoots = mutableListOf<Path>()
    private val now = Instant.parse("2025-01-01T00:00:00Z")

    @AfterTest
    fun cleanUp() {
        temporaryRoots.forEach { runCatching { it.toFile().deleteRecursively() } }
    }

    @Test
    fun fakeSourcesAnalyzeThroughRuntimeAndPublish() {
        val recorder = FakeRecorder()
        val clock = FakeClock()
        val path = "Sample.fake"
        val text = "fun findUser() {}"
        val knowledge =
            FakeKnowledgeStore(
                functionAnnotations = listOf(annotation("findUser", Intents.LOOKUP)),
                clock = clock,
                recorder = recorder,
            )
        val published = mutableListOf<AnalysisResult>()
        val runtime =
            RuntimeFactory
                .create(
                    RuntimeConfig(),
                    RuntimeComponents(
                        languageComposition = fakeComposition(recorder),
                        index = FakeIndex(mutableMapOf(path to text), clock, recorder = recorder),
                        packSourceFactory = { FakeRulePackSource(recorder = recorder) },
                        knowledgeStore = knowledge,
                        pipeline = FakeIntentPipeline(recorder),
                        publisher =
                            AnalysisPublisher { result, _ ->
                                published += result
                                ResultFactory.success(Unit)
                            },
                    ),
                ).getOrThrow()

        val result =
            runtime
                .analyze(AnalysisRequest(targetPaths = listOf(path), language = "fake"))
                .getOrThrow()

        assertEquals(setOf(Intents.LOOKUP), result.annotations.single().intents)
        assertTrue(recorder.called("pipeline.analyze"))
        assertEquals(1, runtime.status().getOrThrow().indexedFiles)

        runtime.publish(result).getOrThrow()
        assertEquals(listOf(result), published)
        runtime.shutdown().getOrThrow()
    }

    @Test
    fun inMemorySourcesAnalyzeThroughRuntime() {
        val recorder = FakeRecorder()
        val runtime =
            RuntimeFactory
                .create(
                    RuntimeConfig(),
                    RuntimeComponents(
                        languageComposition = fakeComposition(recorder),
                        index = FakeIndex(recorder = recorder),
                        packSourceFactory = { FakeRulePackSource(recorder = recorder) },
                        knowledgeStore = FakeKnowledgeStore(recorder = recorder),
                        pipeline = FakeIntentPipeline(recorder),
                    ),
                ).getOrThrow()

        val result =
            runtime
                .analyzeSource(
                    SourceInput.InMemory(
                        text = "fun findUser() {}",
                        language = "fake",
                        version = 7,
                        path = "Sample.fake",
                        documentId = "file:///Sample.fake",
                    ),
                    AnalysisRequest(language = "fake"),
                ).getOrThrow()

        assertTrue(result.sourceEpoch > 0)
        assertEquals("memory:fake:7:Sample.fake", recorder.details("index.ensure").single())
        assertEquals(1, runtime.status().getOrThrow().indexedFiles)
    }

    @Test
    fun realInMemoryIndexAnalyzesInMemorySourcesThroughRuntime() {
        val recorder = FakeRecorder()
        val runtime =
            RuntimeFactory
                .create(
                    RuntimeConfig(),
                    RuntimeComponents(
                        languageComposition = fakeComposition(recorder),
                        index = InMemoryIndex(RuntimeConfig.defaultIndexPlan(), semanticTimeoutMs = 1_000),
                        packSourceFactory = { FakeRulePackSource(recorder = recorder) },
                        knowledgeStore = FakeKnowledgeStore(recorder = recorder),
                        pipeline = FakeIntentPipeline(recorder),
                    ),
                ).getOrThrow()

        val result =
            runtime
                .analyzeSource(
                    SourceInput.InMemory(
                        text = "fun findUser() {}",
                        language = "fake",
                        version = 3,
                        path = "Sample.fake",
                        documentId = "file:///Sample.fake",
                    ),
                    AnalysisRequest(language = "fake"),
                ).getOrThrow()

        assertTrue(result.sourceEpoch > 0)
        assertEquals(1, runtime.status().getOrThrow().indexedFiles)
        runtime.shutdown().getOrThrow()
    }

    @Test
    fun sharedBundledEvaluatorsAnalyzeThroughTheDefaultPipeline() {
        val recorder = FakeRecorder()
        val runtime =
            RuntimeFactory
                .create(
                    RuntimeConfig(),
                    RuntimeComponents(
                        languageComposition = fakeComposition(recorder),
                        index = InMemoryIndex(RuntimeConfig.defaultIndexPlan(), semanticTimeoutMs = 1_000),
                        packSourceFactory = { BundledRulePackSource() },
                        knowledgeStore = FakeKnowledgeStore(recorder = recorder),
                    ),
                ).getOrThrow()

        val result =
            runtime
                .analyzeSource(
                    SourceInput.InMemory(
                        text = "fun work() { call println }",
                        language = "kotlin",
                        version = 1,
                        path = "Sample.kt",
                        documentId = "file:///Sample.kt",
                    ),
                    AnalysisRequest(language = "kotlin"),
                ).getOrThrow()

        assertTrue(result.annotations.any { Intents.LOGGING in it.intents })
        assertTrue(result.annotations.all { it.provenance.all { contribution -> contribution.rule == "logging:logging" } })
        runtime.shutdown().getOrThrow()
    }

    @Test
    fun defaultComponentsCreateWithoutAnInjectedRuleRegistry() {
        val root = temporaryRoot()
        val runtime =
            RuntimeFactory
                .create(
                    RuntimeConfig(rootPath = root.toString(), detectLanguages = false),
                ).getOrThrow()

        val status = runtime.status().getOrThrow()
        assertFalse(status.isInitialized)
        assertTrue(status.isRuntimeOpen)
        runtime.shutdown().getOrThrow()
    }

    @Test
    fun packsExposeManifestsAndPlaybooks() {
        val runtime =
            RuntimeFactory
                .create(
                    RuntimeConfig(),
                    RuntimeComponents(
                        packSourceFactory = {
                            FakeRulePackSource(
                                packManifests =
                                    listOf(
                                        PackManifest(
                                            id = "logging",
                                            name = "Logging",
                                            version = "1",
                                            languages = listOf("fake"),
                                        ),
                                    ),
                                playbooks =
                                    mapOf(
                                        "logging" to
                                            dev.cint.contracts.ports.Playbook(
                                                packId = "logging",
                                                rules =
                                                    listOf(
                                                        dev.cint.contracts.ports
                                                            .RuleSpec("logging:logging", Intents.LOGGING),
                                                    ),
                                            ),
                                    ),
                            )
                        },
                        knowledgeStore = FakeKnowledgeStore(),
                        pipeline = FakeIntentPipeline(),
                    ),
                ).getOrThrow()

        val packs = runtime.packs().getOrThrow()

        assertEquals("logging", packs.single().manifest.id)
        assertEquals(
            "logging:logging",
            packs
                .single()
                .playbook.rules
                .single()
                .id,
        )
    }

    @Test
    fun suggestDefaultsReadsPacksWithoutWritingKnowledge() {
        val root = temporaryRoot()
        val packSource =
            FakeRulePackSource(
                packManifests = listOf(PackManifest("demo", "Demo", "1", listOf("fake"))),
                defaultsByLanguage = mapOf("fake" to listOf(default("packed", Intents.LOGGING))),
            )
        val runtime =
            createRuntime(
                RuntimeConfig(
                    rootPath = root.toString(),
                    initializeLanguages = setOf("fake"),
                    detectLanguages = false,
                    materializeDefaultsOnOpen = false,
                ),
                packSource,
            ).getOrThrow()

        val defaults = runtime.suggestDefaults(setOf("fake")).getOrThrow()

        assertEquals(listOf("packed"), defaults.map { it.selector.qualifiedName })
        assertTrue(
            runtime
                .knowledge()
                .functionAnnotations()
                .getOrThrow()
                .isEmpty(),
        )
        assertTrue(!Files.exists(root.resolve(".cint/knowledge.json")))
        runtime.shutdown().getOrThrow()
    }

    @Test
    fun initializationMaterializesOnlyWhenNoStoreExists() {
        val root = temporaryRoot()
        val packSource =
            FakeRulePackSource(
                packManifests = listOf(PackManifest("demo", "Demo", "1", listOf("fake"))),
                defaultsByLanguage = mapOf("fake" to listOf(default("packed", Intents.LOGGING))),
            )
        val config =
            RuntimeConfig(
                rootPath = root.toString(),
                initializeLanguages = setOf("fake"),
                detectLanguages = false,
            )
        val first = createRuntime(config, packSource).getOrThrow()
        val knowledgeFile = root.resolve(".cint/knowledge.json")
        assertTrue(Files.isRegularFile(knowledgeFile))
        assertTrue(first.status().getOrThrow().isInitialized)
        assertEquals(
            listOf("packed"),
            first
                .knowledge()
                .functionAnnotations()
                .getOrThrow()
                .map { it.selector.qualifiedName },
        )

        val edited = InMemoryKnowledgeStore.empty { now }
        edited.setFunctionAnnotation(annotation("edited", Intents.LOOKUP)).getOrThrow()
        KnowledgeStoreFiles.save(knowledgeFile, edited.snapshot().getOrThrow()).getOrThrow()

        val second = createRuntime(config, packSource).getOrThrow()
        second
            .materializeDefaults(
                KnowledgeChange.MaterializeDefaults(listOf(default("packed", Intents.LOGGING))),
            ).getOrThrow()

        val annotations = second.knowledge().functionAnnotations().getOrThrow()
        assertEquals(listOf("edited"), annotations.map { it.selector.qualifiedName })
        assertEquals(Provenance.USER, annotations.single().provenance)
        assertEquals(setOf(Intents.LOOKUP), annotations.single().intents)
    }

    @Test
    fun knowledgeMutationsPersistThroughTheRepository() {
        val root = temporaryRoot()
        val packSource =
            FakeRulePackSource(
                packManifests = listOf(PackManifest("demo", "Demo", "1", listOf("fake"))),
                defaultsByLanguage = mapOf("fake" to listOf(default("packed", Intents.LOGGING))),
            )
        val config =
            RuntimeConfig(
                rootPath = root.toString(),
                initializeLanguages = setOf("fake"),
                detectLanguages = false,
            )
        val first = createRuntime(config, packSource).getOrThrow()
        first.knowledge().setFunctionAnnotation(annotation("edited", Intents.SEARCH)).getOrThrow()

        val second = createRuntime(config, packSource).getOrThrow()

        assertTrue(
            second.knowledge().functionAnnotations().getOrThrow().any {
                it.selector.qualifiedName == "edited"
            },
        )
    }

    @Test
    fun initializationUsesTheInjectedLanguageCompositionCatalog() {
        val root = temporaryRoot()
        Files.writeString(root.resolve("Sample.fake"), "fun findUser() {}")
        val catalog = LanguageCatalog.of(listOf(fakeDescriptor()))
        val composition =
            object : RuntimeLanguageComposition {
                override val catalog: LanguageCatalog = catalog

                override fun resolve(
                    input: SourceInput,
                    options: LanguageOptions,
                ): ContractResult<LanguageSupport> = ResultFactory.success(FakeLanguageSupport(fakeDescriptor()))
            }
        val packSource =
            FakeRulePackSource(
                packManifests = listOf(PackManifest("demo", "Demo", "1", listOf("fake"))),
                defaultsByLanguage = mapOf("fake" to listOf(default("packed", Intents.LOGGING))),
            )

        val runtime =
            RuntimeFactory
                .create(
                    RuntimeConfig(rootPath = root.toString(), detectLanguages = true),
                    RuntimeComponents(
                        languageComposition = composition,
                        packSourceFactory = { packSource },
                        ruleEvaluators = emptyRegistry(),
                    ),
                ).getOrThrow()

        assertEquals(
            listOf("packed"),
            runtime
                .knowledge()
                .functionAnnotations()
                .getOrThrow()
                .map { it.selector.qualifiedName },
        )
    }

    @Test
    fun defaultFactoryUsesTheBundledEvaluatorRegistry() {
        val result =
            RuntimeFactory.create(
                RuntimeConfig(),
                RuntimeComponents(packSourceFactory = { FakeRulePackSource() }),
            )

        assertTrue(result.isSuccess)
        result.getOrThrow().shutdown().getOrThrow()
    }

    @Test
    fun reloadPropagatesSupportShutdownFailures() {
        val recorder = FakeRecorder()
        val runtime =
            RuntimeFactory
                .create(
                    RuntimeConfig(),
                    RuntimeComponents(
                        languageComposition =
                            RuntimeLanguageComposition { _, _ ->
                                ResultFactory.success(FailingShutdownSupport(FakeLanguageSupport(fakeDescriptor())))
                            },
                        index = FakeIndex(mutableMapOf("Sample.fake" to "fun findUser() {}"), recorder = recorder),
                        packSourceFactory = { FakeRulePackSource(recorder = recorder) },
                        ruleEvaluators = emptyRegistry(),
                    ),
                ).getOrThrow()
        runtime.analyze(AnalysisRequest(targetPaths = listOf("Sample.fake"), language = "fake")).getOrThrow()

        val result = runtime.reload()

        assertTrue(result.isFailure)
        assertIs<ContractError.InternalError>(result.errorOrNull)
        assertEquals(1, runtime.status().getOrThrow().indexedFiles)
    }

    @Test
    fun relativeKnowledgePathIsResolvedAgainstTheRoot() {
        val root = temporaryRoot()
        val packSource =
            FakeRulePackSource(
                packManifests = listOf(PackManifest("demo", "Demo", "1", listOf("fake"))),
                defaultsByLanguage = mapOf("fake" to listOf(default("packed", Intents.LOGGING))),
            )
        val config =
            RuntimeConfig(
                rootPath = root.toString(),
                knowledgePath = "custom/knowledge.json",
                initializeLanguages = setOf("fake"),
                detectLanguages = false,
            )

        createRuntime(config, packSource).getOrThrow()

        assertTrue(Files.isRegularFile(root.resolve("custom/knowledge.json")))
    }

    @Test
    fun unmaterializedSnapshotSeedsDefaultsWithoutDroppingStoredEntries() {
        val root = temporaryRoot()
        val file = root.resolve(".cint/knowledge.json")
        Files.createDirectories(file.parent)
        KnowledgeStoreFiles
            .save(
                file,
                KnowledgeSnapshot(
                    functionAnnotations = listOf(annotation("stored", Intents.LOOKUP)),
                    spanOverrides = emptyList(),
                    patternHypotheses = emptyList(),
                    materialized = false,
                    timestamp = 1,
                ),
            ).getOrThrow()
        val packSource =
            FakeRulePackSource(
                packManifests = listOf(PackManifest("demo", "Demo", "1", listOf("fake"))),
                defaultsByLanguage = mapOf("fake" to listOf(default("packed", Intents.LOGGING))),
            )
        val config =
            RuntimeConfig(
                rootPath = root.toString(),
                initializeLanguages = setOf("fake"),
                detectLanguages = false,
            )

        val runtime = createRuntime(config, packSource).getOrThrow()

        val snapshot = runtime.knowledge().snapshot().getOrThrow()
        assertTrue(snapshot.materialized)
        assertEquals(listOf("packed", "stored"), snapshot.functionAnnotations.map { it.selector.qualifiedName })
    }

    @Test
    fun reloadReReadsKnowledgeAndInvalidatesTheIndex() {
        val root = temporaryRoot()
        val recorder = FakeRecorder()
        val path = "Sample.fake"
        val index = FakeIndex(mutableMapOf(path to "fun findUser() {}"), recorder = recorder)
        val config =
            RuntimeConfig(
                rootPath = root.toString(),
                initializeLanguages = setOf("fake"),
                detectLanguages = false,
            )
        val runtime =
            RuntimeFactory
                .create(
                    config,
                    RuntimeComponents(
                        languageComposition = fakeComposition(recorder),
                        index = index,
                        packSourceFactory = { FakeRulePackSource(recorder = recorder) },
                        ruleEvaluators = emptyRegistry(),
                    ),
                ).getOrThrow()
        runtime.analyze(AnalysisRequest(targetPaths = listOf(path), language = "fake")).getOrThrow()
        recorder.clear()

        val disk = InMemoryKnowledgeStore.empty { now }
        disk.setFunctionAnnotation(annotation("fromDisk", Intents.SEARCH)).getOrThrow()
        KnowledgeStoreFiles
            .save(
                root.resolve(".cint/knowledge.json"),
                disk.snapshot().getOrThrow(),
            ).getOrThrow()

        runtime.reload().getOrThrow()

        assertEquals(
            listOf("fromDisk"),
            runtime
                .knowledge()
                .functionAnnotations()
                .getOrThrow()
                .map { it.selector.qualifiedName },
        )
        assertTrue(recorder.details("index.invalidate").single().contains(path))
        assertEquals(0, runtime.status().getOrThrow().indexedFiles)
    }

    @Test
    fun reloadRejectsInjectedLifecyclePorts() {
        val runtime =
            RuntimeFactory
                .create(
                    RuntimeConfig(),
                    RuntimeComponents(
                        languageComposition = fakeComposition(FakeRecorder()),
                        index = FakeIndex(),
                        packSourceFactory = { FakeRulePackSource() },
                        knowledgeStore = FakeKnowledgeStore(),
                        pipeline = FakeIntentPipeline(),
                    ),
                ).getOrThrow()

        val result = runtime.reload()

        assertTrue(result.isFailure)
        assertIs<ContractError.PreconditionFailed>(result.errorOrNull)
    }

    @Test
    fun corruptKnowledgeFailsExplicitly() {
        val root = temporaryRoot()
        val file = root.resolve(".cint/knowledge.json")
        Files.createDirectories(file.parent)
        Files.writeString(file, "{not json")

        val result = RuntimeFactory.create(RuntimeConfig(rootPath = root.toString()))

        assertTrue(result.isFailure)
        assertIs<ContractError.SerializationError>(result.errorOrNull)
    }

    @Test
    fun invalidKnowledgePathFailsExplicitly() {
        val result =
            RuntimeFactory.create(
                RuntimeConfig(knowledgePath = "bad\u0000path"),
                RuntimeComponents(ruleEvaluators = emptyRegistry()),
            )

        assertTrue(result.isFailure)
        assertIs<ContractError.InvalidInput>(result.errorOrNull)
    }

    @Test
    fun saveFailureDuringInitializationFailsExplicitly() {
        val root = temporaryRoot()
        val repository = FakeRepository(saveFailures = 1)
        val packSource =
            FakeRulePackSource(
                packManifests = listOf(PackManifest("demo", "Demo", "1", listOf("fake"))),
                defaultsByLanguage = mapOf("fake" to listOf(default("packed", Intents.LOGGING))),
            )

        val result =
            RuntimeFactory.create(
                RuntimeConfig(
                    rootPath = root.toString(),
                    initializeLanguages = setOf("fake"),
                    detectLanguages = false,
                ),
                RuntimeComponents(
                    packSourceFactory = { packSource },
                    knowledgeRepositoryFactory = KnowledgeRepositoryFactory { ResultFactory.success(repository) },
                    ruleEvaluators = emptyRegistry(),
                ),
            )

        assertTrue(result.isFailure)
        assertIs<ContractError.InternalError>(result.errorOrNull)
    }

    @Test
    fun shutdownRetriesAFailedSave() {
        val root = temporaryRoot()
        val repository = FakeRepository(saveFailures = 1, snapshot = materializedSnapshot())
        val runtime =
            RuntimeFactory
                .create(
                    RuntimeConfig(rootPath = root.toString(), detectLanguages = false),
                    RuntimeComponents(
                        knowledgeRepositoryFactory = KnowledgeRepositoryFactory { ResultFactory.success(repository) },
                        ruleEvaluators = emptyRegistry(),
                    ),
                ).getOrThrow()

        val first = runtime.shutdown()
        val second = runtime.shutdown()

        assertTrue(first.isFailure)
        assertTrue(second.isSuccess)
        assertEquals(2, repository.saveCalls)
        assertTrue(repository.snapshot != null)
    }

    @Test
    fun missingOraclePropagatesToTheCaller() {
        val oracleFailure =
            ContractError.DependencyUnavailable("semantic-provider-fake", "no oracle available")
        val runtime =
            RuntimeFactory
                .create(
                    RuntimeConfig(),
                    RuntimeComponents(
                        languageComposition =
                            RuntimeLanguageComposition { _, _ -> ResultFactory.failure(oracleFailure) },
                        index = FakeIndex(),
                        packSourceFactory = { FakeRulePackSource() },
                        knowledgeStore = FakeKnowledgeStore(),
                        pipeline = FakeIntentPipeline(),
                    ),
                ).getOrThrow()

        val result = runtime.analyze(AnalysisRequest(targetPaths = listOf("Sample.fake"), language = "fake"))

        assertTrue(result.isFailure)
        assertEquals(oracleFailure, result.errorOrNull)
    }

    @Test
    fun missingGrammarPropagatesToTheCaller() {
        val root = temporaryRoot()
        val descriptor =
            fakeDescriptor().copy(
                id = "fake",
                grammarName = "tree-sitter-missing",
                providerMode = ProviderMode.EDITOR,
            )
        val catalog = LanguageCatalog.of(listOf(descriptor))
        val runtime =
            RuntimeFactory
                .create(
                    RuntimeConfig(rootPath = root.toString()),
                    RuntimeComponents(
                        languageComposition =
                            DefaultRuntimeLanguageComposition(
                                catalog = catalog,
                                factory =
                                    LanguageSupportFactory(
                                        catalog = catalog,
                                        providerFactory = fakeProviderFactory(),
                                    ),
                            ),
                        index = FakeIndex(),
                        packSourceFactory = { FakeRulePackSource() },
                        knowledgeStore = FakeKnowledgeStore(),
                        pipeline = FakeIntentPipeline(),
                    ),
                ).getOrThrow()

        val result = runtime.analyze(AnalysisRequest(targetPaths = listOf("Sample.fake"), language = "fake"))

        assertTrue(result.isFailure)
        val error = assertIs<ContractError.DependencyUnavailable>(result.errorOrNull)
        assertTrue(error.reason.contains("tree-sitter-missing"), error.reason)
    }

    @Test
    fun forceFullAnalysisInvalidatesTheTargetBeforeEnsuring() {
        val recorder = FakeRecorder()
        val path = "Sample.fake"
        val index = FakeIndex(mutableMapOf(path to "fun findUser() {}"), recorder = recorder)
        val runtime =
            RuntimeFactory
                .create(
                    RuntimeConfig(),
                    RuntimeComponents(
                        languageComposition = fakeComposition(recorder),
                        index = index,
                        packSourceFactory = { FakeRulePackSource(recorder = recorder) },
                        knowledgeStore = FakeKnowledgeStore(recorder = recorder),
                        pipeline = FakeIntentPipeline(recorder),
                    ),
                ).getOrThrow()
        runtime.analyze(AnalysisRequest(targetPaths = listOf(path), language = "fake")).getOrThrow()
        recorder.clear()

        runtime
            .analyze(
                AnalysisRequest(targetPaths = listOf(path), language = "fake", forceFullAnalysis = true),
            ).getOrThrow()

        assertEquals(listOf(path), recorder.details("index.invalidate"))
        assertEquals(1, recorder.count("index.indexed"))
        assertEquals(0, recorder.count("index.cacheHit"))
    }

    @Test
    fun publishOnAnalyzePublishesTheReturnedResult() {
        val recorder = FakeRecorder()
        val path = "Sample.fake"
        val published = mutableListOf<AnalysisResult>()
        val runtime =
            RuntimeFactory
                .create(
                    RuntimeConfig(publishOnAnalyze = true),
                    RuntimeComponents(
                        languageComposition = fakeComposition(recorder),
                        index = FakeIndex(mutableMapOf(path to "fun findUser() {}"), recorder = recorder),
                        packSourceFactory = { FakeRulePackSource(recorder = recorder) },
                        knowledgeStore = FakeKnowledgeStore(recorder = recorder),
                        pipeline = FakeIntentPipeline(recorder),
                        publisher =
                            AnalysisPublisher { result, _ ->
                                published += result
                                ResultFactory.success(Unit)
                            },
                    ),
                ).getOrThrow()

        val result = runtime.analyze(AnalysisRequest(targetPaths = listOf(path), language = "fake")).getOrThrow()

        assertEquals(listOf(result), published)
    }

    @Test
    fun multiFileResultsKeepDistinctAnchorsAndQualifiedFacts() {
        val recorder = FakeRecorder()
        val paths = listOf("one/Sample.fake", "two/Sample.fake")
        val pipeline =
            FakeIntentPipeline(recorder) { _, source, _ ->
                ResultFactory.success(unannotatedAnalysis(source))
            }
        val runtime =
            RuntimeFactory
                .create(
                    RuntimeConfig(),
                    RuntimeComponents(
                        languageComposition = fakeComposition(recorder),
                        index = FakeIndex(paths.associateWith { "fun findUser() {}" }.toMutableMap(), recorder = recorder),
                        packSourceFactory = { FakeRulePackSource(recorder = recorder) },
                        knowledgeStore = FakeKnowledgeStore(recorder = recorder),
                        pipeline = pipeline,
                    ),
                ).getOrThrow()

        val result =
            runtime.analyze(AnalysisRequest(targetPaths = paths, language = "fake")).getOrThrow()

        assertEquals(
            paths.map { "$it|span:0:0:0:0" }.sorted(),
            result.annotations.mapNotNull { it.nodeAnchor?.id }.sorted(),
        )
        assertEquals(paths.sorted(), result.candidates.mapNotNull { it.path }.sorted())
        val tree = assertNotNull(result.facts.syntaxTree)
        assertTrue(tree.nodes.keys.all { key -> paths.any { key.startsWith("$it|") } || key == "merge-root" })
    }

    @Test
    fun rootlessSupportCacheReusesOneProviderPerLanguage() {
        val recorder = FakeRecorder()
        val composition = fakeComposition(recorder)
        val index = CapturingIndex()
        val runtime =
            RuntimeFactory
                .create(
                    RuntimeConfig(),
                    RuntimeComponents(
                        languageComposition = composition,
                        index = index,
                        packSourceFactory = { FakeRulePackSource(recorder = recorder) },
                        knowledgeStore = FakeKnowledgeStore(recorder = recorder),
                        pipeline = FakeIntentPipeline(recorder),
                    ),
                ).getOrThrow()

        runtime
            .analyze(AnalysisRequest(targetPaths = listOf("one/Sample.fake"), language = "fake"))
            .getOrThrow()
        runtime
            .analyze(AnalysisRequest(targetPaths = listOf("two/Sample.fake"), language = "fake"))
            .getOrThrow()

        assertEquals(2, index.supports.size)
        assertEquals(1, index.supports.toSet().size)
    }

    @Test
    fun blankRootIsTreatedAsUnset() {
        val result =
            RuntimeFactory.create(
                RuntimeConfig(rootPath = ""),
                RuntimeComponents(
                    packSourceFactory = { FakeRulePackSource() },
                    ruleEvaluators = emptyRegistry(),
                ),
            )

        assertTrue(result.isSuccess)
    }

    @Test
    fun supportCacheKeysIncludeTheRequestedRoot() {
        val recorder = FakeRecorder()
        val composition = fakeComposition(recorder)
        val index = CapturingIndex()
        val runtime =
            RuntimeFactory
                .create(
                    RuntimeConfig(),
                    RuntimeComponents(
                        languageComposition = composition,
                        index = index,
                        packSourceFactory = { FakeRulePackSource(recorder = recorder) },
                        knowledgeStore = FakeKnowledgeStore(recorder = recorder),
                        pipeline = FakeIntentPipeline(recorder),
                    ),
                ).getOrThrow()
        val rootA = temporaryRoot()
        val rootB = temporaryRoot()

        runtime
            .analyze(
                AnalysisRequest(
                    targetPaths = listOf("Sample.fake"),
                    language = "fake",
                    options = mapOf("rootPath" to rootA.toString()),
                ),
            ).getOrThrow()
        runtime
            .analyze(
                AnalysisRequest(
                    targetPaths = listOf("Sample.fake"),
                    language = "fake",
                    options = mapOf("rootPath" to rootB.toString()),
                ),
            ).getOrThrow()

        assertEquals(2, index.supports.size)
        assertNotSame(index.supports[0], index.supports[1])
    }

    @Test
    fun realTreeSitterSyntaxBacksRuntimeAnalysis() {
        val root = temporaryRoot()
        Files.writeString(root.resolve("settings.gradle.kts"), "")
        val source = root.resolve("src/Main.kt")
        Files.createDirectories(source.parent)
        Files.writeString(source, "fun main() = Unit\n")
        val catalog =
            LanguageCatalog.of(
                listOf(
                    fakeDescriptor().copy(
                        id = "kotlin",
                        name = "Kotlin",
                        extensions = listOf("kt", "kts"),
                        grammarName = "tree-sitter-kotlin",
                        providerMode = ProviderMode.MANAGED,
                        rootMarkers = listOf("settings.gradle.kts", ".git"),
                    ),
                ),
            )

        val runtime =
            RuntimeFactory
                .create(
                    RuntimeConfig(
                        rootPath = root.toString(),
                        detectLanguages = false,
                    ),
                    RuntimeComponents(
                        languageComposition =
                            DefaultRuntimeLanguageComposition(
                                catalog = catalog,
                                factory =
                                    LanguageSupportFactory(
                                        catalog = catalog,
                                        providerFactory = fakeProviderFactory(),
                                        providerDefaults =
                                            LanguageProviderDefaults {
                                                SemanticProviderConfig(
                                                    mode = it.providerMode,
                                                    command = "kotlin-lsp --stdio",
                                                )
                                            },
                                    ),
                            ),
                        packSourceFactory = { FakeRulePackSource() },
                        ruleEvaluators = emptyRegistry(),
                    ),
                ).getOrThrow()

        val result =
            runtime
                .analyze(AnalysisRequest(targetPaths = listOf(source.toString())))
                .getOrThrow()

        val tree = assertNotNull(result.facts.syntaxTree)
        assertEquals("kotlin", tree.language)
        assertTrue(tree.nodes.isNotEmpty())
        assertEquals(1, runtime.status().getOrThrow().indexedFiles)
        runtime.shutdown().getOrThrow()
    }

    private fun createRuntime(
        config: RuntimeConfig,
        packSource: FakeRulePackSource,
    ): ContractResult<DefaultRuntime> =
        RuntimeFactory.create(
            config,
            RuntimeComponents(
                packSourceFactory = { packSource },
                ruleEvaluators = emptyRegistry(),
            ),
        )

    private fun temporaryRoot(): Path {
        val root = Files.createTempDirectory("cint-runtime-078")
        temporaryRoots.add(root)
        return root
    }

    private fun fakeComposition(recorder: FakeRecorder): RuntimeLanguageComposition =
        RuntimeLanguageComposition { _, _ ->
            ResultFactory.success(FakeLanguageSupport(fakeDescriptor(), recorder = recorder))
        }

    private fun emptyRegistry(): RuleEvaluatorRegistry = RuleEvaluatorCatalog.bundled()

    private fun annotation(
        name: String,
        intent: String,
    ): FunctionAnnotation =
        FunctionAnnotation(
            selector = FunctionSelector(qualifiedName = name),
            intents = setOf(intent),
            scope = AnnotationScope.GLOBAL,
            provenance = Provenance.USER,
            confidence = Confidence.HIGH,
            status = Status.ACCEPTED,
            createdAt = now,
            updatedAt = now,
        )

    private fun default(
        name: String,
        intent: String,
    ): PackDefault =
        PackDefault(
            selector = FunctionSelector(qualifiedName = name),
            intents = setOf(intent),
            packId = "demo",
            packVersion = "1",
            confidence = Confidence.MEDIUM,
            status = Status.SUGGESTED,
            createdAt = now,
        )

    private fun materializedSnapshot(): KnowledgeSnapshot =
        KnowledgeSnapshot(
            functionAnnotations = emptyList(),
            spanOverrides = emptyList(),
            patternHypotheses = emptyList(),
            materialized = true,
            timestamp = 1,
        )

    private fun unannotatedAnalysis(source: IndexedSource): AnalysisResult =
        AnalysisResult(
            annotations =
                listOf(
                    Annotation(
                        role = Role.SOURCE_INTENT,
                        intents = setOf(Intents.LOOKUP),
                        span = Span.ZERO,
                        source = "test",
                        confidence = Confidence.HIGH,
                        startByte = 0,
                        endByte = 1,
                        provenance =
                            listOf(
                                Contribution(
                                    rule = "test",
                                    role = Role.SOURCE_INTENT,
                                    intents = setOf(Intents.LOOKUP),
                                    span = Span.ZERO,
                                    source = "test",
                                    confidence = Confidence.HIGH,
                                    startByte = 0,
                                    endByte = 1,
                                    provenance = Provenance.RULE,
                                ),
                            ),
                    ),
                ),
            candidates =
                listOf(
                    dev.cint.contracts.data.Candidate(
                        intents = setOf(Intents.LOOKUP),
                        span = Span.ZERO,
                        startByte = 0,
                        endByte = 1,
                        source = "test",
                        confidence = Confidence.HIGH,
                        reason = dev.cint.contracts.data.CandidateReason.GAP,
                    ),
                ),
            facts = source.facts,
            sourceEpoch = source.sourceEpoch,
            completeness = source.completeness,
            errors = emptyList(),
        )

    private fun fakeProviderFactory(): LanguageProviderFactory =
        object : LanguageProviderFactory {
            override fun create(
                descriptor: LanguageDescriptor,
                config: SemanticProviderConfig,
                hostChannel: dev.cint.contracts.ports.HostSemanticChannel?,
                hostChannelProvider: dev.cint.contracts.ports.HostSemanticChannelProvider?,
            ): ContractResult<SemanticProvider> = ResultFactory.success(FakeSemanticProvider(descriptor.id))
        }

    private class CapturingIndex : Index {
        val supports = mutableListOf<LanguageSupport>()

        override fun ensure(
            input: SourceInput,
            support: LanguageSupport,
            cancellation: CancellationToken,
        ): ContractResult<IndexedSource> {
            supports += support
            return ResultFactory.success(
                IndexedSource(
                    input = input,
                    facts = FactBatch(syntaxTree = null),
                    sourceEpoch = supports.size.toLong(),
                    completeness = Completeness.COMPLETE,
                    contentHash = ContentHash.compute(input.toString()).value,
                ),
            )
        }

        override fun fullRescan(
            inputs: List<IndexInput>,
            cancellation: CancellationToken,
        ): ContractResult<RescanResult> = ResultFactory.success(RescanResult(emptyList()))

        override fun invalidate(
            paths: List<String>,
            cancellation: CancellationToken,
        ): ContractResult<Unit> = ResultFactory.success(Unit)

        override fun snapshot(
            path: String,
            cancellation: CancellationToken,
        ): ContractResult<IndexedSource?> = ResultFactory.success(null)

        override fun dependentsOf(
            path: String,
            cancellation: CancellationToken,
        ): ContractResult<List<String>> = ResultFactory.success(emptyList())
    }

    private class FailingShutdownSupport(
        private val delegate: FakeLanguageSupport,
    ) : LanguageSupport by delegate {
        override fun shutdown(): ContractResult<Unit> = ResultFactory.failure(ContractError.InternalError("shutdown failed"))
    }

    private class FakeRepository(
        private var saveFailures: Int = 0,
        var snapshot: KnowledgeSnapshot? = null,
    ) : KnowledgeRepository {
        var saveCalls = 0

        override fun load(cancellation: CancellationToken): ContractResult<KnowledgeSnapshot?> = ResultFactory.success(snapshot)

        override fun save(
            snapshot: KnowledgeSnapshot,
            cancellation: CancellationToken,
        ): ContractResult<Unit> {
            saveCalls += 1
            if (saveFailures > 0) {
                saveFailures -= 1
                return ResultFactory.failure(ContractError.InternalError("save failed"))
            }
            this.snapshot = snapshot
            return ResultFactory.success(Unit)
        }
    }
}
