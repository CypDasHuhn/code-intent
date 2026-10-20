package dev.cint.treesitter.view.profilefacts

import dev.cint.api.Emit
import dev.cint.core.facts.flow.ControlFlowBuilder
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.NullOracle
import dev.cint.core.facts.store.AnnotationStore
import dev.cint.core.runtime.engine.AnalysisContext
import dev.cint.core.test.testConfig
import dev.cint.treesitter.view.support.parse
import dev.cint.treesitter.view.support.syntax
import dev.cint.treesitter.view.support.view
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CallFactsLanguageTest {
    @Test
    fun nonTrailingLambdaArgumentsAreNotReported() {
        val cases =
            listOf(
                "kotlin" to
                    """
                    fun f() {
                        foo(bar { })
                    }
                    """.trimIndent(),
                "javascript" to
                    """
                    function f() {
                        setTimeout(() => {}, 100);
                    }
                    """.trimIndent(),
                "typescript" to
                    """
                    function f(): void {
                        setTimeout(() => {}, 100);
                    }
                    """.trimIndent(),
                "csharp" to
                    """
                    class C {
                        void F() {
                            setTimeout(() => {}, 100);
                        }
                    }
                    """.trimIndent(),
            )
        for ((language, text) in cases) {
            val source = SourceFile("Sample", text)
            val tree = parse(language, source)
            val syntax = syntax(language)
            val flow = ControlFlowBuilder(tree, syntax).build()
            val context =
                AnalysisContext(
                    source,
                    tree,
                    syntax,
                    flow,
                    NullOracle,
                    testConfig(language),
                    AnnotationStore(),
                )
            val view = context.view("BOOKKEEPING", emptySet())
            val name = if (language == "kotlin") "foo" else "setTimeout"
            val outer = view.calls().single { it.callee == name }
            assertTrue(outer.trailingLambda == null, "$language incorrectly reported a trailing lambda")
        }
    }

    @Test
    fun nestedNonTrailingLambdaDoesNotPromoteTheCallSite() {
        val source = SourceFile("Sample.js", "function f() { foo()(() => {}, 2); }")
        val view = view("javascript", source)
        val call = view.calls().single { it.node.text == "foo()" }

        assertEquals("foo()", call.node.text)
        assertEquals("foo()", call.site.text)
        val emit = Emit.call("LOGGING", call)
        assertEquals(call.node.span(), emit.span)
    }
}
