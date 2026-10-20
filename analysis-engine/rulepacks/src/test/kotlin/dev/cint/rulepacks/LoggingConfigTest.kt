package dev.cint.rulepacks

import dev.cint.api.RulePackConfigException
import dev.cint.rulepacks.logging.LoggingConfig
import dev.cint.rulepacks.logging.LoggingRulePack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LoggingConfigTest {
    @Test
    fun defaultsApplyWithoutOverrides() {
        val config = LoggingConfig.from(emptyMap())

        assertEquals(LoggingConfig.defaults(), config.languages)
        assertEquals(setOf("println", "print"), config.forLanguage("kotlin").printCalls)
        assertEquals(setOf("console"), config.forLanguage("javascript").printQualifiers)
    }

    @Test
    fun overridesReplaceOnlyTheNamedValues() {
        val config =
            LoggingConfig.from(
                mapOf(
                    "kotlin" to
                        mapOf(
                            "printCalls" to listOf("audit"),
                            "guardNames" to listOf("auditEnabled"),
                        ),
                ),
            )

        val kotlin = config.forLanguage("kotlin")
        assertEquals(setOf("audit"), kotlin.printCalls)
        assertEquals(setOf("auditEnabled"), kotlin.guardNames)
        assertEquals(setOf("System.out", "System.err"), kotlin.printQualifiers)
        assertEquals(setOf("info", "debug"), kotlin.methods.intersect(setOf("info", "debug")))
        assertEquals(setOf("log", "error", "warn", "info", "debug", "trace"), config.forLanguage("javascript").printCalls)
    }

    @Test
    fun unknownLanguageIsRejected() {
        val error =
            assertFailsWith<RulePackConfigException> {
                LoggingConfig.from(mapOf("vue" to mapOf("printCalls" to listOf("log"))))
            }

        assertTrue(error.message.orEmpty().contains("logging.vue: unknown language"))
    }

    @Test
    fun unknownKeyIsRejected() {
        val error =
            assertFailsWith<RulePackConfigException> {
                LoggingConfig.from(mapOf("kotlin" to mapOf("bogus" to listOf("x"))))
            }

        assertTrue(error.message.orEmpty().contains("logging.kotlin.bogus: unknown key"))
    }

    @Test
    fun wrongValueTypeIsRejected() {
        val error =
            assertFailsWith<RulePackConfigException> {
                LoggingConfig.from(mapOf("kotlin" to mapOf("printCalls" to mapOf("nested" to true))))
            }

        assertTrue(error.message.orEmpty().contains("logging.kotlin.printCalls"))
    }

    @Test
    fun packConfigureReturnsTheTypedOverride() {
        val pack = LoggingRulePack().configure(mapOf("kotlin" to mapOf("printCalls" to listOf("audit"))))

        assertEquals("logging", pack.id)
        assertEquals(setOf("kotlin", "javascript", "typescript", "csharp"), pack.languages)
    }
}
