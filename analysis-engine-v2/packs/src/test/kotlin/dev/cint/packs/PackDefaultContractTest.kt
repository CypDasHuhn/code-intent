package dev.cint.packs

import dev.cint.contracts.data.Provenance
import dev.cint.contracts.data.Status
import dev.cint.contracts.knowledge.FunctionAnnotation
import dev.cint.contracts.knowledge.KnowledgeChange
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PackDefaultContractTest {
    @Test
    fun defaultsConvertToFunctionAnnotationsWithoutPackSpecificConfig() {
        val source = BundledRulePackSource()
        for (language in TestLanguages.ALL) {
            val defaults = source.defaults(language).getOrThrow()
            assertTrue(defaults.isNotEmpty())
            for (default in defaults) {
                val annotation: FunctionAnnotation = default.toFunctionAnnotation()
                assertEquals(default.selector, annotation.selector)
                assertEquals(default.intents, annotation.intents)
                assertEquals(Provenance.PACK_DEFAULT, annotation.provenance)
                assertEquals(Status.SUGGESTED, annotation.status)
                assertEquals(default.confidence, annotation.confidence)
            }
        }
    }

    @Test
    fun defaultsRoundTripThroughTheMaterializeDefaultsContract() {
        val defaults = BundledRulePackSource().defaults(TestLanguages.KOTLIN).getOrThrow()
        val change: KnowledgeChange = KnowledgeChange.MaterializeDefaults(defaults)
        val json = Json { encodeDefaults = true }
        val decoded = json.decodeFromString<KnowledgeChange>(json.encodeToString(change))

        val materialized = assertIs<KnowledgeChange.MaterializeDefaults>(decoded)
        assertEquals(defaults, materialized.defaults)
        assertTrue(materialized.defaults.all { it.provenance == Provenance.PACK_DEFAULT })
        assertTrue(materialized.defaults.all { it.status == Status.SUGGESTED })
    }

}
