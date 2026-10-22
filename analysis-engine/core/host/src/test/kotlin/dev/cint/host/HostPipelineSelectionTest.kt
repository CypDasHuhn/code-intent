package dev.cint.host

import dev.cint.core.config.ProjectConfig
import dev.cint.core.runtime.rulepack.RulePackLoadFailureKind
import dev.cint.core.runtime.rulepack.RulePackLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HostPipelineSelectionTest {
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
    fun vocabularyIsTheUnionOfSelectedPlans() {
        withService(AlphaPack::class.java, BetaPack::class.java) { pipeline ->
            assertEquals(listOf("alpha"), pipeline.packs("alpha").map { it.id })
            assertEquals(listOf("beta"), pipeline.packs("beta").map { it.id })
            assertEquals(listOf("ALPHA", "BETA", "UNTAGGED"), pipeline.intents(listOf("alpha", "beta")))
            assertFalse("LOGGING" in pipeline.intents(listOf("alpha", "beta")))
        }
    }
}
