package dev.cint.core.scheduler

import dev.cint.api.Emit
import dev.cint.api.Intent
import dev.cint.api.Relation
import dev.cint.api.Rule
import dev.cint.api.RulePack
import dev.cint.api.UNTAGGED_INTENT
import dev.cint.api.View
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class TestRule(
    override val id: String,
    override val owns: Intent,
    override val reads: Set<Intent> = emptySet(),
) : Rule {
    override fun run(view: View): List<Emit> = emptyList()
}

private class TestPack(
    override val id: String,
    private val entries: List<Rule>,
) : RulePack {
    override val languages: Set<String> = setOf("kotlin")

    override fun rules(): List<Rule> = entries
}

class RuleSchedulerTest {
    private val logging = TestRule("example:logging", "LOGGING")
    private val errorHandling = TestRule("example:error-handling", "ERROR_HANDLING")
    private val bookkeeping = TestRule("example:bookkeeping", "BOOKKEEPING")
    private val validation = TestRule("example:validation", "VALIDATION", setOf("BOOKKEEPING"))
    private val examplePack =
        TestPack("example", listOf(logging, errorHandling, bookkeeping, validation))

    @Test
    fun workedExampleStratifiesIntoExpectedStrataAndTerminal() {
        val plan = RuleScheduler().plan(listOf(examplePack))

        assertEquals(
            listOf(
                listOf(bookkeeping, errorHandling, logging),
                listOf(validation),
            ),
            plan.strata,
        )
        assertEquals(
            listOf(Relation(validation, "BOOKKEEPING", bookkeeping)),
            plan.relations,
        )
        assertEquals(
            setOf("LOGGING", "ERROR_HANDLING", "BOOKKEEPING", "VALIDATION"),
            plan.terminal.sourceIntents,
        )
    }

    @Test
    fun duplicateIntentOwnerFailsWithNamedError() {
        val first = TestRule("a:first", "LOGGING")
        val second = TestRule("b:second", "LOGGING")

        val error =
            assertFailsWith<DuplicateIntentOwner> {
                RuleScheduler().plan(
                    listOf(TestPack("a", listOf(first)), TestPack("b", listOf(second))),
                )
            }

        assertEquals(SchedulerError.DUPLICATE_INTENT_OWNER, error.error)
        assertEquals("LOGGING", error.intent)
        assertEquals(listOf(first, second), error.owners)
        assertTrue(error.message.orEmpty().contains("LOGGING"))
    }

    @Test
    fun unknownDependencyFailsWithNamedError() {
        val reader = TestRule("example:reader", "OUTPUT", setOf("MISSING"))

        val error =
            assertFailsWith<UnknownDependency> {
                RuleScheduler().plan(listOf(TestPack("example", listOf(reader))))
            }

        assertEquals(SchedulerError.UNKNOWN_DEPENDENCY, error.error)
        assertEquals("MISSING", error.intent)
        assertEquals(reader, error.reader)
        assertTrue(error.message.orEmpty().contains("MISSING"))
    }

    @Test
    fun duplicateRuleIdFailsWithNamedError() {
        val first = TestRule("shared:rule", "FIRST")
        val second = TestRule("shared:rule", "SECOND")

        val error =
            assertFailsWith<DuplicateRuleId> {
                RuleScheduler().plan(
                    listOf(TestPack("a", listOf(first)), TestPack("b", listOf(second))),
                )
            }

        assertEquals(SchedulerError.DUPLICATE_RULE_ID, error.error)
        assertEquals("shared:rule", error.ruleId)
        assertEquals(listOf(first, second), error.rules)
        assertTrue(error.message.orEmpty().contains("shared:rule"))
    }

    @Test
    fun reservedCoreContributorRuleIdFailsWithNamedError() {
        val rule = TestRule("core:scaffold", "LOGGING")

        val error =
            assertFailsWith<ReservedContributorId> {
                RuleScheduler().plan(listOf(TestPack("plugin", listOf(rule))))
            }

        assertEquals(SchedulerError.RESERVED_CONTRIBUTOR_ID, error.error)
        assertEquals(rule, error.rule)
        assertTrue(error.message.orEmpty().contains("core:"))
    }

