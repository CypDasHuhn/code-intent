package dev.cint.core.engine

import dev.cint.api.Annotation
import dev.cint.api.CallFact
import dev.cint.api.Contribution
import dev.cint.api.Coverage
import dev.cint.api.FunctionFact
import dev.cint.api.IfFact
import dev.cint.api.Intent
import dev.cint.api.PropertyFact
import dev.cint.api.SyntaxNode
import dev.cint.api.TryFact
import dev.cint.api.UNTAGGED_INTENT
import dev.cint.api.View
import dev.cint.core.config.IntentConfig
import dev.cint.core.flow.ControlFlow
import dev.cint.core.flow.ControlFlowBuilder
import dev.cint.core.model.SourceFile
import dev.cint.core.oracle.TypeOracle
import dev.cint.core.store.AnnotationStore
import dev.cint.core.syntax.SyntaxModel
import dev.cint.core.syntax.SyntaxTree
import dev.cint.core.view.CapabilityView
import java.util.IdentityHashMap

enum class Phase {
    SOURCE,
    DERIVATIVE,
    SCAFFOLD,
}

interface RuleContext {
    val file: SourceFile
    val language: String
    val tree: SyntaxTree
    val syntax: SyntaxModel
    val flow: ControlFlow
    val oracle: TypeOracle

    val calls: List<CallFact>
    val properties: List<PropertyFact>
    val functions: List<FunctionFact>
    val ifs: List<IfFact>
    val tries: List<TryFact>
    val catches: List<SyntaxNode>
    val guardConditions: List<SyntaxNode>
    val imports: List<String>
    val references: Map<String, List<SyntaxNode>>
    val throws: List<SyntaxNode>

    fun annotations(): List<Annotation>
    fun covering(node: SyntaxNode): List<Annotation>
    fun containingAnnotations(node: SyntaxNode): List<Annotation>
    fun intentsAt(node: SyntaxNode): Set<String>
    fun coverage(
        node: SyntaxNode,
        effect: Boolean = true,
    ): Coverage
}

interface RuleBlock {
    val id: String
    val phase: Phase get() = Phase.SOURCE
    val readsAnnotations: Boolean get() = false

    fun apply(ctx: RuleContext): List<Contribution>
}

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
    override val file: SourceFile,
    override val tree: SyntaxTree,
    override val syntax: SyntaxModel,
    override val flow: ControlFlow,
    override val oracle: TypeOracle,
    val config: IntentConfig,
    private val store: AnnotationStore,
) : RuleContext {
    override val language: String get() = config.language
    private var exactIndex: Map<Long, List<Annotation>>? = null
    private var intervalIndex: IntervalIndex? = null
    private var indexRevision = -1L

    override val calls: List<CallFact> by lazy(LazyThreadSafetyMode.NONE) { syntax.calls(tree).toList() }
    override val properties: List<PropertyFact> by lazy(LazyThreadSafetyMode.NONE) { syntax.properties(tree).toList() }
    override val functions: List<FunctionFact> by lazy(LazyThreadSafetyMode.NONE) { syntax.functions(tree).toList() }
    override val ifs: List<IfFact> by lazy(LazyThreadSafetyMode.NONE) { syntax.ifs(tree).toList() }
    override val tries: List<TryFact> by lazy(LazyThreadSafetyMode.NONE) { syntax.tries(tree).toList() }
    override val catches: List<SyntaxNode> by lazy(LazyThreadSafetyMode.NONE) { syntax.catches(tree).toList() }
    override val guardConditions: List<SyntaxNode> by lazy(LazyThreadSafetyMode.NONE) { syntax.guardConditions(tree).toList() }
    override val imports: List<String> by lazy(LazyThreadSafetyMode.NONE) { syntax.imports(tree) }
    override val references: Map<String, List<SyntaxNode>> by lazy(LazyThreadSafetyMode.NONE) { syntax.referencesByName(tree) }
    override val throws: List<SyntaxNode> by lazy(LazyThreadSafetyMode.NONE) {
        tree.root
            .descendants()
            .filter { syntax.isThrow(it) }
            .toList()
    }

    private fun refreshIndexes() {
        val revision = store.revision()
        if (revision == indexRevision) return
        exactIndex = null
        intervalIndex = null
        indexRevision = revision
    }

    fun view(
        owns: Intent,
        reads: Set<Intent>,
        failOnUndeclaredRead: Boolean = false,
    ): View = CapabilityView(this, owns, reads, config.pureAnnotations, failOnUndeclaredRead)

    override fun annotations(): List<Annotation> = store.annotations()

    override fun covering(node: SyntaxNode): List<Annotation> {
        refreshIndexes()
        val index = exactIndex ?: annotations().groupBy { spanKey(it.startByte, it.endByte) }.also { exactIndex = it }
        return index[spanKey(node.startByte, node.endByte)].orEmpty()
    }

    override fun containingAnnotations(node: SyntaxNode): List<Annotation> {
        refreshIndexes()
        val index = intervalIndex ?: IntervalIndex(annotations()).also { intervalIndex = it }
        val result = mutableListOf<Annotation>()
        index.containing(node.startByte, node.endByte) { result += it }
        return result
    }

    override fun intentsAt(node: SyntaxNode): Set<String> {
        refreshIndexes()
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

    override fun coverage(
        node: SyntaxNode,
        effect: Boolean,
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
    private val blockKeys: Map<RuleBlock, String> =
        IdentityHashMap<RuleBlock, String>().apply {
            blocks.forEachIndexed { index, block -> put(block, "${block.id}#$index") }
        }

    fun analyze(
        file: SourceFile,
        tree: SyntaxTree,
        syntax: SyntaxModel,
        oracle: TypeOracle,
        config: IntentConfig,
    ): List<Annotation> {
        val flow = ControlFlowBuilder(tree, syntax).build()
        val store = AnnotationStore()
        val ctx = AnalysisContext(file, tree, syntax, flow, oracle, config, store)
        runPhase(ctx, store, Phase.SOURCE)
        runPhase(ctx, store, Phase.DERIVATIVE)
        runPhase(ctx, store, Phase.SCAFFOLD)

        return ctx.annotations().sortedWith(
            compareBy<Annotation>({ it.span.startLine }, { it.span.startCol }, { -it.endByte }),
        )
    }

    private fun runPhase(
        ctx: RuleContext,
        store: AnnotationStore,
        phase: Phase,
    ) {
        val phaseBlocks = blocks.filter { it.phase == phase }
        if (phaseBlocks.isEmpty()) return
        var first = true
        do {
            store.clearDirty()
            for (block in phaseBlocks) {
                if (first || block.readsAnnotations) {
                    store.replaceRule(blockKeys.getValue(block), block.apply(ctx))
                }
            }
            first = false
        } while (store.isDirty())
    }
}
