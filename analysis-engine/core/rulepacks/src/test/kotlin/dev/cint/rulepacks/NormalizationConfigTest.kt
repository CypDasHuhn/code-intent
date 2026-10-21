package dev.cint.rulepacks

import dev.cint.api.RulePackConfigException
import dev.cint.rulepacks.intents.normalization.NormalizationConfig
import dev.cint.rulepacks.intents.normalization.NormalizationRulePack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NormalizationConfigTest {
    @Test
    fun defaultsApplyWithoutOverrides() {
        val config = NormalizationConfig.from(emptyMap())

        assertEquals(NormalizationConfig.defaults(), config.languages)
        assertTrue("trim" in config.forLanguage("kotlin").calls)
        assertTrue("normalize" in config.forLanguage("kotlin").instanceCalls)
        assertTrue("LocalDate" in config.forLanguage("kotlin").qualifiers)
        assertTrue("JSON" in config.forLanguage("javascript").qualifiers)
        assertTrue("normalize" in config.forLanguage("javascript").instanceCalls)
        assertTrue("Trim" in config.forLanguage("csharp").calls)
        assertTrue("Normalize" in config.forLanguage("csharp").instanceCalls)
        assertEquals(config.forLanguage("javascript"), config.forLanguage("typescript"))
    }

    @Test
    fun overridesReplaceOnlyTheNamedValues() {
        val config =
            NormalizationConfig.from(
                mapOf(
                    "kotlin" to
                        mapOf(
                            "calls" to listOf("audit"),
                            "prefixes" to listOf("canon"),
                            "candidatePrefixes" to listOf("render"),
                        ),
                ),
            )

        val kotlin = config.forLanguage("kotlin")
        assertEquals(setOf("audit"), kotlin.calls)
        assertEquals(listOf("canon"), kotlin.prefixes)
        assertEquals(listOf("render"), kotlin.candidatePrefixes)
        assertEquals(NormalizationConfig.defaults().getValue("kotlin").qualifiers, kotlin.qualifiers)
        assertEquals(NormalizationConfig.defaults().getValue("javascript"), config.forLanguage("javascript"))
    }

    @Test
    fun unknownLanguageIsRejected() {
        val error =
            assertFailsWith<RulePackConfigException> {
                NormalizationConfig.from(mapOf("vue" to mapOf("calls" to listOf("trim"))))
            }

        assertTrue(error.message.orEmpty().contains("normalization.vue: unknown language"))
    }

    @Test
    fun unknownKeyIsRejected() {
        val error =
            assertFailsWith<RulePackConfigException> {
                NormalizationConfig.from(mapOf("kotlin" to mapOf("bogus" to listOf("x"))))
            }

        assertTrue(error.message.orEmpty().contains("normalization.kotlin.bogus: unknown key"))
    }

    @Test
    fun wrongValueTypeIsRejected() {
        val error =
            assertFailsWith<RulePackConfigException> {
                NormalizationConfig.from(mapOf("kotlin" to mapOf("calls" to mapOf("nested" to true))))
            }

        assertTrue(error.message.orEmpty().contains("normalization.kotlin.calls"))
    }

    @Test
    fun packConfigureRejectsUnknownKeysAndKeepsIdentity() {
        val unknown =
            assertFailsWith<RulePackConfigException> {
                NormalizationRulePack().configure(mapOf("kotlin" to mapOf("bogus" to true)))
            }
        assertTrue(unknown.message.orEmpty().contains("normalization.kotlin.bogus: unknown key"))

        val pack = NormalizationRulePack().configure(mapOf("kotlin" to mapOf("calls" to listOf("audit"))))
        assertEquals("normalization", pack.id)
        assertEquals(setOf("kotlin", "javascript", "typescript", "csharp"), pack.languages)
    }
}
