package dev.cint.server.live

import dev.cint.api.ConfigFieldKind
import dev.cint.api.ConfigFieldScope
import dev.cint.core.config.ProjectConfig
import dev.cint.core.facts.frontend.OracleOptions
import dev.cint.host.HostConfig
import dev.cint.host.HostPipeline
import dev.cint.host.HostRuntime
import dev.cint.host.PackConfigOverlay
import dev.cint.rulepacks.BundledRulePackConfigResolver
import dev.cint.server.LIVE_LSP_TIMEOUT_MS
import java.io.BufferedWriter
import java.io.StringWriter
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
}
