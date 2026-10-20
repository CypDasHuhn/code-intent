package dev.cint.core.syntax

import dev.cint.api.CallFact
import dev.cint.api.CommentFact
import dev.cint.api.SyntaxNode
import dev.cint.api.WriteFact
import dev.cint.api.WriteKind

private val separatorKinds = setOf("(", ")", ",", ";", "{", "}")

internal class CallFacts(
    private val kinds: ProfileSyntaxKinds,
) {
    fun calls(tree: SyntaxTree): Sequence<CallFact> = callsIn(tree.root)

    fun callsIn(node: SyntaxNode): Sequence<CallFact> =
        node
            .descendants()
            .filter { it.kind in kinds.callKinds }
            .map(::callFact)

    fun callPath(call: SyntaxNode): String? {
        val callee = calleeName(call) ?: return null
        val receiver = receiverNode(call)
        return if (receiver != null) "${receiver.text}.$callee" else callee
    }

    fun calleeName(call: SyntaxNode): String? {
        val navigation = navigationOf(call)
        if (navigation != null) {
            kinds.fieldNode(navigation, SyntaxFields.MEMBER_PROPERTY)?.let { return it.text.substringBefore('<') }
            val suffix = navigation.children.lastOrNull { it.kind in kinds.calleeSuffixKinds }
            return suffix?.children?.firstOrNull { it.kind in kinds.identifierKinds }?.text ?: suffix?.text?.substringBefore('<')
        }
        val function = callFunctionNode(call)
        if (function != null && function.kind in kinds.identifierKinds) return function.text
        return (function ?: call).children.firstOrNull { it.kind in kinds.identifierKinds }?.text
    }

    fun receiverNode(call: SyntaxNode): SyntaxNode? {
        val navigation = navigationOf(call) ?: return null
        kinds.fieldNode(navigation, SyntaxFields.MEMBER_OBJECT)?.let { return it }
        return navigation.children.firstOrNull { it.kind !in kinds.calleeSuffixKinds }
    }

    fun receiverOffset(receiver: SyntaxNode): Int =
        if (receiver.kind in kinds.callKinds) {
            (receiver.endByte - 1).coerceAtLeast(receiver.startByte)
        } else {
            receiver.startByte
        }

    fun trailingLambda(call: SyntaxNode): SyntaxNode? {
        val suffix = call.children.lastOrNull { it.kind in kinds.callSuffixKinds } ?: return null
        val last = suffix.children.lastOrNull { it.kind !in separatorKinds && it.kind !in kinds.commentKinds } ?: return null
        if (last.kind in kinds.lambdaKinds) return last
        if (last.kind in kinds.callArgumentKinds) {
            return last.children.lastOrNull { it.kind in kinds.lambdaKinds }
        }
        return null
    }

    private fun callFact(call: SyntaxNode): CallFact {
        val site = callSite(call)
        return CallFact(
            node = call,
            site = site,
            callee = calleeName(call),
            receiver = receiverNode(call),
            path = callPath(call),
            trailingLambda = trailingLambda(site),
        )
    }

    private fun callFunctionNode(call: SyntaxNode): SyntaxNode? = kinds.fieldNode(call, SyntaxFields.CALL_FUNCTION)

    private fun navigationOf(call: SyntaxNode): SyntaxNode? {
        val function = callFunctionNode(call)
        if (function != null) {
            if (function.kind in kinds.navigationKinds) return function
            return function.children.firstOrNull { it.kind in kinds.navigationKinds }
        }
        return call.children.firstOrNull { it.kind in kinds.navigationKinds }
    }

    private fun callSite(call: SyntaxNode): SyntaxNode {
        var current = call
        var parent = current.parent
        while (parent != null && parent.kind in kinds.callKinds) {
            if (trailingLambda(parent) == null) break
            current = parent
            parent = current.parent
        }
        return current
    }
}

