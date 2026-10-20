package dev.cint.treesitter.view.profilefacts

import dev.cint.api.DeclarationKind
import dev.cint.api.ScopeKind
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

class ScopeFactsLanguageTest {
    @Test
    fun objectLiteralsAndCreationsAreNotClassScopes() {
        val cases =
            listOf(
                "javascript" to
                    """
                    function f() {
                        const value = {};
                    }
                    """.trimIndent(),
                "typescript" to
                    """
                    function f(): void {
                        const value = {};
                    }
                    """.trimIndent(),
                "csharp" to
                    """
                    class C {
                        void F() {
                            var value = new Box();
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
            assertTrue(
                view.scopes().none {
                    it.kind == ScopeKind.CLASS &&
                        (it.node.kind == "object" || it.node.kind == "object_creation_expression")
                },
                "$language classified an object expression as a class scope",
            )
        }
    }

    @Test
    fun typeLiteralsAndNamespaceBodiesAreNotClassScopes() {
        val typescript = SourceFile("Sample.ts", "type T = { value: string }")
        val tsTree = parse("typescript", typescript)
        val tsScopes = view("typescript", typescript).scopes()
        val typeLiteral = tsTree.root.descendants("object_type").single()
        assertTrue(
            tsScopes.none {
                it.kind == ScopeKind.CLASS &&
                    it.node.startByte == typeLiteral.startByte &&
                    it.node.endByte == typeLiteral.endByte
            },
            "TypeScript object type was classified as a class scope",
        )

        val csharp = SourceFile("Sample.cs", "namespace N { class C { int value = 0; } }")
        val csTree = parse("csharp", csharp)
        val csView = view("csharp", csharp)
        val namespaceBody =
            csTree.root
                .descendants("namespace_declaration")
                .single()
                .children
                .single { it.kind == "declaration_list" }
        assertTrue(
            csView.scopes().none {
                it.kind == ScopeKind.CLASS &&
                    it.node.startByte == namespaceBody.startByte &&
                    it.node.endByte == namespaceBody.endByte
            },
            "C# namespace body was classified as a class scope",
        )
        assertTrue(csView.scopes().any { it.kind == ScopeKind.CLASS }, "C# class scope missing")
    }

    @Test
    fun programRootIsFileScopeForJavascriptAndTypescript() {
        val sources =
            mapOf(
                "javascript" to "let top = 0;\ntop = 1;",
                "typescript" to "let top: number = 0;\ntop = 1;",
            )
        for ((language, text) in sources) {
            val view = view(language, SourceFile("Sample", text))
            val declaration = view.declarations().single { it.name == "top" }
            assertEquals(ScopeKind.FILE, declaration.scope?.kind, "$language declaration scope")
            assertEquals(DeclarationKind.PROPERTY, declaration.kind, "$language top-level kind")
            assertEquals(ScopeKind.FILE, view.enclosingScope(declaration.node)?.kind, "$language enclosing scope")
            assertTrue(
                view.scopes().any {
                    it.kind == ScopeKind.FILE &&
                        it.node.startByte == declaration.scope?.node?.startByte &&
                        it.node.endByte == declaration.scope?.node?.endByte
                },
                "$language root FILE scope is not exposed",
            )
        }
    }

    @Test
    fun objectAndCompanionBodiesBelongToTheirOwners() {
        val source =
            SourceFile(
                "Sample.kt",
                """
                class Box {
                    var total = 0
                    companion object {
                        var total = 0
                        fun bump() {
                            total++
                        }
                    }
                    object Registry {
                        var size = 0
                        fun reset() {
                            size = 0
                        }
                    }
                    val literal = object {
                        var x = 0
                        fun touch() {
                            x = 1
                        }
                    }
                }
                """.trimIndent(),
            )
        val view = view("kotlin", source)
        val totals = view.declarations().filter { it.name == "total" }
        val companionTotal = totals.single { it.scope?.owner?.kind == "companion_object" }
        val outerTotal = totals.single { it.scope?.owner?.kind == "class_declaration" }
        val size = view.declarations().single { it.name == "size" }
        val x = view.declarations().single { it.name == "x" }

        assertTrue(outerTotal.writes().isEmpty(), "outer field received a companion write")
        assertEquals(setOf("total++"), companionTotal.writes().map { it.node.text }.toSet())
        assertEquals("object_declaration", size.scope?.owner?.kind)
        assertEquals(setOf("size = 0"), size.writes().map { it.node.text }.toSet())
        assertEquals("object_literal", x.scope?.owner?.kind)
        assertEquals(setOf("x = 1"), x.writes().map { it.node.text }.toSet())
    }
}
