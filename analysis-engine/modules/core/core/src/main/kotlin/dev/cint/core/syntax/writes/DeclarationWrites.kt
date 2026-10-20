package dev.cint.core.syntax.writes

import dev.cint.api.DeclarationKind
import dev.cint.api.PropertyFact
import dev.cint.api.ScopeFact
import dev.cint.api.ScopeKind
import dev.cint.api.SyntaxNode
import dev.cint.api.WriteFact
import dev.cint.core.syntax.ControlFlowFacts
import dev.cint.core.syntax.ProfileSyntaxKinds
import dev.cint.core.syntax.SemanticFacts
import dev.cint.core.syntax.SyntaxFields
import dev.cint.core.syntax.SyntaxTree

private data class WriteTarget(
    val name: String,
    val node: SyntaxNode,
    val nameNode: SyntaxNode,
    val scope: ScopeFact?,
    val kind: DeclarationKind,
)

private enum class WriteQualification {
    UNQUALIFIED,
    OWNER,
    OTHER,
}

internal fun declarationKind(scope: ScopeFact?): DeclarationKind =
    when (scope?.kind) {
        ScopeKind.FUNCTION, ScopeKind.LAMBDA, ScopeKind.BLOCK -> DeclarationKind.VARIABLE
        else -> DeclarationKind.PROPERTY
    }

