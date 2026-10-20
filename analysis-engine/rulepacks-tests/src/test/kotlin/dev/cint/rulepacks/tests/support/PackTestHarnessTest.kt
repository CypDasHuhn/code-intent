package dev.cint.rulepacks.tests.support

import dev.cint.api.Annotation
import dev.cint.api.Contribution
import dev.cint.api.Role
import dev.cint.api.Span
import dev.cint.rulepacks.bookkeeping.BookkeepingRulePack
import dev.cint.test.fixtures.text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PackTestHarnessTest {
    @Test
    fun dispatchesParsersAndSyntaxModelsByLanguage() {
        val sources =
            mapOf(
                "kotlin" to """fun f() { println("x") }""",
                "javascript" to """function f() { console.log("x"); }""",
                "typescript" to """function f(): void { console.log("x"); }""",
                "csharp" to """class C { void F() { Console.WriteLine("x"); } }""",
            )

        for ((language, text) in sources) {
            val input = parsePackInput(language, text)
            assertTrue(
                input.tree.root.kind
                    .isNotBlank(),
                "$language tree",
            )
            assertTrue(input.syntax.declarations(input.tree).any(), "$language declarations")
            assertEquals(language, input.config.language)
        }
    }

    @Test
    fun annotationTextUsesUtf8ByteOffsets() {
        val source = "πfun f() {}"
        val contribution =
            Contribution(
                rule = "test:rule",
                role = Role.SOURCE_INTENT,
                intents = setOf("TEST"),
                span = Span(0, 0, 0, 0),
                source = "treesitter",
                confidence = 1.0,
                startByte = 2,
                endByte = 8,
            )
        val annotation =
            Annotation(
                role = Role.SOURCE_INTENT,
                intents = setOf("TEST"),
                span = Span(0, 0, 0, 0),
                source = "treesitter",
                confidence = 1.0,
                startByte = 2,
                endByte = 8,
                provenance = listOf(contribution),
            )

        assertEquals("fun f(", annotation.text(source))
    }

    @Test
    fun loadsFixturesAndAssertsIntentSpans() {
        val text = fixture("kotlin/Bookkeeping.kt")
        val analysis = analyzePack("kotlin", text, listOf(BookkeepingRulePack()))
        val annotation = analysis.source("BOOKKEEPING").first()

        analysis.assertSourceCount("BOOKKEEPING", analysis.source("BOOKKEEPING").size)
        analysis.assertSourceSpan("BOOKKEEPING", annotation.startByte, annotation.endByte)
        analysis.assertSourceText("BOOKKEEPING", annotation.text(text))
        analysis.assertNoSource("MISSING")
        assertTrue(text.substring(annotation.startByte, annotation.endByte).isNotBlank())
    }
}
