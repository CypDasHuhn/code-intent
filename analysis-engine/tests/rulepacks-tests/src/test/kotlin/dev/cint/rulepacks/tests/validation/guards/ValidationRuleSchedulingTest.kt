package dev.cint.rulepacks.tests.validation.guards

import dev.cint.core.facts.flow.ControlFlowBuilder
import dev.cint.core.facts.oracle.NullOracle
import dev.cint.core.facts.store.AnnotationStore
import dev.cint.core.runtime.engine.AnalysisContext
import dev.cint.core.runtime.scheduler.RuleScheduler
import dev.cint.rulepacks.bookkeeping.BOOKKEEPING_INTENT
import dev.cint.rulepacks.bookkeeping.BookkeepingRulePack
import dev.cint.rulepacks.intents.mutation.DATA_MODIFY_INTENT
import dev.cint.rulepacks.intents.mutation.LOCAL_VALUE_MODIFY_INTENT
import dev.cint.rulepacks.intents.mutation.MutationRulePack
import dev.cint.rulepacks.intents.structural.LocalCopyRulePack
import dev.cint.rulepacks.tests.support.parseValidationInput
import dev.cint.rulepacks.validation.VALIDATION_RULE_ID
import dev.cint.rulepacks.validation.ValidationRulePack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ValidationRuleSchedulingTest {
    @Test
    fun validationSchedulesAfterBookkeepingAndMutation() {
        val plan = RuleScheduler().plan(listOf(ValidationRulePack(), BookkeepingRulePack(), MutationRulePack(), LocalCopyRulePack()))
        val validation = plan.strata.flatten().single { it.id == VALIDATION_RULE_ID }
        val validationIndex = plan.strata.indexOfFirst { stratum -> stratum.any { it.id == VALIDATION_RULE_ID } }
        val bookkeepingIndex = plan.strata.indexOfFirst { stratum -> stratum.any { it.id == "bookkeeping:bookkeeping" } }

        assertEquals(
            setOf(BOOKKEEPING_INTENT, DATA_MODIFY_INTENT, LOCAL_VALUE_MODIFY_INTENT),
            validation.reads,
        )
        assertTrue(validationIndex > bookkeepingIndex)
    }

    @Test
    fun ruleIsPureAndDoesNotMutateTheStore() {
        val input =
            parseValidationInput(
                "kotlin",
                "Sample.kt",
                """
                fun f(values: List<String>) {
                    if (values.isEmpty()) {
                        return
                    }
                }
                """.trimIndent(),
            )
        val store = AnnotationStore()
        val flow = ControlFlowBuilder(input.tree, input.syntax).build()
        val context =
            AnalysisContext(
                input.source,
                input.tree,
                input.syntax,
                flow,
                NullOracle,
                input.config,
                store,
            )
        val rule = ValidationRulePack().rules().single()
        val view = context.view(rule.owns, rule.reads)

        val first = rule.run(view)
        val second = rule.run(view)

        assertEquals(first, second)
        assertTrue(first.isNotEmpty())
        assertTrue(store.annotations().isEmpty())
    }
}
