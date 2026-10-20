package dev.cint.rulepacks.tests.support

import dev.cint.rulepacks.bookkeeping.BookkeepingRulePack
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
    fun loadsFixturesAndAssertsIntentSpans() {
        val text = fixture("kotlin/Bookkeeping.kt")
        val analysis = analyzePack("kotlin", text, listOf(BookkeepingRulePack()))
        val annotation = analysis.source("BOOKKEEPING").first()

        analysis.assertSourceCount("BOOKKEEPING", analysis.source("BOOKKEEPING").size)
        analysis.assertSourceSpan("BOOKKEEPING", annotation.startByte, annotation.endByte)
        analysis.assertSourceEvidence("BOOKKEEPING", annotation.evidence)
        analysis.assertNoSource("MISSING")
        assertTrue(text.substring(annotation.startByte, annotation.endByte).isNotBlank())
    }
}
