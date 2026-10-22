package dev.cint.host

import dev.cint.api.RulePackConfigResolver
import dev.cint.core.config.ProjectConfig
import dev.cint.core.runtime.rulepack.RulePackLoadFailureKind
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HostPipelineConfigTest {
    @Test
    fun overlayOnLegacyPackIsReportedWithoutLosingThePackOrProjectConfig() {
        LegacyAdditivePack.reset()
        val root = Files.createTempDirectory("cint-host-legacy-overlay")
        try {
            writeOverlay(root, "legacy-additive:\n  calls: [overlay]\n")
            val project =
                ProjectConfig(
                    packs = mapOf("legacy-additive" to mapOf("calls" to listOf("project"))),
                )
            withService(LegacyAdditivePack::class.java, project = project, root = root) { pipeline ->
                assertEquals(listOf("legacy-additive"), pipeline.packs("testlang").map { it.id })
                assertEquals(setOf("project"), LegacyAdditivePack.lastCalls)
                assertTrue(
                    pipeline.failures.any {
                        it.kind == RulePackLoadFailureKind.UNSUPPORTED_PACK_OVERLAY &&
                            it.packId == "legacy-additive"
                    },
                )
                assertEquals(
                    listOf("calls"),
                    pipeline
                        .configDescriptors()
                        .single { it.packId == "legacy-additive" }
                        .fields
                        .map { it.key },
                )
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun overlayOnPlainConfiguredResultIsReportedWithoutDroppingThePack() {
        val root = Files.createTempDirectory("cint-host-plain-overlay")
        try {
            writeOverlay(root, "schema-captured:\n  calls: [overlay]\n")
            val project =
                ProjectConfig(
                    packs = mapOf("schema-captured" to mapOf("calls" to listOf("project"))),
                )
            withService(SchemaCapturedPack::class.java, project = project, root = root) { pipeline ->
                assertEquals(listOf("schema-captured"), pipeline.packs("testlang").map { it.id })
                assertTrue(
                    pipeline.failures.any {
                        it.kind == RulePackLoadFailureKind.UNSUPPORTED_PACK_OVERLAY &&
                            it.packId == "schema-captured"
                    },
                )
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun overlayMergesAdditivelyAndReloadReappliesIt() {
        ConfigurableTestPack.reset()
        val root = Files.createTempDirectory("cint-host-overlay")
        try {
            val overlayDirectory = root.resolve(".cint")
            Files.createDirectories(overlayDirectory)
            val overlay = overlayDirectory.resolve("config.overlay.yml")
            Files.writeString(
                overlay,
                """
                constructed-configurable:
                  calls: [audit]
                """.trimIndent(),
            )
            val project =
                ProjectConfig(
                    packs = mapOf("constructed-configurable" to mapOf("calls" to listOf("base"))),
                )

            withService(ConfigurableTestPack::class.java, project = project, root = root) { pipeline ->
                assertEquals(listOf("constructed-configurable"), pipeline.packs("testlang").map { it.id })
                assertEquals(setOf("base", "audit"), ConfigurableTestPack.lastCalls)

                Files.writeString(
                    overlay,
                    """
                    constructed-configurable:
                      calls: [trace]
                    """.trimIndent(),
                )
                pipeline.reload()
                assertEquals(setOf("base", "trace"), ConfigurableTestPack.lastCalls)
                assertTrue(pipeline.effectivePacks.any { it.id == "constructed-configurable" })

                Files.delete(overlay)
                pipeline.reload()
                assertEquals(setOf("base"), ConfigurableTestPack.lastCalls)
                assertTrue(pipeline.failures.isEmpty())
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun configResolverSeesDiscoveredIdsAndCanRewriteConfig() {
        ConfigurableTestPack.reset()
        val project =
            ProjectConfig(
                packs = mapOf("legacy-configurable" to mapOf("calls" to listOf("audit"))),
            )
        var discoveredIds: Set<String>? = null
        val resolver =
            RulePackConfigResolver { config, ids, _ ->
                discoveredIds = ids
                val values = config["legacy-configurable"] ?: return@RulePackConfigResolver config
                config - "legacy-configurable" + ("constructed-configurable" to values)
            }

        withService(ConfigurableTestPack::class.java, project = project, configResolver = resolver) { pipeline ->
            assertTrue("constructed-configurable" in discoveredIds.orEmpty())
            assertEquals(setOf("audit"), ConfigurableTestPack.lastCalls)
            assertEquals(listOf("constructed-configurable"), pipeline.packs("testlang").map { it.id })
            assertTrue(pipeline.failures.isEmpty())
        }
    }

    @Test
    fun configResolverFailuresAreReportedAsInvalidConfig() {
        val resolver = RulePackConfigResolver { _, _, _ -> error("resolver boom") }

        withService(ConfigurableTestPack::class.java, configResolver = resolver) { pipeline ->
            assertTrue(
                pipeline.failures.any {
                    it.kind == RulePackLoadFailureKind.INVALID_PACK_CONFIG &&
                        it.message.contains("resolver boom")
                },
            )
            assertEquals(listOf("constructed-configurable"), pipeline.packs("testlang").map { it.id })
        }
    }
}
