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
    val path: String? = callee?.let { name -> receiver?.let { "${it.text}.$name" } ?: name },
    val trailingLambda: SyntaxNode? = null,
) {
    val trailingLambdaSpan: Span? get() = trailingLambda?.span()

    val trailingLambdaStartByte: Int? get() = trailingLambda?.startByte

    val trailingLambdaEndByte: Int? get() = trailingLambda?.endByte
}

enum class WriteKind {
    ASSIGNMENT,
    INCREMENT,
    UNKNOWN,
}

data class WriteFact(
    val node: SyntaxNode,
    val kind: WriteKind,
    val reference: SyntaxNode? = null,
)

enum class DeclarationKind {
    PROPERTY,
    VARIABLE,
    FUNCTION,
}

enum class CommentKind {
    LINE,
    BLOCK,
    DOC,
    OTHER,
    ;

    companion object {
        fun fromConfig(names: Collection<String>): Set<CommentKind> =
            names.flatMapTo(linkedSetOf()) { name ->
                when (name.trim().lowercase()) {
                    "comment" -> values().toList()
                    "line", "line_comment" -> listOf(LINE)
                    "block", "block_comment", "multiline_comment" -> listOf(BLOCK)
                    "doc", "documentation", "doc_comment", "documentation_comment" -> listOf(DOC)
                    "other" -> listOf(OTHER)
                    else -> emptyList()
                }
            }
    }
}

data class CommentFact
    @JvmOverloads
    constructor(
        val node: SyntaxNode,
        val magicComment: String? = magicCommentIn(node.text),
        val kind: CommentKind = CommentKind.OTHER,
    ) {
        val text: String get() = node.text

        fun isMagicComment(name: String): Boolean = matchesMagicComment(node.text, name)
    }

data class DeclarationFact(
    val node: SyntaxNode,
    val kind: DeclarationKind,
    val name: String?,
    val nameNode: SyntaxNode?,
    val annotations: List<String> = emptyList(),
    val annotationNodes: List<SyntaxNode> = emptyList(),
    val precedingComments: List<CommentFact> = emptyList(),
    val scope: ScopeFact? = null,
    val writes: List<WriteFact> = emptyList(),
) {
    val precedingComment: CommentFact? get() = precedingComments.lastOrNull()

    val isProperty: Boolean get() = kind == DeclarationKind.PROPERTY

    val isVariable: Boolean get() = kind == DeclarationKind.VARIABLE

    fun hasAnnotation(name: String): Boolean = annotations.any { matchesAnnotation(it, name) }

    fun hasMagicComment(name: String): Boolean = magicComment(name) != null

    fun magicComment(name: String): CommentFact? = precedingComments.lastOrNull { it.isMagicComment(name) }

    fun writes(): List<WriteFact> = writes
}

enum class ScopeKind {
    FILE,
    FUNCTION,
    BLOCK,
    LAMBDA,
    CLASS,
}

data class ScopeFact(
    val node: SyntaxNode,
    val kind: ScopeKind,
    val owner: SyntaxNode? = null,
)

enum class GuardKind {
    CONDITIONAL,
    LOOP,
    OTHER,
}

data class GuardFact(
    val node: SyntaxNode,
    val kind: GuardKind,
    val condition: SyntaxNode?,
    val thenBody: SyntaxNode?,
    val elseBody: SyntaxNode?,
    val exits: List<SyntaxNode> = emptyList(),
)

data class ReturnGuardFact(
    val condition: SyntaxNode,
    val guard: SyntaxNode,
    val onTrue: Boolean,
)

data class ReturnFact(
    val node: SyntaxNode,
    val expression: SyntaxNode?,
    val guards: List<ReturnGuardFact> = emptyList(),
    val loop: SyntaxNode? = null,
    val handler: SyntaxNode? = null,
    val scope: ScopeFact? = null,
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

private fun magicCommentIn(text: String): String? {
    val match = Regex("""cint\s*:\s*([A-Za-z0-9_.-]+)""", RegexOption.IGNORE_CASE).find(text) ?: return null
    return match.groupValues[1]
}

private fun matchesMagicComment(
    text: String,
    name: String,
): Boolean = Regex("""cint\s*:\s*${Regex.escape(name)}(?![A-Za-z0-9_.-])""", RegexOption.IGNORE_CASE).containsMatchIn(text)

private fun matchesAnnotation(
    annotation: String,
    pattern: String,
): Boolean {
    val actual = annotation.trim()
    val expected = pattern.trim()
    if (actual == expected) return true
    if (actual.contains('(') || expected.contains('(')) return false
    return actual.endsWith(".$expected")
}
