package dev.cint.treesitter.view.profilefacts

import dev.cint.api.DeclarationKind
import dev.cint.api.GuardKind
import dev.cint.api.ScopeKind
import dev.cint.api.WriteKind
import dev.cint.core.facts.flow.ControlFlowBuilder
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.NullOracle
import dev.cint.core.facts.store.AnnotationStore
import dev.cint.core.runtime.engine.AnalysisContext
import dev.cint.core.test.testConfig
import dev.cint.treesitter.view.support.fixtures
import dev.cint.treesitter.view.support.parse
import dev.cint.treesitter.view.support.syntax
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProfileFactsLanguageTest {
    @Test
    fun profilesExposeDeclarationWriteGuardAndClassScopeFacts() {
        for ((language, fileName, text, callName, fieldWrites, localWrite) in fixtures) {
            val source = SourceFile(fileName, text)
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

            val field = view.declarations().single { it.name == "count" && it.kind == DeclarationKind.PROPERTY }
            val local = view.declarations().single { it.name == "count" && it.kind == DeclarationKind.VARIABLE }

            assertTrue(field.hasAnnotation("Bookkeeping"), "$language annotation")
            assertEquals(listOf("Bookkeeping"), field.annotations, "$language normalized annotation")
            assertTrue(field.hasMagicComment("bookkeeping"), "$language magic comment")
            assertTrue(field.isProperty, "$language field is property")
            assertTrue(local.isVariable, "$language local is variable")
            assertEquals(ScopeKind.CLASS, field.scope?.kind, "$language field scope")
            assertEquals("class_declaration", field.scope?.owner?.kind, "$language class owner")
            assertTrue(view.scopes().any { it.kind == ScopeKind.CLASS }, "$language class scope")
            assertEquals(fieldWrites, field.writes().map { it.node.text }.toSet(), "$language field writes")
            assertEquals(setOf(localWrite), local.writes().map { it.node.text }.toSet(), "$language local writes")
            assertTrue(field.writes().any { it.kind == WriteKind.INCREMENT }, "$language increment write")
            assertTrue(field.writes().any { it.kind == WriteKind.ASSIGNMENT }, "$language assignment write")

            val conditional = view.guards().single { it.kind == GuardKind.CONDITIONAL }
            val loop = view.guards().single { it.kind == GuardKind.LOOP }
            assertNotNull(conditional.condition, "$language if condition")
            assertTrue(conditional.exits.isNotEmpty(), "$language if exits")
            assertNotNull(loop.condition, "$language loop condition")
            assertTrue(loop.exits.isNotEmpty(), "$language loop exits")

            val call = view.calls().single { it.callee == callName }
            assertNotNull(call.path, "$language call path")
            assertNotNull(call.trailingLambda, "$language trailing lambda")
            assertTrue(call.trailingLambda!!.text.contains("detail"), "$language trailing lambda text")
        }
    }
}
