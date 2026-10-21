package dev.cint.server

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.cint.api.CINT_API_VERSION
import dev.cint.api.ConfigField
import dev.cint.api.ConfigFieldKind
import dev.cint.api.ConfigFieldScope
import dev.cint.api.ConfigSchema
import dev.cint.api.ConfigurableRulePack
import dev.cint.api.Emit
import dev.cint.api.Intent
import dev.cint.api.Rule
import dev.cint.api.RuleEmit
import dev.cint.api.RulePack
import dev.cint.api.RulePackConfigException
import dev.cint.api.View
import dev.cint.core.config.ProjectConfig
import dev.cint.core.facts.frontend.OracleOptions
import dev.cint.core.runtime.rulepack.RulePackLoader
import dev.cint.host.HostConfig
import dev.cint.host.HostPipeline
import dev.cint.host.HostRuntime
import dev.cint.host.HostSettings
import dev.cint.host.PackConfigOverlay
import dev.cint.rulepacks.BundledRulePackConfigResolver
import java.io.BufferedWriter
import java.io.StringWriter
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class ServerLegacyRule : Rule {
    override val id: String = "legacy-server:rule"
    override val owns: Intent = "LEGACY_SERVER"
    override val reads: Set<Intent> = emptySet()

    override fun run(view: View): List<RuleEmit> = emptyList()
}

class ServerLegacyPack(
    private val calls: Set<String> = setOf("default"),
) : ConfigurableRulePack {
    companion object {
        var lastCalls: Set<String>? = null
            private set

        fun reset() {
            lastCalls = null
        }
    }

    override val id: String = "legacy-server"
    override val languages: Set<String> = setOf("testlang")
    override val apiVersion: Int = CINT_API_VERSION

    override fun configure(values: Map<String, Any?>): RulePack {
        val decoded =
            when (val raw = values["calls"]) {
                null -> calls
                is String -> setOf(raw)
                is List<*> -> raw.map { it as String }.toSet()
                else -> throw RulePackConfigException("legacy-server.calls: expected strings")
            }
        lastCalls = decoded
        return ServerLegacyPack(decoded)
    }

    override fun configSchema(): ConfigSchema<*> =
        ConfigSchema.of(
            ConfigField.strings<ServerLegacyPack>(
                "calls",
                { it.calls },
                { _, value -> ServerLegacyPack(value) },
            ),
        )

    override fun rules(): List<Rule> = listOf(ServerLegacyRule())
}

class LiveServerConfigTest {
    @Test
    fun liveOracleKeepsItsDefaultTimeoutWithoutExplicitConfig() {
        val settings = HostConfig.load(null)
        val server = server(settings)

        assertEquals(OracleOptions(enabled = true, timeoutMs = LIVE_LSP_TIMEOUT_MS), server.oracleOptions())
        assertEquals(OracleOptions(enabled = false, timeoutMs = LIVE_LSP_TIMEOUT_MS), server.oracleOptions(false))
    }