internal class DeclarationWrites(
    private val kinds: ProfileSyntaxKinds,
    private val semantics: SemanticFacts,
    private val controlFlow: ControlFlowFacts,
) {
    fun declarationWrites(
        tree: SyntaxTree,
        entries: List<Pair<PropertyFact, ScopeFact?>>,
        references: Map<String, List<SyntaxNode>>,
    ): Map<Pair<Int, Int>, List<WriteFact>> {
        val propertyTargets =
            entries.mapNotNull { (property, scope) ->
                val name = property.name ?: return@mapNotNull null
                val nameNode = property.nameNode ?: return@mapNotNull null
                WriteTarget(name, property.node, nameNode, scope, declarationKind(scope))
            }
        val byName = (propertyTargets + bindingTargets(tree)).groupBy { it.name }
        val writes = linkedMapOf<Pair<Int, Int>, MutableList<WriteFact>>()
        val seen = mutableMapOf<Pair<Int, Int>, MutableSet<Pair<Int, Int>>>()
        for ((name, targets) in byName) {
            for (reference in references[name].orEmpty()) {
                val write = semantics.writes(reference) ?: continue
                val qualification = writeQualification(write, reference)
                if (qualification == WriteQualification.OTHER) continue
                val target =
                    targets
                        .filter { candidate ->
                            !sameNode(candidate.nameNode, reference) &&
                                scopeContains(candidate.scope, reference) &&
                                (qualification != WriteQualification.OWNER || candidate.kind == DeclarationKind.PROPERTY) &&
                                (
                                    qualification == WriteQualification.OWNER ||
                                        candidate.kind != DeclarationKind.VARIABLE ||
                                        candidate.nameNode.startByte <= reference.startByte
                                )
                        }.minWithOrNull(
                            compareBy(
                                { scopeSpan(it.scope) },
                                { it.node.startByte },
                            ),
                        ) ?: continue
                val key = declarationKey(target.node)
                if (seen.getOrPut(key) { mutableSetOf() }.add(write.startByte to write.endByte)) {
                    writes.getOrPut(key) { mutableListOf() } +=
                        WriteFact(write, semantics.writeKind(write), reference)
                }
            }
        }
        return writes
    }

    private fun bindingTargets(tree: SyntaxTree): List<WriteTarget> {
        val result = mutableListOf<WriteTarget>()

        fun add(
            nameNode: SyntaxNode?,
            scope: ScopeFact?,
        ) {
            if (nameNode == null || nameNode.text.isBlank() || scope == null) return
            result += WriteTarget(nameNode.text, nameNode, nameNode, scope, DeclarationKind.VARIABLE)
        }
        for (node in tree.root.descendants()) {
            when {
                node.kind in kinds.parameterKinds -> {
                    bindingNameNodes(node).forEach { add(it, callableScope(node)) }
                }

                node.kind in kinds.parameterListKinds -> {
                    for (child in node.children.filter { it.kind in kinds.identifierKinds }) {
                        add(child, callableScope(child))
                    }
                }
            }
        }
        for (loop in tree.root.descendants().filter { it.kind in kinds.loopKinds }) {
            val limit = controlFlow.loopBody(loop)?.startByte ?: loop.endByte
            loopBindingNames(loop, limit).forEach { add(it, ScopeFact(loop, ScopeKind.BLOCK, loop)) }
        }
        for (catch in tree.root.descendants().filter { it.kind in kinds.catchKinds }) {
            val bodyStart =
                catch
                    .descendants()
                    .firstOrNull {
                        it.kind in kinds.statementListKinds || it.kind in kinds.controlBodyKinds
                    }?.startByte ?: catch.endByte
            val nameNode =
                catch
                    .descendants()
                    .filter {
                        it.startByte < bodyStart &&
                            it.kind in kinds.identifierKinds &&
                            !isWithinTypeReference(it, catch)
                    }.lastOrNull()
            add(nameNode, ScopeFact(catch, ScopeKind.BLOCK, catch))
        }
        return result.distinctBy { it.nameNode.startByte to it.nameNode.endByte }
    }

    private fun loopBindingNames(
        loop: SyntaxNode,
        limit: Int,
    ): List<SyntaxNode> {
        kinds.fieldNode(loop, SyntaxFields.LOOP_BINDING)?.let { return bindingNameNodes(it) }
        return loop.children
            .filter { it.startByte < limit }
            .flatMap { child ->
                when {
                    child.kind in kinds.bindingKinds -> bindingNameNodes(child)
                    child.kind in kinds.loopBindingContainerKinds -> bindingsWithin(child)
                    child.kind in kinds.declarationWrapperKinds -> bindingsWithin(child)
                    else -> emptyList()
                }
            }
    }

    private fun bindingsWithin(node: SyntaxNode): List<SyntaxNode> =
        node.children.flatMap { child ->
            when {
                child.kind in kinds.bindingKinds -> bindingNameNodes(child)
                child.kind in kinds.declarationWrapperKinds -> bindingsWithin(child)
                else -> emptyList()
            }
        }

    private fun bindingNameNodes(node: SyntaxNode): List<SyntaxNode> {
        if (node.kind in kinds.bindingNameKinds) return listOf(node)
        if (node.kind in kinds.patternBindingKinds) {
            return kinds.fieldNode(node, SyntaxFields.PATTERN_BINDING)?.let(::bindingNameNodes).orEmpty()
        }
        if (node.kind in kinds.patternKinds) return node.children.flatMap(::bindingNameNodes)
        kinds.fieldNode(node, SyntaxFields.VARIABLE_NAME)?.let { return bindingNameNodes(it) }
        val nested = node.children.filter { it.kind in kinds.bindingKinds || it.kind in kinds.patternKinds }
        if (nested.isNotEmpty()) return nested.flatMap(::bindingNameNodes)
        val direct = node.children.filter { it.kind in kinds.bindingNameKinds }
        if (direct.isNotEmpty()) return direct
        return node.children
            .filter { it.kind in kinds.variableKinds }
            .flatMap { it.children.flatMap(::bindingNameNodes) }
    }

    private fun isWithinTypeReference(
        node: SyntaxNode,
        stop: SyntaxNode,
    ): Boolean {
        var current = node.parent
        while (current != null && !sameNode(current, stop)) {
            if (current.kind in kinds.typeReferenceKinds) return true
            current = current.parent
        }
        return false
    }

    private fun callableScope(node: SyntaxNode): ScopeFact? {
        var current = node.parent
        while (current != null) {
            if (current.kind in kinds.functionKinds) return ScopeFact(current, ScopeKind.FUNCTION, current)
            if (current.kind in kinds.lambdaKinds) return ScopeFact(current, ScopeKind.LAMBDA, current)
            current = current.parent
        }
        return controlFlow.enclosingScope(node)
    }

    private fun writeQualification(
        write: SyntaxNode,
        reference: SyntaxNode,
    ): WriteQualification {
        val operand = writeOperand(write, reference) ?: return WriteQualification.OTHER
        val target = operand.text.trim()
        if (!target.contains('.')) {
            return if (target == reference.text.trim()) WriteQualification.UNQUALIFIED else WriteQualification.OTHER
        }
        val receiver = target.substringBeforeLast('.').trim()
        val member = target.substringAfterLast('.').trim()
        if (member != reference.text.trim()) return WriteQualification.OTHER
        return if (isOwnerReceiver(receiver)) WriteQualification.OWNER else WriteQualification.OTHER
    }

    private fun writeOperand(
        write: SyntaxNode,
        reference: SyntaxNode,
    ): SyntaxNode? {
        var current = reference
        while (true) {
            val parent = current.parent ?: return null
            if (sameNode(parent, write)) return current
            current = parent
        }
    }

    private fun isOwnerReceiver(receiver: String): Boolean =
        receiver == "this" || receiver == "self" || receiver == "Me" || receiver.startsWith("this@")

    private fun declarationKey(node: SyntaxNode): Pair<Int, Int> = node.startByte to node.endByte

    private fun scopeContains(
        scope: ScopeFact?,
        node: SyntaxNode,
    ): Boolean {
        val range = scope?.node ?: return false
        return node.startByte >= range.startByte && node.endByte <= range.endByte
    }

    private fun scopeSpan(scope: ScopeFact?): Int {
        val node = scope?.node ?: return Int.MAX_VALUE
        return node.endByte - node.startByte
    }

    private fun sameNode(
        first: SyntaxNode?,
        second: SyntaxNode,
    ): Boolean = first != null && first.startByte == second.startByte && first.endByte == second.endByte
}
