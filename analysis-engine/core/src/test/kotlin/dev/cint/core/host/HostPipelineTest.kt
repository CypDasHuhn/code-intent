package dev.cint.core.host

import dev.cint.api.CINT_API_VERSION
import dev.cint.api.Emit
import dev.cint.api.Intent
import dev.cint.api.Rule
import dev.cint.api.RulePack
import dev.cint.api.View
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

class ServiceValidationPack : RulePack {
    override val id: String = "service-validation"
    override val languages: Set<String> = setOf("servicelang")
    override val apiVersion: Int = CINT_API_VERSION

    override fun rules(): List<Rule> =
        listOf(TestRule("service-validation:validation", "VALIDATION"))
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

        val ruleIds = pipeline.plan("kotlin").strata.flatten().map { it.id }
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
