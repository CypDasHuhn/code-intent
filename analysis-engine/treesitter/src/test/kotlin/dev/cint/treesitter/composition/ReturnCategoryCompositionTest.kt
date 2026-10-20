package dev.cint.treesitter.composition

import dev.cint.api.Emit
import dev.cint.api.Role
import dev.cint.api.Span
import dev.cint.api.UNTAGGED_INTENT
import dev.cint.core.CoreContributors
import dev.cint.core.facts.model.SourceFile
import dev.cint.treesitter.KotlinParser
import dev.cint.treesitter.KotlinSyntaxModel
import dev.cint.treesitter.support.ReplayPack
import dev.cint.treesitter.support.ReplayRule
import dev.cint.treesitter.support.analyzePipeline
import dev.cint.treesitter.support.emitFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReturnCategoryCompositionTest {
    @Test
    fun returnCategoryIsDerivedFromTheCfgAndValidationSourceIntents() {
        val text =
            """
            fun choose(value: Int): Int {
                if (value < 0) {
                    return 0
                } else {
                    return 1
                }
            }
            """.trimIndent()
        val validation =
            ReplayRule(
                id = "test:validation",
                owns = "VALIDATION",
                emissions =
                    listOf(
                        Emit(
                            intents = setOf("VALIDATION"),
                            evidence = "value < 0",
                            span = Span(1, 7, 1, 16),
                            source = "treesitter",
                            confidence = 1.0,
                            startByte = text.indexOf("value < 0"),
                            endByte = text.indexOf("value < 0") + "value < 0".length,
                        ),
                    ),
            )

        val annotations =
            analyzePipeline(
                text,
                listOf(ReplayPack("test", listOf(validation))),
            )

        val returns = annotations.filter { it.rule == CoreContributors.RETURN_CATEGORY }
        val validationReturn = returns.first { "VALIDATION" in it.intents }
        val untaggedReturn = returns.first { UNTAGGED_INTENT in it.intents }
        assertTrue(returns.all { it.rule == CoreContributors.RETURN_CATEGORY })
        assertEquals(Role.SCAFFOLD, validationReturn.role)
        assertEquals(setOf("VALIDATION"), validationReturn.intents)
        assertEquals(setOf(UNTAGGED_INTENT), untaggedReturn.intents)
        assertTrue(validationReturn.evidence.contains("return 0"))
        assertTrue(untaggedReturn.evidence.contains("return 1"))
    }

    @Test
    fun returnCategoryCoversRejectionSentinelsOnFalseEdgesAndFinalReturns() {
        val text =
            """
            fun chooseNull(ok: Boolean): String? {
                if (!ok) {
                    work()
                } else {
                    return null
                }
            }
            fun chooseBare(ok: Boolean) {
                if (!ok) {
                    work()
                } else {
                    return
                }
            }
            fun finalValue(value: Int): Int {
                return value
            }
            fun work() {}
            """.trimIndent()
        val source = SourceFile("Sample.kt", text)
        val tree = KotlinParser().parse(source)
        val conditions = KotlinSyntaxModel.ifs(tree).mapNotNull { it.condition }.toList()
        val validation =
            ReplayRule(
                id = "test:validation",
                owns = "VALIDATION",
                emissions = conditions.map { condition -> emitFor("VALIDATION", condition) },
            )

        val annotations =
            analyzePipeline(
                text,
                listOf(ReplayPack("test", listOf(validation))),
            )
        val returns = annotations.filter { it.rule == CoreContributors.RETURN_CATEGORY }

        val nullReturn = returns.first { it.evidence.contains("return null") }
        val bareReturn = returns.first { it.evidence.trim() == "return" }
        val finalReturn = returns.first { it.evidence.contains("return value") }

        assertEquals(setOf("VALIDATION"), nullReturn.intents)
        assertEquals(setOf("VALIDATION"), bareReturn.intents)
        assertEquals(setOf(UNTAGGED_INTENT), finalReturn.intents)
        assertTrue(
            annotations.none {
                it.rule == CoreContributors.SCAFFOLD && it.evidence.contains("fun finalValue")
            },
        )
    }

    @Test
    fun returnScaffoldsTreatBareReturnsAsNeutralAndCarryExpressionIntents() {
        val text =
            """
            fun bare(value: String?) {
                requireNotNull(value)
                return
            }
            fun expression(value: String?) {
                return requireNotNull(value)
            }
            """.trimIndent()
        val source = SourceFile("Sample.kt", text)
        val tree = KotlinParser().parse(source)
        val calls = KotlinSyntaxModel.calls(tree).filter { it.callee == "requireNotNull" }.toList()
        val errorHandling =
            ReplayRule(
                id = "test:error",
                owns = "ERROR_HANDLING",
                emissions = calls.map { call -> emitFor("ERROR_HANDLING", call.site) },
            )

        val annotations =
            analyzePipeline(
                text,
                listOf(ReplayPack("test", listOf(errorHandling))),
            )
        val functions = annotations.filter { it.rule == CoreContributors.SCAFFOLD }

        val bare = functions.first { it.evidence.contains("fun bare") }
        val expression = functions.first { it.evidence.contains("fun expression") }
        assertEquals(setOf("ERROR_HANDLING"), bare.intents)
        assertEquals(setOf("ERROR_HANDLING"), expression.intents)
    }
}
