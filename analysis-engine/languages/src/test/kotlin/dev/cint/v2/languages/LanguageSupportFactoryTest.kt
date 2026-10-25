package dev.cint.v2.languages

import dev.cint.contracts.facts.ChangedRanges
import dev.cint.contracts.facts.FactBatch
import dev.cint.contracts.facts.LanguageDescriptor
import dev.cint.contracts.facts.SemanticRequest
import dev.cint.contracts.facts.SemanticRequestKind
import dev.cint.contracts.facts.SemanticResult
import dev.cint.contracts.facts.SourceInput
import dev.cint.contracts.facts.SyntaxTree
import dev.cint.contracts.ports.HostChannelConfig
import dev.cint.contracts.ports.HostSemanticChannel
import dev.cint.contracts.ports.HostSemanticChannelProvider
import dev.cint.contracts.ports.ProviderMode
import dev.cint.contracts.ports.SemanticProvider
import dev.cint.contracts.ports.SemanticProviderConfig
import dev.cint.contracts.ports.SyntaxEngine
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.Deadline
import dev.cint.contracts.util.ResultFactory
import dev.cint.v2.lsp.LspTransport
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanguageSupportFactoryTest {
    private val temporaryRoots = mutableListOf<Path>()

    @AfterTest
    fun cleanUp() {
        temporaryRoots.forEach { runCatching { it.toFile().deleteRecursively() } }
    }

    @Test
    fun kotlinResolvesSyntaxAndProvider() {
        val root = projectRoot()
        val providers = RecordingProviderFactory()
        val factory =
            LanguageSupportFactory(
                providerFactory = providers,
                rootLocator = MarkerRootLocator,
                providerDefaults = kotlinDefaults,
            )

        val support =
            factory
                .resolveForPath(root.resolve("src/Main.kt").toString())
                .successValue()

        assertEquals("kotlin", support.descriptor().successValue().id)
        assertEquals("tree-sitter-kotlin", support.descriptor().successValue().grammarName)
        val syntax = support.syntax().successValue()
        assertEquals("kotlin", syntax.language)
        val tree = syntax.parse("fun main() = Unit".encodeToByteArray(), null).successValue()
        assertEquals("kotlin", tree.language)

        val provider = support.semanticProvider().successValue()
        assertEquals("kotlin", provider.language)
        assertEquals(ProviderMode.MANAGED, providers.modes.single())
        assertEquals(root.toString(), providers.configs.single().rootPath)
        assertEquals("kotlin-lsp --stdio", providers.configs.single().command)
        support.shutdown().successValue()
        assertEquals(1, providers.shutdownCount)
    }

    @Test
    fun providerDefaultsCanOverrideTheShippedKotlinCommand() {
        val root = projectRoot()
        val providers = RecordingProviderFactory()
        val factory =
            LanguageSupportFactory(
                providerFactory = providers,
                rootLocator = MarkerRootLocator,
                providerDefaults =
                    LanguageProviderDefaults { descriptor ->
                        SemanticProviderConfig(
                            mode = descriptor.providerMode,
                            command = "custom-kotlin-lsp --stdio",
                        )
                    },
            )

        factory.resolveForPath(root.resolve("src/Main.kt").toString()).successValue()

        assertEquals(ProviderMode.MANAGED, providers.modes.single())
        assertEquals("custom-kotlin-lsp --stdio", providers.configs.single().command)
    }

    @Test
    fun eachConfiguredModeSelectsTheMatchingProvider() {
        val root = projectRoot()
        val providers = RecordingProviderFactory()
        val factory = LanguageSupportFactory(providerFactory = providers)

        val configs =
            listOf(
                SemanticProviderConfig(mode = ProviderMode.MANAGED, command = "managed-lsp"),
                SemanticProviderConfig(mode = ProviderMode.ATTACHED, endpoint = "unix:/tmp/cint-074.sock"),
                SemanticProviderConfig(mode = ProviderMode.EDITOR, hostChannelConfig = HostChannelConfig("test")),
            )
        for (config in configs) {
            factory
                .resolve("kotlin", LanguageOptions(providerConfig = config, rootPath = root.toString()))
                .successValue()
        }

        assertEquals(listOf(ProviderMode.MANAGED, ProviderMode.ATTACHED, ProviderMode.EDITOR), providers.modes)
        assertEquals(configs.map { it.mode }, providers.configs.map { it.mode })
        assertEquals("managed-lsp", providers.configs[0].command)
        assertEquals("unix:/tmp/cint-074.sock", providers.configs[1].endpoint)
    }

    @Test
    fun transportBackedManagedAndAttachedModesDispatchRequests() {
        val root = projectRoot()
        for (mode in listOf(ProviderMode.MANAGED, ProviderMode.ATTACHED)) {
            val transport = ScriptedTransport()
            val factory =
                LanguageSupportFactory(
                    transport,
                    rootLocator = MarkerRootLocator,
                    providerDefaults = kotlinDefaults,
                )
            val options =
                if (mode == ProviderMode.MANAGED) {
                    LanguageOptions()
                } else {
                    LanguageOptions(providerConfig = SemanticProviderConfig(mode = mode))
                }
            val support =
                factory
                    .resolveForPath(root.resolve("src/Main.kt").toString(), options)
                    .successValue()
            try {
                val provider = support.semanticProvider().successValue()
                provider.openDocument("file:///tmp/Main.kt", "val value = 1", 1).successValue()
                val result = provider.request(semanticRequest(SemanticRequestKind.HOVER_AT)).successValue()
                assertEquals("kotlin.Int", assertIs<SemanticResult.HoverAt>(result).content)
            } finally {
                support.shutdown().successValue()
            }
        }
    }

    @Test
    fun descriptorAndExtensionLookupIsDeterministic() {
        val first = TreeSitterLanguageRegistry.descriptors.map { it.id }
        val second = TreeSitterLanguageRegistry.descriptors.map { it.id }
        assertEquals(first, second)
        assertEquals(listOf("kotlin", "c", "javascript", "typescript", "vue", "csharp"), first)

        assertEquals("kotlin", TreeSitterLanguageRegistry.forExtension(".KT")?.id)
        assertEquals("kotlin", TreeSitterLanguageRegistry.forExtension("kts")?.id)
        assertEquals("javascript", TreeSitterLanguageRegistry.forPath("src/main.mjs")?.id)
        assertEquals("typescript", TreeSitterLanguageRegistry.forPath("src/App.tsx")?.id)
        assertEquals("csharp", TreeSitterLanguageRegistry.forLanguage("C#")?.id)
        assertEquals("kotlin", TreeSitterLanguageRegistry.forLanguage("kt")?.id)
        assertNull(TreeSitterLanguageRegistry.forLanguage("brainfuck"))
        assertNull(TreeSitterLanguageRegistry.forPath("Makefile"))
        assertTrue(!TreeSitterLanguageRegistry.descriptors.first { it.id == "javascript" }.matchesPath("Main.kt"))
    }

    @Test
    fun catalogRejectsDuplicateIdsAliasesAndExtensions() {
        val kotlin = assertNotNull(TreeSitterLanguageRegistry.forLanguage("kotlin"))
        val javascript = assertNotNull(TreeSitterLanguageRegistry.forLanguage("javascript"))

        assertFailsWith<IllegalArgumentException> {
            LanguageCatalog.of(listOf(kotlin, kotlin.copy(name = "Kotlin again")))
        }
        assertFailsWith<IllegalArgumentException> {
            LanguageCatalog.of(listOf(kotlin, javascript.copy(aliases = listOf("kt"))))
        }
        assertFailsWith<IllegalArgumentException> {
            LanguageCatalog.of(listOf(kotlin, javascript.copy(extensions = listOf("kt"))))
        }
    }

    @Test
    fun badLanguageHintDoesNotFallBackToExtension() {
        val root = projectRoot()
        val providers = RecordingProviderFactory()
        val factory = LanguageSupportFactory(providerFactory = providers)
        val input =
            SourceInput.FilePath(
                path = root.resolve("src/Main.kt").toString(),
                languageHint = "brainfuck",
            )

        val error = factory.resolveForInput(input, LanguageOptions(rootPath = root.toString())).requireFailure()

        assertIs<ContractError.NotFound>(error)
        assertEquals("brainfuck", error.identifier)
        assertTrue(providers.modes.isEmpty())
    }

    @Test
    fun missingGrammarFailsBeforeProviderResolution() {
        val root = projectRoot()
        val providers = RecordingProviderFactory()
        val kotlin = assertNotNull(TreeSitterLanguageRegistry.forLanguage("kotlin"))
        val missingGrammar = kotlin.copy(grammarName = "tree-sitter-missing")
        val factory =
            LanguageSupportFactory(
                catalog = LanguageCatalog.of(listOf(missingGrammar)),
                providerFactory = providers,
            )

        val error =
            factory
                .resolve("kotlin", LanguageOptions(rootPath = root.toString()))
                .requireFailure()

        assertIs<ContractError.DependencyUnavailable>(error)
        assertTrue(error.reason.contains("tree-sitter-missing"))
        assertTrue(providers.modes.isEmpty())
    }

    @Test
    fun unloadableSyntaxEngineFailsBeforeProviderResolution() {
        val root = projectRoot()
        val providers = RecordingProviderFactory()
        val factory =
            LanguageSupportFactory(
                syntaxFactory = {
                    ResultFactory.success(
                        object : SyntaxEngine {
                            override val language: String = "kotlin"

                            override fun parse(
                                content: ByteArray,
                                previous: SyntaxTree?,
                                cancellation: CancellationToken,
                            ): ContractResult<SyntaxTree> =
                                ResultFactory.failure(
                                    ContractError.DependencyUnavailable("tree-sitter-kotlin", "native library missing"),
                                )

                            override fun diff(
                                previous: SyntaxTree,
                                next: SyntaxTree,
                            ): ChangedRanges = ChangedRanges.WHOLE_FILE

                            override fun nodeHash(
                                tree: SyntaxTree,
                                nodeId: String,
                            ): ContractResult<String> = ResultFactory.failure(ContractError.InternalError("unusable"))

                            override fun facts(
                                tree: SyntaxTree,
                                queryIds: List<String>,
                                cancellation: CancellationToken,
                            ): ContractResult<FactBatch> = ResultFactory.failure(ContractError.InternalError("unusable"))
                        },
                    )
                },
                providerFactory = providers,
            )

        val error =
            factory
                .resolve("kotlin", LanguageOptions(rootPath = root.toString()))
                .requireFailure()

        assertIs<ContractError.DependencyUnavailable>(error)
        assertTrue(error.reason.contains("native library missing"))
        assertTrue(providers.modes.isEmpty())
    }

    @Test
    fun editorModeWiresTheHostChannelThroughTheLspProvider() {
        val root = projectRoot()
        val channel =
            object : HostSemanticChannel {
                override fun send(request: SemanticRequest): ContractResult<SemanticResult> =
                    ResultFactory.success(SemanticResult.HoverAt("editor-owned"))

                override fun close(): ContractResult<Unit> = ResultFactory.success(Unit)
            }
        val factory = LanguageSupportFactory()
        val support =
            factory
                .resolve(
                    "kotlin",
                    LanguageOptions(
                        providerConfig = SemanticProviderConfig(mode = ProviderMode.EDITOR),
                        rootPath = root.toString(),
                        hostChannel = channel,
                    ),
                ).successValue()
        val provider = support.semanticProvider().successValue()

        assertEquals("kotlin", provider.language)
        val hover = provider.request(semanticRequest(SemanticRequestKind.HOVER_AT)).successValue()
        assertEquals("editor-owned", assertIs<SemanticResult.HoverAt>(hover).content)
        support.shutdown().successValue()
    }

    @Test
    fun rootlessInMemoryEditorSourceResolves() {
        val providers = RecordingProviderFactory()
        val factory = LanguageSupportFactory(providerFactory = providers)

        val support =
            factory
                .resolveForInput(
                    SourceInput.InMemory(text = "fun main() = Unit", language = "kotlin"),
                    LanguageOptions(
                        providerConfig =
                            SemanticProviderConfig(
                                mode = ProviderMode.EDITOR,
                                hostChannelConfig = HostChannelConfig("test"),
                            ),
                    ),
                ).successValue()

        assertEquals(ProviderMode.EDITOR, providers.modes.single())
        assertNull(providers.configs.single().rootPath)
        support.shutdown().successValue()
    }

    @Test
    fun missingCommandOrChannelFailsBeforeProviderFactory() {
        val root = projectRoot()
        val providers = RecordingProviderFactory()
        val factory = LanguageSupportFactory(providerFactory = providers)

        val managed =
            factory
                .resolve(
                    "kotlin",
                    LanguageOptions(
                        providerConfig = SemanticProviderConfig(mode = ProviderMode.MANAGED),
                        rootPath = root.toString(),
                    ),
                ).requireFailure()
        assertIs<ContractError.PreconditionFailed>(managed)
        assertTrue(managed.message.contains("command"))

        val editor =
            factory
                .resolve(
                    "kotlin",
                    LanguageOptions(
                        providerConfig = SemanticProviderConfig(mode = ProviderMode.EDITOR),
                        rootPath = root.toString(),
                    ),
                ).requireFailure()
        assertIs<ContractError.PreconditionFailed>(editor)
        assertTrue(editor.message.contains("HostSemanticChannel"))
        assertTrue(providers.modes.isEmpty())
    }

    @Test
    fun missingProviderFailsBeforeSupportIsReturned() {
        val root = projectRoot()
        val factory = LanguageSupportFactory()

        val attached =
            factory
                .resolve(
                    "kotlin",
                    LanguageOptions(
                        providerConfig = SemanticProviderConfig(mode = ProviderMode.ATTACHED),
                        rootPath = root.toString(),
                    ),
                ).requireFailure()
        assertIs<ContractError.PreconditionFailed>(attached)

        val started =
            factory
                .resolve(
                    "kotlin",
                    LanguageOptions(
                        providerConfig =
                            SemanticProviderConfig(
                                mode = ProviderMode.MANAGED,
                                command = "/definitely/missing/cint-lsp-binary",
                                timeoutMs = 100,
                                initTimeoutMs = 100,
                            ),
                        rootPath = root.toString(),
                    ),
                ).requireFailure()
        assertIs<ContractError.DependencyUnavailable>(started)
    }

    @Test
    fun missingRequiredRootFailsBeforeProviderResolution() {
        val root = Files.createTempDirectory("cint-074-rootless")
        Files.createDirectories(root.resolve("src"))
        Files.createFile(root.resolve("src/Main.kt"))
        val providers = RecordingProviderFactory()
        val factory = LanguageSupportFactory(providerFactory = providers)

        val error =
            factory
                .resolveForPath(root.resolve("src/Main.kt").toString(), LanguageOptions(requireRoot = true))
                .requireFailure()

        assertIs<ContractError.DependencyUnavailable>(error)
        assertTrue(error.reason.contains("root marker"))
        assertTrue(providers.modes.isEmpty())
    }

    @Test
    fun discoversRootFromLanguageMarkers() {
        val root = projectRoot()
        val file = root.resolve("src/Main.kt")
        val providers = RecordingProviderFactory()
        val factory =
            LanguageSupportFactory(
                providerFactory = providers,
                rootLocator = MarkerRootLocator,
                providerDefaults = kotlinDefaults,
            )

        factory.resolveForPath(file.toString()).successValue()

        assertEquals(root.toRealPath().toString(), Path.of(providers.configs.single().rootPath!!).toRealPath().toString())
    }

    @Test
    fun configuredRootLocatorDiscoversCsharpRootAndResolvesEditorProvider() {
        val root = Files.createTempDirectory("cint-074-csharp-default")
        temporaryRoots.add(root)
        Files.writeString(root.resolve("App.sln"), "")
        val nested = Files.createDirectories(root.resolve("nested"))
        val file = Files.createFile(nested.resolve("Main.cs"))
        val channel =
            object : HostSemanticChannel {
                override fun send(request: SemanticRequest): ContractResult<SemanticResult> =
                    ResultFactory.success(SemanticResult.HoverAt("csharp-editor"))

                override fun close(): ContractResult<Unit> = ResultFactory.success(Unit)
            }

        val support =
            LanguageSupportFactory(rootLocator = MarkerRootLocator)
                .resolveForPath(file.toString(), LanguageOptions(hostChannel = channel))
                .successValue()
        try {
            assertEquals("csharp", support.descriptor().successValue().id)
            val provider = support.semanticProvider().successValue()
            assertEquals("csharp", provider.language)
            val hover = provider.request(semanticRequest(SemanticRequestKind.HOVER_AT))
            assertEquals("csharp-editor", assertIs<SemanticResult.HoverAt>(hover.successValue()).content)
        } finally {
            support.shutdown().successValue()
        }
    }

    @Test
    fun csharpRootMarkersPreferSolutionAndTreatGitAsFallback() {
        val parent = Files.createTempDirectory("cint-074-csharp-parent")
        temporaryRoots.add(parent)
        Files.writeString(parent.resolve("App.sln"), "")
        val nested = Files.createDirectories(parent.resolve("nested"))
        Files.writeString(nested.resolve("Nested.csproj"), "")
        Files.createDirectories(nested.resolve(".git"))
        val file = Files.createFile(nested.resolve("Main.cs"))
        val markers = assertNotNull(TreeSitterLanguageRegistry.forLanguage("csharp")).rootMarkers

        assertEquals(parent.toString(), MarkerRootLocator.locate(file.toString(), markers))

        Files.delete(parent.resolve("App.sln"))
        assertEquals(nested.toString(), MarkerRootLocator.locate(file.toString(), markers))

        Files.delete(nested.resolve("Nested.csproj"))
        assertEquals(nested.toString(), MarkerRootLocator.locate(file.toString(), markers))
    }

    private fun projectRoot(): Path {
        val root = Files.createTempDirectory("cint-074-kotlin")
        temporaryRoots.add(root)
        Files.writeString(root.resolve("settings.gradle.kts"), "")
        Files.createDirectories(root.resolve("src"))
        Files.createFile(root.resolve("src/Main.kt"))
        return root
    }

    private fun semanticRequest(kind: SemanticRequestKind): SemanticRequest =
        SemanticRequest(
            uri = "file:///tmp/Main.kt",
            version = 1,
            offset = 0,
            kind = kind,
            deadline = Deadline.fromNowMs(2000),
        )

    private class RecordingProviderFactory : LanguageProviderFactory {
        val modes = mutableListOf<ProviderMode>()
        val configs = mutableListOf<SemanticProviderConfig>()
        var shutdownCount = 0

        override fun create(
            descriptor: LanguageDescriptor,
            config: SemanticProviderConfig,
            hostChannel: HostSemanticChannel?,
            hostChannelProvider: HostSemanticChannelProvider?,
        ): ContractResult<SemanticProvider> {
            modes += config.mode
            configs += config
            return ResultFactory.success(RecordingProvider(descriptor.id) { shutdownCount++ })
        }
    }

    private class RecordingProvider(
        override val language: String,
        private val onShutdown: () -> Unit,
    ) : SemanticProvider {
        override fun request(request: SemanticRequest): ContractResult<SemanticResult> =
            ResultFactory.success(SemanticResult.DiagnosticsFor(emptyList()))

        override fun shutdown(): ContractResult<Unit> {
            onShutdown()
            return ResultFactory.success(Unit)
        }
    }

    private class ScriptedTransport : LspTransport {
        private val clientToServer = PipedOutputStream()
        private val serverInput = PipedInputStream(clientToServer, 64 * 1024)
        private val serverToClient = PipedOutputStream()
        private val clientInput = PipedInputStream(serverToClient, 64 * 1024)
        private val thread = Thread({ serve() }, "cint-074-scripted-lsp").apply { isDaemon = true }

        override val input: InputStream = clientInput
        override val output: java.io.OutputStream = clientToServer
        override val description: String = "scripted-074"

        init {
            thread.start()
        }

        override fun close() {
            runCatching { clientInput.close() }
            runCatching { clientToServer.close() }
            runCatching { serverInput.close() }
            runCatching { serverToClient.close() }
            thread.join(500)
        }

        private fun serve() {
            try {
                while (true) {
                    val body = readMessage(serverInput) ?: break
                    val text = body.decodeToString()
                    val method = Regex("\"method\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: continue
                    val id = Regex("\"id\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)
                    if (id == null) {
                        if (method == "exit") break
                        continue
                    }
                    val result =
                        when (method) {
                            "initialize" -> """{"capabilities":{"hoverProvider":true}}"""
                            "textDocument/hover" -> """{"contents":{"kind":"markdown","value":"kotlin.Int"}}"""
                            else -> "null"
                        }
                    writeMessage("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
                }
            } catch (_: Exception) {
            }
        }

        private fun writeMessage(text: String) {
            val body = text.encodeToByteArray()
            serverToClient.write("Content-Length: ${body.size}\r\n\r\n".encodeToByteArray())
            serverToClient.write(body)
            serverToClient.flush()
        }

        private fun readMessage(input: InputStream): ByteArray? {
            var contentLength = -1
            while (true) {
                val line = readLine(input) ?: return null
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(':').trim().toInt()
                }
            }
            if (contentLength < 0) return null
            return input.readNBytes(contentLength).takeIf { it.size == contentLength }
        }

        private fun readLine(input: InputStream): String? {
            val line = StringBuilder()
            while (true) {
                val byte = input.read()
                if (byte < 0) return line.toString().takeIf { it.isNotEmpty() }
                if (byte == '\r'.code) {
                    if (input.read() == '\n'.code) return line.toString()
                } else {
                    line.append(byte.toChar())
                }
            }
        }
    }

    private fun <T> ContractResult<T>.successValue(): T {
        assertTrue(isSuccess, "expected success but was $this")
        @Suppress("UNCHECKED_CAST")
        return getValue() as T
    }

    private fun ContractResult<*>.requireFailure(): ContractError {
        assertTrue(isFailure, "expected failure but was $this")
        return assertNotNull(errorOrNull)
    }
}

private val kotlinDefaults =
    LanguageProviderDefaults { descriptor ->
        SemanticProviderConfig(
            mode = descriptor.providerMode,
            command = if (descriptor.id == "kotlin") "kotlin-lsp --stdio" else null,
        )
    }

private object MarkerRootLocator : LanguageRootLocator {
    private const val GIT_MARKER = ".git"

    override fun locate(
        path: String?,
        markers: List<String>,
    ): String? {
        val file = path?.let { runCatching { Path.of(it).toAbsolutePath().normalize() }.getOrNull() } ?: return null
        val start = if (Files.isDirectory(file)) file else file.parent ?: return null
        val languageMarkers = markers.filterNot { it == GIT_MARKER }
        for (marker in languageMarkers) {
            var directory: Path? = start
            while (directory != null) {
                if (hasMarker(directory, marker)) return directory.toString()
                directory = directory.parent
            }
        }
        if (GIT_MARKER in markers) {
            var directory: Path? = start
            while (directory != null) {
                if (hasMarker(directory, GIT_MARKER)) return directory.toString()
                directory = directory.parent
            }
        }
        return null
    }

    private fun hasMarker(
        directory: Path,
        marker: String,
    ): Boolean =
        if (marker.contains('*') || marker.contains('?')) {
            runCatching {
                Files.newDirectoryStream(directory, marker).use { it.iterator().hasNext() }
            }.getOrDefault(false)
        } else {
            Files.exists(directory.resolve(marker))
        }
}
