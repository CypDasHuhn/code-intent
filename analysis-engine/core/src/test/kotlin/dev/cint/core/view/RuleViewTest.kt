package dev.cint.core.view

import dev.cint.contracts.data.Intents
import dev.cint.contracts.facts.FactBatch
import dev.cint.core.store.AnnotationStore
import dev.cint.core.support.contribution
import dev.cint.core.support.facts
import dev.cint.core.support.node
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RuleViewTest {
    private val span = node("n", "identifier", 0, 10)
    private val hiddenSpan = node("hidden", "identifier", 20, 30)

    private fun view(failOnUndeclaredRead: Boolean = false): RuleView {
        val store = AnnotationStore()
        store.replaceRule(
            "rule:a",
            listOf(contribution("rule:a", Intents.LOGGING, span)),
        )
        store.replaceRule(
            "rule:b",
            listOf(
                contribution("rule:b", Intents.LOOKUP, span),
                contribution("rule:b", Intents.LOOKUP, hiddenSpan),
            ),
        )
        val factBatch: FactBatch = facts("root", listOf(node("root", "program", 0, 40)))
        return RuleView(
            owns = "OUTPUT",
            reads = setOf(Intents.LOGGING),
            optionalReads = emptySet(),
            facts = factBatch,
            store = store,
            failOnUndeclaredRead = failOnUndeclaredRead,
        )
    }

    @Test
    fun annotationReadsAreLimitedToOwnedAndDeclaredIntents() {
        val view = view()

        assertEquals(1, view.annotations(Intents.LOGGING).size)
        assertEquals(setOf(Intents.LOGGING), view.intentsAt(span))
        assertTrue(view.annotations(Intents.LOOKUP).isEmpty())
        assertTrue(view.annotations(Intents.UNTAGGED).isEmpty())
    }

    @Test
    fun coverageIsAlsoLimitedToVisibleIntents() {
        val view = view()

        assertEquals(setOf(Intents.LOGGING), view.coverage(span))
        assertEquals(setOf(Intents.UNTAGGED), view.coverage(hiddenSpan))
        assertEquals(emptySet(), view.coverage(hiddenSpan, effect = false))
    }

    @Test
    fun optionalReadsAreVisibleWhenPresent() {
        val store = AnnotationStore()
        store.replaceRule(
            "rule:optional",
            listOf(contribution("rule:optional", "OPTIONAL", span)),
        )
        val view =
            RuleView(
                owns = "OUTPUT",
                reads = emptySet(),
                optionalReads = setOf("OPTIONAL"),
                facts = facts("root", listOf(node("root", "program", 0, 10))),
                store = store,
            )

        assertEquals(setOf("OPTIONAL"), view.intentsAt(span))
    }

    @Test
    fun undeclaredReadCanFailLoudly() {
        val view = view(failOnUndeclaredRead = true)

        assertFailsWith<IllegalStateException> {
            view.annotations(Intents.LOOKUP)
        }
    }
}
