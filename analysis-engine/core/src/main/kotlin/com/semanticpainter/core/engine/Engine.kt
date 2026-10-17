package com.semanticpainter.core.engine

import com.semanticpainter.core.config.IntentConfig
import com.semanticpainter.core.model.Annotation
import com.semanticpainter.core.model.RawAnnotation
import com.semanticpainter.core.model.Role
import com.semanticpainter.core.model.SourceFile
import com.semanticpainter.core.model.UNTAGGED_INTENT
import com.semanticpainter.core.oracle.TypeOracle
import com.semanticpainter.core.syntax.SyntaxNode
import com.semanticpainter.core.syntax.SyntaxTree

enum class Phase {
    SOURCE,
    DERIVATIVE,
    SCAFFOLD,
}

interface RuleBlock {
    val id: String
    val phase: Phase get() = Phase.SOURCE

    fun apply(ctx: AnalysisContext): List<RawAnnotation>
}

data class Coverage(val intents: Set<String>) {
    val hasRealIntent: Boolean get() = intents.any { it != UNTAGGED_INTENT }
    val hasUntagged: Boolean get() = UNTAGGED_INTENT in intents
}

private data class AnnotationKey(
    val role: Role,
    val startByte: Int,
    val endByte: Int,
)

class AnalysisContext(
    val file: SourceFile,
    val tree: SyntaxTree,
    val oracle: TypeOracle,
    val config: IntentConfig,
) {
    private val raw = LinkedHashMap<AnnotationKey, RawAnnotation>()
    private var dirty = false

    fun emit(annotation: RawAnnotation) {
        val key = AnnotationKey(annotation.role, annotation.startByte, annotation.endByte)
        val existing = raw[key]
        val merged = when {
            existing == null -> annotation
            annotation.role == Role.SCAFFOLD -> annotation
            else -> existing.merge(annotation)
        }
        if (merged != existing) {
            raw[key] = merged
            dirty = true
        }
    }

    fun emitAll(annotations: Iterable<RawAnnotation>) {
        annotations.forEach(::emit)
    }

    fun remove(role: Role, startByte: Int, endByte: Int) {
        if (raw.remove(AnnotationKey(role, startByte, endByte)) != null) {
            dirty = true
        }
    }

    fun clearDirty() {
        dirty = false
    }

    fun isDirty(): Boolean = dirty

    fun annotations(): List<Annotation> = raw.values.map { rawAnnotation ->
        Annotation(
            role = rawAnnotation.role,
            intents = rawAnnotation.intents,
            rule = rawAnnotation.rule,
            evidence = rawAnnotation.evidence,
            span = rawAnnotation.span,
            source = rawAnnotation.source,
            confidence = rawAnnotation.confidence,
            startByte = rawAnnotation.startByte,
            endByte = rawAnnotation.endByte,
        )
    }

    fun covering(node: SyntaxNode): List<Annotation> =
        annotations().filter { it.startByte == node.startByte && it.endByte == node.endByte }

    fun intentsAt(node: SyntaxNode): Set<String> {
        val containing = annotations().filter {
            it.startByte <= node.startByte && it.endByte >= node.endByte
        }
        if (containing.isEmpty()) return setOf(UNTAGGED_INTENT)
        val smallest = containing.minOf { it.endByte - it.startByte }
        return containing
            .filter { it.endByte - it.startByte == smallest }
            .flatMap { it.intents }
            .toSet()
    }

    fun coverage(node: SyntaxNode, effect: Boolean = true): Coverage {
        val covering = covering(node)
        if (covering.isNotEmpty()) {
            return Coverage(covering.flatMap { it.intents }.toSet())
        }
        return if (effect) Coverage(setOf(UNTAGGED_INTENT)) else Coverage(emptySet())
    }
}

class AnalysisEngine(private val blocks: List<RuleBlock>) {
    fun analyze(
        file: SourceFile,
        tree: SyntaxTree,
        oracle: TypeOracle,
        config: IntentConfig,
    ): List<Annotation> {
        val ctx = AnalysisContext(file, tree, oracle, config)
        runPhase(ctx, Phase.SOURCE)
        runPhase(ctx, Phase.DERIVATIVE)
        runPhase(ctx, Phase.SCAFFOLD)

        return ctx.annotations().sortedWith(
            compareBy<Annotation>({ it.span.startLine }, { it.span.startCol }, { -it.endByte }),
        )
    }

    private fun runPhase(ctx: AnalysisContext, phase: Phase) {
        val phaseBlocks = blocks.filter { it.phase == phase }
        if (phaseBlocks.isEmpty()) return
        do {
            ctx.clearDirty()
            for (block in phaseBlocks) {
                ctx.emitAll(block.apply(ctx))
            }
        } while (ctx.isDirty())
    }
}
