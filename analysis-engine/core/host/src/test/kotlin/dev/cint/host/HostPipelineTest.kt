package dev.cint.host

import dev.cint.api.CINT_API_VERSION
import dev.cint.api.ConfigField
import dev.cint.api.ConfigSchema
import dev.cint.api.ConfigurableRulePack
import dev.cint.api.Emit
import dev.cint.api.Intent
import dev.cint.api.Rule
import dev.cint.api.RulePack
import dev.cint.api.RulePackConfigException
import dev.cint.api.RulePackConfigResolver
import dev.cint.api.View
import dev.cint.core.config.ProjectConfig
import dev.cint.core.runtime.rulepack.RulePackLoadFailureKind
import dev.cint.core.runtime.rulepack.RulePackLoader
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class TestRule(
    override val id: String,
    override val owns: Intent,
    override val reads: Set<Intent> = emptySet(),
) : Rule {
    override fun run(view: View): List<Emit> = emptyList()
}

class AlphaPack : RulePack {
    override val id: String = "alpha"
    override val languages: Set<String> = setOf("alpha")
    override val apiVersion: Int = CINT_API_VERSION

    override fun rules(): List<Rule> = listOf(TestRule("alpha:alpha", "ALPHA"))
}

class BetaPack : RulePack {
    override val id: String = "beta"
    override val languages: Set<String> = setOf("beta")
    override val apiVersion: Int = CINT_API_VERSION

    override fun rules(): List<Rule> = listOf(TestRule("beta:beta", "BETA"))
}

class ConfigurableTestPack(
    private val calls: Set<String> = setOf("default"),
) : ConfigurableRulePack {
    companion object {
        var lastCalls: Set<String>? = null
            private set

        fun reset() {
            lastCalls = null
        }
    }

    override val id: String = "constructed-configurable"
    override val languages: Set<String> = setOf("testlang")
    override val apiVersion: Int = CINT_API_VERSION

    override fun configure(values: Map<String, Any?>): RulePack {
        val calls = decodeCalls(values)
        lastCalls = calls
        return ConfigurableTestPack(calls)
    }

    override fun configureAdditive(values: Map<String, Any?>): RulePack {
        val calls = this.calls + decodeCalls(values)
        lastCalls = calls
        return ConfigurableTestPack(calls)
    }

    private fun decodeCalls(values: Map<String, Any?>): Set<String> {
        val unknown = values.keys - "calls"
        if (unknown.isNotEmpty()) {
            throw RulePackConfigException("constructed-configurable.${unknown.minOf { it }}: unknown key")
        }
        return when (val raw = values["calls"]) {
            null -> {
                calls
            }

            is String -> {
                setOf(raw)
            }

            is List<*> -> {
                if (raw.all { it is String }) {
                    raw.map { it as String }.toSet()
                } else {
                    throw RulePackConfigException("constructed-configurable.calls: expected strings")
                }
            }

            else -> {
                throw RulePackConfigException("constructed-configurable.calls: expected a string or list")
            }
        }
    }

    override fun rules(): List<Rule> = listOf(TestRule("constructed-configurable:configurable", "CONFIGURABLE"))
}

class ConfigurableMismatchTestPack : ConfigurableRulePack {
    companion object {
        var configureCalls: Int = 0
            private set

        fun reset() {
            configureCalls = 0
        }
    }

    override val id: String = "constructed-configurable-mismatch"
    override val languages: Set<String> = setOf("testlang")
    override val apiVersion: Int = CINT_API_VERSION + 1

    override fun configure(values: Map<String, Any?>): RulePack {
        configureCalls++
        return this
    }

    override fun rules(): List<Rule> = listOf(TestRule("constructed-configurable-mismatch:configurable", "MISMATCH"))
}

class ServiceValidationPack : RulePack {
    override val id: String = "service-validation"
    override val languages: Set<String> = setOf("servicelang")
    override val apiVersion: Int = CINT_API_VERSION

    override fun rules(): List<Rule> = listOf(TestRule("service-validation:validation", "VALIDATION"))
}

private data class SchemaPackConfig(
    val calls: Set<String> = setOf("default"),
)

private val schemaPackSchema =
    ConfigSchema.of(
        ConfigField.strings<SchemaPackConfig>(
            "calls",
            { it.calls },
            { config, value -> config.copy(calls = value) },
        ),
    )

private class PlainConfiguredRulePack : RulePack {
    override val id: String = "schema-captured"
    override val languages: Set<String> = setOf("testlang")
    override val apiVersion: Int = CINT_API_VERSION

    override fun rules(): List<Rule> = listOf(TestRule("schema-captured:rule", "SCHEMA_CAPTURED"))
}

class SchemaCapturedPack : ConfigurableRulePack {
    override val id: String = "schema-captured"
    override val languages: Set<String> = setOf("testlang")
    override val apiVersion: Int = CINT_API_VERSION

    override fun configure(values: Map<String, Any?>): RulePack = PlainConfiguredRulePack()

    override fun configSchema(): ConfigSchema<*> = schemaPackSchema

    override fun rules(): List<Rule> = listOf(TestRule("schema-captured:rule", "SCHEMA_CAPTURED"))
}

