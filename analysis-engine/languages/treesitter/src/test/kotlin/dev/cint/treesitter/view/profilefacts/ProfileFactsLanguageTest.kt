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
        for (fixture in fixtures) {
            val source = SourceFile(fixture.fileName, fixture.text)
            val tree = parse(fixture.language, source)
            val syntax = syntax(fixture.language)
            val flow = ControlFlowBuilder(tree, syntax).build()
            val context =
                AnalysisContext(
                    source,
                    tree,
                    syntax,
                    flow,
                    NullOracle,
                    testConfig(fixture.language),
                    AnnotationStore(),
                )
            val view = context.view("BOOKKEEPING", emptySet())

            val field = view.declarations().single { it.name == "count" && it.kind == DeclarationKind.PROPERTY }
            val local = view.declarations().single { it.name == "count" && it.kind == DeclarationKind.VARIABLE }

            assertTrue(field.hasAnnotation("Bookkeeping"), "${fixture.language} annotation")
            assertEquals(listOf("Bookkeeping"), field.annotations, "${fixture.language} normalized annotation")
            assertTrue(field.hasMagicComment("bookkeeping"), "${fixture.language} magic comment")
            assertTrue(field.isProperty, "${fixture.language} field is property")
            assertTrue(local.isVariable, "${fixture.language} local is variable")
            assertEquals(ScopeKind.CLASS, field.scope?.kind, "${fixture.language} field scope")
            assertEquals("class_declaration", field.scope?.owner?.kind, "${fixture.language} class owner")
            assertTrue(view.scopes().any { it.kind == ScopeKind.CLASS }, "${fixture.language} class scope")
            assertEquals(fixture.fieldWrites, field.writes().map { it.node.text }.toSet(), "${fixture.language} field writes")
            assertEquals(setOf(fixture.localWrite), local.writes().map { it.node.text }.toSet(), "${fixture.language} local writes")
            assertTrue(field.writes().any { it.kind == WriteKind.INCREMENT }, "${fixture.language} increment write")
            assertTrue(field.writes().any { it.kind == WriteKind.ASSIGNMENT }, "${fixture.language} assignment write")

            val conditional = view.guards().single { it.kind == GuardKind.CONDITIONAL }
            val loop = view.guards().single { it.kind == GuardKind.LOOP }
            assertNotNull(conditional.condition, "${fixture.language} if condition")
            assertTrue(conditional.exits.isNotEmpty(), "${fixture.language} if exits")
            assertNotNull(loop.condition, "${fixture.language} loop condition")
            assertTrue(loop.exits.isNotEmpty(), "${fixture.language} loop exits")

            val call = view.calls().single { it.callee == fixture.callName }
            assertNotNull(call.path, "${fixture.language} call path")
            assertNotNull(call.trailingLambda, "${fixture.language} trailing lambda")
            assertTrue(call.trailingLambda!!.text.contains("detail"), "${fixture.language} trailing lambda text")
        }
    }
}
