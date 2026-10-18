package com.semanticpainter.core.engine

import com.semanticpainter.core.config.IntentConfig
import com.semanticpainter.core.model.Annotation
import com.semanticpainter.core.model.RawAnnotation
import com.semanticpainter.core.model.Role
import com.semanticpainter.core.model.SourceFile
import com.semanticpainter.core.model.UNTAGGED_INTENT
import com.semanticpainter.core.oracle.TypeOracle
import com.semanticpainter.core.syntax.CallFact
import com.semanticpainter.core.syntax.FunctionFact
import com.semanticpainter.core.syntax.IfFact
import com.semanticpainter.core.syntax.PropertyFact
import com.semanticpainter.core.syntax.SyntaxModel
import com.semanticpainter.core.syntax.SyntaxNode
import com.semanticpainter.core.syntax.SyntaxTree
import com.semanticpainter.core.syntax.TryFact

enum class Phase {
    SOURCE,
    DERIVATIVE,
    SCAFFOLD,
}

interface RuleBlock {
    val id: String
    val phase: Phase get() = Phase.SOURCE
    val readsAnnotations: Boolean get() = false

    fun apply(ctx: AnalysisContext): List<RawAnnotation>
}

data class Coverage(
    val intents: Set<String>,
) {
    val hasRealIntent: Boolean get() = intents.any { it != UNTAGGED_INTENT }
    val hasUntagged: Boolean get() = UNTAGGED_INTENT in intents
}

private data class AnnotationKey(
    val role: Role,
    val startByte: Int,
    val endByte: Int,
)

private fun spanKey(
    startByte: Int,
    endByte: Int,
): Long = (startByte.toLong() shl 32) or (endByte.toLong() and 0xffffffffL)

