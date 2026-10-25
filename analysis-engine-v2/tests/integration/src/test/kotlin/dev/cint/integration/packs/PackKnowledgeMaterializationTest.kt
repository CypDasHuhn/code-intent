package dev.cint.integration.packs

import dev.cint.contracts.data.Confidence
import dev.cint.contracts.data.Intents
import dev.cint.contracts.data.Provenance
import dev.cint.contracts.data.Status
import dev.cint.contracts.knowledge.KnowledgeSnapshot
import dev.cint.knowledge.InMemoryKnowledgeStore
import dev.cint.packs.BundledRulePackSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackKnowledgeMaterializationTest {
    @Test
    fun materializesPackDefaultsWithProvenanceAndPackOrigin() {
        val defaults = BundledRulePackSource().defaults("kotlin").getOrThrow()
        assertTrue(defaults.isNotEmpty())

        val store = InMemoryKnowledgeStore.empty()
        assertTrue(store.materializeDefaults(defaults).isSuccess)
        assertTrue(store.isMaterialized)

        val annotations = store.functionAnnotations().getOrThrow()
        assertTrue(annotations.isNotEmpty())
        assertTrue(annotations.all { it.provenance == Provenance.PACK_DEFAULT })
        assertTrue(annotations.all { it.status == Status.SUGGESTED })

        val defaultsBySelector = defaults.groupBy { it.selector.identityKey }
        assertEquals(defaultsBySelector.size, annotations.size)
        for (annotation in annotations) {
            val candidates = defaultsBySelector.getValue(annotation.selector.identityKey)
            val origin = assertNotNull(store.packOrigin(annotation.selector).getOrThrow())
            assertTrue(
                candidates.any { it.packId == origin.packId && it.packVersion == origin.packVersion },
                "unexpected pack origin $origin for ${annotation.selector}",
            )
        }
    }

    @Test
    fun materializationDoesNotOverwriteExistingKnowledge() {
        val defaults = BundledRulePackSource().defaults("kotlin").getOrThrow()
        val existingDefault = defaults.first()
        val existing =
            existingDefault.toFunctionAnnotation().copy(
                intents = setOf(Intents.USER_FEEDBACK),
                provenance = Provenance.USER,
                confidence = Confidence.HIGH,
                status = Status.ACCEPTED,
            )
        val store =
            InMemoryKnowledgeStore.fromSnapshot(
                KnowledgeSnapshot(
                    functionAnnotations = listOf(existing),
                    spanOverrides = emptyList(),
                    patternHypotheses = emptyList(),
                    timestamp = 1,
                ),
                exists = false,
            ).getOrThrow()

        assertTrue(store.materializeDefaults(defaults).isSuccess)

        val annotations = store.functionAnnotations().getOrThrow()
        val stored = annotations.first { it.selector == existing.selector }
        assertEquals(existing.intents, stored.intents)
        assertEquals(Provenance.USER, stored.provenance)
        assertEquals(Status.ACCEPTED, stored.status)
        assertNull(store.packOrigin(existing.selector).getOrThrow())
        assertTrue(annotations.size > 1)
    }

    @Test
    fun materializationDoesNotOverwriteAnExistingStore() {
        val defaults = BundledRulePackSource().defaults("kotlin").getOrThrow()
        val store = InMemoryKnowledgeStore.existing()

        assertTrue(store.materializeDefaults(defaults).isSuccess)

        assertTrue(store.isMaterialized)
        assertTrue(store.functionAnnotations().getOrThrow().isEmpty())
    }
}
