package dev.cint.rulepacks.intents.service.rules
import dev.cint.api.CallFact
import dev.cint.api.Emit
import dev.cint.api.FunctionFact
import dev.cint.api.SyntaxNode
import dev.cint.api.View
import dev.cint.rulepacks.intents.service.ServiceLanguageConfig
import dev.cint.rulepacks.intents.support.isTypeQueryableReceiver

internal const val HEURISTIC_SOURCE: String = "heuristic"

internal data class Evidence(
    val source: String,
    val confidence: Double,
    val candidateOnly: Boolean = false,
)

internal data class Creation(
    val node: SyntaxNode,
    val type: String?,
)

internal fun initCallEvidence(
    view: View,
    call: CallFact,
    language: ServiceLanguageConfig,
): Evidence? {
    val callee = call.callee ?: return null
    val path = call.path ?: callee
    if (callee in language.initCalls || pathMatches(path, language.initCalls)) {
        return Evidence(Emit.DEFAULT_SOURCE, 0.9)
    }
    if (language.initQualifiers.any { path == it || path.startsWith("$it.") }) {
        return Evidence(Emit.DEFAULT_SOURCE, 0.85)
    }
    if (call.receiver == null && typeMatches(callee, language.initTypes, language.initSuffixes)) {
        return Evidence(Emit.DEFAULT_SOURCE, 0.9)
    }
    val connectionCall = callee in language.connectionCalls || pathMatches(path, language.connectionCalls)
    val diCall = callee in language.diCalls || pathMatches(path, language.diCalls)
    if (!connectionCall && !diCall) return null
    val receiverName =
        call.receiver
            ?.text
            ?.substringAfterLast('.')
            ?.trim('_')
    val receiverType =
        call.receiver
            ?.takeIf(::isTypeQueryableReceiver)
            ?.let { view.receiverType(call)?.fqName }
    if (connectionCall) {
        return if (isConnectionReceiver(path, receiverName, receiverType, language)) {
            Evidence(Emit.DEFAULT_SOURCE, 0.85)
        } else {
            Evidence(HEURISTIC_SOURCE, 0.4, candidateOnly = true)
        }
    }
    return if (isDiReceiver(path, receiverName, receiverType, language)) {
        Evidence(Emit.DEFAULT_SOURCE, 0.85)
    } else {
        Evidence(HEURISTIC_SOURCE, 0.4, candidateOnly = true)
    }
}

private fun isConnectionReceiver(
    path: String,
    receiverName: String?,
    receiverType: String?,
    language: ServiceLanguageConfig,
): Boolean =
    language.connectionQualifiers.any { path.startsWith("$it.") || receiverName == it } ||
        (
            receiverType != null &&
                typeMatches(receiverType, language.initTypes + language.serviceTypes, language.serviceSuffixes + language.initSuffixes)
        )

private fun isDiReceiver(
    path: String,
    receiverName: String?,
    receiverType: String?,
    language: ServiceLanguageConfig,
): Boolean =
    language.diQualifiers.any { path.startsWith("$it.") || receiverName == it } ||
        (
            receiverType != null &&
                typeMatches(receiverType, language.serviceTypes, language.serviceSuffixes + language.initSuffixes)
        )

internal fun delegationEvidence(
    view: View,
    call: CallFact,
    language: ServiceLanguageConfig,
): Evidence? {
    if (initCallEvidence(view, call, language) != null) return null
    val callee = call.callee ?: return null
    val path = call.path ?: callee
    if (callee in language.delegationCalls || pathMatches(path, language.delegationCalls)) {
        return Evidence(Emit.DEFAULT_SOURCE, 0.9)
    }
    if (language.delegationQualifiers.any { path == it || path.startsWith("$it.") }) {
        return Evidence(Emit.DEFAULT_SOURCE, 0.85)
    }
    val receiver = call.receiver ?: return null
    val receiverName = receiver.text.substringAfterLast('.').trim('_')
    if (receiverName in language.serviceNames) return Evidence(HEURISTIC_SOURCE, 0.75)
    if (language.serviceSuffixes.any { receiverName.endsWith(it, ignoreCase = true) }) {
        return Evidence(HEURISTIC_SOURCE, 0.75)
    }
    if (!isTypeQueryableReceiver(receiver)) return null
    val type = view.receiverType(call)?.fqName ?: return null
    if (!typeMatches(type, language.serviceTypes, language.serviceSuffixes)) return null
    return Evidence(Emit.DEFAULT_SOURCE, 0.8)
}

