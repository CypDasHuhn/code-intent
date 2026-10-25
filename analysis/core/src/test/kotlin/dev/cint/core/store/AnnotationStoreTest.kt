package dev.cint.core.store

import dev.cint.contracts.data.Intents
import dev.cint.contracts.data.Role
import dev.cint.core.support.contribution
import dev.cint.core.support.node
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnnotationStoreTest {
    private val span = node("n", "identifier", 0, 10)

    @Test
    fun differentRulesOnOneSpanUnionIntentsAndProvenance() {
        val store = AnnotationStore()
        store.replaceRule("rule:a", listOf(contribution("rule:a", Intents.LOGGING, span)))
        store.replaceRule("rule:b", listOf(contribution("rule:b", Intents.LOOKUP, span)))

        val annotation = store.annotations().single()

        assertEquals(setOf(Intents.LOGGING, Intents.LOOKUP), annotation.intents)
        assertEquals(setOf("rule:a", "rule:b"), annotation.provenance.map { it.rule }.toSet())
    }

    @Test
    fun replacingARuleLeavesNoStaleFragments() {
        val store = AnnotationStore()
        val second = node("m", "identifier", 20, 30)
        store.replaceRule(
            "rule:a",
            listOf(
                contribution("rule:a", Intents.LOGGING, span),
                contribution("rule:a", Intents.LOGGING, second),
            ),
        )

        assertTrue(store.replaceRule("rule:a", listOf(contribution("rule:a", Intents.LOGGING, span))))

        assertEquals(listOf(span.startByte to span.endByte), store.annotations().map { it.startByte to it.endByte })
    }

    @Test
    fun oneRuleCannotRemoveAnotherRulesContribution() {
        val store = AnnotationStore()
        store.replaceRule("rule:a", listOf(contribution("rule:a", Intents.LOGGING, span)))
        store.replaceRule("rule:b", listOf(contribution("rule:b", Intents.LOOKUP, span)))

        store.replaceRule("rule:a", emptyList())

        val annotation = store.annotations().single()
        assertEquals(setOf(Intents.LOOKUP), annotation.intents)
        assertEquals(listOf("rule:b"), annotation.provenance.map { it.rule })
    }

    @Test
    fun replacingProvenanceReplacesThePreviousIntentSet() {
        val store = AnnotationStore()
        store.replaceRule("rule:a", listOf(contribution("rule:a", Intents.LOGGING, span)))

        assertTrue(store.replaceRule("rule:a", listOf(contribution("rule:a", Intents.LOOKUP, span))))

        val annotation = store.annotations().single()
        assertEquals(setOf(Intents.LOOKUP), annotation.intents)
        assertEquals(Role.SOURCE_INTENT, annotation.role)
        assertEquals(1, annotation.provenance.size)
    }

    @Test
    fun identicalReplacementIsNotAChange() {
        val store = AnnotationStore()
        val contributions = listOf(contribution("rule:a", Intents.LOGGING, span))
        assertTrue(store.replaceRule("rule:a", contributions))
        val revision = store.revision()

        assertFalse(store.replaceRule("rule:a", contributions))
        assertEquals(revision, store.revision())
    }
}
