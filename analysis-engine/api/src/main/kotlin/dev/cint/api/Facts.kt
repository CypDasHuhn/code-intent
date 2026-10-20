package dev.cint.api

interface SyntaxNode {
    val kind: String
    val startByte: Int
    val endByte: Int
    val startLine: Int
    val startCol: Int
    val endLine: Int
    val endCol: Int
    val text: String
    val parent: SyntaxNode?
    val children: List<SyntaxNode>

    fun span(): Span = Span(startLine, startCol, endLine, endCol)

    fun child(kind: String): SyntaxNode? = children.firstOrNull { it.kind == kind }

    fun childByField(name: String): SyntaxNode? = null

    fun childrenOfKind(kind: String): List<SyntaxNode> = children.filter { it.kind == kind }

    fun descendants(): Sequence<SyntaxNode> =
        sequence {
            yield(this@SyntaxNode)
            for (child in children) {
                yieldAll(child.descendants())
            }
        }

    fun descendants(kind: String): Sequence<SyntaxNode> = descendants().filter { it.kind == kind }
}

data class CallFact(
    val node: SyntaxNode,
    val site: SyntaxNode,
    val callee: String?,
    val receiver: SyntaxNode?,
)

data class PropertyFact(
    val node: SyntaxNode,
    val name: String?,
    val nameNode: SyntaxNode?,
    val initializer: SyntaxNode?,
)

data class FunctionFact(
    val node: SyntaxNode,
    val name: String?,
    val nameNode: SyntaxNode?,
    val body: SyntaxNode?,
)

data class IfFact(
    val node: SyntaxNode,
    val condition: SyntaxNode?,
    val thenBody: SyntaxNode?,
    val elseBody: SyntaxNode?,
    val elseKeyword: SyntaxNode?,
)

data class TryFact(
    val node: SyntaxNode,
    val body: SyntaxNode?,
    val catches: List<SyntaxNode>,
    val finallyBlock: SyntaxNode?,
    val headerStart: SyntaxNode?,
    val headerEnd: SyntaxNode?,
)

data class Coverage(
    val intents: Set<Intent>,
) {
    val hasRealIntent: Boolean get() = intents.any { it != UNTAGGED_INTENT }
    val hasUntagged: Boolean get() = UNTAGGED_INTENT in intents
}

data class TypeRef(
    val fqName: String,
    val source: String,
    val confidence: Double,
)
