package dev.cint.v2.cli

import dev.cint.contracts.data.AnalysisError
import dev.cint.contracts.data.AnalysisResult
import dev.cint.contracts.data.Candidate
import dev.cint.contracts.data.CandidateReason
import dev.cint.contracts.data.Completeness
import dev.cint.contracts.data.Confidence
import dev.cint.contracts.data.Intents
import dev.cint.contracts.data.Provenance
import dev.cint.contracts.data.Span
import dev.cint.contracts.data.Status
import dev.cint.contracts.facts.LanguageDescriptor
import dev.cint.contracts.facts.SourceInput
import dev.cint.contracts.knowledge.AnnotationScope
import dev.cint.contracts.knowledge.FunctionAnnotation
import dev.cint.contracts.knowledge.FunctionSelector
import dev.cint.contracts.knowledge.PackDefault
import dev.cint.contracts.ports.AnalysisRequest
import dev.cint.contracts.ports.HostSemanticChannel
import dev.cint.contracts.ports.HostSemanticChannelProvider
import dev.cint.contracts.ports.LanguageSupport
import dev.cint.contracts.ports.PackManifest
import dev.cint.contracts.ports.ProviderMode
import dev.cint.contracts.ports.Runtime
import dev.cint.contracts.ports.SemanticProvider
import dev.cint.contracts.ports.SemanticProviderConfig
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ContentHash
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ResultFactory
import dev.cint.runtime.DefaultRuntimeLanguageComposition
import dev.cint.runtime.RuntimeComponents
import dev.cint.runtime.RuntimeConfig
import dev.cint.runtime.RuntimeFactory
import dev.cint.runtime.RuntimeLanguageComposition
import dev.cint.v2.languages.LanguageCatalog
import dev.cint.v2.languages.LanguageProviderDefaults
import dev.cint.v2.languages.LanguageProviderFactory
import dev.cint.v2.languages.LanguageSupportFactory
import dev.cint.v2.languages.TreeSitterLanguageRegistry
import dev.cint.v2.testing.corpus.FrozenV1Corpus
import dev.cint.v2.testing.fakes.FakeClock
import dev.cint.v2.testing.fakes.FakeIndex
import dev.cint.v2.testing.fakes.FakeIntentPipeline
import dev.cint.v2.testing.fakes.FakeKnowledgeStore
import dev.cint.v2.testing.fakes.FakeLanguageSupport
import dev.cint.v2.testing.fakes.FakeRecorder
import dev.cint.v2.testing.fakes.FakeRulePackSource
import dev.cint.v2.testing.fakes.FakeRuntime
import dev.cint.v2.testing.fakes.FakeSemanticProvider
import dev.cint.v2.testing.fakes.fakeDescriptor
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class CliTest {
    private val temporaryRoots = mutableListOf<Path>()
    private val json = Json { ignoreUnknownKeys = true }
    private val now = FakeClock.DEFAULT_INSTANT

    @AfterTest
    fun cleanUp() {
        temporaryRoots.forEach { runCatching { it.toFile().deleteRecursively() } }
    }

    @Test
    fun happyPathEmitsDeterministicJsonForAFixture() {
        val root = temporaryRoot()
        val file = write(root, "Sample.kt", "fun findUser() { call repository.findUser }\n")
        val environment = fakeEnvironment(mapOf(file to "fun findUser() { call repository.findUser }\n"))

        val first = run(listOf(root.toString(), "--lang", "kotlin"), environment)
        val second = run(listOf(root.toString(), "--lang", "kotlin"), environment)

        assertEquals(CliExitCode.OK, first.exitCode)
        assertEquals("", first.stderr)
        assertEquals(first.stdout, second.stdout)
        val document = parse(first.stdout)
        assertEquals("kotlin", document["language"]!!.jsonPrimitive.content)
        assertFalse(document.containsKey("intents"))
        assertTrue(document.containsKey("meta"))
        val files = document["files"]!!.jsonArray
        assertEquals(1, files.size)
        val analyzed = files.single().jsonObject
        assertEquals("Sample.kt", analyzed["path"]!!.jsonPrimitive.content)
        assertEquals(ContentHash.compute("fun findUser() { call repository.findUser }").value, analyzed["hash"]!!.jsonPrimitive.content)
        val annotations = analyzed["annotations"]!!.jsonArray
        assertEquals(1, annotations.size)
        assertEquals(listOf("LOOKUP"), annotations.single().jsonObject["intents"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(1, document["meta"]!!.jsonObject["fileCount"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun outputOrderIsIndependentOfFileSystemAndAnalysisOrder() {
        val root = temporaryRoot()
        val first = write(root, "z.kt", "fun work() {}\n")
        val second = write(root, "a.kt", "fun work() {}\n")
        val environment =
            fakeEnvironment(
                mapOf(
                    first to "fun work() {}\n",
                    second to "fun work() {}\n",
                ),
            )

        val result = run(listOf(root.toString(), "--lang", "kotlin"), environment)

        assertEquals(CliExitCode.OK, result.exitCode)
        val files = parse(result.stdout)["files"]!!.jsonArray.map { it.jsonObject["path"]!!.jsonPrimitive.content }
        assertEquals(listOf("a.kt", "z.kt"), files)
    }

    @Test
    fun invalidArgumentsHaveAStableExitCodeAndStructuredError() {
        val unknown = run(listOf("--bogus"))
        assertEquals(CliExitCode.INVALID_ARGUMENTS, unknown.exitCode)
        assertEquals("INVALID_ARGUMENTS", error(unknown.stdout, unknown.stderr))

        val missingRoot = run(emptyList())
        assertEquals(CliExitCode.INVALID_ARGUMENTS, missingRoot.exitCode)

        val invalidProvider = run(listOf(temporaryRoot().toString(), "--provider", "magic"))
        assertEquals(CliExitCode.INVALID_ARGUMENTS, invalidProvider.exitCode)

        val invalidLanguage = run(listOf(temporaryRoot().toString(), "--lang", "typo"))
        assertEquals(CliExitCode.INVALID_ARGUMENTS, invalidLanguage.exitCode)
        assertEquals("INVALID_ARGUMENTS", error(invalidLanguage.stdout, invalidLanguage.stderr))
    }

    @Test
    fun missingRootProviderGrammarAndKnowledgeErrorsAreDistinct() {
        val missing = temporaryRoot().resolve("missing")
        val missingRoot = run(listOf(missing.toString()))
        assertEquals(CliExitCode.ROOT_NOT_FOUND, missingRoot.exitCode)
        assertEquals("ROOT_NOT_FOUND", error(missingRoot.stdout, missingRoot.stderr))

        val root = temporaryRoot()
        write(root, "Sample.kt", "fun findUser() {}\n")
        val provider =
            run(
                listOf(root.toString(), "--lang", "kotlin"),
                failingEnvironment(ContractError.DependencyUnavailable("semantic-provider-fake", "no oracle")),
            )
        assertEquals(CliExitCode.MISSING_PROVIDER, provider.exitCode)
        assertEquals("MISSING_PROVIDER", error(provider.stdout, provider.stderr))

        val grammar =
            run(
                listOf(root.toString(), "--lang", "kotlin"),
                failingEnvironment(ContractError.DependencyUnavailable("tree-sitter-fake", "no grammar")),
            )
        assertEquals(CliExitCode.MISSING_GRAMMAR, grammar.exitCode)
        assertEquals("MISSING_GRAMMAR", error(grammar.stdout, grammar.stderr))

        val knowledge =
            run(
                listOf(root.toString(), "--lang", "kotlin", "--knowledge", "broken.json", "--init"),
                failingEnvironment(ContractError.SerializationError("Unable to read knowledge store", "bad json")),
            )
        assertEquals(CliExitCode.KNOWLEDGE_ERROR, knowledge.exitCode)
        assertEquals("KNOWLEDGE_ERROR", error(knowledge.stdout, knowledge.stderr))

        val analysis =
            run(
                listOf(root.toString(), "--lang", "kotlin"),
                failingEnvironment(ContractError.InternalError("pipeline exploded")),
            )
        assertEquals(CliExitCode.ANALYSIS_FAILED, analysis.exitCode)
        assertEquals("ANALYSIS_FAILED", error(analysis.stdout, analysis.stderr))
    }

    @Test
    fun knowledgeInitializationMaterializesDefaultsOnlyWhenRequested() {
        val root = temporaryRoot()
        write(root, "README.txt", "no analyzable sources\n")
        var seenConfig: RuntimeConfig? = null
        val environment =
            CliEnvironment(
                CliRuntimeFactory { config ->
                    seenConfig = config
                    RuntimeFactory.create(
                        config,
                        RuntimeComponents(
                            packSourceFactory = {
                                FakeRulePackSource(
                                    packManifests = listOf(PackManifest("demo", "Demo", "1", listOf("kotlin"))),
                                    defaultsByLanguage =
                                        mapOf(
                                            "kotlin" to
                                                listOf(
                                                    PackDefault(
                                                        selector = FunctionSelector(qualifiedName = "findUser"),
                                                        intents = setOf(Intents.LOGGING),
                                                        packId = "demo",
                                                        packVersion = "1",
                                                        createdAt = now,
                                                    ),
                                                ),
                                        ),
                                )
                            },
                            pipeline = FakeIntentPipeline(),
                        ),
                    )
                },
            )

        val initialized = run(listOf(root.toString(), "--lang", "kotlin", "--init"), environment)

        assertEquals(CliExitCode.OK, initialized.exitCode)
        assertEquals(root.toString(), seenConfig?.rootPath)
        assertEquals(setOf("kotlin"), seenConfig?.initializeLanguages)
        val knowledge = root.resolve(".cint/knowledge.json")
        assertTrue(Files.isRegularFile(knowledge))
        assertTrue(Files.readString(knowledge).contains("findUser"))
        assertTrue(Files.readString(knowledge).contains("LOGGING"))
    }

    @Test
    fun outputFileReceivesTheSameDocumentAsStdout() {
        val root = temporaryRoot()
        val file = write(root, "Sample.kt", "fun findUser() {}\n")
        val environment = fakeEnvironment(mapOf(file to "fun findUser() {}\n"))

        val stdoutRun = run(listOf(root.toString(), "--lang", "kotlin"), environment)
        val fileRun = run(listOf(root.toString(), "--lang", "kotlin", "--out", root.resolve("out/result.json").toString()), environment)

        assertEquals(CliExitCode.OK, fileRun.exitCode)
        assertEquals("", fileRun.stdout)
        val output = root.resolve("out/result.json")
        assertTrue(Files.isRegularFile(output))
        assertEquals(parse(stdoutRun.stdout), parse(Files.readString(output)))
    }

    @Test
    fun outputShapeMatchesTheFrozenCorpusWhereSemanticsAreUnchanged() {
        val corpus = FrozenV1Corpus(Paths.get(System.getProperty("v2.frozenCorpusRoot")))
        val frozen = corpus.cli("kotlin")
        val frozenFile = frozen.raw["files"]!!.jsonArray.first().jsonObject
        val frozenSpan = frozenFile["spans"]!!.jsonArray.first().jsonObject

        val root = temporaryRoot()
        val file = write(root, "Sample.kt", "fun findUser() {}\n")
        val result = run(listOf(root.toString(), "--lang", "kotlin"), fakeEnvironment(mapOf(file to "fun findUser() {}\n")))
        val document = parse(result.stdout)
        val v2File = document["files"]!!.jsonArray.first().jsonObject
        val v2Annotation = v2File["annotations"]!!.jsonArray.first().jsonObject

        assertEquals(setOf("language", "files", "meta"), document.keys)
        assertTrue(frozen.raw.containsKey("intents"))
        assertEquals(setOf("path", "hash", "annotations", "candidates", "diagnostics"), v2File.keys)
        assertEquals(setOf("path", "hash", "spans", "candidates"), frozenFile.keys)
        val unchanged = listOf("role", "intents", "source", "confidence", "startLine", "startCol", "endLine", "endCol", "startByte", "endByte", "nodeAnchor", "provenance")
        unchanged.forEach { field ->
            assertTrue(field in frozenSpan, "frozen span is missing $field")
            assertTrue(field in v2Annotation, "V2 annotation is missing $field")
        }
    }

    @Test
    fun fileRootsUseTheirParentAsTheProjectRoot() {
        val root = temporaryRoot()
        val file = write(root, "Single.kt", "fun findUser() {}\n")
        val delegate = fakeRuntime(mapOf(file to "fun findUser() {}\n"))
        var seen: AnalysisRequest? = null
        val recording =
            object : Runtime by delegate {
                override fun analyze(
                    request: AnalysisRequest,
                    cancellation: CancellationToken,
                ): ContractResult<AnalysisResult> {
                    seen = request
                    return delegate.analyze(request, cancellation)
                }
            }
        val result =
            run(
                listOf(file.toString(), "--lang", "kotlin"),
                CliEnvironment(CliRuntimeFactory { ResultFactory.success(recording) }),
            )

        assertEquals(CliExitCode.OK, result.exitCode)
        assertEquals(root.toString(), seen!!.options["rootPath"])
        val analyzed = parse(result.stdout)["files"]!!.jsonArray.single().jsonObject
        assertEquals("Single.kt", analyzed["path"]!!.jsonPrimitive.content)
        assertEquals(1, analyzed["annotations"]!!.jsonArray.size)
    }

    @Test
    fun rootsInsideSkippedDirectoryNamesAreStillAnalyzed() {
        val parent = temporaryRoot()
        val root = Files.createDirectories(parent.resolve("build/project"))
        val file = write(root, "Sample.kt", "fun findUser() {}\n")

        val result = run(listOf(root.toString(), "--lang", "kotlin"), fakeEnvironment(mapOf(file to "fun findUser() {}\n")))

        assertEquals(CliExitCode.OK, result.exitCode)
        assertEquals(1, parse(result.stdout)["files"]!!.jsonArray.size)
    }

    @Test
    fun languageAliasesAreCanonicalizedForOutputExtensionsAndConfig() {
        val root = temporaryRoot()
        val file = write(root, "Sample.kt", "fun findUser() {}\n")
        val aliased = run(listOf(root.toString(), "--lang", "kt"), fakeEnvironment(mapOf(file to "fun findUser() {}\n")))
        assertEquals(CliExitCode.OK, aliased.exitCode)
        assertEquals("kotlin", parse(aliased.stdout)["language"]!!.jsonPrimitive.content)

        var seen: RuntimeConfig? = null
        val environment =
            CliEnvironment(
                CliRuntimeFactory { config ->
                    seen = config
                    ResultFactory.failure(ContractError.DependencyUnavailable("semantic-provider-kotlin", "stop"))
                },
            )
        val configured =
            run(
                listOf(
                    root.toString(),
                    "--lang",
                    "kt",
                    "--provider",
                    "managed",
                    "--provider-command",
                    "custom-lsp",
                    "--init",
                ),
                environment,
            )
        assertEquals(CliExitCode.MISSING_PROVIDER, configured.exitCode)
        assertEquals(setOf("kotlin"), seen?.initializeLanguages)
        assertEquals("custom-lsp", seen?.providerConfigs?.getValue("kotlin")?.command)
        assertFalse(seen?.providerConfigs?.containsKey("kt") == true)
    }

    @Test
    fun knowledgeRequiresInitAndNoInitDoesNotCreateAStore() {
        val root = temporaryRoot()
        val file = write(root, "Sample.kt", "fun findUser() {}\n")
        val noInit =
            run(
                listOf(root.toString(), "--lang", "kotlin", "--knowledge", "knowledge.json"),
                fakeEnvironment(mapOf(file to "fun findUser() {}\n")),
            )
        assertEquals(CliExitCode.INVALID_ARGUMENTS, noInit.exitCode)
        assertFalse(Files.exists(root.resolve("knowledge.json")))

        val ordinary = run(listOf(root.toString(), "--lang", "kotlin"), fakeEnvironment(mapOf(file to "fun findUser() {}\n")))
        assertEquals(CliExitCode.OK, ordinary.exitCode)
        assertFalse(Files.exists(root.resolve(".cint/knowledge.json")))
    }

    @Test
    fun diagnosticOnlyFilesRemainVisible() {
        val root = temporaryRoot()
        val file = write(root, "Broken.kt", "fun broken(\n")
        val pipeline =
            FakeIntentPipeline(
                FakeRecorder(),
                { _, source, _ ->
                    ResultFactory.success(
                        AnalysisResult(
                            annotations = emptyList(),
                            candidates = emptyList(),
                            facts = source.facts,
                            sourceEpoch = source.sourceEpoch,
                            completeness = Completeness.PARTIAL,
                            errors = listOf(AnalysisError("SYNTAX_ERROR", "unexpected eof")),
                        ),
                    )
                },
            )
        val environment = CliEnvironment(CliRuntimeFactory { ResultFactory.success(fakeRuntime(mapOf(file to "fun broken(\n"), pipeline)) })

        val result = run(listOf(root.toString(), "--lang", "kotlin"), environment)

        assertEquals(CliExitCode.OK, result.exitCode)
        val analyzed = parse(result.stdout)["files"]!!.jsonArray.single().jsonObject
        assertEquals(0, analyzed["annotations"]!!.jsonArray.size)
        assertEquals("SYNTAX_ERROR", analyzed["diagnostics"]!!.jsonArray.single().jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun absentCandidateOptionalsAreOmitted() {
        val root = temporaryRoot()
        val file = write(root, "Sample.kt", "fun findUser() {}\n")
        val pipeline =
            FakeIntentPipeline(
                FakeRecorder(),
                { _, source, _ ->
                    ResultFactory.success(
                        AnalysisResult(
                            annotations = emptyList(),
                            candidates =
                                listOf(
                                    Candidate(
                                        intents = setOf(Intents.LOOKUP),
                                        span = Span(0, 0, 0, 10),
                                        startByte = 0,
                                        endByte = 10,
                                        source = "rule:lookup",
                                        confidence = Confidence.LOW,
                                        reason = CandidateReason.GAP,
                                    ),
                                ),
                            facts = source.facts,
                            sourceEpoch = source.sourceEpoch,
                            completeness = source.completeness,
                            errors = emptyList(),
                        ),
                    )
                },
            )
        val environment = CliEnvironment(CliRuntimeFactory { ResultFactory.success(fakeRuntime(mapOf(file to "fun findUser() {}\n"), pipeline)) })

        val result = run(listOf(root.toString(), "--lang", "kotlin"), environment)

        assertEquals(CliExitCode.OK, result.exitCode)
        val candidate =
            parse(result.stdout)["files"]!!.jsonArray.single().jsonObject["candidates"]!!.jsonArray.single().jsonObject
        assertEquals("GAP", candidate["reason"]!!.jsonPrimitive.content)
        for (field in listOf("provider", "model", "rationale", "path")) {
            assertFalse(candidate.containsKey(field), "$field should be omitted")
        }
    }

    @Test
    fun ndjsonEmitsOneRecordPerFileAndAMetaRecord() {
        val root = temporaryRoot()
        val file = write(root, "Sample.kt", "fun findUser() {}\n")
        val result = run(listOf(root.toString(), "--lang", "kotlin", "--format", "ndjson"), fakeEnvironment(mapOf(file to "fun findUser() {}\n")))

        assertEquals(CliExitCode.OK, result.exitCode)
        val lines = result.stdout.lineSequence().filter { it.isNotBlank() }.map(::parse).toList()
        assertEquals(2, lines.size)
        assertEquals("file", lines[0]["type"]!!.jsonPrimitive.content)
        assertEquals("Sample.kt", lines[0]["file"]!!.jsonObject["path"]!!.jsonPrimitive.content)
        assertEquals("meta", lines[1]["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun fullRescanSetsTheRuntimeRequestFlag() {
        val root = temporaryRoot()
        val file = write(root, "Sample.kt", "fun findUser() {}\n")
        val delegate = fakeRuntime(mapOf(file to "fun findUser() {}\n"))
        var seen: AnalysisRequest? = null
        val recording =
            object : Runtime by delegate {
                override fun analyze(
                    request: AnalysisRequest,
                    cancellation: CancellationToken,
                ): ContractResult<AnalysisResult> {
                    seen = request
                    return delegate.analyze(request, cancellation)
                }
            }
        val environment = CliEnvironment(CliRuntimeFactory { ResultFactory.success(recording) })

        val result = run(listOf(root.toString(), "--lang", "kotlin", "--full-rescan"), environment)

        assertEquals(CliExitCode.OK, result.exitCode)
        assertTrue(seen!!.forceFullAnalysis)
    }

    @Test
    fun outputFailuresUseTheOutputExitCode() {
        val root = temporaryRoot()
        val file = write(root, "Sample.kt", "fun findUser() {}\n")
        val directory = Files.createDirectory(root.resolve("output-dir"))

        val result =
            run(
                listOf(root.toString(), "--lang", "kotlin", "--out", directory.toString()),
                fakeEnvironment(mapOf(file to "fun findUser() {}\n")),
            )

        assertEquals(CliExitCode.OUTPUT_ERROR, result.exitCode)
        assertEquals("OUTPUT_ERROR", error(result.stdout, result.stderr))
    }

    @Test
    fun realRuntimeReportsProviderAndGrammarFailures() {
        val root = temporaryRoot()
        write(root, "Sample.kt", "fun findUser() = Unit\n")
        val providerFailure =
            object : LanguageProviderFactory {
                override fun create(
                    descriptor: LanguageDescriptor,
                    config: SemanticProviderConfig,
                    hostChannel: HostSemanticChannel?,
                    hostChannelProvider: HostSemanticChannelProvider?,
                ): ContractResult<SemanticProvider> =
                    ResultFactory.failure(ContractError.DependencyUnavailable("semantic-provider-kotlin", "missing"))
            }

        val provider =
            run(listOf(root.toString(), "--lang", "kotlin"), realRuntimeEnvironment(providerFactory = providerFailure))
        assertEquals(CliExitCode.MISSING_PROVIDER, provider.exitCode)
        assertEquals("MISSING_PROVIDER", error(provider.stdout, provider.stderr))

        val descriptor =
            fakeDescriptor().copy(
                id = "kotlin",
                name = "Kotlin",
                extensions = listOf("kt"),
                grammarName = "tree-sitter-missing",
                providerMode = ProviderMode.MANAGED,
            )
        val grammar =
            run(
                listOf(root.toString(), "--lang", "kotlin"),
                realRuntimeEnvironment(catalog = LanguageCatalog.of(listOf(descriptor))),
            )
        assertEquals(CliExitCode.MISSING_GRAMMAR, grammar.exitCode)
        assertEquals("MISSING_GRAMMAR", error(grammar.stdout, grammar.stderr))
    }

    @Test
    fun realRuntimeAnalyzesTheFrozenKotlinCorpusShape() {
        val corpus = FrozenV1Corpus(Paths.get(System.getProperty("v2.frozenCorpusRoot")))
        val frozen = corpus.cli("kotlin").files.first { it.path == "Bookkeeping.kt" }
        val root = temporaryRoot()
        write(root, "Bookkeeping.kt", corpus.source("kotlin", "Bookkeeping.kt"))

        val result = run(listOf(root.toString(), "--lang", "kotlin"), realRuntimeEnvironment())

        assertEquals(CliExitCode.OK, result.exitCode)
        val analyzed = parse(result.stdout)["files"]!!.jsonArray.single().jsonObject
        assertEquals(frozen.path, analyzed["path"]!!.jsonPrimitive.content)
        assertEquals(frozen.hash, analyzed["hash"]!!.jsonPrimitive.content)
        val annotations = analyzed["annotations"]!!.jsonArray
        assertTrue(annotations.isNotEmpty())
        val intents = annotations.flatMap { it.jsonObject["intents"]!!.jsonArray }.map { it.jsonPrimitive.content }.toSet()
        assertTrue(intents.any { it == Intents.BOOKKEEPING || it == Intents.VALIDATION }, "intents=$intents")
    }

    @Test
    fun cliClasspathDoesNotContainV1Modules() {
        assertFailsWith<ClassNotFoundException> { Class.forName("dev.cint.host.HostRuntime") }
        assertFailsWith<ClassNotFoundException> { Class.forName("dev.cint.cli.Main") }
        assertEquals("dev.cint.v2.cli.Main", Class.forName("dev.cint.v2.cli.Main").name)
    }

    private fun fakeEnvironment(texts: Map<Path, String>): CliEnvironment =
        CliEnvironment(CliRuntimeFactory { ResultFactory.success(fakeRuntime(texts)) })

    private fun fakeRuntime(
        texts: Map<Path, String>,
        pipeline: dev.cint.contracts.ports.IntentPipeline? = null,
    ): FakeRuntime {
        val recorder = FakeRecorder()
        val clock = FakeClock()
        val sourceMap = texts.mapKeys { it.key.toString() }.mapValues { it.value }
        val knowledge =
            FakeKnowledgeStore(
                functionAnnotations =
                    listOf(
                        annotation("findUser", Intents.LOOKUP),
                        annotation("work", Intents.LOGGING),
                    ),
                clock = clock,
                recorder = recorder,
            )
        return FakeRuntime(
            supports = mapOf("kotlin" to FakeLanguageSupport(fakeDescriptor(), recorder = recorder)),
            sources =
                sourceMap.mapValues { (path, text) ->
                    SourceInput.InMemory(text, "kotlin", 1, path)
                }.toMutableMap(),
            index = FakeIndex(sourceMap.toMutableMap(), clock, recorder = recorder),
            pipeline = pipeline ?: FakeIntentPipeline(recorder),
            knowledgeStore = knowledge,
            clock = clock,
            recorder = recorder,
        )
    }

    private fun failingEnvironment(error: ContractError): CliEnvironment =
        CliEnvironment(CliRuntimeFactory { ResultFactory.failure(error) })

    private fun realRuntimeEnvironment(
        catalog: LanguageCatalog = TreeSitterLanguageRegistry,
        providerFactory: LanguageProviderFactory = fakeProviderFactory(),
    ): CliEnvironment =
        CliEnvironment(
            CliRuntimeFactory { config ->
                RuntimeFactory.create(
                    config,
                    RuntimeComponents(languageComposition = realComposition(catalog, providerFactory)),
                )
            },
        )

    private fun realComposition(
        catalog: LanguageCatalog,
        providerFactory: LanguageProviderFactory,
    ): RuntimeLanguageComposition {
        val factory =
            LanguageSupportFactory(
                catalog = catalog,
                providerFactory = providerFactory,
                providerDefaults =
                    LanguageProviderDefaults { descriptor ->
                        SemanticProviderConfig(
                            mode = descriptor.providerMode,
                            command = if (descriptor.providerMode == ProviderMode.MANAGED) "fake-lsp --stdio" else null,
                        )
                    },
            )
        return DefaultRuntimeLanguageComposition(catalog, factory)
    }

    private fun fakeProviderFactory(): LanguageProviderFactory =
        object : LanguageProviderFactory {
            override fun create(
                descriptor: LanguageDescriptor,
                config: SemanticProviderConfig,
                hostChannel: HostSemanticChannel?,
                hostChannelProvider: HostSemanticChannelProvider?,
            ): ContractResult<SemanticProvider> = ResultFactory.success(FakeSemanticProvider(descriptor.id))
        }

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

    private fun write(
        root: Path,
        name: String,
        text: String,
    ): Path {
        val file = root.resolve(name).toAbsolutePath().normalize()
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
        return file
    }

    private fun temporaryRoot(): Path {
        val root = Files.createTempDirectory("cint-v2-cli")
        temporaryRoots.add(root)
        return root
    }

    private fun parse(text: String): JsonObject = json.parseToJsonElement(text).jsonObject

    private fun error(
        stdout: String,
        stderr: String,
    ): String {
        assertEquals("", stdout)
        return parse(stderr)["error"]!!.jsonObject["code"]!!.jsonPrimitive.content
    }

    private data class RunResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )

    private fun run(
        args: List<String>,
        environment: CliEnvironment = CliEnvironment(),
    ): RunResult {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val code =
            Cli.run(
                args.toTypedArray(),
                PrintStream(stdout, true, StandardCharsets.UTF_8),
                PrintStream(stderr, true, StandardCharsets.UTF_8),
                environment,
            )
        return RunResult(
            code,
            stdout.toString(StandardCharsets.UTF_8),
            stderr.toString(StandardCharsets.UTF_8),
        )
    }
}
