package dev.cint.rulepacks.tests.validation

import dev.cint.api.Role
import dev.cint.rulepacks.bookkeeping.BookkeepingRulePack
import dev.cint.rulepacks.tests.support.analyzePack
import dev.cint.rulepacks.tests.support.parseValidationInput
import dev.cint.rulepacks.tests.support.validationSources
import dev.cint.rulepacks.validation.VALIDATION_INTENT
import dev.cint.rulepacks.validation.VALIDATION_RULE_ID
import dev.cint.rulepacks.validation.ValidationRulePack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ValidationRuleCommentTest {
    @Test
    fun stringContentIsNotAValidationComment() {
        val cases =
            listOf(
                "kotlin" to
                    """
                    fun consume(value: Int): Boolean = value > 0
                    fun f(value: Int) {
                        val label = "// cint:validation"
                        if (consume(value)) {
                            return
                        }
                    }
                    """.trimIndent(),
                "kotlin" to
                    """
                    fun consume(value: Int): Boolean = value > 0
                    fun f(value: Int) {
                        val label = "/* cint:validation */"
                        if (consume(value)) {
                            return
                        }
                    }
                    """.trimIndent(),
                "javascript" to
                    """
                    function consume(value) { return value > 0; }
                    function f(value) {
                        const label = "// cint:validation";
                        if (consume(value)) {
                            return;
                        }
                    }
                    """.trimIndent(),
            )

        for ((language, text) in cases) {
            val input = parseValidationInput(language, "Sample", text)
            assertTrue(validationSources(input).isEmpty(), "$language string content tagged validation")
        }
    }

    @Test
    fun defaultMagicCommentCertifiesWithCommentSourceAndConfidence() {
        val text =
            """
            fun consume(value: Int): Boolean = value > 0
            fun f(value: Int) {
                // cint:validation
                if (consume(value)) {
                    return
                }
            }
            """.trimIndent()
        val input = parseValidationInput("kotlin", "Sample.kt", text)
        val condition =
            input.syntax
                .ifs(input.tree)
                .single()
                .condition
        assertNotNull(condition)
        val source =
            validationSources(input).single {
                it.startByte == condition.startByte && it.endByte == condition.endByte
            }

        assertEquals(setOf(VALIDATION_INTENT), source.intents)
        assertEquals("comment", source.source)
        assertEquals(1.0, source.confidence)
    }

    @Test
    fun nearestCommentWinsOverEarlierMagicComment() {
        val text =
            """
            fun consume(value: Int): Boolean = value > 0
            fun f(value: Int) {
                // cint:validation
                // ordinary note
                if (consume(value)) {
                    return
                }
            }
            """.trimIndent()

        val input = parseValidationInput("kotlin", "Sample.kt", text)

        assertTrue(validationSources(input).isEmpty())
    }

    @Test
    fun nearestMagicCommentWinsOverEarlierOrdinaryComment() {
        val text =
            """
            fun consume(value: Int): Boolean = value > 0
            fun f(value: Int) {
                // ordinary note
                // cint:validation
                if (consume(value)) {
                    return
                }
            }
            """.trimIndent()

        val input = parseValidationInput("kotlin", "Sample.kt", text)

        assertEquals(1, validationSources(input).size)
        assertEquals("comment", validationSources(input).single().source)
    }

    @Test
    fun magicCommentMoreThanOneLineAboveDoesNotCertify() {
        val text =
            """
            fun consume(value: Int): Boolean = value > 0
            fun f(value: Int) {
                // cint:validation

                if (consume(value)) {
                    return
                }
            }
            """.trimIndent()

        val input = parseValidationInput("kotlin", "Sample.kt", text)

        assertTrue(validationSources(input).isEmpty())
    }

    @Test
    fun magicCommentInPreviousFunctionDoesNotCertifyNextFunction() {
        val text =
            """
            fun consume(value: Int): Boolean = value > 0
            fun first() { /* cint:validation */ }
            fun second(value: Int) { if (consume(value)) { return } }
            """.trimIndent()

        val input = parseValidationInput("kotlin", "Sample.kt", text)

        assertTrue(validationSources(input).isEmpty())
    }

    @Test
    fun commentKindsAliasIsAcceptedWithoutDroppingThePack() {
        val text =
            """
            fun consume(value: Int): Boolean = value > 0
            fun f(value: Int) {
                // cint:validation
                if (consume(value)) {
                    return
                }
            }
            """.trimIndent()
        val input = parseValidationInput("kotlin", "Sample.kt", text)
        val pack = ValidationRulePack().configure(mapOf("commentKinds" to listOf("line_comment")))
        val sources =
            analyzePack(input, listOf(BookkeepingRulePack(), pack))
                .annotations
                .filter { it.role == Role.SOURCE_INTENT && it.rule == VALIDATION_RULE_ID }

        assertEquals(1, sources.size)
        assertEquals("comment", sources.single().source)
    }

    @Test
    fun lineOnlyCommentKindsExcludeBlockMagicComment() {
        val text =
            """
            fun consume(value: Int): Boolean = value > 0
            fun f(value: Int) {
                /* cint:validation */
                if (consume(value)) {
                    return
                }
            }
            """.trimIndent()
        val input = parseValidationInput("kotlin", "Sample.kt", text)
        val pack = ValidationRulePack().configure(mapOf("commentKinds" to listOf("line_comment")))

        val annotations = analyzePack(input, listOf(BookkeepingRulePack(), pack)).annotations
        val sources =
            annotations.filter { it.role == Role.SOURCE_INTENT && it.rule == VALIDATION_RULE_ID }

        assertTrue(sources.isEmpty(), "block comment bypassed line-only commentKinds")
    }

    @Test
    fun legacyBlockCommentKindAcceptsBlockMagicComment() {
        val text =
            """
            fun consume(value: Int): Boolean = value > 0
            fun f(value: Int) {
                /* cint:validation */
                if (consume(value)) {
                    return
                }
            }
            """.trimIndent()
        val input = parseValidationInput("kotlin", "Sample.kt", text)
        val pack = ValidationRulePack().configure(mapOf("commentKinds" to listOf("multiline_comment")))

        val sources =
            analyzePack(input, listOf(BookkeepingRulePack(), pack))
                .annotations
                .filter { it.role == Role.SOURCE_INTENT && it.rule == VALIDATION_RULE_ID }

        assertEquals(1, sources.size)
        assertEquals("comment", sources.single().source)
    }
}