class LegacyAdditivePack(
    private val calls: Set<String> = setOf("default"),
) : ConfigurableRulePack {
    companion object {
        var lastCalls: Set<String>? = null
            private set

        fun reset() {
            lastCalls = null
        }
    }

    override val id: String = "legacy-additive"
    override val languages: Set<String> = setOf("testlang")
    override val apiVersion: Int = CINT_API_VERSION

    override fun configure(values: Map<String, Any?>): RulePack {
        val decoded =
            when (val raw = values["calls"]) {
                null -> calls
                is String -> setOf(raw)
                is List<*> -> raw.map { it as String }.toSet()
                else -> throw RulePackConfigException("legacy-additive.calls: expected strings")
            }
        lastCalls = decoded
        return LegacyAdditivePack(decoded)
    }

    override fun configSchema(): ConfigSchema<*> = schemaPackSchema

    override fun rules(): List<Rule> = listOf(TestRule("legacy-additive:rule", "LEGACY_ADDITIVE"))
}

class HostPipelineTest {
    @Test
    fun discoveredPacksAreSelectedByLanguageAndDriveTheIntentVocabulary() {
        val pipeline = HostPipeline(loader = RulePackLoader(onFailure = {}))

        assertEquals(listOf("fixture-good"), pipeline.packs("kotlin").map { it.id })
        assertTrue(pipeline.packs("typescript").isEmpty())
        assertEquals(listOf("LOGGING", "UNTAGGED"), pipeline.intents(listOf("kotlin")))
    }

    @Test
    fun invalidDiscoveredPacksAreRejectedByTheLoader() {
        ConfigurableMismatchTestPack.reset()
        withService(ConfigurableMismatchTestPack::class.java) { pipeline ->
            assertTrue(pipeline.packs("testlang").isEmpty())
            assertEquals(0, ConfigurableMismatchTestPack.configureCalls)
            assertTrue(
                pipeline.failures.any {
                    it.kind == RulePackLoadFailureKind.INCOMPATIBLE_API_VERSION &&
                        it.packId == "constructed-configurable-mismatch"
                },
            )
        }
    }

    @Test
    fun discoveredPackConfigFailureIsReported() {
        ConfigurableTestPack.reset()
        val project =
            ProjectConfig(
                packs = mapOf("constructed-configurable" to mapOf("bogus" to true)),
            )
        withService(ConfigurableTestPack::class.java, project = project) { pipeline ->
            assertTrue(pipeline.packs("testlang").isEmpty())
            assertTrue(
                pipeline.failures.any {
                    it.kind == RulePackLoadFailureKind.INVALID_PACK_CONFIG &&
                        it.packId == "constructed-configurable"
                },
            )
            assertEquals(listOf("UNTAGGED"), pipeline.intents(listOf("testlang")))
        }
    }

    @Test
    fun descriptorSchemaIsCapturedBeforeConfigureReturnsAPlainPack() {
        withService(SchemaCapturedPack::class.java) { pipeline ->
            val descriptor = pipeline.configDescriptors().single { it.packId == "schema-captured" }
            assertEquals(listOf("calls"), descriptor.fields.map { it.key })
            assertEquals(listOf("schema-captured:rule"), descriptor.rules.map { it.id })
            assertEquals(setOf("testlang"), descriptor.languages)
        }
    }

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
    fun serviceValidationPackJoinsSelectionAndVocabulary() {
        withService(ServiceValidationPack::class.java) { pipeline ->
            assertEquals(listOf("service-validation"), pipeline.packs("servicelang").map { it.id })
            assertEquals(listOf("UNTAGGED", "VALIDATION"), pipeline.intents(listOf("servicelang")))
        }
    }

    @Test
    fun projectConfigIsAppliedToDiscoveredPacks() {
        ConfigurableTestPack.reset()
        val project =
            ProjectConfig(
                packs = mapOf("constructed-configurable" to mapOf("calls" to listOf("audit"))),
            )
        withService(ConfigurableTestPack::class.java, project = project) { pipeline ->
            assertEquals(listOf("constructed-configurable"), pipeline.packs("testlang").map { it.id })
            assertEquals(setOf("audit"), ConfigurableTestPack.lastCalls)
            assertEquals(listOf("CONFIGURABLE", "UNTAGGED"), pipeline.intents(listOf("testlang")))
            assertTrue(pipeline.failures.isEmpty())
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

    @Test
    fun vocabularyIsTheUnionOfSelectedPlans() {
        withService(AlphaPack::class.java, BetaPack::class.java) { pipeline ->
            assertEquals(listOf("alpha"), pipeline.packs("alpha").map { it.id })
            assertEquals(listOf("beta"), pipeline.packs("beta").map { it.id })
            assertEquals(listOf("ALPHA", "BETA", "UNTAGGED"), pipeline.intents(listOf("alpha", "beta")))
            assertFalse("LOGGING" in pipeline.intents(listOf("alpha", "beta")))
        }
    }

    private fun writeOverlay(
        root: Path,
        text: String,
    ) {
        val path = root.resolve(".cint").resolve("config.overlay.yml")
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
    }

    private fun withService(
        vararg providers: Class<out RulePack>,
        project: ProjectConfig = ProjectConfig(),
        configResolver: RulePackConfigResolver = RulePackConfigResolver.NONE,
        root: Path? = null,
        block: (HostPipeline) -> Unit,
    ) {
        val directory = Files.createTempDirectory("cint-host-pipeline")
        val service = directory.resolve("META-INF/services/${RulePack::class.java.name}")
        Files.createDirectories(service.parent)
        Files.writeString(service, providers.joinToString("\n") { it.name })
        try {
            val classLoader =
                URLClassLoader(
                    arrayOf(directory.toUri().toURL()),
                    HostPipelineTest::class.java.classLoader,
                )
            block(HostPipeline(RulePackLoader(classLoader, onFailure = {}), project, configResolver, root))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