    @Test
    fun reservedUntaggedOwnerFailsWithNamedError() {
        val rule = TestRule("example:untagged", UNTAGGED_INTENT)

        val error =
            assertFailsWith<ReservedIntentOwner> {
                RuleScheduler().plan(listOf(TestPack("example", listOf(rule))))
            }

        assertEquals(SchedulerError.RESERVED_INTENT_OWNER, error.error)
        assertEquals(rule, error.rule)
        assertTrue(error.message.orEmpty().contains(UNTAGGED_INTENT))
    }

    @Test
    fun readingReservedUntaggedFailsAsUnknownDependency() {
        val reader = TestRule("example:reader", "OUTPUT", setOf(UNTAGGED_INTENT))

        val error =
            assertFailsWith<UnknownDependency> {
                RuleScheduler().plan(listOf(TestPack("example", listOf(reader))))
            }

        assertEquals(SchedulerError.UNKNOWN_DEPENDENCY, error.error)
        assertEquals(UNTAGGED_INTENT, error.intent)
        assertEquals(reader, error.reader)
    }

    @Test
    fun cycleFailsWithNamedError() {
        val first = TestRule("example:first", "FIRST", setOf("SECOND"))
        val second = TestRule("example:second", "SECOND", setOf("FIRST"))

        val error =
            assertFailsWith<DependencyCycle> {
                RuleScheduler().plan(listOf(TestPack("example", listOf(first, second))))
            }

        assertEquals(SchedulerError.DEPENDENCY_CYCLE, error.error)
        assertEquals(listOf("example:first", "example:second", "example:first"), error.rules.map { it.id })
    }

    @Test
    fun selfReadingRuleStaysInItsStratumWithASelfRelation() {
        val self = TestRule("example:self", "SELF", setOf("SELF"))

        val plan = RuleScheduler().plan(listOf(TestPack("example", listOf(self))))

        assertEquals(listOf(listOf(self)), plan.strata)
        assertEquals(listOf(Relation(self, "SELF", self)), plan.relations)
    }

    @Test
    fun packOrderDoesNotChangeThePlan() {
        val all = RuleScheduler().plan(listOf(examplePack))
        val split =
            RuleScheduler().plan(
                listOf(
                    TestPack("b", listOf(validation, bookkeeping)),
                    TestPack("a", listOf(logging, errorHandling)),
                ),
            )
        val splitReversed =
            RuleScheduler().plan(
                listOf(
                    TestPack("r1", listOf(validation)),
                    TestPack("r2", listOf(errorHandling, logging, bookkeeping)),
                ).reversed(),
            )

        assertEquals(all, split)
        assertEquals(all, splitReversed)
    }

    @Test
    fun packOrderDoesNotChangeLoadErrors() {
        val first = TestRule("a:first", "LOGGING")
        val second = TestRule("b:second", "LOGGING")
        val firstPack = TestPack("a", listOf(first))
        val secondPack = TestPack("b", listOf(second))

        val forward =
            assertFailsWith<DuplicateIntentOwner> {
                RuleScheduler().plan(listOf(firstPack, secondPack))
            }
        val reverse =
            assertFailsWith<DuplicateIntentOwner> {
                RuleScheduler().plan(listOf(secondPack, firstPack))
            }

        assertEquals(forward.message, reverse.message)
        assertEquals(forward.owners.map { it.id }, reverse.owners.map { it.id })
    }

    @Test
    fun terminalConsumesTheUnionOfSourceIntentsOnly() {
        val both =
            RuleScheduler().plan(
                listOf(TestPack("both", listOf(logging, bookkeeping))),
            )
        val loggingOnly =
            RuleScheduler().plan(listOf(TestPack("logging", listOf(logging))))
        val bookkeepingOnly =
            RuleScheduler().plan(listOf(TestPack("bookkeeping", listOf(bookkeeping))))

        assertEquals(setOf("LOGGING", "BOOKKEEPING"), both.terminal.sourceIntents)
        assertEquals(
            loggingOnly.terminal.sourceIntents + bookkeepingOnly.terminal.sourceIntents,
            both.terminal.sourceIntents,
        )
    }

    @Test
    fun terminalNamesNoRuleOrPack() {
        val fields = TerminalNode::class.java.declaredFields.map { it.name.lowercase() }

        assertTrue(fields.none { it.contains("rule") || it.contains("pack") })
    }
}
