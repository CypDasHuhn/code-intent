package dev.cint.core.runtime.engine

import dev.cint.api.Annotation
import dev.cint.api.CallFact
import dev.cint.api.CommentFact
import dev.cint.api.Coverage
import dev.cint.api.DeclarationFact
import dev.cint.api.FunctionFact
import dev.cint.api.GuardFact
import dev.cint.api.IfFact
import dev.cint.api.Intent
import dev.cint.api.PropertyFact
import dev.cint.api.ReturnFact
import dev.cint.api.ReturnGuardFact
import dev.cint.api.ScopeFact
import dev.cint.api.SyntaxNode
import dev.cint.api.TryFact
import dev.cint.api.TypeRef
import dev.cint.api.UNTAGGED_INTENT
import dev.cint.api.View
import dev.cint.api.WriteFact
import dev.cint.core.config.IntentConfig
import dev.cint.core.facts.flow.ControlFlow
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.TypeOracle
import dev.cint.core.facts.store.AnnotationStore
import dev.cint.core.facts.view.CapabilityView
import dev.cint.core.runtime.context.RuleContext
import dev.cint.core.syntax.SyntaxModel
import dev.cint.core.syntax.SyntaxTree

private fun spanKey(
    startByte: Int,
    endByte: Int,
): Long = (startByte.toLong() shl 32) or (endByte.toLong() and 0xffffffffL)

private fun sameNode(
    first: SyntaxNode,
    second: SyntaxNode,
): Boolean = first.startByte == second.startByte && first.endByte == second.endByte

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
    override val declarations: List<DeclarationFact> by lazy(LazyThreadSafetyMode.NONE) { syntax.declarations(tree).toList() }
    override val writes: List<WriteFact> by lazy(LazyThreadSafetyMode.NONE) { syntax.writes(tree).toList() }
    override val comments: List<CommentFact> by lazy(LazyThreadSafetyMode.NONE) { syntax.comments(tree).toList() }
    override val scopes: List<ScopeFact> by lazy(LazyThreadSafetyMode.NONE) { syntax.scopes(tree).toList() }
    override val returnSites: List<ReturnFact> by lazy(LazyThreadSafetyMode.NONE) {
        flow.returns.map { site ->
            ReturnFact(
                node = site.node,
                expression = site.expression,
                guards = site.guards.map { ReturnGuardFact(it.condition, it.node, it.onTrue) },
                loop = site.loop,
                handler = site.handler,
                scope = syntax.enclosingScope(site.node),
            )
        }
    }
    override val guards: List<GuardFact> by lazy(LazyThreadSafetyMode.NONE) {
        syntax.guards(tree).map { guard -> guard.copy(exits = guardExits(guard.node)) }.toList()
    }
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

    override fun receiverType(call: CallFact): TypeRef? =
        call.receiver?.let { receiver ->
            oracle
                .typeAt(file, syntax.receiverOffset(receiver))
                ?.let { TypeRef(it.fqName, it.source, it.confidence) }
        }

    override fun commentBefore(node: SyntaxNode): CommentFact? = syntax.commentBefore(node)

    private fun guardExits(guard: SyntaxNode): List<SyntaxNode> =
        returnSites
            .filter { site ->
                site.guards.any { sameNode(it.guard, guard) } ||
                    (site.loop?.let { sameNode(it, guard) } == true)
            }.map { it.node }

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
