package dev.cint.pipeline

import dev.cint.contracts.data.Confidence
import dev.cint.contracts.data.Intent
import dev.cint.contracts.data.Provenance
import dev.cint.contracts.facts.CallFact
import dev.cint.contracts.facts.CommentFact
import dev.cint.contracts.facts.DeclarationFact
import dev.cint.contracts.facts.FunctionFact
import dev.cint.contracts.facts.ImportFact
import dev.cint.contracts.facts.SemanticFact
import dev.cint.contracts.facts.SemanticRequestKind
import dev.cint.contracts.facts.SemanticResult
import dev.cint.contracts.facts.SyntaxNode
import dev.cint.contracts.facts.SyntaxTree
import dev.cint.contracts.knowledge.FunctionSelector

internal enum class KnowledgeTargetKind {
    FUNCTION,
    CALL,
}

internal data class KnowledgeTarget(
    val kind: KnowledgeTargetKind,
    val node: SyntaxNode,
    val name: String,
    val literalNames: Set<String>,
    val names: Set<String>,
    val signatures: Set<String>,
    val receiverTypes: Set<String>,
    val declaringTypes: Set<String>,
    val importPaths: Set<String>,
    val annotations: Set<String>,
    val comments: Set<String>,
    val arity: Int? = null,
    val inChain: Boolean = false,
)

internal data class IntentContract(
    val intents: Set<Intent>,
    val provenance: Provenance,
    val confidence: Confidence,
    val rule: String,
)

internal sealed interface SelectorMatch {
    data object Matched : SelectorMatch
    data object NoMatch : SelectorMatch
    data class Unsupported(val reason: String) : SelectorMatch
}