private class IntervalIndex(
    annotations: List<Annotation>,
) {
    private val items = annotations.sortedBy { it.startByte }
    private val maxEnd = IntArray(items.size * 4)

    init {
        if (items.isNotEmpty()) {
            build(1, 0, items.size - 1)
        }
    }

    fun containing(
        start: Int,
        end: Int,
        visit: (Annotation) -> Unit,
    ) {
        if (items.isEmpty()) return
        val limit = lastStartAtOrBefore(start)
        if (limit < 0) return
        query(1, 0, items.size - 1, limit, end, visit)
    }

    private fun lastStartAtOrBefore(start: Int): Int {
        var low = 0
        var high = items.size - 1
        var best = -1
        while (low <= high) {
            val mid = (low + high) / 2
            if (items[mid].startByte <= start) {
                best = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return best
    }

    private fun build(
        node: Int,
        left: Int,
        right: Int,
    ) {
        if (left == right) {
            maxEnd[node] = items[left].endByte
            return
        }
        val mid = (left + right) / 2
        build(node * 2, left, mid)
        build(node * 2 + 1, mid + 1, right)
        maxEnd[node] = maxOf(maxEnd[node * 2], maxEnd[node * 2 + 1])
    }

    private fun query(
        node: Int,
        left: Int,
        right: Int,
        limit: Int,
        end: Int,
        visit: (Annotation) -> Unit,
    ) {
        if (left > limit || maxEnd[node] < end) return
        if (left == right) {
            val annotation = items[left]
            if (annotation.endByte >= end) {
                visit(annotation)
            }
            return
        }
        val mid = (left + right) / 2
        query(node * 2, left, mid, limit, end, visit)
        if (mid + 1 <= limit) {
            query(node * 2 + 1, mid + 1, right, limit, end, visit)
        }
    }
}

class AnalysisContext(
    val file: SourceFile,
    val tree: SyntaxTree,
    val syntax: SyntaxModel,
    val oracle: TypeOracle,
    val config: IntentConfig,
) {
    private val raw = LinkedHashMap<AnnotationKey, RawAnnotation>()
    private var dirty = false
    private var cachedAnnotations: List<Annotation>? = null
    private var exactIndex: Map<Long, List<Annotation>>? = null
    private var intervalIndex: IntervalIndex? = null

    val calls: List<CallFact> by lazy(LazyThreadSafetyMode.NONE) { syntax.calls(tree).toList() }
    val properties: List<PropertyFact> by lazy(LazyThreadSafetyMode.NONE) { syntax.properties(tree).toList() }
    val functions: List<FunctionFact> by lazy(LazyThreadSafetyMode.NONE) { syntax.functions(tree).toList() }
    val ifs: List<IfFact> by lazy(LazyThreadSafetyMode.NONE) { syntax.ifs(tree).toList() }
    val tries: List<TryFact> by lazy(LazyThreadSafetyMode.NONE) { syntax.tries(tree).toList() }
    val catches: List<SyntaxNode> by lazy(LazyThreadSafetyMode.NONE) { syntax.catches(tree).toList() }
    val guardConditions: List<SyntaxNode> by lazy(LazyThreadSafetyMode.NONE) { syntax.guardConditions(tree).toList() }
    val imports: List<String> by lazy(LazyThreadSafetyMode.NONE) { syntax.imports(tree) }
    val references: Map<String, List<SyntaxNode>> by lazy(LazyThreadSafetyMode.NONE) { syntax.referencesByName(tree) }
    val throws: List<SyntaxNode> by lazy(LazyThreadSafetyMode.NONE) {
        tree.root
            .descendants()
            .filter { syntax.isThrow(it) }
            .toList()
    }

    private fun invalidateAnnotations() {
        cachedAnnotations = null
        exactIndex = null
        intervalIndex = null
    }

    fun emit(annotation: RawAnnotation) {
        val key = AnnotationKey(annotation.role, annotation.startByte, annotation.endByte)
        val existing = raw[key]
        val merged =
            when {
                existing == null -> annotation
                annotation.role == Role.SCAFFOLD -> annotation
                else -> existing.merge(annotation)
            }
        if (merged != existing) {
            raw[key] = merged
            dirty = true
            invalidateAnnotations()
        }
    }

    fun emitAll(annotations: Iterable<RawAnnotation>) {
        annotations.forEach(::emit)
    }

    fun remove(
        role: Role,
        startByte: Int,
        endByte: Int,
    ) {
        if (raw.remove(AnnotationKey(role, startByte, endByte)) != null) {
            dirty = true
            invalidateAnnotations()
        }
    }

    fun clearDirty() {
        dirty = false
    }

    fun isDirty(): Boolean = dirty

    fun annotations(): List<Annotation> {
        val cached = cachedAnnotations
        if (cached != null) return cached
        val list =
            raw.values.map { rawAnnotation ->
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
        cachedAnnotations = list
        exactIndex = list.groupBy { spanKey(it.startByte, it.endByte) }
        return list
    }

    fun covering(node: SyntaxNode): List<Annotation> {
        annotations()
        return exactIndex?.get(spanKey(node.startByte, node.endByte)).orEmpty()
    }

    fun intentsAt(node: SyntaxNode): Set<String> {
        val index = intervalIndex ?: IntervalIndex(annotations()).also { intervalIndex = it }
        var smallest = Int.MAX_VALUE
        var intents: MutableSet<String>? = null
        index.containing(node.startByte, node.endByte) { annotation ->
            val size = annotation.endByte - annotation.startByte
            when {
                size < smallest -> {
                    smallest = size
                    intents = annotation.intents.toMutableSet()
                }

                size == smallest -> {
                    intents?.addAll(annotation.intents)
                }
            }
        }
        return intents ?: setOf(UNTAGGED_INTENT)
    }

    fun coverage(
        node: SyntaxNode,
        effect: Boolean = true,
    ): Coverage {
        val covering = covering(node)
        if (covering.isNotEmpty()) {
            return Coverage(covering.flatMap { it.intents }.toSet())
        }
        return if (effect) Coverage(setOf(UNTAGGED_INTENT)) else Coverage(emptySet())
    }
}

class AnalysisEngine(
    private val blocks: List<RuleBlock>,
) {
    fun analyze(
        file: SourceFile,
        tree: SyntaxTree,
        syntax: SyntaxModel,
        oracle: TypeOracle,
        config: IntentConfig,
    ): List<Annotation> {
        val ctx = AnalysisContext(file, tree, syntax, oracle, config)
        runPhase(ctx, Phase.SOURCE)
        runPhase(ctx, Phase.DERIVATIVE)
        runPhase(ctx, Phase.SCAFFOLD)

        return ctx.annotations().sortedWith(
            compareBy<Annotation>({ it.span.startLine }, { it.span.startCol }, { -it.endByte }),
        )
    }

    private fun runPhase(
        ctx: AnalysisContext,
        phase: Phase,
    ) {
        val phaseBlocks = blocks.filter { it.phase == phase }
        if (phaseBlocks.isEmpty()) return
        var first = true
        do {
            ctx.clearDirty()
            for (block in phaseBlocks) {
                if (first || block.readsAnnotations) {
                    ctx.emitAll(block.apply(ctx))
                }
            }
            first = false
        } while (ctx.isDirty())
    }
}
