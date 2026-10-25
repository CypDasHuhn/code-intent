package dev.cint.core.view

import dev.cint.contracts.data.Annotation
import dev.cint.contracts.data.Contribution
import dev.cint.contracts.data.Intent
import dev.cint.contracts.data.Intents
import dev.cint.contracts.data.Role
import dev.cint.contracts.facts.DeclarationFact
import dev.cint.contracts.facts.FactBatch
import dev.cint.contracts.facts.FunctionFact
import dev.cint.contracts.facts.SyntaxNode
import dev.cint.contracts.ports.RuleEvaluationView
import dev.cint.core.facts.FactsIndex
import dev.cint.core.store.AnnotationStore

class RuleView internal constructor(
    override val owns: Intent,
    override val reads: Set<Intent>,
    override val optionalReads: Set<Intent>,
    private val facts: FactBatch,
    private val store: AnnotationStore,
    private val failOnUndeclaredRead: Boolean = false,
) : RuleEvaluationView {
    private val index = FactsIndex(facts)
    private val visible: Set<Intent> = reads + optionalReads + owns

    override val calls get() = facts.calls
    override val writes get() = facts.writes
    override val declarations get() = facts.declarations
    override val comments get() = facts.comments
    override val scopes get() = facts.scopes
    override val guards get() = facts.guards
    override val returns get() = facts.returns
    override val properties get() = facts.properties
    override val functions get() = facts.functions
    override val ifs get() = facts.ifs
    override val tries get() = facts.tries
    override val imports get() = facts.imports
    override val references get() = facts.references

    override fun node(id: String?): SyntaxNode? = index.node(id)

    override fun children(node: SyntaxNode): List<SyntaxNode> = index.children(node)

    override fun descendants(node: SyntaxNode): Sequence<SyntaxNode> = index.descendants(node)

    override fun enclosingFunction(node: SyntaxNode): FunctionFact? = index.enclosingFunction(node)

    override fun enclosingDeclaration(node: SyntaxNode): DeclarationFact? =
        index.enclosingDeclaration(node)

    private fun covering(node: SyntaxNode): List<Annotation> =
        store
            .annotations()
            .filter { it.startByte == node.startByte && it.endByte == node.endByte }

    private fun containing(node: SyntaxNode): List<Annotation> =
        store
            .annotations()
            .filter { annotation ->
                annotation.startByte <= node.startByte && annotation.endByte >= node.endByte
            }

    override fun annotations(intent: Intent): List<Annotation> {
        if (intent !in visible) {
            if (failOnUndeclaredRead) {
                error(
                    "intent '$intent' is not declared by the rule owning '$owns' " +
                        "(reads: ${(reads + optionalReads).sorted()})",
                )
            }
            return emptyList()
        }
        return store
            .annotations()
            .asSequence()
            .filter { it.role == Role.SOURCE_INTENT }
            .mapNotNull { it.restrictedTo(visible) }
            .filter { intent in it.intents }
            .toList()
    }

    override fun intentsAt(node: SyntaxNode): Set<Intent> {
        val sourceCovering = containing(node).filter { it.role == Role.SOURCE_INTENT }
        val covered =
            sourceCovering.mapNotNull { annotation ->
                val intents = annotation.visibleIntents(visible)
                if (intents.isEmpty()) null else annotation to intents
            }
        if (covered.isEmpty()) {
            if (failOnUndeclaredRead && sourceCovering.isNotEmpty()) {
                error(
                    "node is covered only by intents not declared by the rule owning '$owns' " +
                        "(reads: ${(reads + optionalReads).sorted()})",
                )
            }
            return emptySet()
        }
        val smallest = covered.minOf { (annotation, _) -> annotation.endByte - annotation.startByte }
        return covered
            .filter { (annotation, _) -> annotation.endByte - annotation.startByte == smallest }
            .flatMapTo(linkedSetOf()) { (_, intents) -> intents }
    }

    override fun coverage(
        node: SyntaxNode,
        effect: Boolean,
    ): Set<Intent> {
        val covering = covering(node)
        val visibleIntents =
            covering
                .mapNotNull { it.restrictedTo(visible) }
                .flatMap { it.intents }
                .toSet()
        if (visibleIntents.isNotEmpty()) return visibleIntents
        return if (effect) setOf(Intents.UNTAGGED) else emptySet()
    }
}

private fun Annotation.visibleProvenance(visible: Set<Intent>): List<Contribution> =
    provenance.mapNotNull { contribution ->
        val intents = contribution.intents.filterTo(linkedSetOf()) { it in visible }
        if (intents.isEmpty()) null else contribution.copy(intents = intents)
    }

private fun Annotation.visibleIntents(visible: Set<Intent>): Set<Intent> =
    visibleProvenance(visible).flatMapTo(linkedSetOf()) { it.intents }

private fun Annotation.restrictedTo(visible: Set<Intent>): Annotation? {
    val provenance = visibleProvenance(visible)
    if (provenance.isEmpty()) return null
    return copy(
        intents = provenance.flatMap { it.intents }.sorted().toSet(),
        source = provenance.map { it.source }.distinct().singleOrNull() ?: "mixed",
        confidence = provenance.maxByOrNull { it.confidence.value }!!.confidence,
        provenance = provenance,
    )
}
