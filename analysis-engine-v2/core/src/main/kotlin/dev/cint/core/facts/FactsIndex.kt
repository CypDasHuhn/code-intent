package dev.cint.core.facts

import dev.cint.contracts.facts.DeclarationFact
import dev.cint.contracts.facts.FactBatch
import dev.cint.contracts.facts.FunctionFact
import dev.cint.contracts.facts.StatementFact
import dev.cint.contracts.facts.SyntaxNode
import dev.cint.contracts.facts.SyntaxTree
import dev.cint.contracts.facts.ValueFact

class FactsIndex(
    val facts: FactBatch,
) {
    val tree: SyntaxTree? = facts.syntaxTree
    val root: SyntaxNode? = tree?.getNode(tree.rootId)

    fun node(id: String?): SyntaxNode? = id?.let { tree?.getNode(it) }

    fun nodes(): Collection<SyntaxNode> = tree?.nodes?.values.orEmpty()

    fun children(node: SyntaxNode): List<SyntaxNode> = node.childIds.mapNotNull { tree?.getNode(it) }

    fun descendants(node: SyntaxNode): Sequence<SyntaxNode> =
        sequence {
            yield(node)
            for (childId in node.childIds) {
                val child = tree?.getNode(childId) ?: continue
                yieldAll(descendants(child))
            }
        }

    fun contains(
        container: SyntaxNode,
        node: SyntaxNode,
    ): Boolean = container.startByte <= node.startByte && container.endByte >= node.endByte

    fun enclosingFunction(node: SyntaxNode): FunctionFact? =
        facts.functions
            .mapNotNull { fact ->
                val candidate = this.node(fact.nodeId) ?: return@mapNotNull null
                if (!contains(candidate, node)) return@mapNotNull null
                fact to (candidate.endByte - candidate.startByte)
            }.minByOrNull { it.second }
            ?.first

    fun enclosingDeclaration(node: SyntaxNode): DeclarationFact? =
        facts.declarations
            .mapNotNull { fact ->
                val candidate = this.node(fact.nodeId) ?: return@mapNotNull null
                if (!contains(candidate, node)) return@mapNotNull null
                fact to (candidate.endByte - candidate.startByte)
            }.minByOrNull { it.second }
            ?.first

    fun returnFact(node: SyntaxNode): dev.cint.contracts.facts.ReturnFact? =
        facts.returns.firstOrNull { it.nodeId == node.id }

    fun ifFact(node: SyntaxNode): dev.cint.contracts.facts.IfFact? =
        facts.ifs.firstOrNull { it.nodeId == node.id }

    fun statements(container: SyntaxNode): List<SyntaxNode> {
        val direct =
            facts.statements
                .filter { it.containerId == container.id }
                .mapNotNull { node(it.nodeId) }
                .sortedBy { it.startByte }
        if (direct.isNotEmpty()) return direct
        for (childId in container.childIds) {
            val nested =
                facts.statements
                    .filter { it.containerId == childId }
                    .mapNotNull { node(it.nodeId) }
                    .sortedBy { it.startByte }
            if (nested.isNotEmpty()) return nested
        }
        return emptyList()
    }

    fun statement(node: SyntaxNode): StatementFact? =
        facts.statements.firstOrNull { it.nodeId == node.id }

    fun value(node: SyntaxNode): ValueFact? =
        facts.values.firstOrNull { it.nodeId == node.id }

    fun returnsWithin(node: SyntaxNode): List<dev.cint.contracts.facts.ReturnFact> =
        facts.returns.filter { fact ->
            val returnNode = this.node(fact.nodeId) ?: return@filter false
            contains(node, returnNode)
        }
}
