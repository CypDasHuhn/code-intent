package dev.cint.rulepacks.intents.service

import dev.cint.api.RulePackConfigException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ServiceConfigTest {
    @Test
    fun defaultsApplyWithoutOverrides() {
        val config = ServiceConfig.from(emptyMap())

        assertEquals(ServiceConfig.defaults(), config.languages)
        assertTrue("service" in config.forLanguage("kotlin").serviceNames)
        assertTrue("HttpClient" in config.forLanguage("kotlin").initTypes)
        assertTrue("Factory" in config.forLanguage("kotlin").initSuffixes)
        assertTrue("Factory" !in config.forLanguage("kotlin").serviceSuffixes)
        assertTrue("container" in config.forLanguage("kotlin").diQualifiers)
        assertTrue("find" !in config.forLanguage("kotlin").initMethods)
    }

    @Test
    fun overridesReplaceOnlyTheNamedValues() {
        val config =
            ServiceConfig.from(
                mapOf(
                    "kotlin" to
                        mapOf(
                            "serviceNames" to listOf("primary"),
                            "initCalls" to listOf("openPool"),
                        ),
                ),
            )

        val kotlin = config.forLanguage("kotlin")
        assertEquals(setOf("primary"), kotlin.serviceNames)
        assertEquals(setOf("openPool"), kotlin.initCalls)
        assertTrue("HttpClient" in kotlin.initTypes)
        assertTrue("service" in config.forLanguage("csharp").serviceNames)
    }

    @Test
    fun overridesPreserveFieldsNotNamedInTheOverride() {
        val base =
            ServiceConfig.from(
                mapOf(
                    "kotlin" to
                        mapOf(
                            "serviceNames" to listOf("primary"),
                            "delegationCalls" to listOf("forwardPrimary"),
                        ),
                ),
            )
        val configured =
            base.withOverrides(
                mapOf(
                    "kotlin" to mapOf("delegationCalls" to listOf("forwardSecondary")),
                ),
            )

        val kotlin = configured.forLanguage("kotlin")
        assertEquals(setOf("primary"), kotlin.serviceNames)
        assertEquals(setOf("forwardSecondary"), kotlin.delegationCalls)
    }

    @Test
    fun additiveOverridesUnionRepeatedFields() {
        val base =
            ServiceConfig.from(
                mapOf(
                    "kotlin" to
                        mapOf(
                            "serviceNames" to listOf("primary"),
                            "initCalls" to listOf("openPool"),
                        ),
                ),
            )
        val config =
            base.withAdditiveOverrides(
                mapOf(
                    "kotlin" to
                        mapOf(
                            "serviceNames" to listOf("secondary"),
                            "initCalls" to listOf("openClient"),
                        ),
                ),
            )

        val kotlin = config.forLanguage("kotlin")
        assertEquals(setOf("primary", "secondary"), kotlin.serviceNames)
        assertEquals(setOf("openPool", "openClient"), kotlin.initCalls)
    }

    @Test
    fun unknownLanguageIsRejected() {
        val error =
            assertFailsWith<RulePackConfigException> {
                ServiceConfig.from(mapOf("vue" to mapOf("serviceNames" to listOf("service"))))
            }

        assertTrue(error.message.orEmpty().contains("service.vue: unknown language"))
    }

    @Test
    fun unknownKeyIsRejected() {
        val error =
            assertFailsWith<RulePackConfigException> {
                ServiceConfig.from(mapOf("kotlin" to mapOf("bogus" to listOf("x"))))
            }

        assertTrue(error.message.orEmpty().contains("service.kotlin.bogus: unknown key"))
    }

    @Test
    fun wrongValueTypeIsRejected() {
        val error =
            assertFailsWith<RulePackConfigException> {
                ServiceConfig.from(mapOf("kotlin" to mapOf("serviceNames" to mapOf("nested" to true))))
            }

        assertTrue(error.message.orEmpty().contains("service.kotlin.serviceNames"))
    }

    @Test
    fun packConfigureReturnsAServicePackForReplaceAndAdditiveConfiguration() {
        val configured =
            ServiceRulePack().configure(
                mapOf("kotlin" to mapOf("serviceNames" to listOf("primary"))),
            )
        assertEquals("service", configured.id)
        assertEquals(setOf("kotlin", "javascript", "typescript", "csharp"), configured.languages)

        val additive =
            ServiceRulePack().configureAdditive(
                mapOf("kotlin" to mapOf("serviceNames" to listOf("secondary"))),
            )
        assertEquals("service", additive.id)
        assertEquals(setOf("kotlin", "javascript", "typescript", "csharp"), additive.languages)
    }
}
