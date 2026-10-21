package dev.cint.rulepacks.tests.intents.structural

import dev.cint.api.Annotation
import dev.cint.api.CallFact
import dev.cint.api.CommentFact
import dev.cint.api.Coverage
import dev.cint.api.DeclarationFact
import dev.cint.api.Emit
import dev.cint.api.FunctionFact
import dev.cint.api.GuardFact
import dev.cint.api.IfFact
import dev.cint.api.Intent
import dev.cint.api.PropertyFact
import dev.cint.api.ReturnFact
import dev.cint.api.ScopeFact
import dev.cint.api.SyntaxNode
import dev.cint.api.TryFact
import dev.cint.api.TypeRef
import dev.cint.api.View
import dev.cint.api.WriteFact
import dev.cint.rulepacks.intents.structural.ReturnValueRulePack
import dev.cint.rulepacks.tests.support.parsePackInput
import kotlin.test.Test
import kotlin.test.assertEquals

private open class EmptyView : View {
    override fun language(): String = "kotlin"

    override fun typeAt(node: SyntaxNode): TypeRef? = null

    override fun receiverType(call: CallFact): TypeRef? = null

    override fun calls(): List<CallFact> = emptyList()

    override fun properties(): List<PropertyFact> = emptyList()

    override fun functions(): List<FunctionFact> = emptyList()

    override fun declarations(): List<DeclarationFact> = emptyList()

    override fun writes(): List<WriteFact> = emptyList()

    override fun comments(): List<CommentFact> = emptyList()

    override fun commentBefore(node: SyntaxNode): CommentFact? = null

    override fun scopes(): List<ScopeFact> = emptyList()

    override fun enclosingScope(node: SyntaxNode): ScopeFact? = null

    override fun enclosingBlock(node: SyntaxNode): SyntaxNode? = null

    override fun guards(): List<GuardFact> = emptyList()

    override fun returnSites(): List<ReturnFact> = emptyList()

    override fun ifs(): List<IfFact> = emptyList()

    override fun guardConditions(): List<SyntaxNode> = emptyList()

    override fun tries(): List<TryFact> = emptyList()

    override fun catches(): List<SyntaxNode> = emptyList()

    override fun imports(): List<String> = emptyList()

    override fun references(): Map<String, List<SyntaxNode>> = emptyMap()

    override fun throws(): List<SyntaxNode> = emptyList()

    override fun coverage(node: SyntaxNode): Coverage = Coverage(emptySet())

    override fun enclosingFunction(node: SyntaxNode): SyntaxNode? = null

    override fun enclosingDeclaration(node: SyntaxNode): SyntaxNode? = null

    override fun writes(reference: SyntaxNode): SyntaxNode? = null

    override fun isAnnotatedPure(node: SyntaxNode): Boolean = false

    override fun isConstant(node: SyntaxNode): Boolean = false

    override fun isSideEffectFree(node: SyntaxNode): Boolean = false

    override fun statements(container: SyntaxNode): List<SyntaxNode>? = null

    override fun isExit(node: SyntaxNode): Boolean = false

    override fun annotations(intent: Intent): List<Annotation> = emptyList()

    override fun intentsAt(node: SyntaxNode): Set<Intent> = emptySet()
}

class ReturnValueLabeledReturnTest {
    @Test
    fun labeledReturnValuesEmitButBareLabeledReturnsDoNot() {
        val text =
            """
            fun f(values: List<Int>) {
                values.map { return@map it + 1 }
                values.forEach { return@forEach }
            }
            """.trimIndent()
        val tree = parsePackInput("kotlin", text).tree
        val nodes = tree.root.descendants().toList()
        val valueSite = nodes.first { it.text.trim() == "return@map it + 1" }
        val valueExpression =
            valueSite.children.lastOrNull { child ->
                val text = child.text.trim()
                text.isNotEmpty() && text != "return" && !text.startsWith("@")
            } ?: error("missing labeled return expression: ${valueSite.children.map { it.kind + ":" + it.text }}")
        val bareSite = nodes.first { it.text.trim() == "return@forEach" }
        val view =
            object : EmptyView() {
                override fun returnSites(): List<ReturnFact> =
                    listOf(
                        ReturnFact(valueSite, valueExpression),
                        ReturnFact(bareSite, null),
                    )
            }

        val emits = ReturnValueRulePack().rules().single().run(view)
        val emit = emits.single() as Emit

        assertEquals(valueExpression.startByte, emit.startByte)
        assertEquals(valueExpression.endByte, emit.endByte)
    }
}
