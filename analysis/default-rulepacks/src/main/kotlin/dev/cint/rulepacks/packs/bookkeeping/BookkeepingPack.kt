package dev.cint.rulepacks.packs.bookkeeping

import dev.cint.contracts.data.Span
import dev.cint.contracts.facts.CommentFact
import dev.cint.contracts.facts.DeclarationFact
import dev.cint.contracts.facts.DeclarationKind
import dev.cint.contracts.facts.SyntaxNode
import dev.cint.contracts.facts.matchesDirective
import dev.cint.contracts.ports.RuleEmission
import dev.cint.contracts.ports.RuleEvaluationView
import dev.cint.contracts.ports.RuleEvaluator
import dev.cint.contracts.ports.RuleSpec
import dev.cint.rulepacks.ANNOTATION_SOURCE
import dev.cint.rulepacks.COMMENT_SOURCE
import dev.cint.rulepacks.PackEvaluator
import dev.cint.rulepacks.PackRules
import dev.cint.rulepacks.decodeRule
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
private data class BookkeepingData(
    val annotationNames: Set<String> = emptySet(),
    val directive: String? = null,
)

internal object BookkeepingPack : PackRules {
    override val packId = "bookkeeping"

    override fun create(
        spec: RuleSpec,
        data: JsonObject,
    ): RuleEvaluator = BookkeepingRule(spec, data.decodeRule())
}

private class BookkeepingRule(
    spec: RuleSpec,
    private val data: BookkeepingData,
) : PackEvaluator(spec) {
    override fun evaluate(view: RuleEvaluationView): List<RuleEmission> {
        val result = mutableListOf<RuleEmission>()
        val directive = data.directive ?: return emptyList()
        for (declaration in view.declarations) {
            if (declaration.kind == DeclarationKind.FUNCTION) continue
            val node = view.node(declaration.nodeId) ?: continue
            val annotationNames = declaration.annotationNames.ifEmpty { declaration.annotations }
            val source =
                when {
                    annotationNames.any { it in data.annotationNames } -> {
                        ANNOTATION_SOURCE
                    }

                    declaration.precedingCommentIds.any { id ->
                        view.comments.firstOrNull { it.nodeId == id }?.matchesDirective(directive) == true
                    } -> {
                        COMMENT_SOURCE
                    }

                    view.comments.any { comment -> isBookkeepingComment(view, comment, node, directive) } -> {
                        COMMENT_SOURCE
                    }

                    else -> {
                        continue
                    }
                }
            val start = if (source == COMMENT_SOURCE) precedingComment(view, declaration, node, directive) ?: node else node
            val endSpan = declaration.trimmedEndSpan ?: node.span
            val endByte = declaration.trimmedEndByte ?: node.endByte
            result +=
                view.emitSpan(
                    Span(start.startLine, start.startCol, endSpan.endLine, endSpan.endCol),
                    start.startByte,
                    endByte,
                    1.0,
                    source,
                )
            val writeIds =
                if (declaration.name != null) {
                    (declaration.writeIds + view.writes.filter { it.targetName == declaration.name }.map { it.nodeId }).distinct()
                } else {
                    declaration.writeIds
                }
            for (writeId in writeIds) {
                view.node(writeId)?.let { write -> view.emitNode(write, 1.0, source)?.let(result::add) }
            }
        }
        return result.distinctBy { Triple(it.startByte, it.endByte, it.intents) }
    }

    private fun precedingComment(
        view: RuleEvaluationView,
        declaration: DeclarationFact,
        node: SyntaxNode,
        directive: String,
    ): SyntaxNode? =
        declaration.precedingCommentIds.mapNotNull { view.node(it) }.maxByOrNull { it.startByte }
            ?: view.comments
                .filter { it.matchesDirective(directive) }
                .mapNotNull { comment -> view.node(comment.nodeId) }
                .filter { it.endByte <= node.startByte && node.startLine - it.endLine <= 1 }
                .maxByOrNull { it.startByte }

    private fun isBookkeepingComment(
        view: RuleEvaluationView,
        comment: CommentFact,
        node: SyntaxNode,
        directive: String,
    ): Boolean {
        if (!comment.matchesDirective(directive)) return false
        val commentNode = view.node(comment.nodeId) ?: return false
        return commentNode.endByte <= node.startByte && node.startLine - commentNode.endLine <= 1
    }
}
