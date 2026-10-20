package dev.cint.core.facts.store

import dev.cint.api.Contribution
import dev.cint.api.Role
import dev.cint.api.Span
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AnnotationStoreTest {
    private val span = Span(1, 2, 1, 9)

    @Test
    fun mergesUnionAndKeepsBothProvenanceEntries() {
        val store = AnnotationStore()
        store.replaceRule("logging", listOf(contribution("logging", "LOGGING")))
        store.replaceRule("validation", listOf(contribution("validation", "VALIDATION")))

        val annotation = store.annotations().single()

        assertEquals(setOf("LOGGING", "VALIDATION"), annotation.intents)
        assertEquals(listOf("logging", "validation"), annotation.provenance.map { it.rule })
    }

    @Test
    fun changedOutputShapeLeavesNoStaleFragments() {
        val store = AnnotationStore()
        store.replaceRule("scaffold", listOf(contribution("scaffold", "LOGGING", 10, 16)))
        store.replaceRule("scaffold", listOf(contribution("scaffold", "LOGGING", 20, 26)))

        val annotation = store.annotations().single()

        assertEquals(20, annotation.startByte)
        assertEquals(26, annotation.endByte)
        assertEquals(1, annotation.provenance.size)
    }

    @Test
    fun changedSplitShapeReplacesTheWholePreviousContribution() {
        val store = AnnotationStore()
        store.replaceRule("scaffold", listOf(contribution("scaffold", "LOGGING", 10, 16)))
        store.replaceRule(
            "scaffold",
            listOf(
                contribution("scaffold", "LOGGING", 10, 12),
                contribution("scaffold", "LOGGING", 13, 16),
            ),
        )

        assertEquals(listOf(10 to 12, 13 to 16), store.annotations().map { it.startByte to it.endByte })

        store.replaceRule("scaffold", listOf(contribution("scaffold", "LOGGING", 10, 16)))

        assertEquals(listOf(10 to 16), store.annotations().map { it.startByte to it.endByte })
    }

    @Test
    fun oneRuleCannotShrinkAnotherContribution() {
        val store = AnnotationStore()
        store.replaceRule("logging", listOf(contribution("logging", "LOGGING")))
        store.replaceRule("validation", listOf(contribution("validation", "VALIDATION")))
        store.replaceRule("logging", emptyList())

        val annotation = store.annotations().single()

        assertEquals(setOf("VALIDATION"), annotation.intents)
        assertEquals(listOf("validation"), annotation.provenance.map { it.rule })
    }

    @Test
    fun provenanceRoundTripsEveryContributorField() {
        val logging =
            Contribution(
                rule = "logging",
                role = Role.SOURCE_INTENT,
                intents = setOf("LOGGING"),
                evidence = "log.debug",
                span = span,
                source = "treesitter",
                confidence = 0.8,
                startByte = 10,
                endByte = 16,
            )
        val validation =
            Contribution(
                rule = "validation",
                role = Role.SOURCE_INTENT,
                intents = setOf("VALIDATION"),
                evidence = "check",
                span = span,
                source = "lsp",
                confidence = 0.6,
                startByte = 10,
                endByte = 16,
            )
        val store = AnnotationStore()
        store.replaceRule("logging-rule", listOf(logging))
        store.replaceRule("validation-rule", listOf(validation))

        val annotation = store.annotations().single()

        assertEquals(listOf(logging, validation), annotation.provenance)
        assertEquals("mixed", annotation.source)
        assertEquals(0.8, annotation.confidence)
    }

    @Test
    fun orderingIsDeterministicAcrossInsertionOrder() {
        val late = contribution("z-rule", "LOGGING", 20, 26)
        val early = contribution("a-rule", "VALIDATION", 10, 16)
        val first = AnnotationStore()
        first.replaceRule("z", listOf(late))
        first.replaceRule("a", listOf(early))
        val second = AnnotationStore()
        second.replaceRule("a", listOf(early))
        second.replaceRule("z", listOf(late))

        assertEquals(first.annotations(), second.annotations())
        assertEquals(listOf(early, late), first.annotations().flatMap { it.provenance })
    }

    @Test
    fun hasNoRemovalApi() {
        assertFalse(AnnotationStore::class.java.methods.any { it.name == "remove" })
    }

    private fun contribution(
        rule: String,
        intent: String,
        startByte: Int = 10,
        endByte: Int = 16,
    ): Contribution =
        Contribution(
            rule = rule,
            role = Role.SOURCE_INTENT,
            intents = setOf(intent),
            evidence = rule,
            span = span,
            source = "treesitter",
            confidence = 1.0,
            startByte = startByte,
            endByte = endByte,
        )
}
