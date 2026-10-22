package dev.cint.rulepacks.tests.validation

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