internal fun creationNodes(view: View): List<Creation> {
    val seen = mutableSetOf<Pair<Int, Int>>()
    return view
        .declarations()
        .flatMap { declaration -> declaration.node.descendants().map { declaration.node to it } }
        .mapNotNull { (container, node) ->
            if (isLiteralOrComment(node, container)) return@mapNotNull null
            val text = node.text.trim()
            val type = explicitCreationType(text) ?: if (TARGET_CREATION.containsMatchIn(text)) targetType(node) else null
            if (type == null) null else Creation(node, type)
        }.filter { seen.add(it.node.startByte to it.node.endByte) }
}

internal fun creationWithin(
    view: View,
    creation: Creation,
    function: FunctionFact,
): Boolean =
    within(creation.node, function.node) &&
        sameFunction(view.enclosingFunction(creation.node), function.node)

private fun isLiteralOrComment(
    node: SyntaxNode,
    boundary: SyntaxNode,
): Boolean {
    var current: SyntaxNode? = node
    while (current != null) {
        if (current.startByte == boundary.startByte && current.endByte == boundary.endByte) return false
        val text = current.text.trim()
        if (text.startsWith("\"") || text.startsWith("'") || text.startsWith("`") || text.startsWith("//") || text.startsWith("/*")) {
            return true
        }
        current = current.parent
    }
    return false
}

private fun explicitCreationType(text: String): String? {
    val match = EXPLICIT_CREATION.find(text) ?: return null
    var index = match.range.last + 1
    while (index < text.length && text[index].isWhitespace()) index++
    if (index < text.length && text[index] == '<') {
        var depth = 0
        while (index < text.length) {
            when (text[index]) {
                '<' -> {
                    depth++
                }

                '>' -> {
                    depth--
                    if (depth == 0) {
                        index++
                        break
                    }
                }
            }
            index++
        }
        while (index < text.length && text[index].isWhitespace()) index++
    }
    if (index < text.length && text[index] == '(') {
        var depth = 0
        while (index < text.length) {
            when (text[index]) {
                '(' -> {
                    depth++
                }

                ')' -> {
                    depth--
                    if (depth == 0) {
                        index++
                        break
                    }
                }
            }
            index++
        }
    }
    val rest = text.substring(index).trim()
    return if (rest.isEmpty() || rest == ";" || rest.startsWith("{")) match.groupValues[1] else null
}

private fun targetType(node: SyntaxNode): String? {
    var current: SyntaxNode = node
    while (true) {
        val parent = current.parent ?: return null
        val offset = (node.startByte - parent.startByte).coerceIn(0, parent.text.length)
        val before = parent.text.substring(0, offset)
        ASSIGNED_TYPE.findAll(before).lastOrNull()?.let { return it.groupValues[1] }
        current = parent
    }
}

internal fun typeMatches(
    actual: String,
    configured: Set<String>,
    suffixes: Set<String>,
): Boolean {
    val simple = actual.substringAfterLast('.')
    return configured.any { expected ->
        actual == expected ||
            actual.endsWith(".$expected") ||
            (!actual.contains('.') && expected.substringAfterLast('.') == actual)
    } || suffixes.any { simple.endsWith(it) }
}

internal fun pathMatches(
    path: String,
    names: Set<String>,
): Boolean =
    names.any { name ->
        path == name || path.endsWith(".$name")
    }

private val EXPLICIT_CREATION = Regex("""^new\s+([A-Za-z_][A-Za-z0-9_.]*)""")
private val TARGET_CREATION = Regex("""^new\s*\(""")
private val ASSIGNED_TYPE = Regex("""([A-Za-z_][A-Za-z0-9_.]*(?:<[^=]*>)?)\s+[A-Za-z_][A-Za-z0-9_]*\s*=\s*$""")
