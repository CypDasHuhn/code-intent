package com.semanticpainter.treesitter

import com.semanticpainter.core.config.loadIntentConfig
import com.semanticpainter.core.engine.AnalysisEngine
import com.semanticpainter.core.model.Annotation
import com.semanticpainter.core.model.Role
import com.semanticpainter.core.model.SourceFile
import com.semanticpainter.core.oracle.NullOracle
import com.semanticpainter.core.rules.IntentBlocks
import com.semanticpainter.core.syntax.ProfileSyntaxModel
import com.semanticpainter.core.syntax.SyntaxProfiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebBlockTest {
    private val javascriptConfig = loadIntentConfig(null, "javascript")
    private val typescriptConfig = loadIntentConfig(null, "typescript")

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
        val config = if (language == "javascript") javascriptConfig else typescriptConfig
        val model = ProfileSyntaxModel(SyntaxProfiles.load(language))
        return AnalysisEngine(IntentBlocks.build(config)).analyze(source, tree, model, NullOracle, config)
    }

    @Test
    fun javascriptConsoleLogging() {
        val annotations = analyze("javascript", """function f() { console.log("x") }""")
        val source = annotations.first { it.role == Role.SOURCE_INTENT }
        assertEquals("print", source.rule)
        assertEquals(setOf("LOGGING"), source.intents)
    }

    @Test
    fun javascriptThrowIsErrorHandling() {
        val annotations = analyze("javascript", """function f() { throw new Error("x") }""")
        assertTrue(
            annotations.any {
                it.rule == "error-handling-throw" && it.intents == setOf("ERROR_HANDLING")
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
        val guard = annotations.first { it.rule == "validation-guard" }
        assertEquals(setOf("VALIDATION"), guard.intents)
        val scaffold = annotations.first { it.role == Role.SCAFFOLD }
        assertEquals(setOf("VALIDATION"), scaffold.intents)
    }

    @Test
    fun javascriptBookkeepingCommentTagsWrites() {
        val text =
            """
            // scp:bookkeeping
            let skipped = 0
            function f() {
                skipped++
            }
            """.trimIndent()
        val annotations = analyze("javascript", text)
        assertTrue(
            annotations.any {
                it.rule == "bookkeeping-declaration" && it.intents == setOf("BOOKKEEPING")
            },
        )
        val write = annotations.first { it.rule == "bookkeeping-write" }
        assertEquals("skipped++", write.evidence)
    }

    @Test
    fun javascriptDerivativeUse() {
        val text =
            """
            function f() {
                const message = format()
                console.log(message)
            }
            function format() { return "x" }
            """.trimIndent()
        val derivative =
            analyze("javascript", text).first {
                it.role == Role.DERIVATIVE_USE && it.evidence.contains("message")
            }
        assertEquals(setOf("LOGGING"), derivative.intents)
    }

    @Test
    fun typescriptConsoleLogging() {
        val annotations = analyze("typescript", """function f() { console.log("x") }""")
        val source = annotations.first { it.role == Role.SOURCE_INTENT }
        assertEquals("print", source.rule)
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
                it.rule == "bookkeeping-declaration" && it.intents == setOf("BOOKKEEPING")
            },
        )
        val write = annotations.first { it.rule == "bookkeeping-write" }
        assertEquals("this.skipped++", write.evidence)
    }
}
