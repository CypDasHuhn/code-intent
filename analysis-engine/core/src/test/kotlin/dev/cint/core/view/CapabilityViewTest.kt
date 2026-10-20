package dev.cint.core.view

import dev.cint.api.Annotation
import dev.cint.api.CallFact
import dev.cint.api.Contribution
import dev.cint.api.Coverage
import dev.cint.api.FunctionFact
import dev.cint.api.IfFact
import dev.cint.api.PropertyFact
import dev.cint.api.Role
import dev.cint.api.Span
import dev.cint.api.SyntaxNode
import dev.cint.api.TryFact
import dev.cint.core.engine.RuleContext
import dev.cint.core.flow.ControlFlow
import dev.cint.core.model.SourceFile
import dev.cint.core.oracle.TypeOracle
import dev.cint.core.syntax.SyntaxModel
import dev.cint.core.syntax.SyntaxTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class AnnotationContext(
    private val entries: List<Annotation>,
) : RuleContext {
    override val file: SourceFile get() = error("unused")
    override val tree: SyntaxTree get() = error("unused")
    override val syntax: SyntaxModel get() = error("unused")
    override val flow: ControlFlow get() = error("unused")
    override val oracle: TypeOracle get() = error("unused")
    override val calls: List<CallFact> get() = emptyList()
    override val properties: List<PropertyFact> get() = emptyList()
    override val functions: List<FunctionFact> get() = emptyList()
    override val ifs: List<IfFact> get() = emptyList()
    override val tries: List<TryFact> get() = emptyList()
    override val catches: List<SyntaxNode> get() = emptyList()
    override val guardConditions: List<SyntaxNode> get() = emptyList()
    override val imports: List<String> get() = emptyList()
    override val references: Map<String, List<SyntaxNode>> get() = emptyMap()
    override val throws: List<SyntaxNode> get() = emptyList()

    override fun annotations(): List<Annotation> = entries

    override fun covering(node: SyntaxNode): List<Annotation> = emptyList()

    override fun containingAnnotations(node: SyntaxNode): List<Annotation> = entries

    override fun intentsAt(node: SyntaxNode): Set<String> = emptySet()

    override fun coverage(
        node: SyntaxNode,
        effect: Boolean,
    ): Coverage = Coverage(emptySet())
}

class CapabilityViewTest {
    @Test
    fun annotationsRebuildIntentsFromSurvivingProvenance() {
        val contribution =
            Contribution(
                rule = "demo:bookkeeping",
                role = Role.SOURCE_INTENT,
                intents = setOf("BOOKKEEPING"),
                evidence = "bookkeeping",
                span = Span(0, 0, 0, 1),
                source = "treesitter",
                confidence = 1.0,
                startByte = 0,
                endByte = 1,
            )
        val annotation =
            Annotation(
                role = Role.SOURCE_INTENT,
                intents = setOf("BOOKKEEPING", "VALIDATION"),
                evidence = "advertised",
                span = Span(0, 0, 0, 1),
                source = "treesitter",
                confidence = 1.0,
                startByte = 0,
                endByte = 1,
                provenance = listOf(contribution),
            )
        val view =
            CapabilityView(
                context = AnnotationContext(listOf(annotation)),
                owns = "VALIDATION",
                reads = setOf("BOOKKEEPING"),
                pureAnnotations = emptyList(),
            )

        assertEquals(setOf("BOOKKEEPING"), view.annotations("BOOKKEEPING").single().intents)
        assertTrue(view.annotations("VALIDATION").isEmpty())
    }
}
