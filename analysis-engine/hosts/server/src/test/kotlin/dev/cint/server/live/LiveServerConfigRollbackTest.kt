package dev.cint.server.live

import dev.cint.api.RulePack
import dev.cint.core.config.ProjectConfig
import dev.cint.core.runtime.rulepack.RulePackLoader
import dev.cint.host.HostConfig
import dev.cint.host.HostPipeline
import dev.cint.host.PackConfigOverlay
import java.io.BufferedWriter
import java.io.StringWriter
import java.net.URLClassLoader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveServerConfigRollbackTest {
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
                    LiveServerConfigRollbackTest::class.java.classLoader,
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
                    LiveServerConfigRollbackTest::class.java.classLoader,
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
}
