package dev.cint.core.runtime.engine.support

import dev.cint.api.FunctionFact
import dev.cint.api.SyntaxNode
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.syntax.ProfileSyntaxModel
import dev.cint.core.syntax.SyntaxModel
import dev.cint.core.syntax.SyntaxProfile
import dev.cint.core.syntax.SyntaxTree

internal class FakeNode(
    override val kind: String,
    override val startByte: Int,
    override val endByte: Int,
    override val text: String,
    override val children: List<SyntaxNode> = emptyList(),
    override val parent: SyntaxNode? = null,
) : SyntaxNode {
    override val startLine: Int get() = 0
    override val startCol: Int get() = startByte
    override val endLine: Int get() = 0
    override val endCol: Int get() = endByte
}

private val emptyProfile =
    SyntaxProfile(
        language = "kotlin",
        groups = emptyMap(),
        tokens = emptyMap(),
        keywords = emptyMap(),
        operators = emptyMap(),
        fields = emptyMap(),
    )

private val emptyModel = ProfileSyntaxModel(emptyProfile)

internal fun fakeFunction(text: String = "fun f"): FunctionFact =
    FunctionFact(
        node = FakeNode("function_declaration", 0, text.length, text),
        name = "f",
        nameNode = FakeNode("identifier", 0, 1, "f"),
        body = null,
    )

internal fun fakeSyntax(functions: List<FunctionFact> = listOf(fakeFunction())): SyntaxModel =
    object : SyntaxModel by emptyModel {
        override fun functions(tree: SyntaxTree): Sequence<FunctionFact> = functions.asSequence()
    }

internal fun fakeTree(text: String): SyntaxTree {
    val children =
        listOf(
            FakeNode("identifier", 0, 1, text.take(1)),
            FakeNode("identifier", 1, 2, text.drop(1).take(1)),
            FakeNode("identifier", 2, 3, text.drop(2).take(1)),
        )
    val root = FakeNode("program", 0, text.length, text, children)
    return object : SyntaxTree {
        override val source: SourceFile = SourceFile("Sample.kt", text)
        override val root: SyntaxNode = root
    }
}
