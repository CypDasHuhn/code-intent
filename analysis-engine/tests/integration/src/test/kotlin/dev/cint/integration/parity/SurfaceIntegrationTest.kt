package dev.cint.integration.parity

import dev.cint.contracts.data.Intents
import dev.cint.contracts.facts.LanguageDescriptor
import dev.cint.contracts.facts.SemanticRequest
import dev.cint.contracts.facts.SemanticRequestKind
import dev.cint.contracts.facts.SemanticResult
import dev.cint.contracts.ports.AnalysisRequest
import dev.cint.contracts.ports.HostSemanticChannel
import dev.cint.contracts.ports.HostSemanticChannelProvider
import dev.cint.contracts.ports.ProviderMode
import dev.cint.contracts.ports.Runtime
import dev.cint.contracts.ports.SemanticProvider
import dev.cint.contracts.ports.SemanticProviderConfig
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.Deadline
import dev.cint.contracts.util.ResultFactory
import dev.cint.runtime.DefaultRuntimeLanguageComposition
import dev.cint.runtime.RuntimeComponents
import dev.cint.runtime.RuntimeConfig
import dev.cint.runtime.RuntimeFactory
import dev.cint.runtime.RuntimeLanguageComposition
import dev.cint.v2.cli.Cli
import dev.cint.v2.cli.CliEnvironment
import dev.cint.v2.cli.CliExitCode
import dev.cint.v2.cli.CliRuntimeFactory
import dev.cint.v2.languages.LanguageProviderDefaults
import dev.cint.v2.languages.LanguageProviderFactory
import dev.cint.v2.languages.LanguageSupportFactory
import dev.cint.v2.languages.TreeSitterLanguageRegistry
import dev.cint.v2.lsp.LspProviderFactory
import dev.cint.v2.mcp.McpServer
import dev.cint.v2.testing.corpus.FrozenV1Corpus
import dev.cint.v2.testing.fakes.FakeSemanticProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SurfaceIntegrationTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val corpus =
        FrozenV1Corpus(
            Paths.get(
                System.getProperty("v2.frozenCorpusRoot")
                    ?: error("v2.frozenCorpusRoot must be set"),
            ),
        )

    @Test
    fun cliRepeatsDeterministicallyAndReportsFailurePaths() {
        val root = Files.createTempDirectory("cint-surface-cli")
        try {
            Files.writeString(root.resolve("Bookkeeping.kt"), corpus.source("kotlin", "Bookkeeping.kt"))
            val first = runCli(listOf(root.toString(), "--lang", "kotlin"))
            val second = runCli(listOf(root.toString(), "--lang", "kotlin"))
            assertEquals(CliExitCode.OK, first.exitCode, first.stderr)
            assertEquals(first.stdout, second.stdout)
            val document = json.parseToJsonElement(first.stdout).jsonObject
            val file =
                document
                    .getValue("files")
                    .jsonArray
                    .single()
                    .jsonObject
            val annotations = file.getValue("annotations").jsonArray
            assertTrue(annotations.isNotEmpty())
            val intents =
                annotations
                    .flatMap { it.jsonObject.getValue("intents").jsonArray }
                    .map { it.jsonPrimitive.content }
                    .toSet()
            assertTrue(Intents.BOOKKEEPING in intents, "intents=$intents")

            val missingRoot = runCli(listOf(root.resolve("missing").toString()))
            assertEquals(CliExitCode.ROOT_NOT_FOUND, missingRoot.exitCode)
            val invalid = runCli(listOf("--bogus"))
            assertEquals(CliExitCode.INVALID_ARGUMENTS, invalid.exitCode)
            val noLsp = runCli(listOf(root.toString(), "--no-lsp"))
            assertEquals(CliExitCode.MISSING_PROVIDER, noLsp.exitCode)
            assertTrue(noLsp.stderr.contains("MISSING_PROVIDER"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun mcpScriptedClientRunsAnalysisAndListsTools() {
        val root = Files.createTempDirectory("cint-surface-mcp")
        val runtime = runtime(root, neutral = true)
        val server = McpServer(runtime, ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream())
        try {
            Files.writeString(root.resolve("Bookkeeping.kt"), corpus.source("kotlin", "Bookkeeping.kt"))
            val initialize =
                assertNotNull(
                    server.handleLine(
                        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"parity","version":"1"}}}""",
                    ),
                )
            assertEquals(
                "2024-11-05",
                initialize
                    .getValue("result")
                    .jsonObject
                    .getValue("protocolVersion")
                    .jsonPrimitive.content,
            )

            val tools = assertNotNull(server.handleLine("""{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}"""))
            val names =
                tools
                    .getValue("result")
                    .jsonObject
                    .getValue("tools")
                    .jsonArray
                    .map {
                        it.jsonObject
                            .getValue("name")
                            .jsonPrimitive.content
                    }
            assertTrue(names.containsAll(listOf("analysis.run", "knowledge.snapshot", "init.begin")))

            val resources = assertNotNull(server.handleLine("""{"jsonrpc":"2.0","id":3,"method":"resources/list","params":{}}"""))
            assertTrue(
                resources
                    .getValue("result")
                    .jsonObject
                    .getValue("resources")
                    .jsonArray
                    .isNotEmpty(),
            )

            val analysis =
                assertNotNull(
                    server.handleLine(
                        """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"analysis.run","arguments":{"targetPaths":["${root.resolve(
                            "Bookkeeping.kt",
                        )}"],"language":"kotlin"}}}""",
                    ),
                )
            val resultObject = analysis.getValue("result").jsonObject
            val payload =
                resultObject["structuredContent"]
                    ?: resultObject
                        .getValue("content")
                        .jsonArray
                        .first()
                        .jsonObject
                        .getValue("text")
            val structured =
                if (payload is kotlinx.serialization.json.JsonPrimitive) {
                    json.parseToJsonElement(payload.content).jsonObject
                } else {
                    payload.jsonObject
                }
            val analyzed = structured.getValue("result").jsonObject
            assertTrue(analyzed.getValue("annotations").jsonArray.isNotEmpty(), "MCP analysis payload: $analysis")

            val failed =
                assertNotNull(
                    server.handleLine("""{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"missing.tool","arguments":{}}}"""),
                )
            assertTrue(failed.containsKey("error") || failed.getValue("result").jsonObject.containsKey("isError"))
            assertNotNull(server.handleLine("""{"jsonrpc":"2.0","id":6,"method":"shutdown","params":{}}"""))
        } finally {
            server.handleLine("""{"jsonrpc":"2.0","id":7,"method":"shutdown","params":{}}""")
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun runtimePerformanceSmokeIsBoundedAndDeterministic() {
        val root = Files.createTempDirectory("cint-surface-perf")
        try {
            for (file in corpus.cli("kotlin").files) {
                Files.writeString(root.resolve(file.path), corpus.source("kotlin", file.path))
            }
            val runtime = runtime(root, neutral = true)
            val paths = Files.list(root).use { stream -> stream.filter { Files.isRegularFile(it) }.sorted().toList() }
            val elapsed =
                measureTimeMillis {
                    var previous: List<Any?>? = null
                    repeat(3) {
                        val current =
                            paths.map { path ->
                                val result =
                                    runtime
                                        .analyze(
                                            AnalysisRequest(
                                                targetPaths = listOf(path.toString()),
                                                language = "kotlin",
                                                options = mapOf("rootPath" to root.toString()),
                                                forceFullAnalysis = true,
                                            ),
                                        ).getOrThrow()
                                result.annotations.map { annotation ->
                                    listOf(
                                        annotation.role,
                                        annotation.intents,
                                        annotation.span,
                                        annotation.startByte,
                                        annotation.endByte,
                                        annotation.confidence,
                                    )
                                } +
                                    result.candidates.map { candidate ->
                                        listOf(
                                            candidate.intents,
                                            candidate.span,
                                            candidate.startByte,
                                            candidate.endByte,
                                            candidate.reason,
                                        )
                                    }
                            }
                        if (previous != null) assertEquals(previous, current)
                        previous = current
                    }
                }
            assertTrue(elapsed < 30_000, "3x frozen Kotlin corpus analysis took ${elapsed}ms")
            runtime.shutdown().getOrThrow()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun sharedScriptedLspFixtureDrivesTheManagedProvider() {
        val java = Paths.get(System.getProperty("java.home"), "bin", "java").toString()
        val classpath = System.getProperty("java.class.path")
        val command = "$java -cp \"$classpath\" dev.cint.v2.testing.lsp.ScriptedLspServerMain"
        val provider =
            LspProviderFactory
                .create(
                    SemanticProviderConfig(
                        mode = ProviderMode.MANAGED,
                        command = command,
                        timeoutMs = 10_000,
                        initTimeoutMs = 10_000,
                    ),
                    "kotlin",
                ).getOrThrow()
        val uri = "file:///tmp/SharedScripted.kt"
        try {
            provider.openDocument(uri, "fun holder() { val value = 1 }", version = 1).getOrThrow()
            val type =
                provider
                    .request(
                        SemanticRequest(
                            kind = SemanticRequestKind.TYPE_AT,
                            uri = uri,
                            offset = 20,
                            version = 1,
                            deadline = Deadline.fromNowMs(10_000),
                        ),
                    ).getOrThrow()
            assertTrue(type is SemanticResult.TypeAt)
            assertTrue(type.typeRef?.fqName?.contains("Int") == true)
        } finally {
            provider.shutdown().getOrThrow()
        }
    }

    @Test
    fun runtimeClasspathHasNoV1Modules() {
        assertFailsWith<ClassNotFoundException> { Class.forName("dev.cint.host.HostRuntime") }
        assertFailsWith<ClassNotFoundException> { Class.forName("dev.cint.cli.Main") }
    }

    private fun runCli(args: List<String>): CliRun {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val exitCode =
            Cli.run(
                args.toTypedArray(),
                PrintStream(stdout, true, StandardCharsets.UTF_8),
                PrintStream(stderr, true, StandardCharsets.UTF_8),
                CliEnvironment(CliRuntimeFactory { config -> ResultFactory.success(runtimeFor(config)) }),
            )
        return CliRun(exitCode, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8))
    }

    private fun runtimeFor(config: RuntimeConfig): Runtime =
        RuntimeFactory.create(config, RuntimeComponents(languageComposition = composition())).getOrThrow()

    private fun runtime(
        root: Path,
        neutral: Boolean,
    ): Runtime =
        RuntimeFactory
            .create(
                RuntimeConfig(rootPath = root.toString()),
                RuntimeComponents(languageComposition = composition()),
            ).getOrThrow()

    private fun composition(): RuntimeLanguageComposition {
        val factory =
            LanguageSupportFactory(
                catalog = TreeSitterLanguageRegistry,
                providerFactory =
                    object : LanguageProviderFactory {
                        override fun create(
                            descriptor: LanguageDescriptor,
                            config: SemanticProviderConfig,
                            hostChannel: HostSemanticChannel?,
                            hostChannelProvider: HostSemanticChannelProvider?,
                        ): ContractResult<SemanticProvider> =
                            ResultFactory.success(
                                FakeSemanticProvider(
                                    language = descriptor.id,
                                    responseFactory = { request -> ResultFactory.success(neutral(request.kind)) },
                                ),
                            )
                    },
                providerDefaults =
                    LanguageProviderDefaults { _ ->
                        SemanticProviderConfig(mode = ProviderMode.MANAGED, command = "surface-neutral")
                    },
            )
        return DefaultRuntimeLanguageComposition(TreeSitterLanguageRegistry, factory)
    }

    private fun neutral(kind: SemanticRequestKind): SemanticResult =
        when (kind) {
            SemanticRequestKind.TYPE_AT -> SemanticResult.TypeAt(typeRef = null)
            SemanticRequestKind.SYMBOL_AT -> SemanticResult.SymbolAt(symbol = null)
            SemanticRequestKind.DEFINITION_OF -> SemanticResult.DefinitionOf(emptyList())
            SemanticRequestKind.DIAGNOSTICS_FOR -> SemanticResult.DiagnosticsFor(emptyList())
            SemanticRequestKind.HOVER_AT -> SemanticResult.HoverAt(content = null)
            SemanticRequestKind.REFERENCES_AT -> SemanticResult.ReferencesAt(emptyList())
        }

    private data class CliRun(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )
}
