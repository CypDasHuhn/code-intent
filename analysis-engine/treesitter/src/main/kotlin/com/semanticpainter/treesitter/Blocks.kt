package com.semanticpainter.treesitter

import com.semanticpainter.core.engine.AnalysisContext
import com.semanticpainter.core.engine.RuleBlock
import com.semanticpainter.core.model.Span
import com.semanticpainter.core.model.TagKind
import com.semanticpainter.core.model.TaggedSpan
import com.semanticpainter.core.syntax.SyntaxNode

private val PRINT_CALLS = setOf("println", "print")
private val STD_STREAMS = setOf("System.out", "System.err")

private fun typeMatches(actual: String, configured: List<String>): Boolean {
    val actualSimple = actual.substringAfterLast('.')
    return configured.any { expected ->
        actual == expected ||
            actual.endsWith(".$expected") ||
            (!actual.contains('.') && expected.substringAfterLast('.') == actualSimple)
    }
}

class PrintBlock : RuleBlock {
    override val id: String = "print"

    override fun apply(ctx: AnalysisContext): List<TaggedSpan> =
        KotlinSyntax.calls(ctx.tree).mapNotNull { call ->
            val callee = KotlinSyntax.calleeName(call) ?: return@mapNotNull null
            if (callee !in PRINT_CALLS) return@mapNotNull null
            val receiver = KotlinSyntax.receiverNode(call)
            if (receiver != null && receiver.text !in STD_STREAMS) return@mapNotNull null
            call.tag(ctx.config.tag, TagKind.INTERACTION, "print", "treesitter", 1.0)
        }.toList()
}

class LoggerInteractionBlock : RuleBlock {
    override val id: String = "logger-interaction"

    override fun apply(ctx: AnalysisContext): List<TaggedSpan> {
        val methods = ctx.config.list("methods").toSet()
        val types = ctx.config.list("types")
        val receiverNames = ctx.config.list("receiverNames")
        val loggerSymbols = KotlinSyntax.properties(ctx.tree)
            .filter { it.initCallPath != null && ctx.config.has("factories", it.initCallPath) }
            .map { it.name }
            .toSet()
        val importsMatch = KotlinSyntax.imports(ctx.tree).any { import ->
            ctx.config.list("imports").any { import == it || import.startsWith("$it.") }
        }

        return KotlinSyntax.calls(ctx.tree).mapNotNull { call ->
            val callee = KotlinSyntax.calleeName(call) ?: return@mapNotNull null
            if (methods.isNotEmpty() && callee !in methods) return@mapNotNull null
            val receiver = KotlinSyntax.receiverNode(call) ?: return@mapNotNull null
            val receiverName = receiver.text.substringAfterLast('.')

            val type = ctx.oracle.typeAt(ctx.file, KotlinSyntax.receiverOffset(receiver))
            val matched = when {
                type != null && typeMatches(type.fqName, types) ->
                    Match("typed-receiver", type.source, type.confidence)

                receiverName in loggerSymbols ->
                    Match("call-on-logger-symbol", "heuristic", 0.8)

                receiverNames.any { it.equals(receiverName, ignoreCase = true) } ->
                    Match("receiver-name", "heuristic", 0.6)

                ctx.config.has("qualifiers", receiver.text) ->
                    Match("qualified-static", "heuristic", 0.8)

                importsMatch ->
                    Match("name-import-gated", "heuristic", 0.5)

                else -> null
            } ?: return@mapNotNull null

            call.tag(ctx.config.tag, TagKind.INTERACTION, matched.rule, matched.source, matched.confidence)
        }.toList()
    }

    private data class Match(
        val rule: String,
        val source: String,
        val confidence: Double,
    )
}

class IntentScaffoldBlock : RuleBlock {
    override val id: String = "intent-scaffold"

