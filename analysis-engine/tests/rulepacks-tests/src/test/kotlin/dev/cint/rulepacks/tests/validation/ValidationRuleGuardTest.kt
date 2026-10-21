package dev.cint.rulepacks.tests.validation

import dev.cint.api.Role
import dev.cint.api.UNTAGGED_INTENT
import dev.cint.core.CoreContributors
import dev.cint.rulepacks.bookkeeping.BOOKKEEPING_INTENT
import dev.cint.rulepacks.tests.support.parseValidationInput
import dev.cint.rulepacks.tests.support.validationPipeline
import dev.cint.rulepacks.tests.support.validationSources
import dev.cint.rulepacks.validation.VALIDATION_INTENT
import dev.cint.rulepacks.validation.VALIDATION_RULE_ID
import dev.cint.test.fixtures.text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ValidationRuleGuardTest {
    @Test
    fun nestedFunctionAndLambdaReturnsDoNotInheritEnclosingGuardIntents() {
        val cases =
            listOf(
                """
                fun f(values: List<String>) {
                    // cint:validation
                    if (values.isNotEmpty()) {
                        fun positive() {
                            return
                        }
                        positive()
                    }
                }
                """.trimIndent(),
                """
                fun f(values: List<String>) {
                    // cint:validation
                    if (values.isNotEmpty()) {
                        values.forEach { return@forEach }
                    }
                }
                """.trimIndent(),
            )

        for (text in cases) {
            val input = parseValidationInput("kotlin", "Sample.kt", text)
            val annotations = validationPipeline(input)
            assertTrue(validationSources(input).isNotEmpty())
            assertTrue(
                annotations.none {
                    it.rule == CoreContributors.RETURN_CATEGORY &&
                        VALIDATION_INTENT in it.intents
                },
            )
        }
    }

    @Test
    fun validationGuardClassifiesPlumbingLocals() {
        val text =
            """
            fun f(value: String?): String {
                val current = value
                if (current == null || current.isEmpty()) {
                    return ""
                }
                return "x"
            }
            """.trimIndent()
        val input = parseValidationInput("kotlin", "Sample.kt", text)
        val annotations = validationPipeline(input)
        val derivative =
            annotations.first {
                it.role == Role.DERIVATIVE_USE && it.text(text).contains("val current")
            }
        val guard = input.syntax.ifs(input.tree).single()
        val condition = guard.condition
        assertNotNull(condition)
        val source =
            annotations.single {
                it.role == Role.SOURCE_INTENT &&
                    it.rule == VALIDATION_RULE_ID &&
                    it.startByte == condition.startByte &&
                    it.endByte == condition.endByte
            }
        val scaffold =
            annotations.firstOrNull {
                it.role == Role.SCAFFOLD &&
                    it.rule == CoreContributors.SCAFFOLD &&
                    it.startByte == guard.node.startByte &&
                    it.endByte == guard.node.endByte
            }

        assertEquals(setOf(VALIDATION_INTENT), source.intents)
        assertNotNull(scaffold, "missing ambient validation scaffold")
        assertEquals(setOf(VALIDATION_INTENT), scaffold.intents)
        assertEquals(setOf(VALIDATION_INTENT), derivative.intents)
    }

    @Test
    fun bookkeepingOnlyGuardDoesNotMakeItsScaffoldUntagged() {
        val text =
            """
            class C {
                @Bookkeeping var skipped = 0
                fun f(value: Int): Boolean {
                    if (consume(value)) {
                        skipped++
                        return false
                    }
                    return true
                }
                fun consume(value: Int): Boolean = value > 0
            }
            """.trimIndent()
        val input = parseValidationInput("kotlin", "Sample.kt", text)
        val annotations = validationPipeline(input)
        val guard = input.syntax.ifs(input.tree).single()
        val condition = guard.condition
        assertNotNull(condition)
        val source =
            annotations.single {
                it.role == Role.SOURCE_INTENT &&
                    it.rule == VALIDATION_RULE_ID &&
                    it.startByte == condition.startByte &&
                    it.endByte == condition.endByte
            }
        val scaffold =
            annotations.firstOrNull {
                it.role == Role.SCAFFOLD &&
                    it.rule == CoreContributors.SCAFFOLD &&
                    it.startByte == guard.node.startByte &&
                    it.endByte == guard.node.endByte
            }
        val returnFalse =
            annotations.first {
                it.rule == CoreContributors.RETURN_CATEGORY && it.text(text).contains("return false")
            }

        assertEquals(setOf(VALIDATION_INTENT), source.intents)
        assertEquals(setOf(VALIDATION_INTENT), returnFalse.intents)
        assertNotNull(scaffold, "missing bookkeeping guard scaffold")
        assertEquals(setOf(VALIDATION_INTENT, BOOKKEEPING_INTENT), scaffold.intents)
        assertTrue(UNTAGGED_INTENT !in scaffold.intents)
    }
}
