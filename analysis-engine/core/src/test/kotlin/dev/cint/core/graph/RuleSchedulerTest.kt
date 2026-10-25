package dev.cint.core.graph

import dev.cint.contracts.data.Intents
import dev.cint.core.support.TestRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RuleSchedulerTest {
    private val logging = TestRule("example:logging", Intents.LOGGING) { emptyList() }
    private val errorHandling = TestRule("example:error", Intents.ERROR_HANDLING) { emptyList() }
    private val bookkeeping = TestRule("example:bookkeeping", Intents.BOOKKEEPING) { emptyList() }
    private val validation =
        TestRule("example:validation", Intents.VALIDATION, setOf(Intents.BOOKKEEPING)) { emptyList() }

    @Test
    fun stratifiesByDependencyAndNamesOnlySourceIntents() {
        val plan = RuleScheduler().plan(listOf(validation, logging, errorHandling, bookkeeping))

        assertEquals(
            listOf(listOf(bookkeeping, errorHandling, logging), listOf(validation)),
            plan.strata,
        )
        assertEquals(
            listOf(RuleRelation(validation, Intents.BOOKKEEPING, bookkeeping)),
            plan.relations,
        )
        assertEquals(
            setOf(
                Intents.LOGGING,
                Intents.ERROR_HANDLING,
                Intents.BOOKKEEPING,
                Intents.VALIDATION,
            ),
            plan.terminal.sourceIntents,
        )
    }

    @Test
    fun packOrderDoesNotChangeStrata() {
        val forward = RuleScheduler().plan(listOf(logging, errorHandling, bookkeeping, validation))
        val reverse = RuleScheduler().plan(listOf(validation, bookkeeping, errorHandling, logging))

        assertEquals(forward.strata, reverse.strata)
        assertEquals(forward.relations, reverse.relations)
        assertEquals(forward.terminal, reverse.terminal)
    }

    @Test
    fun unknownDependencyFailsWithNamedError() {
        val reader = TestRule("example:reader", "OUTPUT", setOf("MISSING")) { emptyList() }

        val error =
            assertFailsWith<UnknownDependency> {
                RuleScheduler().plan(listOf(reader))
            }

        assertEquals(SchedulerError.UNKNOWN_DEPENDENCY, error.error)
        assertEquals("MISSING", error.intent)
        assertEquals(reader, error.reader)
    }

    @Test
    fun dependencyCycleFailsWithNamedError() {
        val first = TestRule("example:first", "FIRST", setOf("SECOND")) { emptyList() }
        val second = TestRule("example:second", "SECOND", setOf("FIRST")) { emptyList() }

        val error =
            assertFailsWith<DependencyCycle> {
                RuleScheduler().plan(listOf(first, second))
            }

        assertEquals(SchedulerError.DEPENDENCY_CYCLE, error.error)
        assertEquals(
            listOf("example:first", "example:second", "example:first"),
            error.rules.map { it.spec.id },
        )
        assertTrue(error.message.orEmpty().contains("dependency cycle"))
    }

    @Test
    fun duplicateIntentOwnerFailsWithNamedError() {
        val first = TestRule("a:first", Intents.LOGGING) { emptyList() }
        val second = TestRule("b:second", Intents.LOGGING) { emptyList() }

        val error =
            assertFailsWith<DuplicateIntentOwner> {
                RuleScheduler().plan(listOf(first, second))
            }

        assertEquals(SchedulerError.DUPLICATE_INTENT_OWNER, error.error)
        assertEquals(Intents.LOGGING, error.intent)
        assertEquals(listOf(first, second), error.owners)
    }

    @Test
    fun absentOptionalDependencyDoesNotCreateAnEdge() {
        val optional =
            TestRule("example:optional", "OUTPUT", optionalReads = setOf("MISSING")) { emptyList() }

        val plan = RuleScheduler().plan(listOf(optional))

        assertEquals(listOf(listOf(optional)), plan.strata)
        assertTrue(plan.relations.isEmpty())
    }

    @Test
    fun presentOptionalDependencyOrdersTheReader() {
        val owner = TestRule("example:owner", "OPTIONAL") { emptyList() }
        val optional =
            TestRule("example:optional", "OUTPUT", optionalReads = setOf("OPTIONAL")) { emptyList() }

        val plan = RuleScheduler().plan(listOf(optional, owner))

        assertEquals(listOf(listOf(owner), listOf(optional)), plan.strata)
        assertEquals(listOf(RuleRelation(optional, "OPTIONAL", owner)), plan.relations)
    }
}