internal class SemanticFacts(
    private val kinds: ProfileSyntaxKinds,
) {
    fun imports(tree: SyntaxTree): List<String> =
        tree.root
            .descendants()
            .filter { it.kind in kinds.importKinds }
            .map { node ->
                val source = kinds.fieldNode(node, SyntaxFields.IMPORT_SOURCE)
                val value =
                    when {
                        source != null -> source.text.trim('"', '\'', '`')
                        kinds.importPrefix != null -> node.text.removePrefix(kinds.importPrefix).trim()
                        else -> node.text.trim()
                    }
                value.trimEnd(';')
            }.toList()

    fun referencesByName(tree: SyntaxTree): Map<String, List<SyntaxNode>> =
        tree.root
            .descendants()
            .filter { it.kind in kinds.identifierKinds }
            .groupBy { it.text }

    fun comments(tree: SyntaxTree): Sequence<CommentFact> =
        tree.root
            .descendants()
            .filter { it.kind in kinds.commentKinds }
            .map(::CommentFact)

    fun allWrites(tree: SyntaxTree): Sequence<WriteFact> =
        tree.root
            .descendants()
            .filter { it.kind in kinds.writeKinds }
            .filter { write ->
                write.kind !in kinds.incrementKinds ||
                    kinds.incrementOperators.any { write.text.contains(it) }
            }.map { write -> WriteFact(write, writeKind(write)) }

    fun writeKind(node: SyntaxNode): WriteKind =
        when (node.kind) {
            in kinds.assignmentKinds -> WriteKind.ASSIGNMENT
            in kinds.incrementKinds -> WriteKind.INCREMENT
            else -> WriteKind.UNKNOWN
        }

    fun writes(reference: SyntaxNode): SyntaxNode? {
        var parent = reference.parent ?: return null
        if (parent.kind in kinds.calleeSuffixKinds) {
            parent = parent.parent ?: return null
        }
        return when (parent.kind) {
            in kinds.incrementKinds -> {
                if (kinds.incrementOperators.any { parent.text.contains(it) }) parent else null
            }

            in kinds.assignableKinds -> {
                val assignment = parent.parent ?: return null
                if (assignment.kind in kinds.assignmentKinds && isLeftOperand(parent, assignment)) assignment else null
            }

            in kinds.assignmentKinds -> {
                val left = parent.childByField("left") ?: parent.children.firstOrNull()
                if (left != null && (left.startByte == reference.startByte || reference.startByte in left.startByte until left.endByte)) {
                    parent
                } else {
                    null
                }
            }

            in kinds.navigationKinds -> {
                val operation = parent.parent ?: return null
                when (operation.kind) {
                    in kinds.incrementKinds -> {
                        if (kinds.incrementOperators.any { operation.text.contains(it) }) operation else null
                    }

                    in kinds.assignmentKinds -> {
                        if (isLeftOperand(parent, operation)) operation else null
                    }

                    else -> {
                        null
                    }
                }
            }

            else -> {
                null
            }
        }
    }

    private fun isLeftOperand(
        operand: SyntaxNode,
        assignment: SyntaxNode,
    ): Boolean {
        val left = assignment.childByField("left") ?: assignment.children.firstOrNull() ?: return false
        return operand.startByte >= left.startByte && operand.endByte <= left.endByte
    }

    fun isConstant(node: SyntaxNode): Boolean = node.descendants().none { it.kind in kinds.referenceKinds }

    fun isSideEffectFree(node: SyntaxNode): Boolean =
        node.descendants().none { child ->
            child.kind in kinds.effectKinds ||
                kinds.effectFragments.any { child.kind.contains(it) } ||
                (
                    child.kind in kinds.incrementKinds &&
                        kinds.incrementOperators.any { child.text.contains(it) }
                )
        }

    fun isThrow(node: SyntaxNode): Boolean =
        node.kind in kinds.jumpKinds &&
            kinds.throwKeyword != null &&
            node.text.trimStart().startsWith(kinds.throwKeyword)

    fun isExit(node: SyntaxNode): Boolean = node.kind in kinds.jumpKinds && kinds.exitKeywords.any { node.text.trimStart().startsWith(it) }

    fun isReturn(node: SyntaxNode): Boolean = node.kind in kinds.jumpKinds && node.text.trimStart().startsWith("return")

    fun exitExpression(node: SyntaxNode): SyntaxNode? {
        if (!isExit(node)) return null
        kinds.fieldNode(node, SyntaxFields.RETURN_ARGUMENT)?.let { return it }
        return node.children.lastOrNull { it.kind !in kinds.exitKeywords && it.kind !in kinds.commentKinds }
    }

    fun isIdentifier(node: SyntaxNode): Boolean = node.kind in kinds.identifierKinds
}
