package dev.cint.treesitter.language

import dev.cint.api.Annotation
import dev.cint.api.Role
import dev.cint.core.CoreContributors
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.NullOracle
import dev.cint.core.runtime.scheduler.RuleEngine
import dev.cint.core.syntax.ProfileSyntaxModel
import dev.cint.core.syntax.SyntaxProfiles
import dev.cint.treesitter.JavascriptParser
import dev.cint.treesitter.TypescriptParser
import dev.cint.treesitter.support.testConfig
import dev.cint.treesitter.support.testPacks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebBlockTest {
    private fun analyze(
        language: String,
        text: String,
    ): List<Annotation> {
        val path = if (language == "javascript") "Sample.js" else "Sample.ts"
        val source = SourceFile(path, text)
        val tree =
            if (language == "javascript") {
                JavascriptParser().parse(source)
            } else {
                TypescriptParser().parse(source)
            }
        val model = ProfileSyntaxModel(SyntaxProfiles.load(language))
        return RuleEngine().analyze(source, tree, model, NullOracle, testConfig(language), testPacks(language))
    }

    @Test
    fun javascriptConsoleLogging() {
        val annotations = analyze("javascript", """function f() { console.log("x") }""")
        val source = annotations.first { it.role == Role.SOURCE_INTENT }
        assertEquals("logging:logging", source.rule)
        assertEquals(setOf("LOGGING"), source.intents)
    }

    @Test
    fun javascriptThrowIsErrorHandling() {
        val annotations = analyze("javascript", """function f() { throw new Error("x") }""")
        assertTrue(
            annotations.any {
                it.rule == "error-handling-javascript:error-handling" && it.intents == setOf("ERROR_HANDLING")
            },
        )
    }

    @Test
    fun javascriptValidationGuard() {
        val text =
            """
            function f(values) {
                if (values.isEmpty()) {
                    return
                }
            }
            """.trimIndent()
        val annotations = analyze("javascript", text)
        val guard = annotations.first { it.rule == "validation:validation" }
        assertEquals(setOf("VALIDATION"), guard.intents)
        val scaffold = annotations.first { it.role == Role.SCAFFOLD }
        assertEquals(setOf("VALIDATION"), scaffold.intents)
    }

    @Test
    fun javascriptBookkeepingCommentTagsWrites() {
        val text =
            """
            // cint:bookkeeping
            let skipped = 0
            function f() {
                skipped++
            }
            """.trimIndent()
        val annotations = analyze("javascript", text)
        assertTrue(
            annotations.any {
                it.rule == "bookkeeping:bookkeeping" && it.intents == setOf("BOOKKEEPING")
            },
        )
        val write = annotations.first { it.rule == "bookkeeping:bookkeeping" && it.evidence == "skipped++" }
        assertEquals("skipped++", write.evidence)
    }

    @Test
    fun javascriptDerivativeUse() {
        val text =
            """
            function f() {
                const message = "x"
                console.log(message)
            }
            """.trimIndent()
        val derivative =
            analyze("javascript", text).first {
                it.role == Role.DERIVATIVE_USE && it.evidence.contains("message")
            }
        assertEquals(setOf("LOGGING"), derivative.intents)
    }

    @Test
    fun returnCategorizationUsesControlFlow() {
        val text =
            """
            function run(options) {
                if (!options.id) {
                    return 0;
                }
                console.log("x");
                return options.id.length;
            }
            """.trimIndent()
        val annotations = analyze("typescript", text)
        val returns = annotations.filter { it.rule == CoreContributors.RETURN_CATEGORY }
        assertTrue(returns.any { it.intents == setOf("VALIDATION") })
        assertTrue(returns.any { it.intents == setOf("UNTAGGED") })
    }

    @Test
    fun typescriptConsoleLogging() {
        val annotations = analyze("typescript", """function f() { console.log("x") }""")
        val source = annotations.first { it.role == Role.SOURCE_INTENT }
        assertEquals("logging:logging", source.rule)
        assertEquals(setOf("LOGGING"), source.intents)
    }

    @Test
    fun typescriptClassFieldBookkeeping() {
        val text =
            """
            class C {
                @Bookkeeping
                skipped: number = 0;
                bump(): void {
                    this.skipped++;
                }
            }
            """.trimIndent()
        val annotations = analyze("typescript", text)
        assertTrue(
            annotations.any {
                it.rule == "bookkeeping:bookkeeping" && it.intents == setOf("BOOKKEEPING")
            },
        )
        val write = annotations.first { it.rule == "bookkeeping:bookkeeping" && it.evidence == "this.skipped++" }
        assertEquals("this.skipped++", write.evidence)
    }
}