    override fun apply(ctx: AnalysisContext): List<TaggedSpan> {
        val tag = ctx.config.tag
        val ifSpans = ctx.tree.root.descendants("if_expression").mapNotNull { ifNode ->
            val body = ifNode.child("control_structure_body") ?: return@mapNotNull null
            if (!hasTaggedEffect(ctx, body, tag)) return@mapNotNull null

            val condition = conditionOf(ifNode) ?: return@mapNotNull null
            if (!isNeutral(ctx, condition, tag)) return@mapNotNull null

            val elseBody = elseBodyOf(ifNode)
            if (elseBody != null && !hasTaggedEffect(ctx, elseBody, tag)) return@mapNotNull null

            val confidence = if (KotlinSyntax.isConstant(condition)) 0.95 else 0.85
            ifNode.tag(tag, TagKind.CONTROL_FLOW, "intent-scaffold", "treesitter", confidence)
        }.toList()

        val catchSpans = ctx.tree.root.descendants("catch_block").mapNotNull { catchNode ->
            if (!hasTaggedEffect(ctx, catchNode, tag)) return@mapNotNull null
            catchNode.tag(tag, TagKind.CONTROL_FLOW, "intent-scaffold-catch", "treesitter", 0.9)
        }.toList()

        val trySpans = mutableListOf<TaggedSpan>()
        val headerSpans = mutableListOf<TaggedSpan>()

        for (tryNode in ctx.tree.root.descendants("try_expression")) {
            if (ctx.covers(tryNode, tag)) continue

            val catches = tryNode.childrenOfKind("catch_block")
            if (catches.isEmpty()) continue

            val catchesCovered = catches.all { catchNode ->
                ctx.covers(catchNode, tag) ||
                    catchSpans.any { it.startByte <= catchNode.startByte && it.endByte >= catchNode.endByte }
            }
            if (!catchesCovered) continue

            val finallyBlock = tryNode.children.firstOrNull { it.kind == "finally_block" }
            val finallyCovered = finallyBlock == null || hasTaggedEffect(ctx, finallyBlock, tag)

            if (hasTaggedEffect(ctx, tryNode, tag) && finallyCovered) {
                trySpans.add(tryNode.tag(tag, TagKind.CONTROL_FLOW, "intent-scaffold-try", "treesitter", 0.9))
            } else {
                val start = tryNode.children.firstOrNull { it.kind == "try" }
                val end = tryNode.children.firstOrNull { it.kind == "{" }
                if (start != null && end != null) {
                    headerSpans.add(
                        tagRange(
                            start,
                            end,
                            tag,
                            TagKind.CONTROL_FLOW,
                            "intent-scaffold-try-header",
                            "treesitter",
                            0.8,
                        ),
                    )
                }
            }
        }

        return ifSpans + catchSpans + trySpans + headerSpans
    }

    private fun conditionOf(ifNode: SyntaxNode): SyntaxNode? =
        ifNode.children.firstOrNull {
            it.kind !in setOf("if", "(", ")", "else", "control_structure_body")
        }

    private fun elseBodyOf(ifNode: SyntaxNode): SyntaxNode? {
        val elseIndex = ifNode.children.indexOfFirst { it.kind == "else" }
        if (elseIndex < 0) return null
        return ifNode.children.drop(elseIndex + 1)
            .firstOrNull { it.kind in setOf("control_structure_body", "if_expression") }
    }

    private fun hasTaggedEffect(ctx: AnalysisContext, body: SyntaxNode, tag: String): Boolean {
        val statements = body.child("statements") ?: return false
        if (statements.children.isEmpty()) return false
        return statements.children.all { statement -> ctx.covers(statement, tag) }
    }

    private fun isNeutral(ctx: AnalysisContext, condition: SyntaxNode, tag: String): Boolean {
        if (KotlinSyntax.isConstant(condition)) return true
        if (ctx.covers(condition, tag)) return true

        val guardNames = ctx.config.list("guardNames")
        if (guardNames.any { condition.text.contains(it, ignoreCase = true) }) return true

        val guardTypes = ctx.config.list("guardTypes")
        if (guardTypes.isNotEmpty()) {
            val reference = condition.descendants().firstOrNull {
                it.kind in setOf("simple_identifier", "navigation_expression", "call_expression")
            }
            val type = reference?.let { ctx.oracle.typeAt(ctx.file, KotlinSyntax.receiverOffset(it)) }
            if (type != null && guardTypes.any { type.fqName == it || type.fqName.endsWith(".$it") }) return true
        }

        return false
    }
}

private fun SyntaxNode.tag(
    tag: String,
    kind: TagKind,
    rule: String,
    source: String,
    confidence: Double,
): TaggedSpan = TaggedSpan(
    tag = tag,
    kind = kind,
    rule = rule,
    evidence = text.replace("\n", " ").take(160),
    span = span(),
    source = source,
    confidence = confidence,
    startByte = startByte,
    endByte = endByte,
)

private fun tagRange(
    start: SyntaxNode,
    end: SyntaxNode,
    tag: String,
    kind: TagKind,
    rule: String,
    source: String,
    confidence: Double,
): TaggedSpan = TaggedSpan(
    tag = tag,
    kind = kind,
    rule = rule,
    evidence = "${start.text} ${end.text}".take(160),
    span = Span(start.startLine, start.startCol, end.endLine, end.endCol),
    source = source,
    confidence = confidence,
    startByte = start.startByte,
    endByte = end.endByte,
)

object LoggingBlocks {
    fun all(): List<RuleBlock> = listOf(
        PrintBlock(),
        LoggerInteractionBlock(),
        IntentScaffoldBlock(),
    )

    fun select(ids: List<String>): List<RuleBlock> {
        val all = all()
        if (ids.isEmpty()) return all
        val selected = all.filter { it.id in ids }
        return selected.ifEmpty { all }
    }
}
