package dev.cint.rulepacks

import dev.cint.api.ConfigFieldScope
import dev.cint.api.RulePackConfigException
import dev.cint.api.configDescriptor
import dev.cint.rulepacks.intents.dataaccess.DATA_ACCESS_PACK_ID
import dev.cint.rulepacks.intents.dataaccess.DataAccessRulePack
import dev.cint.rulepacks.intents.dataaccess.LOOKUP_INTENT
import dev.cint.rulepacks.intents.dataaccess.LOOKUP_RULE_ID
import dev.cint.rulepacks.intents.dataaccess.SEARCH_INTENT
import dev.cint.rulepacks.intents.dataaccess.SEARCH_RULE_ID
import dev.cint.rulepacks.intents.dataaccess.config.DataAccessConfig
import dev.cint.rulepacks.intents.mutation.DURABLE_COMPOUND_MUTATION_CALLS
import dev.cint.rulepacks.intents.mutation.LOCAL_ATOMIC_MUTATION_CALLS
import dev.cint.rulepacks.intents.mutation.LOCAL_CANDIDATE_EXACT
import dev.cint.rulepacks.intents.mutation.MutationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DataAccessConfigTest {
    @Test
    fun defaultsApplyWithoutOverrides() {
        val config = DataAccessConfig.from(emptyMap())

        assertEquals(DataAccessConfig.defaults(), config.languages)
        val kotlin = config.forLanguage("kotlin")
        assertTrue("findAll" in kotlin.searchCalls)
        assertTrue("findBy" in kotlin.searchPrefixes)
        assertTrue("getBy" in kotlin.searchPrefixes)
        assertTrue("loadBy" in kotlin.searchPrefixes)
        assertTrue("repository" in kotlin.searchQualifiers)
        assertTrue("getValue" in kotlin.lookupCalls)
        assertTrue("valueOf" in kotlin.enumCalls)
        assertTrue("valueOf" !in kotlin.lookupCalls)
        assertTrue("Status" in kotlin.enumTypeSuffixes)
        assertTrue("String" in kotlin.enumExclusions)
        assertTrue("statusById" in kotlin.knownMaps)
        assertTrue("ById" in kotlin.knownMapSuffixes)
        assertEquals(
            setOf(
                "findOneAndUpdate",
                "findOneAndDelete",
                "findOneAndReplace",
                "findOneAndIncrement",
                "findOneAndDecrement",
                "findOneAndModify",
                "findAndModify",
                "getAndUpdate",
                "getAndIncrement",
                "getAndDecrement",
            ),
            kotlin.compoundMutationCalls,
        )
        assertTrue("getPersist" !in kotlin.compoundMutationCalls)
        assertTrue(kotlin.boundaryPrefixes.isEmpty())
        assertEquals(setOf("indexing_expression", "indexing_suffix"), kotlin.indexKinds)
        assertEquals(setOf("element_access_expression"), config.forLanguage("csharp").indexKinds)
    }

    @Test
    fun overridesReplaceOnlyNamedValues() {
        val config =
            DataAccessConfig.from(
                mapOf(
                    "kotlin" to
                        mapOf(
                            "searchCalls" to listOf("scanAll"),
                            "knownMaps" to listOf("myTable"),
                        ),
                ),
            )

        val kotlin = config.forLanguage("kotlin")
        assertEquals(setOf("scanAll"), kotlin.searchCalls)
        assertEquals(setOf("myTable"), kotlin.knownMaps)
        assertTrue("findBy" in kotlin.searchPrefixes)
        assertTrue("statusById" in config.forLanguage("javascript").knownMaps)
    }

    @Test
    fun additiveOverridesUnionSets() {
        val config =
            DataAccessConfig.SCHEMA.decodeAdditive(
                DataAccessConfig(),
                mapOf(
                    "kotlin" to
                        mapOf(
                            "searchCalls" to listOf("scanAll"),
                            "knownMaps" to listOf("myTable"),
                        ),
                ),
                DATA_ACCESS_PACK_ID,
            )

        val kotlin = config.forLanguage("kotlin")
        assertTrue("findAll" in kotlin.searchCalls)
        assertTrue("scanAll" in kotlin.searchCalls)
        assertTrue("statusById" in kotlin.knownMaps)
        assertTrue("myTable" in kotlin.knownMaps)
    }

    @Test
    fun additiveCompoundMutationExclusionsUnionWithDefaults() {
        val config =
            DataAccessConfig.SCHEMA.decodeAdditive(
                DataAccessConfig(),
                mapOf("kotlin" to mapOf("compoundMutationCalls" to listOf("getPersist"))),
                DATA_ACCESS_PACK_ID,
            )

        val kotlin = config.forLanguage("kotlin")
        assertTrue("findOneAndUpdate" in kotlin.compoundMutationCalls)
        assertTrue("getPersist" in kotlin.compoundMutationCalls)
    }

    @Test
    fun defaultCompoundMutationCallsAlignWithMutationVocabularies() {
        val dataAccess = DataAccessConfig.from(emptyMap())
        val mutation = MutationConfig.from(emptyMap())
        val expected = DURABLE_COMPOUND_MUTATION_CALLS + LOCAL_ATOMIC_MUTATION_CALLS

        for (language in listOf("kotlin", "javascript", "typescript", "csharp")) {
            val compounds = dataAccess.forLanguage(language).compoundMutationCalls
            val persistence = mutation.forLanguage(language).persistenceCalls

            assertEquals(expected, compounds, "$language compound mutations")
            assertTrue(
                (DURABLE_COMPOUND_MUTATION_CALLS - persistence).isEmpty(),
                "$language missing durable compound mutations",
            )
            assertTrue(
                LOCAL_ATOMIC_MUTATION_CALLS.all { it in LOCAL_CANDIDATE_EXACT },
                "$language missing local atomic candidates",
            )
            assertTrue(
                (LOCAL_ATOMIC_MUTATION_CALLS intersect persistence).isEmpty(),
                "$language local atomic calls are persistence",
            )
        }
    }

    @Test
    fun packConfigureAdditiveKeepsDefaults() {
        val pack =
            DataAccessRulePack().configureAdditive(
                mapOf("kotlin" to mapOf("searchCalls" to listOf("scanAll"))),
            )

        assertEquals(DATA_ACCESS_PACK_ID, pack.id)
        assertEquals(setOf(SEARCH_RULE_ID, LOOKUP_RULE_ID), pack.rules().map { it.id }.toSet())
    }

    @Test
    fun unknownLanguageIsRejected() {
        val error =
            assertFailsWith<RulePackConfigException> {
                DataAccessConfig.from(mapOf("vue" to mapOf("searchCalls" to listOf("findAll"))))
            }

        assertTrue(error.message.orEmpty().contains("data-access.vue: unknown language"))
    }

    @Test
    fun unknownKeyIsRejected() {
        val error =
            assertFailsWith<RulePackConfigException> {
                DataAccessConfig.from(mapOf("kotlin" to mapOf("bogus" to listOf("x"))))
            }

        assertTrue(error.message.orEmpty().contains("data-access.kotlin.bogus: unknown key"))
    }

    @Test
    fun wrongValueTypeIsRejected() {
        val error =
            assertFailsWith<RulePackConfigException> {
                DataAccessConfig.from(mapOf("kotlin" to mapOf("searchCalls" to mapOf("nested" to true))))
            }

        assertTrue(error.message.orEmpty().contains("data-access.kotlin.searchCalls"))
    }

    @Test
    fun descriptorExposesBothRulesAndLanguageFields() {
        val descriptor = DataAccessRulePack().configDescriptor()

        assertEquals(DATA_ACCESS_PACK_ID, descriptor.packId)
        assertEquals(setOf(SEARCH_RULE_ID, LOOKUP_RULE_ID), descriptor.rules.map { it.id }.toSet())
        assertEquals(setOf(SEARCH_INTENT, LOOKUP_INTENT), descriptor.rules.map { it.owns }.toSet())
        assertTrue(
            descriptor.fields.any {
                it.key == "searchCalls" && it.scope == ConfigFieldScope.LANGUAGE && "kotlin" in it.languages
            },
        )
        assertTrue(descriptor.fields.any { it.key == "knownMaps" && it.scope == ConfigFieldScope.LANGUAGE })
    }

    @Test
    fun enumAdditiveOverridesUnionSets() {
        val config =
            DataAccessConfig.SCHEMA.decodeAdditive(
                DataAccessConfig(),
                mapOf(
                    "kotlin" to
                        mapOf(
                            "enumCalls" to listOf("parseEnum"),
                            "enumTypes" to listOf("com.acme.Status"),
                        ),
                ),
                DATA_ACCESS_PACK_ID,
            )

        val kotlin = config.forLanguage("kotlin")
        assertTrue("valueOf" in kotlin.enumCalls)
        assertTrue("parseEnum" in kotlin.enumCalls)
        assertEquals(setOf("com.acme.Status"), kotlin.enumTypes)
    }
}
