package dev.cint.rulepacks

import dev.cint.api.RulePackConfigException
import dev.cint.rulepacks.intents.mutation.MutationConfig
import dev.cint.rulepacks.intents.mutation.MutationRulePack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MutationConfigTest {
    @Test
    fun defaultsApplyWithoutOverrides() {
        val config = MutationConfig.from(emptyMap())

        assertEquals(MutationConfig.defaults(), config.languages)
        assertTrue("save" in config.forLanguage("kotlin").persistenceCalls)
        assertTrue("save" in config.forLanguage("javascript").persistenceCalls)
        assertTrue("Save" in config.forLanguage("csharp").persistenceCalls)
        assertTrue("add" in config.forLanguage("kotlin").mutableOperations)
    }

    @Test
    fun defaultsExcludeInMemoryReceiversAndIncludeDurableWrites() {
        val config = MutationConfig.from(emptyMap()).forLanguage("kotlin")

        assertTrue("put" in config.persistenceCalls)
        assertTrue("putObject" in config.persistenceCalls)
        assertTrue("File.WriteAllTextAsync" in config.persistencePaths)
        assertTrue("stream" !in config.persistenceQualifiers)
        assertTrue("channel" !in config.persistenceQualifiers)
        assertTrue("writer" !in config.persistenceQualifiers)
        assertTrue("context" !in config.persistenceQualifiers)
        assertTrue("session" !in config.persistenceQualifiers)
        assertTrue("table" !in config.persistenceQualifiers)
        assertTrue("Stream" !in config.persistenceTypes)
        assertTrue("OutputStream" !in config.persistenceTypes)
        assertTrue("Writer" !in config.persistenceTypes)
        assertTrue("Collection" !in config.persistenceTypes)
        assertTrue("Table" !in config.persistenceTypes)
    }

    @Test
    fun overridesReplaceOnlyTheNamedValues() {
        val config =
            MutationConfig.from(
                mapOf(
                    "kotlin" to
                        mapOf(
                            "persistenceCalls" to listOf("persistRecord"),
                            "mutableOperations" to listOf("frobnicate"),
                            "mutableReceiverNames" to listOf("widget"),
                            "ignoreLoopCounters" to false,
                        ),
                ),
            )

        val kotlin = config.forLanguage("kotlin")
        assertEquals(setOf("persistRecord"), kotlin.persistenceCalls)
        assertEquals(setOf("frobnicate"), kotlin.mutableOperations)
        assertEquals(false, kotlin.ignoreLoopCounters)
        assertTrue("repository" in kotlin.persistenceQualifiers)
        assertTrue("save" in config.forLanguage("javascript").persistenceCalls)
    }

    @Test
    fun unknownLanguageIsRejected() {
        val error =
            assertFailsWith<RulePackConfigException> {
                MutationConfig.from(mapOf("vue" to mapOf("persistenceCalls" to listOf("save"))))
            }

        assertTrue(error.message.orEmpty().contains("mutation.vue: unknown language"))
    }

    @Test
    fun unknownKeyIsRejected() {
        val error =
            assertFailsWith<RulePackConfigException> {
                MutationConfig.from(mapOf("kotlin" to mapOf("bogus" to listOf("x"))))
            }

        assertTrue(error.message.orEmpty().contains("mutation.kotlin.bogus: unknown key"))
    }

    @Test
    fun wrongValueTypeIsRejected() {
        val error =
            assertFailsWith<RulePackConfigException> {
                MutationConfig.from(mapOf("kotlin" to mapOf("persistenceCalls" to mapOf("nested" to true))))
            }

        assertTrue(error.message.orEmpty().contains("mutation.kotlin.persistenceCalls"))
    }

    @Test
    fun additiveOverridesUnionCollectionsAndReplaceScalars() {
        val current = MutationConfig()
        val decoded =
            MutationConfig.SCHEMA.decodeAdditive(
                current,
                mapOf(
                    "kotlin" to
                        mapOf(
                            "persistenceCalls" to listOf("persistRecord"),
                            "mutableOperations" to listOf("frobnicate"),
                            "mutableReceiverNames" to listOf("widget"),
                            "ignoreLoopCounters" to false,
                        ),
                ),
            )

        val kotlin = decoded.forLanguage("kotlin")
        assertTrue("save" in kotlin.persistenceCalls)
        assertTrue("persistRecord" in kotlin.persistenceCalls)
        assertTrue("add" in kotlin.mutableOperations)
        assertTrue("frobnicate" in kotlin.mutableOperations)
        assertTrue("widget" in kotlin.mutableReceiverNames)
        assertEquals(false, kotlin.ignoreLoopCounters)
    }

    @Test
    fun packConfigureAndAdditiveReturnTypedOverrides() {
        val pack = MutationRulePack()
        assertEquals(listOf("mutation:data-modify", "mutation:local-value-modify"), pack.rules().map { it.id })
        assertEquals(setOf("DATA_MODIFY", "LOCAL_VALUE_MODIFY"), pack.rules().map { it.owns }.toSet())

        val configured =
            pack.configure(
                mapOf("kotlin" to mapOf("persistenceCalls" to listOf("persistRecord"))),
            )
        assertEquals("mutation", configured.id)
        assertEquals(pack.languages, configured.languages)

        val additive =
            pack.configureAdditive(
                mapOf("kotlin" to mapOf("persistenceCalls" to listOf("persistRecord"))),
            )
        assertEquals("mutation", additive.id)
        assertEquals(pack.languages, additive.languages)
    }
}