    @Test
    fun liveOracleHonorsAnExplicitConfiguredTimeout() {
        val file = Files.createTempFile("cint-live-lsp", ".yaml")
        Files.writeString(
            file,
            """
            core:
              lsp:
                timeoutMs: 900
            """.trimIndent(),
        )
        try {
            val server = server(HostConfig.load(file.toString()))

            assertEquals(OracleOptions(enabled = true, timeoutMs = 900), server.oracleOptions())
            assertEquals(OracleOptions(enabled = false, timeoutMs = 900), server.oracleOptions(false))
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun configAppendAndRemovePersistOverlayAndChangeAnalysis() {
        val root = Files.createTempDirectory("cint-live-config")
        try {
            val settings = HostConfig.load(null)
            val pipeline = HostRuntime.pipeline(settings, BundledRulePackConfigResolver, root)
            val server = server(settings, pipeline, root, useLsp = false)
            val source = """fun f() { log.audit("x") }"""

            val field =
                server
                    .configDescriptors()
                    .first { it.packId == "logging" }
                    .fields
                    .first { it.key == "methods" }
            assertEquals(ConfigFieldKind.STRINGS, field.kind)
            assertEquals(ConfigFieldScope.LANGUAGE, field.scope)
            assertTrue("kotlin" in field.languages)

            assertTrue(server.analyzeForTest("Test.kt", source, "kotlin").none { "LOGGING" in it.intents })

            val appended = server.configAppend("logging", "kotlin", "methods", "audit")
            val languageValues = appended.getValue("logging").getValue("kotlin") as Map<*, *>
            assertEquals(listOf("audit"), languageValues["methods"])
            assertEquals(appended, server.configRead())
            assertTrue(server.analyzeForTest("Test.kt", source, "kotlin").any { "LOGGING" in it.intents })
            assertTrue(
                server
                    .analyzeForTest("Test.kt", """fun f() { log.info("x") }""", "kotlin")
                    .any { "LOGGING" in it.intents },
            )

            val restarted = HostRuntime.pipeline(settings, BundledRulePackConfigResolver, root)
            val restartedServer = server(settings, restarted, root, useLsp = false)
            assertTrue(restartedServer.analyzeForTest("Test.kt", source, "kotlin").any { "LOGGING" in it.intents })
            assertTrue(
                restartedServer
                    .analyzeForTest("Test.kt", """fun f() { log.info("x") }""", "kotlin")
                    .any { "LOGGING" in it.intents },
            )

            val removed = server.configRemove("logging", "kotlin", "methods", "audit")
            assertTrue(removed.isEmpty())
            assertTrue(server.analyzeForTest("Test.kt", source, "kotlin").none { "LOGGING" in it.intents })

            assertFailsWith<IllegalArgumentException> {
                server.configAppend("error-handling", "kotlin", "detectThrows", "nope")
            }
            assertFailsWith<IllegalArgumentException> {
                server.configAppend("missing-pack", "kotlin", "methods", "audit")
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun additiveOverlayUnionsScalarAndListProjectValues() {
        val settings = HostConfig.load(null)
        val projectScalar =
            ProjectConfig(
                packs = mapOf("logging" to mapOf("kotlin" to mapOf("methods" to "audit"))),
            )
        val firstRoot = Files.createTempDirectory("cint-live-scalar")
        try {
            writeOverlay(firstRoot, "logging:\n  kotlin:\n    methods: [trace]\n")
            val scalarPipeline = HostPipeline(project = projectScalar, root = firstRoot)
            val scalarServer = server(settings, scalarPipeline, firstRoot, useLsp = false)
            assertTrue(
                loggingLines(
                    scalarServer,
                    """fun f() {
                    |    log.audit("x")
                    |    log.trace("y")
                    |}
                    """.trimMargin(),
                ).containsAll(setOf(1, 2)),
            )
            assertTrue(
                scalarServer
                    .analyzeForTest("Test.kt", """fun f() { log.info("x") }""", "kotlin")
                    .none { "LOGGING" in it.intents },
            )
        } finally {
            firstRoot.toFile().deleteRecursively()
        }

        val projectList =
            ProjectConfig(
                packs = mapOf("logging" to mapOf("kotlin" to mapOf("methods" to listOf("audit")))),
            )
        val secondRoot = Files.createTempDirectory("cint-live-list")
        try {
            writeOverlay(secondRoot, "logging:\n  kotlin:\n    methods: trace\n")
            val listPipeline = HostPipeline(project = projectList, root = secondRoot)
            val listServer = server(settings, listPipeline, secondRoot, useLsp = false)
            assertTrue(
                loggingLines(
                    listServer,
                    """fun f() {
                    |    log.audit("x")
                    |    log.trace("y")
                    |}
                    """.trimMargin(),
                ).containsAll(setOf(1, 2)),
            )
        } finally {
            secondRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun clientConfigMutationRollsBackWhenTargetPackRejectsOverlay() {
        ServerLegacyPack.reset()
        val root = Files.createTempDirectory("cint-live-legacy-overlay")
        val serviceRoot = Files.createTempDirectory("cint-live-legacy-service")
        try {
            val service = serviceRoot.resolve("META-INF/services/${RulePack::class.java.name}")
            Files.createDirectories(service.parent)
            Files.writeString(service, ServerLegacyPack::class.java.name)
            val classLoader =
                URLClassLoader(
                    arrayOf(serviceRoot.toUri().toURL()),
                    LiveServerConfigTest::class.java.classLoader,
                )
            val project =
                ProjectConfig(
                    packs = mapOf("legacy-server" to mapOf("calls" to listOf("project"))),
                )
            val pipeline = HostPipeline(RulePackLoader(classLoader, onFailure = {}), project, root = root)
            val settings = HostConfig.load(null)
            val server = server(settings, pipeline, root, useLsp = false)
            val output = StringWriter()
            val writer = BufferedWriter(output)

            assertTrue(server.configDescriptors().any { it.packId == "legacy-server" })
            assertEquals(setOf("project"), ServerLegacyPack.lastCalls)

            server.handleMessage(
                """{"jsonrpc":"2.0","id":1,"method":"cint/config/append","params":{"packId":"legacy-server","language":"testlang","field":"calls","value":"overlay"}}""",
                writer,
            )

            val response =
                output
                    .toString()
                    .trim()
                    .lines()
                    .last()
            assertTrue(response.contains("\"error\""))
            assertTrue(response.contains("does not support additive config"))
            assertTrue(!PackConfigOverlay.read(root).present)
            assertTrue(server.configDescriptors().any { it.packId == "legacy-server" })
            assertEquals(setOf("project"), ServerLegacyPack.lastCalls)

            output.buffer.setLength(0)
            server.handleMessage(
                """{"jsonrpc":"2.0","id":2,"method":"cint/config/append","params":{"packId":"legacy-server","language":"testlang","field":"calls","value":"second"}}""",
                writer,
            )
            val secondResponse =
                output
                    .toString()
                    .trim()
                    .lines()
                    .last()
            assertTrue(secondResponse.contains("\"error\""))
            assertTrue(secondResponse.contains("does not support additive config"))
            assertTrue(!PackConfigOverlay.read(root).present)
            assertEquals(setOf("project"), ServerLegacyPack.lastCalls)
        } finally {
            root.toFile().deleteRecursively()
            serviceRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun preExistingUnsupportedOverlayStillRejectsFurtherMutations() {
        ServerLegacyPack.reset()
        val root = Files.createTempDirectory("cint-live-preseeded-overlay")
        val serviceRoot = Files.createTempDirectory("cint-live-preseeded-service")
        try {
            val seeded = "legacy-server:\n  calls: [seed]\n"
            writeOverlay(root, seeded)
            val service = serviceRoot.resolve("META-INF/services/${RulePack::class.java.name}")
            Files.createDirectories(service.parent)
            Files.writeString(service, ServerLegacyPack::class.java.name)
            val classLoader =
                URLClassLoader(
                    arrayOf(serviceRoot.toUri().toURL()),
                    LiveServerConfigTest::class.java.classLoader,
                )
            val project =
                ProjectConfig(
                    packs = mapOf("legacy-server" to mapOf("calls" to listOf("project"))),
                )
            val pipeline = HostPipeline(RulePackLoader(classLoader, onFailure = {}), project, root = root)
            val settings = HostConfig.load(null)
            val server = server(settings, pipeline, root, useLsp = false)
            val output = StringWriter()
            val writer = BufferedWriter(output)

            assertTrue(
                pipeline.failures.any {
                    it.kind == dev.cint.core.runtime.rulepack.RulePackLoadFailureKind.UNSUPPORTED_PACK_OVERLAY &&
                        it.packId == "legacy-server"
                },
            )

            server.handleMessage(
                """{"jsonrpc":"2.0","id":1,"method":"cint/config/append","params":{"packId":"legacy-server","language":"testlang","field":"calls","value":"overlay"}}""",
                writer,
            )

            val response =
                output
                    .toString()
                    .trim()
                    .lines()
                    .last()
            assertTrue(response.contains("\"error\""))
            assertEquals(seeded, Files.readString(PackConfigOverlay.path(root)))
            assertTrue(server.configDescriptors().any { it.packId == "legacy-server" })
            assertEquals(setOf("project"), ServerLegacyPack.lastCalls)
        } finally {
            root.toFile().deleteRecursively()
            serviceRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun invalidOverlayIsReportedAndNotOverwrittenByProtocolMutations() {
        val root = Files.createTempDirectory("cint-live-invalid-overlay")
        try {
            val settings = HostConfig.load(null)
            val pipeline = HostRuntime.pipeline(settings, BundledRulePackConfigResolver, root)
            val server = server(settings, pipeline, root, useLsp = false)
            val path = PackConfigOverlay.path(root)
            Files.createDirectories(path.parent)
            Files.writeString(path, "logging: [\n")
            val original = Files.readString(path)
            val output = StringWriter()
            val writer = BufferedWriter(output)

            server.handleMessage(
                """{"jsonrpc":"2.0","id":1,"method":"cint/config/read"}""",
                writer,
            )
            assertTrue(
                output
                    .toString()
                    .trim()
                    .lines()
                    .last()
                    .contains("\"error\""),
            )
            output.buffer.setLength(0)

            server.handleMessage(
                """{"jsonrpc":"2.0","id":2,"method":"cint/config/append","params":{"packId":"logging","language":"kotlin","field":"methods","value":"audit"}}""",
                writer,
            )
            assertTrue(
                output
                    .toString()
                    .trim()
                    .lines()
                    .last()
                    .contains("\"error\""),
            )
            assertEquals(original, Files.readString(path))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun configMutationRepublishesDocumentsForOtherConnections() {
        val root = Files.createTempDirectory("cint-live-broadcast")
        try {
            val settings = HostConfig.load(null)
            val pipeline = HostRuntime.pipeline(settings, BundledRulePackConfigResolver, root)
            val server = server(settings, pipeline, root, useLsp = false)
            val firstOutput = StringWriter()
            val first = BufferedWriter(firstOutput)
            val secondOutput = StringWriter()
            val second = BufferedWriter(secondOutput)

            server.handleMessage(
                """{"jsonrpc":"2.0","id":1,"method":"textDocument/didOpen","params":{"uri":"Test1.kt","text":"fun f() {}","version":1,"languageId":"kotlin"}}""",
                first,
            )
            server.handleMessage(
                """{"jsonrpc":"2.0","id":1,"method":"textDocument/didOpen","params":{"uri":"Test2.kt","text":"fun g() {}","version":1,"languageId":"kotlin"}}""",
                second,
            )
            firstOutput.buffer.setLength(0)
            secondOutput.buffer.setLength(0)

            server.handleMessage(
                """{"jsonrpc":"2.0","id":2,"method":"cint/config/append","params":{"packId":"logging","language":"kotlin","field":"methods","value":"audit"}}""",
                first,
            )

            assertTrue(firstOutput.toString().contains("\"cint/publishTags\""))
            assertTrue(secondOutput.toString().contains("\"cint/publishTags\""))
            assertTrue(secondOutput.toString().contains("\"revision\":1"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun concurrentDidChangeAndConfigMutationYieldsPostMutationAtHighestRevision() {
        val root = Files.createTempDirectory("cint-live-race")
        try {
            val settings = HostConfig.load(null)
            val pipeline = HostRuntime.pipeline(settings, BundledRulePackConfigResolver, root)
            val server = server(settings, pipeline, root, useLsp = false)
            val output = StringWriter()
            val writer = BufferedWriter(output)
            val mutationOutput = StringWriter()
            val mutationWriter = BufferedWriter(mutationOutput)
            val source = "fun f() {\n    log.info(\"x\")\n    log.audit(\"y\")\n}\n"

            server.handleMessage(
                """{"jsonrpc":"2.0","id":1,"method":"textDocument/didOpen","params":{"uri":"Race.kt","text":${jsonString(
                    source,
                )},"version":1,"languageId":"kotlin"}}""",
                writer,
            )
            output.buffer.setLength(0)
            val start = CountDownLatch(1)
            val change =
                thread {
                    start.await()
                    server.handleMessage(
                        """{"jsonrpc":"2.0","id":2,"method":"textDocument/didChange","params":{"uri":"Race.kt","text":${jsonString(
                            source,
                        )},"version":2,"languageId":"kotlin"}}""",
                        writer,
                    )
                }
            val mutation =
                thread {
                    start.await()
                    server.handleMessage(
                        """{"jsonrpc":"2.0","id":3,"method":"cint/config/append","params":{"packId":"logging","language":"kotlin","field":"methods","value":"audit"}}""",
                        mutationWriter,
                    )
                }
            start.countDown()
            change.join()
            mutation.join()

            val mapper = jacksonObjectMapper()
            val publishes =
                output
                    .toString()
                    .trim()
                    .lines()
                    .filter { it.contains("\"method\":\"cint/publishTags\"") }
                    .map { mapper.readTree(it) }
            val highestRevision = publishes.maxOf { it["params"]["revision"].asInt() }
            assertEquals(1, highestRevision)
            val message = publishes.last { it["params"]["revision"].asInt() == highestRevision }
            val loggingLines =
                message["params"]["annotations"]
                    .filter { annotation -> annotation["intents"].any { it.asText() == "LOGGING" } }
                    .map { it["startLine"].asInt() }
                    .toSet()
            assertTrue(loggingLines.containsAll(setOf(1, 2)))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun liveProtocolPublishesDescriptorsAndAppliesOverlayMutations() {
        val root = Files.createTempDirectory("cint-live-protocol")
        try {
            val settings = HostConfig.load(null)
            val pipeline = HostRuntime.pipeline(settings, BundledRulePackConfigResolver, root)
            val server = server(settings, pipeline, root, useLsp = false)
            val output = StringWriter()
            val writer = BufferedWriter(output)

            server.handleMessage(
                """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocol":2}}""",
                writer,
            )
            val initialized =
                output
                    .toString()
                    .trim()
                    .lines()
                    .last()
            assertTrue(initialized.contains("\"protocol\":3"))
            assertTrue(initialized.contains("\"packId\":\"logging\""))
            assertTrue(initialized.contains("\"key\":\"methods\""))
            assertTrue(initialized.contains("\"scope\":\"LANGUAGE\""))

            server.handleMessage(
                """{"jsonrpc":"2.0","id":2,"method":"cint/config/read"}""",
                writer,
            )
            val read =
                output
                    .toString()
                    .trim()
                    .lines()
                    .last()
            assertTrue(read.contains("\"overlay\":{}"))

            server.handleMessage(
                """{"jsonrpc":"2.0","id":3,"method":"cint/config/append","params":{"packId":"logging","language":"kotlin","field":"methods","value":"audit"}}""",
                writer,
            )
            val appended =
                output
                    .toString()
                    .trim()
                    .lines()
                    .last()
            assertTrue(appended.contains("\"audit\""))
            assertTrue(server.analyzeForTest("Test.kt", """fun f() { log.audit("x") }""", "kotlin").any { "LOGGING" in it.intents })

            server.handleMessage(
                """{"jsonrpc":"2.0","id":4,"method":"cint/config/remove","params":{"packId":"logging","language":"kotlin","field":"methods","value":"audit"}}""",
                writer,
            )
            val removed =
                output
                    .toString()
                    .trim()
                    .lines()
                    .last()
            assertTrue(removed.contains("\"overlay\":{}"))
            assertTrue(server.analyzeForTest("Test.kt", """fun f() { log.audit("x") }""", "kotlin").none { "LOGGING" in it.intents })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun jsonString(value: String): String = jacksonObjectMapper().writeValueAsString(value)

    private fun loggingLines(
        server: LiveServer,
        source: String,
    ): Set<Int> =
        server
            .analyzeForTest("Test.kt", source, "kotlin")
            .filter { "LOGGING" in it.intents }
            .map { it.span.startLine }
            .toSet()

    private fun writeOverlay(
        root: Path,
        text: String,
    ) {
        val path = PackConfigOverlay.path(root)
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
    }

    private fun server(
        settings: HostSettings,
        pipeline: HostPipeline = HostPipeline(loader = RulePackLoader(onFailure = {})),
        root: Path = Path.of("."),
        useLsp: Boolean = true,
    ): LiveServer =
        LiveServer(
            root = root,
            socketPath = Path.of("/tmp/opencode/cint-013-test.sock"),
            useLsp = useLsp,
            defaultLanguage = "kotlin",
            packs = pipeline,
            settings = settings,
        )
}
