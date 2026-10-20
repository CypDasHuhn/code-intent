package dev.cint.core.host

import dev.cint.api.CINT_API_VERSION
import dev.cint.api.ConfigurableRulePack
import dev.cint.api.Emit
import dev.cint.api.Intent
import dev.cint.api.Rule
import dev.cint.api.RulePack
import dev.cint.api.RulePackConfigException
import dev.cint.api.View
import dev.cint.core.config.ProjectConfig
import dev.cint.core.rulepack.RulePackLoadFailureKind
import dev.cint.core.rulepack.RulePackLoader
import java.net.URLClassLoader
import java.nio.file.Files
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

private class TestPack(
    override val id: String,
    override val languages: Set<String>,
    override val apiVersion: Int = CINT_API_VERSION,
    private val entries: List<Rule>,
) : RulePack {
    override fun rules(): List<Rule> = entries
}

private class ConfigurableTestPack(
    val calls: Set<String> = setOf("default"),
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
        val unknown = values.keys - "calls"
        if (unknown.isNotEmpty()) {
            throw RulePackConfigException("constructed-configurable.${unknown.minOf { it }}: unknown key")
        }
        val calls =
            when (val raw = values["calls"]) {
                null -> {
                    this.calls
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
        lastCalls = calls
        return ConfigurableTestPack(calls)
    }

    override fun rules(): List<Rule> = listOf(TestRule("constructed-configurable:configurable", "CONFIGURABLE"))
}

private class ConfigurableMismatchTestPack : ConfigurableRulePack {
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

class HostPipelineTest {
    private fun pipeline(
        language: String,
        packs: List<RulePack>,
    ): HostPipeline =
        HostPipeline(
            loader = RulePackLoader(onFailure = {}),
            constructedPacks = { if (it == language) packs else emptyList() },
        )

    @Test
    fun constructedPacksAreValidatedAndDriveTheIntentVocabulary() {
        val pack =
            TestPack(
                "constructed-validation",
                setOf("testlang"),
                entries = listOf(TestRule("constructed-validation:validation", "VALIDATION")),
            )
        val pipeline = pipeline("testlang", listOf(pack))

        assertEquals(
            listOf("constructed-validation"),
            pipeline.packs("testlang").map { it.id },
        )
        assertEquals(
            listOf("UNTAGGED", "VALIDATION"),
            pipeline.intents(listOf("testlang")),
        )
    }

    @Test
    fun invalidConstructedPacksAreRejectedByTheLoader() {
        val pack =
            TestPack(
                "constructed-mismatch",
                setOf("testlang"),
                apiVersion = CINT_API_VERSION + 1,
                entries = listOf(TestRule("constructed-mismatch:validation", "VALIDATION")),
            )
        val pipeline = pipeline("testlang", listOf(pack))

        assertTrue(pipeline.packs("testlang").isEmpty())
        assertTrue(pipeline.plan("testlang").strata.isEmpty())
        assertEquals(listOf("UNTAGGED"), pipeline.intents(listOf("testlang")))
    }

    @Test
    fun constructedPacksMayDependOnDiscoveredPacks() {
        val pack =
            TestPack(
                "constructed-dependent",
                setOf("kotlin"),
                entries = listOf(TestRule("constructed-dependent:reader", "DEPENDENT", setOf("LOGGING"))),
            )
        val pipeline = pipeline("kotlin", listOf(pack))

        val ruleIds =
            pipeline
                .plan("kotlin")
                .strata
                .flatten()
                .map { it.id }
        assertTrue("fixture-good:logging" in ruleIds)
        assertTrue("constructed-dependent:reader" in ruleIds)
    }

    @Test
    fun constructedPacksDoNotDisplaceADiscoveredOwner() {
        val pack =
            TestPack(
                "constructed-logging",
                setOf("kotlin"),
                entries = listOf(TestRule("constructed-logging:logging", "LOGGING")),
            )
        val pipeline = pipeline("kotlin", listOf(pack))

        val ids = pipeline.packs("kotlin").map { it.id }
        assertTrue("fixture-good" in ids)
        assertFalse("constructed-logging" in ids)
        assertEquals(listOf("LOGGING", "UNTAGGED"), pipeline.intents(listOf("kotlin")))
    }

    @Test
    fun serviceValidationPackJoinsSelectionAndVocabulary() {
        val directory = Files.createTempDirectory("cint-service-validation")
        val service = directory.resolve("META-INF/services/${RulePack::class.java.name}")
        Files.createDirectories(service.parent)
        Files.writeString(service, ServiceValidationPack::class.java.name)

        try {
            val classLoader =
                URLClassLoader(
                    arrayOf(directory.toUri().toURL()),
                    HostPipelineTest::class.java.classLoader,
                )
            val pipeline = HostPipeline(loader = RulePackLoader(classLoader, onFailure = {}))

            assertEquals(listOf("service-validation"), pipeline.packs("servicelang").map { it.id })
            assertEquals(listOf("UNTAGGED", "VALIDATION"), pipeline.intents(listOf("servicelang")))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun projectConfigIsAppliedToConstructedPacks() {
        ConfigurableTestPack.reset()
        val project =
            ProjectConfig(
                packs = mapOf("constructed-configurable" to mapOf("calls" to listOf("audit"))),
            )
        val pipeline =
            HostPipeline(
                loader = RulePackLoader(onFailure = {}),
                constructedPacks = { language ->
                    if (language == "testlang") listOf(ConfigurableTestPack()) else emptyList()
                },
                project = project,
                knownPackIds = setOf("constructed-configurable"),
            )

        assertEquals(listOf("constructed-configurable"), pipeline.packs("testlang").map { it.id })
        assertEquals(setOf("audit"), ConfigurableTestPack.lastCalls)
        assertEquals(listOf("CONFIGURABLE", "UNTAGGED"), pipeline.intents(listOf("testlang")))
        assertTrue(pipeline.failures.isEmpty())
    }

    @Test
    fun constructedPackApiVersionIsCheckedBeforeConfiguration() {
        ConfigurableMismatchTestPack.reset()
        val project =
            ProjectConfig(
                packs =
                    mapOf(
                        "constructed-configurable-mismatch" to
                            mapOf("calls" to listOf("audit")),
                    ),
            )
        val pipeline =
            HostPipeline(
                loader = RulePackLoader(onFailure = {}),
                constructedPacks = { language ->
                    if (language == "testlang") listOf(ConfigurableMismatchTestPack()) else emptyList()
                },
                project = project,
                knownPackIds = setOf("constructed-configurable-mismatch"),
            )

        assertTrue(pipeline.packs("testlang").isEmpty())
        assertEquals(0, ConfigurableMismatchTestPack.configureCalls)
        assertTrue(
            pipeline.failures.any {
                it.kind == RulePackLoadFailureKind.INCOMPATIBLE_API_VERSION &&
                    it.packId == "constructed-configurable-mismatch"
            },
        )
    }

    @Test
    fun constructedPackConfigFailureIsReported() {
        ConfigurableTestPack.reset()
        val project =
            ProjectConfig(
                packs = mapOf("constructed-configurable" to mapOf("bogus" to true)),
            )
        val pipeline =
            HostPipeline(
                loader = RulePackLoader(onFailure = {}),
                constructedPacks = { language ->
                    if (language == "testlang") listOf(ConfigurableTestPack()) else emptyList()
                },
                project = project,
                knownPackIds = setOf("constructed-configurable"),
            )

        assertTrue(pipeline.packs("testlang").isEmpty())
        assertTrue(
            pipeline.failures.any {
                it.kind == RulePackLoadFailureKind.INVALID_PACK_CONFIG &&
                    it.packId == "constructed-configurable"
            },
        )
        assertEquals(listOf("UNTAGGED"), pipeline.intents(listOf("testlang")))
    }

    @Test
    fun vocabularyIsTheUnionOfSelectedPlans() {
        val alpha =
            TestPack(
                "constructed-alpha",
                setOf("alpha"),
                entries = listOf(TestRule("constructed-alpha:alpha", "ALPHA")),
            )
        val beta =
            TestPack(
                "constructed-beta",
                setOf("beta"),
                entries = listOf(TestRule("constructed-beta:beta", "BETA")),
            )
        val pipeline =
            HostPipeline(
                loader = RulePackLoader(onFailure = {}),
                constructedPacks = { language ->
                    when (language) {
                        "alpha" -> listOf(alpha)
                        "beta" -> listOf(beta)
                        else -> emptyList()
                    }
                },
            )

        assertEquals(listOf("ALPHA", "BETA", "UNTAGGED"), pipeline.intents(listOf("alpha", "beta")))
    }
}
