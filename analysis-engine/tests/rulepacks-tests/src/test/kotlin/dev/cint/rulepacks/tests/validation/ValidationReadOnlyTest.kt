package dev.cint.rulepacks.tests.validation

import dev.cint.rulepacks.tests.support.parseValidationInput
import dev.cint.rulepacks.tests.support.validationAnalysis
import dev.cint.rulepacks.validation.VALIDATION_INTENT
import dev.cint.test.fixtures.text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ValidationReadOnlyTest {
    @Test
    fun elvisContinueAndReadOnlyGuardsAreValidation() {
        val source =
            """
            fun f(uses: List<String>, node: String?): Boolean {
                for (use in uses) {
                    val current = node ?: continue
                    if (uses.isEmpty()) continue
                    if (receiverNode(node) != null) return false
                    consume(current)
                }
                return true
            }
            fun receiverNode(node: String?): String? = node
            fun consume(value: String?) {}
            """.trimIndent()
        val analysis = validationAnalysis(parseValidationInput("kotlin", "Sample.kt", source))
        val values = analysis.source(VALIDATION_INTENT)

        val elvis = values.first { "node ?: continue" in it.text(source) }
        val predicate = values.first { it.text(source) == "uses.isEmpty()" }
        val readOnly = values.first { it.text(source) == "receiverNode(node) != null" }

        assertEquals(setOf(VALIDATION_INTENT), elvis.intents)
        assertEquals(setOf(VALIDATION_INTENT), predicate.intents)
        assertEquals(setOf(VALIDATION_INTENT), readOnly.intents)
        assertTrue(readOnly.confidence < predicate.confidence)
        assertTrue(readOnly.confidence > 0.0)
    }

    @Test
    fun elvisReturnAndBreakFormsAreValidation() {
        val source =
            """
            fun f(value: String?, items: List<String>): String {
                for (item in items) {
                    val current = item ?: break
                    consume(current)
                }
                return value ?: return ""
            }
            fun consume(value: String) {}
            """.trimIndent()
        val analysis = validationAnalysis(parseValidationInput("kotlin", "Sample.kt", source))
        val elvis = analysis.source(VALIDATION_INTENT).filter { "?:" in it.text(source) }

        assertEquals(2, elvis.size)
        assertTrue(elvis.any { "item ?: break" in it.text(source) })
        assertTrue(elvis.any { "value ?: return \"\"" in it.text(source) })
    }

    @Test
    fun nonRejectingReturnsStayOutOfValidation() {
        val source =
            """
            fun computed(): String = "computed"
            fun f(call: String?, value: String?): String? {
                if (receiverNode(call) != null) return computed()
                return value ?: return computed()
            }
            fun g(call: String?): Boolean {
                if (receiverNode(call) != null) return true
                return false
            }
            fun receiverNode(value: String?): String? = value
            """.trimIndent()
        val analysis = validationAnalysis(parseValidationInput("kotlin", "Sample.kt", source))
        val values = analysis.source(VALIDATION_INTENT).map { it.text(source) }

        assertTrue(values.none { "receiverNode(call) != null" in it })
        assertTrue(values.none { "?:" in it })
    }

    @Test
    fun mutationCallConditionsAreNotReadOnlyValidation() {
        val source =
            """
            class C {
                val values = mutableListOf<String>()
                fun f(value: String): Boolean {
                    if (values.add(value)) return false
                    return true
                }
            }
            """.trimIndent()
        val analysis = validationAnalysis(parseValidationInput("kotlin", "Sample.kt", source))

        assertTrue(analysis.source(VALIDATION_INTENT).none { "values.add" in it.text(source) })
    }

    @Test
    fun throwingRejectionGuardsAndElvisAreValidation() {
        val source =
            """
            fun f(call: String?, value: String?): String {
                if (receiverNode(call) == null) throw IllegalArgumentException()
                return value ?: throw IllegalStateException()
            }
            fun receiverNode(value: String?): String? = value
            """.trimIndent()
        val analysis = validationAnalysis(parseValidationInput("kotlin", "Sample.kt", source))
        val values = analysis.source(VALIDATION_INTENT).map { it.text(source) }

        assertTrue(values.any { "receiverNode(call) == null" in it })
        assertTrue(values.any { "value ?: throw" in it })
    }

    @Test
    fun continueInsideANestedLoopDoesNotRejectTheOuterGuard() {
        val source =
            """
            fun f(call: String?, items: List<String>): String? {
                if (receiverNode(call) != null) {
                    for (item in items) {
                        if (item.isEmpty()) continue
                    }
                    consume(call)
                }
                return call
            }
            fun receiverNode(value: String?): String? = value
            fun consume(value: String?) {}
            """.trimIndent()
        val analysis = validationAnalysis(parseValidationInput("kotlin", "Sample.kt", source))

        assertTrue(analysis.source(VALIDATION_INTENT).none { it.text(source) == "receiverNode(call) != null" })
        assertTrue(analysis.source(VALIDATION_INTENT).any { it.text(source) == "item.isEmpty()" })
    }

    @Test
    fun knownWritesDoNotBecomeReadOnlyValidationGuards() {
        val source =
            """
            class C {
                var count = 0
                fun f(): Boolean {
                    if (count++ > 0) return false
                    return true
                }
            }
            """.trimIndent()
        val analysis = validationAnalysis(parseValidationInput("kotlin", "Sample.kt", source))

        assertTrue(analysis.source(VALIDATION_INTENT).none { "count++" in it.text(source) })
    }
}
