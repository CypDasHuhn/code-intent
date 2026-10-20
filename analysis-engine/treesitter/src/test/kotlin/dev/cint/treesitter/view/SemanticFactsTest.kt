package dev.cint.treesitter.view

import dev.cint.api.DeclarationKind
import dev.cint.api.GuardKind
import dev.cint.api.ScopeKind
import dev.cint.api.WriteKind
import dev.cint.core.facts.flow.ControlFlowBuilder
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.SymbolFact
import dev.cint.core.facts.oracle.TypeFact
import dev.cint.core.facts.oracle.TypeOracle
import dev.cint.core.facts.store.AnnotationStore
import dev.cint.core.runtime.engine.AnalysisContext
import dev.cint.treesitter.KotlinParser
import dev.cint.treesitter.KotlinSyntaxModel
import dev.cint.treesitter.support.testConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class DetachedNode(
    override val kind: String,
    override val startByte: Int,
    override val endByte: Int,
) : dev.cint.api.SyntaxNode {
    override val startLine: Int get() = 0
    override val startCol: Int get() = startByte
    override val endLine: Int get() = 0
    override val endCol: Int get() = endByte
    override val text: String get() = kind
    override val parent: dev.cint.api.SyntaxNode? get() = null
    override val children: List<dev.cint.api.SyntaxNode> get() = emptyList()
}

private class FixedTypeOracle : TypeOracle {
    override fun typeAt(
        file: SourceFile,
        byteOffset: Int,
    ): TypeFact = TypeFact("org.slf4j.Logger", "lsp", 0.9)

    override fun definitionAt(
        file: SourceFile,
        byteOffset: Int,
    ): SymbolFact? = null
}

class SemanticFactsTest {
    private val text =
        """
        class Box {
            // cint:bookkeeping
            // implementation note
            @Bookkeeping
            var count = 0
            var other = 0

            fun run(input: String?): String {
                count++
                count = 2
                if (input == null) {
                    return "none"
                }
                logger.info("x") {
                    "detail"
                }
                return input
            }
        }
        """.trimIndent()
    private val source = SourceFile("Sample.kt", text)
    private val tree = KotlinParser().parse(source)
    private val syntax = KotlinSyntaxModel
    private val flow = ControlFlowBuilder(tree, syntax).build()
    private val context = AnalysisContext(source, tree, syntax, flow, FixedTypeOracle(), testConfig("kotlin"), AnnotationStore())
    private val view = context.view("BOOKKEEPING", emptySet())

    @Test
    fun declarationsExposeNamesAnnotationsCommentsScopesAndWrites() {
        val declaration = view.declarations().single { it.name == "count" }

        assertEquals(DeclarationKind.PROPERTY, declaration.kind)
        assertEquals("count", declaration.nameNode?.text)
        assertTrue(declaration.hasAnnotation("Bookkeeping"))
        assertEquals(listOf("Bookkeeping"), declaration.annotations)
        assertEquals(2, declaration.precedingComments.size)
        assertEquals("// implementation note", declaration.precedingComment?.text)
        assertEquals("// cint:bookkeeping", declaration.magicComment("bookkeeping")?.text)
        assertTrue(declaration.hasMagicComment("bookkeeping"))
        assertFalse(declaration.hasMagicComment("book"))
        assertEquals(ScopeKind.CLASS, declaration.scope?.kind)
        assertEquals("class_declaration", declaration.scope?.owner?.kind)
        assertEquals(setOf("count++", "count = 2"), declaration.writes().map { it.node.text }.toSet())
        assertTrue(declaration.writes().any { it.kind == WriteKind.INCREMENT })
        assertTrue(declaration.writes().any { it.kind == WriteKind.ASSIGNMENT })

        val function = view.declarations().single { it.name == "run" }
        assertEquals(DeclarationKind.FUNCTION, function.kind)
        assertNotNull(function.scope)
    }

    @Test
    fun writesCommentsAndCommentBeforeAreAvailable() {
        assertTrue(view.writes().any { it.node.text == "count++" && it.kind == WriteKind.INCREMENT })
        assertTrue(view.writes().any { it.node.text == "count = 2" && it.kind == WriteKind.ASSIGNMENT })

        val comment = view.comments().single { it.magicComment != null }
        assertEquals("bookkeeping", comment.magicComment)
        assertTrue(comment.isMagicComment("bookkeeping"))
        assertFalse(comment.isMagicComment("book"))

        val declaration = view.declarations().single { it.name == "count" && it.kind == DeclarationKind.PROPERTY }
        assertEquals("// implementation note", view.commentBefore(declaration.node)?.text)
        val uncommented = view.declarations().single { it.name == "other" }
        assertEquals(null, view.commentBefore(uncommented.node))

        val emit =
            dev.cint.api.Emit.declaration(
                "BOOKKEEPING",
                declaration,
                source = "comment",
                magicComment = "bookkeeping",
            )
        assertEquals(text.indexOf("// cint:bookkeeping"), emit.startByte)
        assertEquals(declaration.node.endByte, emit.endByte)
    }

    @Test
    fun callsExposePathReceiverTypeAndTrailingLambda() {
        val call = view.calls().single { it.callee == "info" }

        assertEquals("logger.info", call.path)
        assertEquals("logger", call.receiver?.text)
        assertEquals("org.slf4j.Logger", view.receiverType(call)?.fqName)
        assertNotNull(call.trailingLambda)
        assertNotNull(call.trailingLambdaSpan)
        assertTrue(call.trailingLambda!!.text.contains("detail"))
    }

    @Test
    fun guardsAndReturnSitesExposeConditionsAndExits() {
        val guard = view.guards().single { it.kind == GuardKind.CONDITIONAL }
        assertEquals("input == null", guard.condition?.text)
        assertTrue(guard.exits.any { it.text.trim() == "return \"none\"" })

        val returns = view.returnSites()
        assertEquals(2, returns.size)
        assertTrue(returns.any { it.expression?.text == "\"none\"" })
        assertTrue(returns.any { it.guards.any { guardFact -> guardFact.condition.text == "input == null" } })
        assertEquals(view.returnSites(), view.returns())
    }

    @Test
    fun detachedNodesHaveNoEnclosingScope() {
        assertNull(syntax.enclosingScope(DetachedNode("property_declaration", 0, 1)))
    }

    @Test
    fun scopesAndEnclosingBlockAreAvailable() {
        val ifNode = view.guards().single { it.kind == GuardKind.CONDITIONAL }.node
        val function = view.functions().single { it.name == "run" }.node

        assertTrue(view.scopes().any { it.kind == ScopeKind.FUNCTION && it.node.startByte == function.startByte })
        val owner = view.enclosingScope(ifNode)?.owner
        assertNotNull(owner)
        assertEquals(function.kind, owner.kind)
        assertEquals(function.startByte, owner.startByte)
        assertEquals(function.endByte, owner.endByte)
        assertNotNull(view.enclosingBlock(ifNode))
        assertTrue(view.scopes().any { it.kind == ScopeKind.LAMBDA })
    }
}
