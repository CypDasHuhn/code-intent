package dev.cint.rulepacks.tests.validation

import dev.cint.api.Role
import dev.cint.api.RulePack
import dev.cint.api.UNTAGGED_INTENT
import dev.cint.core.CoreContributors
import dev.cint.core.facts.oracle.NullOracle
import dev.cint.core.runtime.scheduler.RuleEngine
import dev.cint.rulepacks.bookkeeping.BookkeepingRulePack
import dev.cint.rulepacks.intents.mutation.MutationRulePack
import dev.cint.rulepacks.intents.structural.LocalCopyRulePack
import dev.cint.rulepacks.tests.support.NoOpValidationPack
import dev.cint.rulepacks.tests.support.ValidationInput
import dev.cint.rulepacks.tests.support.parseValidationInput
import dev.cint.rulepacks.tests.support.validationPipeline
import dev.cint.rulepacks.validation.VALIDATION_INTENT
import dev.cint.rulepacks.validation.VALIDATION_RULE_ID
import dev.cint.rulepacks.validation.ValidationRulePack
import dev.cint.test.fixtures.text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ValidationRulePackTest {
    @Test
    fun validationReturnAndBareReturnAreNotUntagged() {
        val text =
            """
            fun f(value: Int): Boolean {
                if (value < 0) {
                    return false
                }
                return true
            }
            fun g(value: String?) {
                if (value != null) {
                    return
                }
            }
            """.trimIndent()
        val input = parseValidationInput("kotlin", "Sample.kt", text)
        val annotations = validationPipeline(input)
        val returns = annotations.filter { it.rule == CoreContributors.RETURN_CATEGORY }

        val falseReturn = returns.first { it.text(text).contains("return false") }
        val bareReturn = returns.first { it.text(text).trim() == "return" }
        assertEquals(setOf(VALIDATION_INTENT), falseReturn.intents)
        assertEquals(setOf(VALIDATION_INTENT), bareReturn.intents)
        assertTrue(UNTAGGED_INTENT !in falseReturn.intents)
        assertTrue(UNTAGGED_INTENT !in bareReturn.intents)

        for ((node, condition) in input.syntax.ifs(input.tree)) {
            assertNotNull(condition)
            val source =
                annotations.single {
                    it.role == Role.SOURCE_INTENT &&
                        it.rule == VALIDATION_RULE_ID &&
                        it.startByte == condition.startByte &&
                        it.endByte == condition.endByte
                }
            assertEquals(setOf(VALIDATION_INTENT), source.intents)

            val scaffold =
                annotations.firstOrNull {
                    it.role == Role.SCAFFOLD &&
                        it.rule == CoreContributors.SCAFFOLD &&
                        it.startByte == node.startByte &&
                        it.endByte == node.endByte
                }
            assertNotNull(scaffold, "missing guard scaffold for ${node.text}")
            assertEquals(setOf(VALIDATION_INTENT), scaffold.intents)
        }
    }

    @Test
    fun returnsRequireEmittedValidationAnnotations() {
        val text =
            """
            fun f(value: Int): Boolean {
                if (value < 0) {
                    return false
                }
                return true
            }
            """.trimIndent()
        val input = parseValidationInput("kotlin", "Sample.kt", text)

        fun returns(
            source: ValidationInput,
            packs: List<RulePack>,
        ) = RuleEngine()
            .analyze(
                source.source,
                source.tree,
                source.syntax,
                NullOracle,
                source.config,
                packs,
            ).annotations
            .first {
                it.rule == CoreContributors.RETURN_CATEGORY && it.text(text).contains("return false")
            }

        val withoutValidation = returns(input, listOf(BookkeepingRulePack()))
        val withNoOpValidation = returns(input, listOf(BookkeepingRulePack(), NoOpValidationPack()))
        val withValidation = returns(input, listOf(BookkeepingRulePack(), MutationRulePack(), LocalCopyRulePack(), ValidationRulePack()))

        assertEquals(setOf(UNTAGGED_INTENT), withoutValidation.intents)
        assertEquals(setOf(UNTAGGED_INTENT), withNoOpValidation.intents)
        assertEquals(setOf(VALIDATION_INTENT), withValidation.intents)
    }
}
