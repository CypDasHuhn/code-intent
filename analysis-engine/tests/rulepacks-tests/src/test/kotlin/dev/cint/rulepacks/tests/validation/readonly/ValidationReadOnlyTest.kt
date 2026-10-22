package dev.cint.rulepacks.tests.validation.readonly

import dev.cint.core.support.hashing.contentHash
import dev.cint.core.user.UserAnnotation
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
    fun userDirectivesUseSideEffectFreeValidationConfidence() {
        val source =
            """
            fun readOnlyGuard(): Boolean = true
            fun pureGuard(): Boolean = true
            fun plainGuard(): Boolean = true
            fun f(): Boolean {
                if (readOnlyGuard()) return false
                if (pureGuard()) return false
                if (plainGuard()) return false
                return true
            }
            """.trimIndent()
        val input = parseValidationInput("kotlin", "Sample.kt", source)
        val functions = input.syntax.functions(input.tree).associateBy { it.name }
        val readOnly = functions.getValue("readOnlyGuard").node
        val pure = functions.getValue("pureGuard").node
        val analysis =
            validationAnalysis(
                input,
                listOf(
                    UserAnnotation(
                        path = input.source.path,
                        span = readOnly.span(),
                        intents = setOf("READONLY"),
                        symbol = "readOnlyGuard",
                        contentHash = contentHash(readOnly.text),
                    ),
                    UserAnnotation(
                        path = input.source.path,
                        span = pure.span(),
                        intents = setOf("PURE"),
                        symbol = "pureGuard",
                        contentHash = contentHash(pure.text),
                    ),
                ),
            )
        val values = analysis.source(VALIDATION_INTENT)

        assertEquals(0.6, values.first { it.text(source) == "readOnlyGuard()" }.confidence)
        assertEquals(0.6, values.first { it.text(source) == "pureGuard()" }.confidence)
        assertEquals(0.5, values.first { it.text(source) == "plainGuard()" }.confidence)
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