internal fun functionTarget(
    function: FunctionFact,
    node: SyntaxNode,
    declaration: DeclarationFact?,
    comments: Map<String, CommentFact>,
    tree: SyntaxTree,
): KnowledgeTarget {
    val rawName =
        function.name?.takeIf { it.isNotBlank() }
            ?: declaration?.name?.takeIf { it.isNotBlank() }
            ?: node.text.takeIf { it.isNotBlank() }
            ?: ""
    val simpleName = rawName.substringAfterLast('.')
    val names = linkedSetOf<String>()
    if (rawName.isNotBlank()) names += rawName
    if (simpleName.isNotBlank()) names += simpleName
    val declaringTypes = linkedSetOf<String>()
    function.declaringType?.takeIf { it.isNotBlank() }?.let(declaringTypes::add)
    if (declaringTypes.isEmpty() && '.' in rawName) {
        declaringTypes += rawName.substringBeforeLast('.')
    }
    val receiverTypes = linkedSetOf<String>()
    receiverTypes += declaringTypes
    val signatures = linkedSetOf<String>()
    listOf(function.signature, node.text, rawName, simpleName)
        .filterNotNull()
        .filterTo(signatures) { it.isNotBlank() }
    val annotationText =
        buildSet {
            declaration?.annotations?.forEach { add(normalizeAnnotation(it)) }
            declaration?.annotationNodeIds
                ?.mapNotNull { comments[it]?.magicComment }
                ?.forEach { add(normalizeAnnotation(it)) }
        }
    val commentText =
        buildSet {
            declaration?.precedingCommentIds?.forEach { id ->
                comments[id]?.let { comment ->
                    comment.magicComment?.takeIf { it.isNotBlank() }?.let(::add)
                    comment.nodeId.takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }
    return KnowledgeTarget(
        kind = KnowledgeTargetKind.FUNCTION,
        node = node,
        name = simpleName,
        literalNames = names,
        names = names,
        signatures = signatures,
        receiverTypes = receiverTypes,
        declaringTypes = declaringTypes,
        importPaths = emptySet(),
        annotations = annotationText,
        comments = commentText,
        arity = function.arity,
    )
}

internal fun callTarget(
    call: CallFact,
    node: SyntaxNode,
    tree: SyntaxTree,
    imports: List<ImportFact>,
    semanticFacts: List<SemanticFact>,
    inChain: Boolean,
): KnowledgeTarget {
    val calleeText = call.callee?.trim()?.takeIf { it.isNotBlank() }
    val pathText = call.path?.trim()?.takeIf { it.isNotBlank() }
    val nodeName = node.text.trim().removePrefix("call ").trim()
    val simpleName = finalMemberName(calleeText ?: pathText ?: nodeName)
    val qualifiedName =
        (pathText ?: calleeText)
            ?.takeIf { candidate -> candidate.none { it == '(' || it == ')' || it.isWhitespace() } }
            ?.takeIf { it.contains('.') }
    val literalNames = linkedSetOf<String>()
    listOf(simpleName, qualifiedName)
        .filterNotNull()
        .filterTo(literalNames) { it.isNotBlank() }
    val importPaths =
        imports
            .filter { importMatchesName(it, simpleName, qualifiedName.orEmpty()) }
            .map { it.path }
            .distinct()
            .sorted()
    val names = linkedSetOf<String>()
    names += literalNames
    names += importPaths
    val receiverTypes = linkedSetOf<String>()
    resolvedReceiverType(call.receiverId?.let(tree::getNode), semanticFacts)?.let(receiverTypes::add)
    val signatures = linkedSetOf<String>()
    listOf(node.text, calleeText, qualifiedName, simpleName)
        .filterNotNull()
        .filterTo(signatures) { it.isNotBlank() }
    return KnowledgeTarget(
        kind = KnowledgeTargetKind.CALL,
        node = node,
        name = simpleName,
        literalNames = literalNames,
        names = names,
        signatures = signatures,
        receiverTypes = receiverTypes,
        declaringTypes = receiverTypes,
        importPaths = importPaths.toSet(),
        annotations = emptySet(),
        comments = emptySet(),
        arity = call.arity,
        inChain = inChain,
    )
}

private fun resolvedReceiverType(
    receiver: SyntaxNode?,
    semanticFacts: List<SemanticFact>,
): String? {
    if (receiver == null) return null
    return semanticFacts
        .mapNotNull { fact ->
            val offset = fact.request.offset
            if (offset < receiver.startByte || offset > receiver.endByte) return@mapNotNull null
            if (fact.request.kind != SemanticRequestKind.TYPE_AT &&
                fact.request.kind != SemanticRequestKind.SYMBOL_AT
            ) {
                return@mapNotNull null
            }
            when (val result = fact.result) {
                is SemanticResult.TypeAt -> result.typeRef?.fqName
                is SemanticResult.SymbolAt -> result.symbol?.fqName ?: result.symbol?.containerName
                else -> null
            }?.takeIf { it.isNotBlank() }
        }.sorted()
        .firstOrNull()
}

private fun finalMemberName(raw: String): String =
    raw.substringAfterLast('.')
        .substringBefore('(')
        .substringBefore('[')
        .trim()

internal fun selectorMatch(
    selector: FunctionSelector,
    target: KnowledgeTarget,
): SelectorMatch {
    val structural = selector.structuralPattern
    if (structural != null) {
        when (val result = structuralMatch(structural, target)) {
            SelectorMatch.Matched -> Unit
            SelectorMatch.NoMatch -> return SelectorMatch.NoMatch
            is SelectorMatch.Unsupported -> return result
        }
    }
    val importPath = selector.importPath
    if (importPath != null) {
        if (target.kind == KnowledgeTargetKind.FUNCTION) return SelectorMatch.NoMatch
        val matched =
            target.importPaths.any { importMatches(importPath, it) } ||
                target.names.any { it == importPath }
        if (!matched) return SelectorMatch.NoMatch
    }
    val declaringType = selector.declaringType
    if (declaringType != null) {
        if (target.declaringTypes.isEmpty()) {
            return SelectorMatch.Unsupported(
                "selector '${selector.identityKey}' uses declaringType, but no declaring type is resolvable",
            )
        }
        if (target.declaringTypes.none { typeMatches(declaringType, it) }) {
            return SelectorMatch.NoMatch
        }
    }
    val receiverType = selector.receiverType
    if (receiverType != null) {
        if (target.receiverTypes.isEmpty()) {
            return SelectorMatch.Unsupported(
                "selector '${selector.identityKey}' uses receiverType, but no receiver is resolvable for '${target.node.id}'",
            )
        }
        if (target.receiverTypes.none { typeMatches(receiverType, it) }) {
            return SelectorMatch.NoMatch
        }
    }
    if (selector.qualifiedName.isNotBlank() &&
        target.names.none { it == selector.qualifiedName }
    ) {
        return SelectorMatch.NoMatch
    }
    val signature = selector.signature
    if (signature != null &&
        target.signatures.none { signatureMatches(signature, it) }
    ) {
        return SelectorMatch.NoMatch
    }
    return SelectorMatch.Matched
}

internal fun IntentContract.withRule(newRule: String): IntentContract = copy(rule = newRule)

internal fun selectorSpecificity(selector: FunctionSelector): Int {
    var score = 0
    if (selector.importPath != null) score += 100
    if (selector.qualifiedName.isNotBlank()) score += 80 + selector.qualifiedName.count { it == '.' }
    if (selector.declaringType != null) score += 60
    if (selector.receiverType != null) score += 50
    if (selector.signature != null) score += 40
    val structural = selector.structuralPattern
    if (structural != null) {
        score +=
            when {
                structural.startsWith("token-prefix:") -> 20
                structural.startsWith("@") -> 30
                structural.startsWith("cint:") -> 30
                else -> 10
            }
    }
    return score
}

private fun structuralMatch(
    pattern: String,
    target: KnowledgeTarget,
): SelectorMatch {
    if (pattern.startsWith("token-prefix:")) {
        val parts = pattern.removePrefix("token-prefix:").split(';')
        val prefix = parts.firstOrNull()?.takeIf { it.isNotBlank() } ?: return SelectorMatch.NoMatch
        val exceptions =
            parts.drop(1)
                .filter { it.startsWith("except:") }
                .flatMap { it.removePrefix("except:").split(',') }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        val matches =
            target.name.startsWith(prefix) &&
                target.name !in exceptions &&
                (target.name.length == prefix.length || !target.name[prefix.length].isLowerCase())
        return if (matches) SelectorMatch.Matched else SelectorMatch.NoMatch
    }
    if (pattern.startsWith("@")) {
        return if (target.kind == KnowledgeTargetKind.FUNCTION &&
            normalizeAnnotation(pattern) in target.annotations
        ) {
            SelectorMatch.Matched
        } else {
            SelectorMatch.NoMatch
        }
    }
    if (pattern.startsWith("cint:")) {
        val marker = pattern.removePrefix("cint:")
        return if (target.kind == KnowledgeTargetKind.FUNCTION && marker in target.comments) {
            SelectorMatch.Matched
        } else {
            SelectorMatch.NoMatch
        }
    }
    if (pattern == "call-chain") {
        return if (target.kind == KnowledgeTargetKind.CALL && target.inChain) {
            SelectorMatch.Matched
        } else {
            SelectorMatch.NoMatch
        }
    }
    return SelectorMatch.Unsupported(
        "structural selector '$pattern' is not applicable to function or call knowledge",
    )
}

private fun normalizeAnnotation(text: String): String =
    text.removePrefix("@").replace(Regex("""\s+"""), "")

private fun typeMatches(
    expected: String,
    actual: String,
): Boolean {
    if (expected == actual) return true
    val expectedSimple = expected.substringAfterLast('.')
    val actualSimple = actual.substringAfterLast('.')
    if (expectedSimple.equals(actualSimple, ignoreCase = true)) return true
    return actual.endsWith(".$expected") || expected.endsWith(".$actual")
}

private fun importMatches(
    expected: String,
    actual: String,
): Boolean {
    if (expected == actual) return true
    return actual.endsWith(".$expected") || expected.endsWith(".$actual")
}

private fun importMatchesName(
    import: ImportFact,
    simpleName: String,
    pathName: String,
): Boolean {
    if (simpleName.isBlank()) return false
    val importedName = import.path.substringAfterLast('.')
    val alias = import.alias?.takeIf { it.isNotBlank() }
    return when {
        alias != null -> alias == simpleName || pathName == import.path
        importedName == simpleName -> true
        else -> pathName == import.path
    }
}

private fun signatureMatches(
    expected: String,
    actual: String,
): Boolean =
    normalizeSignature(expected) == normalizeSignature(actual)

private fun normalizeSignature(text: String): String =
    text.replace(Regex("""\s+"""), " ").trim()
