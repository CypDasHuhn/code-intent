package dev.cint.treesitter.validation

import dev.cint.treesitter.support.parseValidationInput
import dev.cint.treesitter.support.validationSources
import kotlin.test.Test
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
}
