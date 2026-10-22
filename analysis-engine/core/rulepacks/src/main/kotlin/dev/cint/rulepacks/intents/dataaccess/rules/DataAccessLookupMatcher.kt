package dev.cint.rulepacks.intents.dataaccess

import dev.cint.api.CallFact
import dev.cint.api.Emit
import dev.cint.api.Intent
import dev.cint.api.RuleEmit
import dev.cint.api.SyntaxNode
import dev.cint.api.View
import dev.cint.api.WriteFact
import dev.cint.api.WriteKind
import dev.cint.api.emit
import dev.cint.rulepacks.intents.isTypeQueryableReceiver

private val INCREMENT_OPERATORS = setOf("++", "--")

internal class DataAccessLookupMatcher(
    private val language: DataAccessLanguageConfig,
) {
    fun match(
        view: View,
        ruleId: String,
    ): List<RuleEmit> {
        val result = mutableListOf<RuleEmit>()
        for (call in view.calls()) {
            val callee = call.callee ?: continue
            when {
                isEstablishedLookup(view, call, callee) -> result += call.emit(LOOKUP_INTENT)

                isBoundaryLookup(callee) ||
                    isAmbiguous(callee, language) ||
                    containsName(language.lookupCalls, callee) ||
                    isEnumCandidate(view, call, callee) -> result += candidate(LOOKUP_INTENT, call, ruleId)
            }
        }
        result += knownMapIndexes(view)
        return result
    }

    private fun candidate(
        intent: Intent,
        call: CallFact,
        ruleId: String,
    ) = dataAccessCandidate(intent, call, ruleId)

    private fun isEstablishedLookup(
        view: View,
        call: CallFact,
        callee: String,
    ): Boolean {
        val receiver = call.receiver ?: return false
        val enumCall = containsName(language.enumCalls, callee)
        val lookupCall = containsName(language.lookupCalls, callee)
        if (!enumCall && !lookupCall && !isAmbiguous(callee, language)) return false
        val knownReceiver =
            isKnownMap(receiver.text, language) || matchesName(receiver.text, language.lookupQualifiers)
        val enumReceiver = if (enumCall) isEnumReceiver(view, call) else false
        val typed =
            !knownReceiver &&
                !enumReceiver &&
                isTypeQueryableReceiver(receiver) &&
                typeMatches(view.receiverType(call), language.lookupTypes)
        return if (enumCall) enumReceiver || knownReceiver || typed else knownReceiver || typed
    }

    private fun isEnumCandidate(
        view: View,
        call: CallFact,
        callee: String,
    ): Boolean =
        containsName(language.enumCalls, callee) &&
            (call.receiver == null || isEnumReceiver(view, call) || isEnumStyleReceiver(view, call))

    private fun isEnumStyleReceiver(
        view: View,
        call: CallFact,
    ): Boolean {
        val receiver = call.receiver ?: return false
        val name = receiverName(receiver.text)
        if (name.isEmpty() || !name.first().isUpperCase()) return false
        if (matchesName(receiver.text, language.enumExclusions)) return false
        val type = if (isTypeQueryableReceiver(receiver)) view.receiverType(call) else null
        return type == null || !matchesTypeSuffix(type, language.enumExclusions)
    }

    private fun isEnumReceiver(
        view: View,
        call: CallFact,
    ): Boolean {
        val receiver = call.receiver ?: return false
        if (matchesName(receiver.text, language.enumTypes)) return true
        val name = receiverName(receiver.text)
        if (name.isNotEmpty() &&
            name.first().isUpperCase() &&
            language.enumTypeSuffixes.any { hasTokenSuffix(tokenize(name), tokenize(it)) }
        ) {
            return true
        }
        if (!isTypeQueryableReceiver(receiver)) return false
        val type = view.receiverType(call)
        return typeMatches(type, language.enumTypes) || matchesTypeSuffix(type, language.enumTypeSuffixes)
    }

    private fun isBoundaryLookup(callee: String): Boolean = language.boundaryPrefixes.any { callee.startsWith(it, ignoreCase = true) }

    private fun knownMapIndexes(view: View): List<Emit> {
        if (language.indexKinds.isEmpty()) return emptyList()
        val root = rootNode(view) ?: return emptyList()
        val writeTargets = view.writes().mapNotNull(::writeTarget)
        return root
            .descendants()
            .filter { node -> isIndexNode(node) }
            .filterNot { node ->
                val start = indexStart(node)
                writeTargets.any { it.startByte == start && it.endByte == node.endByte }
            }.mapNotNull { node ->
                val receiver = indexReceiver(node) ?: return@mapNotNull null
                if (!isKnownMap(receiver, language) && !matchesName(receiver, language.lookupQualifiers)) {
                    return@mapNotNull null
                }
                Emit.range(LOOKUP_INTENT, indexStartNode(node), node)
            }.toList()
    }

    private fun writeTarget(write: WriteFact): SyntaxNode? =
        when (write.kind) {
            WriteKind.ASSIGNMENT -> write.node.childByField("left") ?: write.node.children.firstOrNull()
            WriteKind.INCREMENT -> write.node.children.firstOrNull { child -> child.text.trim() !in INCREMENT_OPERATORS }
            else -> null
        }

    private fun isIndexNode(node: SyntaxNode): Boolean =
        node.kind in language.indexKinds &&
            !(node.kind == "indexing_suffix" && node.parent?.kind in language.indexKinds)

    private fun indexStartNode(node: SyntaxNode): SyntaxNode =
        if (node.kind == "indexing_suffix" && node.text.startsWith("[")) node.parent ?: node else node

    private fun indexStart(node: SyntaxNode): Int = indexStartNode(node).startByte

    private fun indexReceiver(node: SyntaxNode): String? {
        val source = indexStartNode(node).text
        val bracket = source.indexOf('[')
        if (bracket <= 0) return null
        return source
            .substring(0, bracket)
            .trim()
            .ifEmpty { null }
    }

    private fun rootNode(view: View): SyntaxNode? {
        val references = view.references().values
        val firstReference = references.firstOrNull { it.isNotEmpty() }?.first()
        val seed =
            view.calls().firstOrNull()?.node
                ?: view.declarations().firstOrNull()?.node
                ?: view.functions().firstOrNull()?.node
                ?: view.properties().firstOrNull()?.node
                ?: firstReference
                ?: return null
        return generateSequence(seed) { it.parent }
            .last()
    }
}
