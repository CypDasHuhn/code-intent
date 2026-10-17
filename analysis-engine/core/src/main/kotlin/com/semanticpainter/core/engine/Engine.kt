package com.semanticpainter.core.engine

import com.semanticpainter.core.config.FeatureConfig
import com.semanticpainter.core.model.SourceFile
import com.semanticpainter.core.model.TaggedSpan
import com.semanticpainter.core.oracle.TypeOracle
import com.semanticpainter.core.syntax.SyntaxNode
import com.semanticpainter.core.syntax.SyntaxTree

interface RuleBlock {
    val id: String

    fun apply(ctx: AnalysisContext): List<TaggedSpan>
}

class AnalysisContext(
    val file: SourceFile,
    val tree: SyntaxTree,
    val oracle: TypeOracle,
    val config: FeatureConfig,
) {
    private val collected = LinkedHashSet<TaggedSpan>()

    fun emit(span: TaggedSpan) {
        collected.add(span)
    }

    fun emitAll(spans: Iterable<TaggedSpan>) {
        spans.forEach(::emit)
    }

    fun tags(): List<TaggedSpan> = collected.toList()

    fun hasTag(node: SyntaxNode, tag: String): Boolean =
        collected.any { it.tag == tag && it.startByte >= node.startByte && it.endByte <= node.endByte }
}

class AnalysisEngine(private val blocks: List<RuleBlock>) {
    fun analyze(
        file: SourceFile,
        tree: SyntaxTree,
        oracle: TypeOracle,
        config: FeatureConfig,
    ): List<TaggedSpan> {
        val ctx = AnalysisContext(file, tree, oracle, config)
        var changed = true
        while (changed) {
            changed = false
            val before = ctx.tags().size
            for (block in blocks) {
                ctx.emitAll(block.apply(ctx))
            }
            if (ctx.tags().size > before) changed = true
        }
        return ctx.tags().sortedWith(
            compareBy<TaggedSpan>({ it.span.startLine }, { it.span.startCol }, { -it.endByte }),
        )
    }
}
