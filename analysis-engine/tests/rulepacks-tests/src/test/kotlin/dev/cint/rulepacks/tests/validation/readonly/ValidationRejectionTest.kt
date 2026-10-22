package dev.cint.rulepacks.tests.validation.readonly

import dev.cint.rulepacks.tests.support.parseValidationInput
import dev.cint.rulepacks.tests.support.validationAnalysis
import dev.cint.rulepacks.validation.VALIDATION_INTENT
import dev.cint.test.fixtures.text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ValidationRejectionTest {
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
    fun throwingGuardsAreErrorHandlingNotValidation() {
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

        assertTrue(values.none { "receiverNode(call) == null" in it })
        assertTrue(values.none { "value ?: throw" in it })
    }

    @Test
    fun validationPredicateWithThrowOnlyIsNotValidation() {
        val source =
            """
            fun validate(values: List<String>) {
                if (values.isEmpty()) throw IllegalArgumentException("empty")
            }
            """.trimIndent()
        val analysis = validationAnalysis(parseValidationInput("kotlin", "Sample.kt", source))
        assertTrue(analysis.source(VALIDATION_INTENT).none { it.text(source) == "values.isEmpty()" })
    }

    @Test
    fun throwOnlyGuardsAreNotValidation() {
        val source =
            """
            fun validate(out: String, dot: String) {
                if (out == dot) {
                    throw IllegalArgumentException("different files")
                }
            }
            """.trimIndent()
        val analysis = validationAnalysis(parseValidationInput("kotlin", "Sample.kt", source))
        assertTrue(analysis.source(VALIDATION_INTENT).none { it.text(source) == "out == dot" })
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
    fun unbracedRejectingBodiesAreValidationAcrossLanguages() {
        val cases =
            listOf(
                "javascript" to
                    """
                    function f(call, items) {
                        if (receiverNode(call) != null) return false;
                        for (const item of items) {
                            if (receiverNode(call) != null) continue;
                            if (receiverNode(call) != null) throw new Error("invalid");
                        }
                        return true;
                    }
                    function receiverNode(value) { return value; }
                    """.trimIndent(),
                "typescript" to
                    """
                    function f(call: string, items: string[]): boolean {
                        if (receiverNode(call) != null) return false;
                        for (const item of items) {
                            if (receiverNode(call) != null) continue;
                            if (receiverNode(call) != null) throw new Error("invalid");
                        }
                        return true;
                    }
                    function receiverNode(value: string): string { return value; }
                    """.trimIndent(),
                "csharp" to
                    """
                    class C {
                        bool F(string call, string[] items) {
                            if (ReceiverNode(call) != null) return false;
                            foreach (var item in items) {
                                if (ReceiverNode(call) != null) continue;
                                if (ReceiverNode(call) != null) throw new System.Exception();
                            }
                            return true;
                        }
                        string ReceiverNode(string value) { return value; }
                    }
                    """.trimIndent(),
            )

        for ((language, source) in cases) {
            val analysis = validationAnalysis(parseValidationInput(language, "Sample", source))
            val values = analysis.source(VALIDATION_INTENT).map { it.text(source) }

            assertEquals(2, values.count { "eceiverNode(call)" in it }, language)
        }
    }

    @Test
    fun ordinaryRaiseCallsAreNotThrowRejections() {
        val cases =
            listOf(
                "kotlin" to
                    """
                    fun raise() {}
                    fun f(flag: Boolean): Boolean {
                        if (flag) raise()
                        return true
                    }
                    """.trimIndent(),
                "javascript" to
                    """
                    function raise() {}
                    function f(flag) {
                        if (flag) raise();
                        return true;
                    }
                    """.trimIndent(),
            )

        for ((language, source) in cases) {
            val analysis = validationAnalysis(parseValidationInput(language, "Sample", source))
            assertTrue(analysis.source(VALIDATION_INTENT).none { it.text(source) == "flag" }, language)
        }
    }

    @Test
    fun breakInsideNestedSwitchDoesNotRejectTheOuterGuard() {
        val source =
            """
            function f(call, value) {
                if (receiverNode(call) != null) {
                    switch (value) {
                        case "x":
                            break;
                    }
                    consume(call);
                }
                return true;
            }
            function receiverNode(value) { return value; }
            function consume(value) {}
            """.trimIndent()
        val analysis = validationAnalysis(parseValidationInput("javascript", "Sample.js", source))

        assertTrue(analysis.source(VALIDATION_INTENT).none { it.text(source) == "receiverNode(call) != null" })
    }
}
