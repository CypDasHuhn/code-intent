package dev.cint.v2.lsp

import dev.cint.contracts.facts.Diagnostic
import dev.cint.contracts.facts.OffsetUnit
import dev.cint.contracts.facts.SemanticRequest
import dev.cint.contracts.facts.SemanticRequestKind
import dev.cint.contracts.facts.SemanticResult
import dev.cint.contracts.ports.HostChannelConfig
import dev.cint.contracts.ports.HostSemanticChannel
import dev.cint.contracts.ports.HostSemanticChannelProvider
import dev.cint.contracts.ports.ProviderMode
import dev.cint.contracts.ports.SemanticProviderConfig
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.Deadline
import dev.cint.contracts.util.ResultFactory
import dev.cint.v2.testing.lsp.ScriptedLspServer
import dev.cint.v2.testing.lsp.ScriptedLspServerMain
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LspSemanticProviderTest {
    @Test
    fun managedModeRunsScriptedServerOverStdio() {
        val config = SemanticProviderConfig(mode = ProviderMode.MANAGED, command = javaCommand(), timeoutMs = 3000)
        val provider = provider(config)
        try {
            val uri = "file:///tmp/Managed.kt"
            provider.openDocument(uri, "fun holder() {\n    val value = 1\n}", version = 3).successValue()

            val type = provider.request(request(SemanticRequestKind.TYPE_AT, uri, version = 3)).successValue()
            assertIs<SemanticResult.TypeAt>(type)
            assertEquals("kotlin.Int", type.typeRef?.fqName)
            assertEquals("lsp", type.typeRef?.source)

            val hover = provider.request(request(SemanticRequestKind.HOVER_AT, uri, version = 3)).successValue()
            assertIs<SemanticResult.HoverAt>(hover)
            assertTrue(hover.content!!.contains("kotlin.Int"))

            val definition = provider.request(request(SemanticRequestKind.DEFINITION_OF, uri, version = 3)).successValue()
            assertIs<SemanticResult.DefinitionOf>(definition)
            assertEquals(1, definition.locations.size)

            val references = provider.request(request(SemanticRequestKind.REFERENCES_AT, uri, version = 3)).successValue()
            assertIs<SemanticResult.ReferencesAt>(references)
            assertEquals(2, references.locations.size)

            val symbol = provider.request(request(SemanticRequestKind.SYMBOL_AT, uri, offset = 4, version = 3)).successValue()
            assertIs<SemanticResult.SymbolAt>(symbol)
            assertEquals("value", symbol.symbol?.name)
            assertEquals("holder", symbol.symbol?.containerName)

            val diagnostics = provider.request(request(SemanticRequestKind.DIAGNOSTICS_FOR, uri, version = 3)).successValue()
            assertIs<SemanticResult.DiagnosticsFor>(diagnostics)
            assertEquals(1, diagnostics.diagnostics.size)
            assertEquals("scripted warning", diagnostics.diagnostics.single().message)
        } finally {
            provider.shutdown().successValue()
            provider.shutdown().successValue()
        }
    }

    @Test
    fun managedModeCanSpawnProcessAndAttachToEndpoint() {
        val directory = Files.createTempDirectory("cint-lsp-managed-endpoint")
        val socket = directory.resolve("lsp.sock")
        val endpoint = "unix:$socket"
        val config =
            SemanticProviderConfig(
                mode = ProviderMode.MANAGED,
                command = javaCommandFor(ManagedEndpointChildMain::class.java.name, "--endpoint=$endpoint"),
                endpoint = endpoint,
                timeoutMs = 5000,
                initTimeoutMs = 10000,
            )
        val provider = provider(config)
        try {
            val uri = "file:///tmp/ManagedEndpoint.kt"
            provider.openDocument(uri, "val value = 1").successValue()
            val hover = provider.request(request(SemanticRequestKind.HOVER_AT, uri)).successValue()
            assertIs<SemanticResult.HoverAt>(hover)
            assertTrue(hover.content!!.contains("kotlin.Int"))
            provider.shutdown().successValue()
        } finally {
            provider.shutdown().successValue()
            runCatching { Files.deleteIfExists(socket) }
            runCatching { Files.deleteIfExists(directory) }
        }
    }

    @Test
    fun attachedModeConnectsOverUnixSocket() {
        startUnixServer().use { server ->
            val config =
                SemanticProviderConfig(
                    mode = ProviderMode.ATTACHED,
                    endpoint = server.endpoint,
                    timeoutMs = 3000,
                )
            val provider = provider(config)
            try {
                val uri = "file:///tmp/Attached.kt"
                provider.openDocument(uri, "val value = 1").successValue()
                val type = provider.request(request(SemanticRequestKind.TYPE_AT, uri)).successValue()
                assertIs<SemanticResult.TypeAt>(type)
                assertEquals("kotlin.Int", type.typeRef?.fqName)
                provider.shutdown().successValue()
                provider.shutdown().successValue()
                assertTrue(server.fake().shutdownSeen)
            } finally {
                provider.shutdown().successValue()
            }
        }
    }

    @Test
    fun attachedModeConnectsOverTcp() {
        startTcpServer().use { server ->
            val config =
                SemanticProviderConfig(
                    mode = ProviderMode.ATTACHED,
                    endpoint = server.endpoint,
                    timeoutMs = 3000,
                )
            val provider = provider(config)
            try {
                val uri = "file:///tmp/Tcp.kt"
                provider.openDocument(uri, "val value = 1").successValue()
                val hover = provider.request(request(SemanticRequestKind.HOVER_AT, uri)).successValue()
                assertIs<SemanticResult.HoverAt>(hover)
                assertTrue(hover.content!!.contains("kotlin.Int"))
            } finally {
                provider.shutdown().successValue()
            }
        }
    }

    @Test
    fun editorModeDoesNotCloseCallerOwnedHostChannel() {
        val expected = SemanticResult.HoverAt("host hover")
        val channel = FakeHostChannel(expected)
        val config =
            SemanticProviderConfig(
                mode = ProviderMode.EDITOR,
                hostChannelConfig = HostChannelConfig("test"),
            )
        val provider = LspSemanticProvider.create(config, "kotlin", hostChannel = channel).successValue()
        val response = provider.request(request(SemanticRequestKind.HOVER_AT, "untitled:test")).successValue()
        assertEquals(expected, response)
        assertEquals(1, channel.requests.size)
        provider.openDocument("untitled:test", "val value = 1", 1).successValue()
        assertEquals(1, channel.openCount)
        provider.shutdown().successValue()
        provider.shutdown().successValue()
        assertEquals(0, channel.closeCount)
    }

    @Test
    fun editorModeResolvesHostChannelProviderFromConfig() {
        val channel = FakeHostChannel(SemanticResult.HoverAt("config channel"))
        var seenConfig: HostChannelConfig? = null
        val channelProvider =
            object : HostSemanticChannelProvider {
                override fun open(config: HostChannelConfig): ContractResult<HostSemanticChannel> {
                    seenConfig = config
                    return ResultFactory.success(channel)
                }
            }
        val config =
            SemanticProviderConfig(
                mode = ProviderMode.EDITOR,
                hostChannelConfig = HostChannelConfig("ipc", mapOf("socket" to "/tmp/cint.sock")),
            )
        val provider =
            LspSemanticProvider
                .create(config, "kotlin", hostChannelProvider = channelProvider)
                .successValue()
        try {
            val response = provider.request(request(SemanticRequestKind.HOVER_AT, "untitled:test")).successValue()
            assertIs<SemanticResult.HoverAt>(response)
            assertEquals("ipc", seenConfig?.type)
        } finally {
            provider.shutdown().successValue()
        }
        assertEquals(1, channel.closeCount)
    }

    @Test
    fun editorModeEnforcesDeadline() {
        val channel = FakeHostChannel(SemanticResult.HoverAt("slow"), delayMs = 300)
        val config = SemanticProviderConfig(mode = ProviderMode.EDITOR, timeoutMs = 50)
        val provider = LspSemanticProvider.create(config, "kotlin", hostChannel = channel).successValue()
        try {
            val error = provider.request(request(SemanticRequestKind.HOVER_AT, "untitled:test")).failureValue()
            assertIs<ContractError.Timeout>(error)
            assertEquals("textDocument/hover", error.operation)
        } finally {
            provider.shutdown().successValue()
        }
    }

    @Test
    fun editorShutdownPropagatesCloseFailureForProviderOpenedChannelOnce() {
        val channel =
            FakeHostChannel(
                SemanticResult.HoverAt("unused"),
                closeError = ContractError.InternalError("close failed"),
            )
        val channelProvider =
            object : HostSemanticChannelProvider {
                override fun open(config: HostChannelConfig): ContractResult<HostSemanticChannel> = ResultFactory.success(channel)
            }
        val provider =
            LspSemanticProvider
                .create(
                    SemanticProviderConfig(
                        mode = ProviderMode.EDITOR,
                        hostChannelConfig = HostChannelConfig("test"),
                    ),
                    "kotlin",
                    hostChannelProvider = channelProvider,
                ).successValue()
        val error = provider.shutdown().failureValue()
        assertIs<ContractError.InternalError>(error)
        assertEquals("close failed", error.message)
        provider.shutdown().successValue()
        assertEquals(1, channel.closeCount)
    }

    @Test
    fun editorModeWithoutChannelFails() {
        val error =
            LspSemanticProvider
                .create(SemanticProviderConfig(mode = ProviderMode.EDITOR), "kotlin")
                .failureValue()
        assertIs<ContractError.PreconditionFailed>(error)
    }

    @Test
    fun languageIsRequiredAndOverridesDriveDocumentSync() {
        val missing =
            LspSemanticProvider
                .create(SemanticProviderConfig(mode = ProviderMode.ATTACHED), "")
                .failureValue()
        assertIs<ContractError.PreconditionFailed>(missing)
        startUnixServer().use { server ->
            val config =
                SemanticProviderConfig(
                    mode = ProviderMode.ATTACHED,
                    endpoint = server.endpoint,
                    languageOverrides = mapOf("kotlin" to "kotlin-override"),
                )
            val provider = provider(config)
            try {
                provider.openDocument("file:///tmp/Language.kt", "val value = 1", 1).successValue()
                waitUntil { server.fake().lastOpenLanguageId != null }
                assertEquals("kotlin-override", server.fake().lastOpenLanguageId)
            } finally {
                provider.shutdown().successValue()
            }
        }
    }

    @Test
    fun rootPathIsPassedToInitialize() {
        val root = Files.createTempDirectory("cint-lsp-root")
        try {
            startUnixServer().use { server ->
                val config =
                    SemanticProviderConfig(
                        mode = ProviderMode.ATTACHED,
                        endpoint = server.endpoint,
                        rootPath = root.toString(),
                    )
                val provider = provider(config)
                try {
                    val initialize = server.fake().requests.first { it["method"] == "initialize" }
                    val rootUri = initialize.asObjectOrNull()?.objectOrNull("params")?.string("rootUri")
                    assertEquals(
                        root
                            .toAbsolutePath()
                            .normalize()
                            .toUri()
                            .toString(),
                        rootUri,
                    )
                } finally {
                    provider.shutdown().successValue()
                }
            }
        } finally {
            Files.deleteIfExists(root)
        }
    }

    @Test
    fun managedStartupFailureIsReported() {
        val config =
            SemanticProviderConfig(
                mode = ProviderMode.MANAGED,
                command = "/definitely/missing/cint-fake-lsp --stdio",
            )
        val error = LspSemanticProvider.create(config, "kotlin").failureValue()
        assertIs<ContractError.DependencyUnavailable>(error)
        assertTrue(error.reason.contains("Failed to start managed LSP command"))
    }

    @Test
    fun attachedServerDisconnectBeforeInitializeIsReported() {
        startUnixServer(ScriptedLspServer.Config(closeBeforeInitialize = true)).use { server ->
            val config = SemanticProviderConfig(mode = ProviderMode.ATTACHED, endpoint = server.endpoint)
            val error = LspSemanticProvider.create(config, "kotlin").failureValue()
            assertIs<ContractError.DependencyUnavailable>(error)
            assertTrue(error.reason.contains("disconnected") || error.reason.contains("crash"))
        }
    }

    @Test
    fun initializeTimeoutIsReportedAndTransportClosed() {
        startUnixServer(ScriptedLspServer.Config(initDelayMs = 400)).use { server ->
            val config =
                SemanticProviderConfig(
                    mode = ProviderMode.ATTACHED,
                    endpoint = server.endpoint,
                    initTimeoutMs = 100,
                )
            val error = LspSemanticProvider.create(config, "kotlin").failureValue()
            assertIs<ContractError.Timeout>(error)
            assertEquals("initialize", error.operation)
            assertEquals(100L, error.timeoutMs)
        }
    }

    @Test
    fun requestTimeoutIsReported() {
        startUnixServer(ScriptedLspServer.Config(hoverDelayMs = 400)).use { server ->
            val config =
                SemanticProviderConfig(
                    mode = ProviderMode.ATTACHED,
                    endpoint = server.endpoint,
                    timeoutMs = 100,
                )
            val provider = provider(config)
            try {
                val uri = "file:///tmp/Timeout.kt"
                provider.openDocument(uri, "val value = 1").successValue()
                val error = provider.request(request(SemanticRequestKind.HOVER_AT, uri)).failureValue()
                assertIs<ContractError.Timeout>(error)
                assertEquals("textDocument/hover", error.operation)
                assertTrue(error.timeoutMs in 1..100)
            } finally {
                provider.shutdown().successValue()
            }
        }
    }

    @Test
    fun expiredDeadlineIsReportedBeforeSending() {
        startUnixServer().use { server ->
            val provider =
                provider(SemanticProviderConfig(mode = ProviderMode.ATTACHED, endpoint = server.endpoint))
            try {
                val uri = "file:///tmp/Expired.kt"
                provider.openDocument(uri, "val value = 1").successValue()
                val request =
                    request(SemanticRequestKind.HOVER_AT, uri)
                        .copy(deadline = Deadline.fromMillis(0))
                val error = provider.request(request).failureValue()
                assertIs<ContractError.Timeout>(error)
                assertEquals(0L, error.timeoutMs)
            } finally {
                provider.shutdown().successValue()
            }
        }
    }

    @Test
    fun crashDuringRequestIsReported() {
        startUnixServer(ScriptedLspServer.Config(crashOnHover = true)).use { server ->
            val provider =
                provider(
                    SemanticProviderConfig(mode = ProviderMode.ATTACHED, endpoint = server.endpoint, timeoutMs = 3000),
                )
            try {
                val uri = "file:///tmp/Crash.kt"
                provider.openDocument(uri, "val value = 1").successValue()
                val error = provider.request(request(SemanticRequestKind.HOVER_AT, uri)).failureValue()
                assertIs<ContractError.DependencyUnavailable>(error)
                assertTrue(error.reason.contains("disconnected") || error.reason.contains("crash"))
                assertTrue(error.reason.contains("textDocument/hover"))
                val second = provider.request(request(SemanticRequestKind.HOVER_AT, uri)).failureValue()
                assertIs<ContractError.DependencyUnavailable>(second)
            } finally {
                provider.shutdown().successValue()
            }
        }
    }

    @Test
    fun malformedResponseIsReported() {
        startUnixServer(ScriptedLspServer.Config(malformedOnHover = true)).use { server ->
            val provider =
                provider(
                    SemanticProviderConfig(mode = ProviderMode.ATTACHED, endpoint = server.endpoint, timeoutMs = 3000),
                )
            try {
                val uri = "file:///tmp/Malformed.kt"
                provider.openDocument(uri, "val value = 1").successValue()
                val error = provider.request(request(SemanticRequestKind.HOVER_AT, uri)).failureValue()
                assertIs<ContractError.SerializationError>(error)
                assertTrue(error.message.contains("Malformed LSP JSON"))
            } finally {
                provider.shutdown().successValue()
            }
        }
    }

    @Test
    fun serverErrorsMapToContractShapes() {
        startUnixServer(ScriptedLspServer.Config(errorOnHover = true, hoverErrorCode = -32601)).use { server ->
            val provider =
                provider(SemanticProviderConfig(mode = ProviderMode.ATTACHED, endpoint = server.endpoint))
            try {
                val uri = "file:///tmp/MethodMissing.kt"
                provider.openDocument(uri, "val value = 1").successValue()
                val error = provider.request(request(SemanticRequestKind.HOVER_AT, uri)).failureValue()
                assertIs<ContractError.NotFound>(error)
                assertEquals("lsp-method", error.resource)
                assertEquals("textDocument/hover", error.identifier)
            } finally {
                provider.shutdown().successValue()
            }
        }
        startUnixServer(ScriptedLspServer.Config(errorOnHover = true, hoverErrorCode = -32800)).use { server ->
            val provider =
                provider(SemanticProviderConfig(mode = ProviderMode.ATTACHED, endpoint = server.endpoint))
            try {
                val uri = "file:///tmp/Cancelled.kt"
                provider.openDocument(uri, "val value = 1").successValue()
                val error = provider.request(request(SemanticRequestKind.HOVER_AT, uri)).failureValue()
                assertIs<ContractError.Cancelled>(error)
                assertEquals("textDocument/hover", error.operation)
            } finally {
                provider.shutdown().successValue()
            }
        }
    }

    @Test
    fun serverErrorWithoutKnownCodeIsInternalError() {
        startUnixServer(ScriptedLspServer.Config(errorOnHover = true, hoverErrorCode = -32000)).use { server ->
            val provider =
                provider(SemanticProviderConfig(mode = ProviderMode.ATTACHED, endpoint = server.endpoint))
            try {
                val uri = "file:///tmp/ServerError.kt"
                provider.openDocument(uri, "val value = 1").successValue()
                val error = provider.request(request(SemanticRequestKind.HOVER_AT, uri)).failureValue()
                assertIs<ContractError.InternalError>(error)
                assertTrue(error.message.contains("hover failed"))
            } finally {
                provider.shutdown().successValue()
            }
        }
    }

    @Test
    fun didChangeIsSentForUpdatedDocumentsAndDidCloseIsSent() {
        startUnixServer().use { server ->
            val provider =
                provider(SemanticProviderConfig(mode = ProviderMode.ATTACHED, endpoint = server.endpoint))
            try {
                val uri = "file:///tmp/Change.kt"
                provider.openDocument(uri, "val value = 1", version = 1).successValue()
                provider.changeDocument(uri, "val value = 2", version = 2).successValue()
                provider.request(request(SemanticRequestKind.HOVER_AT, uri, version = 2)).successValue()
                assertTrue(server.fake().receivedDidOpen)
                assertTrue(server.fake().receivedDidChange)
                provider.closeDocument(uri).successValue()
                waitUntil { server.fake().receivedDidClose }
                val error = provider.request(request(SemanticRequestKind.HOVER_AT, uri, version = 2)).failureValue()
                assertIs<ContractError.InvalidInput>(error)
            } finally {
                provider.shutdown().successValue()
            }
        }
    }

    @Test
    fun incrementalSyncUsesRangeChanges() {
        startUnixServer(ScriptedLspServer.Config(syncKind = 2)).use { server ->
            val provider =
                provider(SemanticProviderConfig(mode = ProviderMode.ATTACHED, endpoint = server.endpoint))
            try {
                val uri = "file:///tmp/Incremental.kt"
                provider.openDocument(uri, "val value = 1", version = 1).successValue()
                provider.changeDocument(uri, "val value = 22", version = 2).successValue()
                waitUntil { server.fake().receivedDidChange }
                assertTrue(server.fake().lastChangeHadRange)
                assertEquals("val value = 22", server.fake().lastDocumentText)
            } finally {
                provider.shutdown().successValue()
            }
        }
    }

    @Test
    fun versionMismatchFailsInsteadOfUsingStaleText() {
        startUnixServer().use { server ->
            val provider =
                provider(SemanticProviderConfig(mode = ProviderMode.ATTACHED, endpoint = server.endpoint))
            try {
                val uri = "file:///tmp/Version.kt"
                provider.openDocument(uri, "val value = 1", version = 1).successValue()
                val error = provider.request(request(SemanticRequestKind.HOVER_AT, uri, version = 2)).failureValue()
                assertIs<ContractError.InvalidInput>(error)
                assertTrue(error.message.contains("version mismatch"))
            } finally {
                provider.shutdown().successValue()
            }
        }
    }

    @Test
    fun closedDocumentIsNotReadFromDisk() {
        val file = Files.createTempFile("cint-lsp", ".kt")
        Files.writeString(file, "val value = 1")
        try {
            startUnixServer().use { server ->
                val provider =
                    provider(SemanticProviderConfig(mode = ProviderMode.ATTACHED, endpoint = server.endpoint))
                try {
                    val error =
                        provider
                            .request(request(SemanticRequestKind.HOVER_AT, file.toUri().toString()))
                            .failureValue()
                    assertIs<ContractError.InvalidInput>(error)
                    assertTrue(error.message.contains("not open"))
                } finally {
                    provider.shutdown().successValue()
                }
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun diagnosticsAreClearedWhenDocumentChanges() {
        val serverConfig = ScriptedLspServer.Config(advertiseDiagnostics = false)
        startUnixServer(serverConfig).use { server ->
            val provider =
                provider(SemanticProviderConfig(mode = ProviderMode.ATTACHED, endpoint = server.endpoint))
            try {
                val uri = "file:///tmp/StaleDiagnostics.kt"
                provider.openDocument(uri, "val value = 1", version = 1).successValue()
                waitUntil { server.fake().publishedCount > 0 }
                var first: SemanticResult.DiagnosticsFor? = null
                waitUntil {
                    val response = provider.request(request(SemanticRequestKind.DIAGNOSTICS_FOR, uri))
                    val diagnostics = response.getValue() as? SemanticResult.DiagnosticsFor
                    if (response.isSuccess && diagnostics?.diagnostics?.size == 1) {
                        first = diagnostics
                        true
                    } else {
                        false
                    }
                }
                assertNotNull(first)
                serverConfig.publishDiagnosticsOnOpen = false
                provider.changeDocument(uri, "val value = 2", version = 2).successValue()
                val second =
                    provider
                        .request(request(SemanticRequestKind.DIAGNOSTICS_FOR, uri, version = 2))
                        .successValue()
                assertIs<SemanticResult.DiagnosticsFor>(second)
                assertTrue(second.diagnostics.isEmpty())
            } finally {
                provider.shutdown().successValue()
            }
        }
    }

    @Test
    fun pullDiagnosticsHandlesUnchangedReports() {
        startUnixServer(
            ScriptedLspServer.Config(
                publishDiagnosticsOnOpen = false,
                unchangedAfterFirstPull = true,
            ),
        ).use { server ->
            val provider =
                provider(SemanticProviderConfig(mode = ProviderMode.ATTACHED, endpoint = server.endpoint))
            try {
                val uri = "file:///tmp/PullDiagnostics.kt"
                provider.openDocument(uri, "val value = 1", version = 1).successValue()
                val first = provider.request(request(SemanticRequestKind.DIAGNOSTICS_FOR, uri)).successValue()
                assertIs<SemanticResult.DiagnosticsFor>(first)
                assertEquals(1, first.diagnostics.size)
                val second = provider.request(request(SemanticRequestKind.DIAGNOSTICS_FOR, uri)).successValue()
                assertIs<SemanticResult.DiagnosticsFor>(second)
                assertEquals(1, second.diagnostics.size)
                assertEquals("scripted warning", second.diagnostics.single().message)
                assertEquals(2, server.fake().pullCount)
            } finally {
                provider.shutdown().successValue()
            }
        }
    }

    @Test
    fun concurrentRequestsRemainCorrelated() {
        startUnixServer().use { server ->
            val provider =
                provider(SemanticProviderConfig(mode = ProviderMode.ATTACHED, endpoint = server.endpoint, timeoutMs = 3000))
            val executor = Executors.newFixedThreadPool(8)
            try {
                val uri = "file:///tmp/Concurrent.kt"
                provider.openDocument(uri, "val value = 1").successValue()
                val futures =
                    (1..16).map {
                        CompletableFuture.supplyAsync(
                            { provider.request(request(SemanticRequestKind.HOVER_AT, uri)) },
                            executor,
                        )
                    }
                for (future in futures) {
                    val result = future.get(5, TimeUnit.SECONDS).successValue()
                    assertIs<SemanticResult.HoverAt>(result)
                    assertTrue(result.content!!.contains("kotlin.Int"))
                }
            } finally {
                executor.shutdownNow()
                provider.shutdown().successValue()
            }
        }
    }

    @Test
    fun utf16OffsetsAreHonored() {
        startUnixServer().use { server ->
            val provider =
                provider(SemanticProviderConfig(mode = ProviderMode.ATTACHED, endpoint = server.endpoint))
            try {
                val uri = "file:///tmp/Utf16.kt"
                provider.openDocument(uri, "a😀b", version = 1).successValue()
                val response =
                    provider
                        .request(
                            request(
                                SemanticRequestKind.HOVER_AT,
                                uri,
                                offset = 3,
                                offsetUnit = OffsetUnit.UTF16_CODE_UNIT,
                            ),
                        ).successValue()
                assertIs<SemanticResult.HoverAt>(response)
                assertEquals(0, server.fake().lastHoverLine)
                assertEquals(3, server.fake().lastHoverCharacter)
            } finally {
                provider.shutdown().successValue()
            }
        }
    }

    @Test
    fun syncNoneAndOpenCloseFalseFailExplicitly() {
        startUnixServer(ScriptedLspServer.Config(syncKind = 0)).use { server ->
            val provider =
                provider(SemanticProviderConfig(mode = ProviderMode.ATTACHED, endpoint = server.endpoint))
            try {
                val error =
                    provider
                        .openDocument("file:///tmp/NoSync.kt", "val value = 1", 1)
                        .failureValue()
                assertIs<ContractError.PreconditionFailed>(error)
            } finally {
                provider.shutdown().successValue()
            }
        }
        startUnixServer(ScriptedLspServer.Config(syncKind = 1, openClose = false)).use { server ->
            val provider =
                provider(SemanticProviderConfig(mode = ProviderMode.ATTACHED, endpoint = server.endpoint))
            try {
                val error =
                    provider
                        .openDocument("file:///tmp/NoOpenClose.kt", "val value = 1", 1)
                        .failureValue()
                assertIs<ContractError.PreconditionFailed>(error)
            } finally {
                provider.shutdown().successValue()
            }
        }
    }

    @Test
    fun documentChangeDoesNotDeadlockWithBlockedDiagnostics() {
        val serverConfig =
            ScriptedLspServer.Config(
                publishDiagnosticsOnOpen = false,
                blockAfterOpen = true,
                blockingDiagnosticsCount = 60_000,
            )
        startUnixServer(serverConfig).use { server ->
            val provider =
                provider(SemanticProviderConfig(mode = ProviderMode.ATTACHED, endpoint = server.endpoint, timeoutMs = 10_000))
            val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable).apply { isDaemon = true } }
            try {
                val uri = "file:///tmp/BlockedDiagnostics.kt"
                provider.openDocument(uri, "val value = 1", 1).successValue()
                waitUntil(5000) { server.fake().readingPaused }
                val future =
                    executor.submit<ContractResult<Unit>> {
                        provider.changeDocument(uri, "x".repeat(4 * 1024 * 1024), 2)
                    }
                future.get(10, TimeUnit.SECONDS).successValue()
            } finally {
                executor.shutdownNow()
                provider.shutdown().successValue()
            }
        }
    }

    @Test
    fun missingAttachedEndpointFails() {
        val error =
            LspSemanticProvider
                .create(SemanticProviderConfig(mode = ProviderMode.ATTACHED), "kotlin")
                .failureValue()
        assertIs<ContractError.PreconditionFailed>(error)
    }

    @Test
    fun missingManagedCommandFails() {
        val error =
            LspSemanticProvider
                .create(SemanticProviderConfig(mode = ProviderMode.MANAGED), "kotlin")
                .failureValue()
        assertIs<ContractError.PreconditionFailed>(error)
    }

    @Test
    fun endpointParserSupportsUnixAndTcpForms() {
        val unix = LspEndpoint.parse("unix:///tmp/cint.sock")
        assertIs<LspEndpoint.Unix>(unix)
        assertEquals(Path.of("/tmp/cint.sock"), unix.path)
        val tcp = LspEndpoint.parse("tcp://127.0.0.1:9333")
        assertIs<LspEndpoint.Tcp>(tcp)
        assertEquals("127.0.0.1", tcp.host)
        assertEquals(9333, tcp.port)
        val bare = LspEndpoint.parse("localhost:1234")
        assertIs<LspEndpoint.Tcp>(bare)
        assertEquals(1234, bare.port)
    }

    @Test
    fun framingReadsSequentialMessagesAndRejectsBadHeaders() {
        val output = ByteArrayOutputStream()
        LspFraming.write(output, "{}".toByteArray())
        LspFraming.write(output, "[]".toByteArray())
        val input = ByteArrayInputStream(output.toByteArray())
        assertEquals("{}", String(LspFraming.read(input)!!))
        assertEquals("[]", String(LspFraming.read(input)!!))
        assertNull(LspFraming.read(input))
        val malformed = ByteArrayInputStream("Bad-Header\r\n\r\n".toByteArray())
        assertFailsWith<LspProtocolException> { LspFraming.read(malformed) }
    }

    @Test
    fun stdioTransportTerminatesProcessOnClose() {
        val transport = StdioTransport(listOf("sh", "-c", "sleep 30"))
        try {
            assertTrue(transport.processAlive())
        } finally {
            transport.close()
        }
        assertFalse(transport.processAlive())
    }

    @Test
    fun requestWaitsForNotificationWriteBeforeReadingNewState() {
        startUnixServer().use { server ->
            val session =
                LspSession(
                    UnixSocketTransport(Path.of(server.endpoint.removePrefix("unix:"))),
                    LspSessionOptions(languageId = "kotlin", requestTimeoutMs = 5000),
                )
            val executor = Executors.newFixedThreadPool(2)
            val reached = CountDownLatch(1)
            val release = CountDownLatch(1)
            try {
                session.initialize().successValue()
                session.openDocument("file:///tmp/Ordering.kt", "val value = 1", 1).successValue()
                session.notificationWriteGate = { method ->
                    if (method == "textDocument/didChange") {
                        reached.countDown()
                        release.await(5, TimeUnit.SECONDS)
                    }
                }
                val changeFuture =
                    executor.submit<ContractResult<Unit>> {
                        session.changeDocument("file:///tmp/Ordering.kt", "val value = 2", 2)
                    }
                assertTrue(reached.await(2, TimeUnit.SECONDS))
                val hoverFuture =
                    executor.submit<ContractResult<Any?>> {
                        val document = session.documentFor("file:///tmp/Ordering.kt", 2)
                        if (document.isFailure) {
                            return@submit ResultFactory.failure(document.errorOrNull ?: error("missing version"))
                        }
                        session.rpc(
                            "textDocument/hover",
                            linkedMapOf(
                                "textDocument" to linkedMapOf("uri" to "file:///tmp/Ordering.kt"),
                                "position" to linkedMapOf("line" to 0, "character" to 0),
                            ),
                            5000,
                            "textDocument/hover",
                        )
                    }
                Thread.sleep(300)
                assertEquals(0, server.fake().hoverCount)
                release.countDown()
                changeFuture.get(5, TimeUnit.SECONDS).successValue()
                hoverFuture.get(5, TimeUnit.SECONDS).successValue()
                val methods = server.fake().receivedMethods
                assertTrue(methods.indexOf("textDocument/didChange") < methods.indexOf("textDocument/hover"))
            } finally {
                release.countDown()
                executor.shutdownNow()
                session.close()
            }
        }
    }

    @Test
    fun fastDiagnosticsPublishDuringSyncIsAssociated() {
        for (versioned in listOf(false, true)) {
            startUnixServer(
                ScriptedLspServer.Config(
                    publishDiagnosticsOnOpen = true,
                    publishDiagnosticsVersioned = versioned,
                ),
            ).use { server ->
                val session =
                    LspSession(
                        UnixSocketTransport(Path.of(server.endpoint.removePrefix("unix:"))),
                        LspSessionOptions(languageId = "kotlin", requestTimeoutMs = 5000),
                    )
                val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable).apply { isDaemon = true } }
                var reached = CountDownLatch(1)
                var release = CountDownLatch(1)
                try {
                    session.initialize().successValue()
                    session.notificationWrittenGate = { method ->
                        if (method == "textDocument/didOpen" || method == "textDocument/didChange") {
                            reached.countDown()
                            release.await(5, TimeUnit.SECONDS)
                        }
                    }
                    val uri = "file:///tmp/FastDiagnostics.kt"
                    val openFuture = executor.submit<ContractResult<Unit>> { session.openDocument(uri, "val value = 1", 1) }
                    assertTrue(reached.await(2, TimeUnit.SECONDS))
                    waitUntil { server.fake().publishedCount > 0 }
                    Thread.sleep(100)
                    release.countDown()
                    openFuture.get(5, TimeUnit.SECONDS).successValue()
                    val openDiagnostics = session.diagnosticsFor(uri, 1)
                    assertNotNull(openDiagnostics, "versioned=$versioned open diagnostics were dropped")
                    assertEquals(1, openDiagnostics.size)

                    reached = CountDownLatch(1)
                    release = CountDownLatch(1)
                    val publishedBeforeChange = server.fake().publishedCount
                    val changeFuture = executor.submit<ContractResult<Unit>> { session.changeDocument(uri, "val value = 2", 2) }
                    assertTrue(reached.await(2, TimeUnit.SECONDS))
                    waitUntil { server.fake().publishedCount > publishedBeforeChange }
                    Thread.sleep(100)
                    release.countDown()
                    changeFuture.get(5, TimeUnit.SECONDS).successValue()
                    val changeDiagnostics = session.diagnosticsFor(uri, 2)
                    assertNotNull(changeDiagnostics, "versioned=$versioned change diagnostics were dropped")
                    assertEquals(1, changeDiagnostics.size)
                } finally {
                    release.countDown()
                    executor.shutdownNow()
                    session.close()
                }
            }
        }
    }

    @Test
    fun shutdownWithoutInitializationIsIdempotent() {
        var closeCount = 0
        val transport =
            object : LspTransport {
                override val input = ByteArrayInputStream(ByteArray(0))
                override val output = ByteArrayOutputStream()
                override val description = "test"

                override fun close() {
                    closeCount++
                }
            }
        val session = LspSession(transport, LspSessionOptions(languageId = "kotlin"))
        session.close()
        session.close()
        assertEquals(1, closeCount)
        assertFalse(session.initialize().isSuccess)
    }

    private fun provider(config: SemanticProviderConfig): LspSemanticProvider = LspSemanticProvider.create(config, "kotlin").successValue()

    private fun request(
        kind: SemanticRequestKind,
        uri: String,
        offset: Int = 4,
        version: Long = 1,
        offsetUnit: OffsetUnit = OffsetUnit.UTF8_BYTE,
    ): SemanticRequest =
        SemanticRequest(
            uri = uri,
            version = version,
            offset = offset,
            kind = kind,
            deadline = Deadline.fromNowMs(5000),
            offsetUnit = offsetUnit,
        )

    private fun waitUntil(
        timeoutMs: Long = 2000,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue(condition(), "condition was not met within ${timeoutMs}ms")
    }

    private fun javaCommand(vararg arguments: String): String = javaCommandFor(ScriptedLspServerMain::class.java.name, *arguments)

    private fun javaCommandFor(
        mainClass: String,
        vararg arguments: String,
    ): String {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val classpath =
            System.getProperty("cint.test.runtimeClasspath")
                ?: error("cint.test.runtimeClasspath is not configured")
        return buildList {
            add(java)
            add("-cp")
            add(classpath)
            add(mainClass)
            addAll(arguments)
        }.joinToString(" ") { quote(it) }
    }

    private fun quote(value: String): String = if (value.any { it.isWhitespace() }) "\"$value\"" else value

    private fun startUnixServer(config: ScriptedLspServer.Config = ScriptedLspServer.Config()): SocketServer {
        val directory = Files.createTempDirectory("cint-lsp-unix")
        val path = directory.resolve("lsp.sock")
        val channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        channel.bind(UnixDomainSocketAddress.of(path))
        val fake = CompletableFuture<ScriptedLspServer>()
        val thread =
            Thread(
                {
                    try {
                        val client = channel.accept()
                        val server =
                            ScriptedLspServer(
                                Channels.newInputStream(client),
                                Channels.newOutputStream(client),
                                config,
                            )
                        fake.complete(server)
                        server.start().await(5000)
                        client.close()
                    } catch (_: Exception) {
                    }
                },
                "scripted-unix-accept",
            )
        thread.isDaemon = true
        thread.start()
        return SocketServer(
            endpoint = "unix:$path",
            fake = fake,
            closeAction = {
                runCatching { channel.close() }
                runCatching { thread.join(1000) }
                runCatching { Files.deleteIfExists(path) }
                runCatching { Files.deleteIfExists(directory) }
            },
        )
    }

    private fun startTcpServer(config: ScriptedLspServer.Config = ScriptedLspServer.Config()): SocketServer {
        val serverSocket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val fake = CompletableFuture<ScriptedLspServer>()
        val thread =
            Thread(
                {
                    try {
                        val client = serverSocket.accept()
                        val server = ScriptedLspServer(client.getInputStream(), client.getOutputStream(), config)
                        fake.complete(server)
                        server.start().await(5000)
                        client.close()
                    } catch (_: Exception) {
                    }
                },
                "scripted-tcp-accept",
            )
        thread.isDaemon = true
        thread.start()
        return SocketServer(
            endpoint = "tcp:127.0.0.1:${serverSocket.localPort}",
            fake = fake,
            closeAction = {
                runCatching { serverSocket.close() }
                runCatching { thread.join(1000) }
            },
        )
    }

    private class SocketServer(
        val endpoint: String,
        private val fake: CompletableFuture<ScriptedLspServer>,
        private val closeAction: () -> Unit,
    ) : AutoCloseable {
        fun fake(): ScriptedLspServer = fake.get(2000, TimeUnit.MILLISECONDS)

        override fun close() {
            closeAction()
        }
    }

    private class FakeHostChannel(
        private val result: SemanticResult,
        private val delayMs: Long = 0,
        private val closeError: ContractError? = null,
    ) : HostSemanticChannel {
        val requests = CopyOnWriteArrayList<SemanticRequest>()
        var closeCount = 0
        var openCount = 0

        override fun send(request: SemanticRequest): ContractResult<SemanticResult> {
            if (delayMs > 0) Thread.sleep(delayMs)
            requests.add(request)
            return ResultFactory.success(result)
        }

        override fun openDocument(
            uri: String,
            text: String,
            version: Long,
        ): ContractResult<Unit> {
            openCount++
            return ResultFactory.success(Unit)
        }

        override fun close(): ContractResult<Unit> {
            closeCount++
            return closeError?.let { ResultFactory.failure(it) } ?: ResultFactory.success(Unit)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> ContractResult<T>.successValue(): T {
        assertTrue(isSuccess, "Expected success but got $this")
        return getValue() as T
    }

    private fun ContractResult<*>.failureValue(): ContractError {
        assertFalse(isSuccess, "Expected failure but got $this")
        return assertNotNull(errorOrNull)
    }
}
