package dev.cint.rulepacks.intents.mutation.rules
import dev.cint.api.CallFact
import dev.cint.api.SyntaxNode
import dev.cint.api.View
import dev.cint.rulepacks.intents.mutation.MutationLanguageConfig

internal class PersistenceMatch(
    private val config: MutationLanguageConfig,
) {
    fun established(
        view: View,
        call: CallFact,
    ): Boolean {
        val path = call.path
        val exactPath =
            config.persistencePaths.any { matchesPath(path, it) } ||
                config.persistenceCalls.any { it.contains('.') && matchesPath(path, it) }
        if (exactPath) return true
        val callee = call.callee ?: return false
        if (!config.persistenceCalls.any { matchesName(callee, path, it) }) return false
        if (call.receiver == null) return false
        return persistentReceiver(view, call)
    }

    fun candidate(
        view: View,
        call: CallFact,
    ): Boolean {
        val callee = call.callee ?: return false
        if (established(view, call)) return false
        val receiver = call.receiver ?: return configuredCandidate(callee, call.path)
        val configured = configuredCandidate(callee, call.path)
        val configuredCompound = configured && isReadCall(callee)
        if (!configured && isReadCall(callee)) return false
        if (persistentReceiver(view, call)) {
            return mutationShaped(callee, call.path)
        }
        val type = view.receiverType(call)
        if (type != null && isInMemoryType(type.fqName)) return false
        val mutableReceiver =
            config.mutableReceiverNames.any { name ->
                name.equals(receiverName(receiver), ignoreCase = true)
            }
        if (mutableReceiver && !configuredCompound) return false
        if (callee in config.mutableOperations) {
            return config.persistenceCalls.any { matchesName(callee, call.path, it) }
        }
        if (!configured) return false
        if (isLocalCandidate(callee) && config.persistenceCalls.none { it == callee }) return false
        return true
    }

    private fun configuredCandidate(
        callee: String,
        path: String?,
    ): Boolean =
        config.persistenceCalls.any { matchesName(callee, path, it) } ||
            config.persistencePrefixes.any { prefix ->
                prefix.isNotEmpty() && callee.startsWith(prefix, ignoreCase = true)
            }

    private fun mutationShaped(
        callee: String,
        path: String?,
    ): Boolean =
        configuredCandidate(callee, path) ||
            callee in config.mutableOperations ||
            isLocalCandidate(callee)

    fun persistentReceiver(
        view: View,
        call: CallFact,
    ): Boolean {
        val receiver = call.receiver ?: return false
        if (matchesQualifier(receiver.text, config.persistenceQualifiers)) return true
        if (isDurableSuffix(receiver.text)) return true
        val type = view.receiverType(call) ?: return false
        return matchesType(type.fqName, config.persistenceTypes)
    }

    private fun matchesPath(
        path: String?,
        expected: String,
    ): Boolean {
        if (path == null) return false
        return path == expected || path.endsWith(".$expected")
    }

    private fun matchesName(
        callee: String,
        path: String?,
        expected: String,
    ): Boolean = callee == expected || matchesPath(path, expected)

    private fun matchesQualifier(
        receiver: String,
        qualifiers: Set<String>,
    ): Boolean {
        val text = normalizeReceiver(receiver)
        return qualifiers.any { qualifier ->
            text.equals(qualifier, ignoreCase = true) ||
                text.endsWith(".$qualifier", ignoreCase = true) ||
                text.endsWith("_$qualifier", ignoreCase = true) ||
                text.endsWith(qualifier.replaceFirstChar { it.uppercase() })
        }
    }

    private fun isDurableSuffix(receiver: String): Boolean {
        val text = normalizeReceiver(receiver)
        return DURABLE_RECEIVER_SUFFIXES.any { text.endsWith(it) }
    }

    private fun matchesType(
        actual: String,
        expected: Set<String>,
    ): Boolean {
        val simple = actual.substringAfterLast('.')
        return expected.any { name ->
            val suffix = name.replaceFirstChar { it.uppercase() }
            actual == name ||
                actual.endsWith(".$name") ||
                simple.equals(name, ignoreCase = true) ||
                simple.endsWith(suffix)
        }
    }

    private fun receiverName(receiver: SyntaxNode): String =
        receiver.text
            .substringBefore('(')
            .substringAfterLast('.')
            .trim()

    private fun normalizeReceiver(receiver: String): String =
        receiver
            .substringBefore('(')
            .trim()
            .removePrefix("this.")
            .removePrefix("self.")
}

private val READ_CALL_PREFIXES =
    setOf(
        "find",
        "get",
        "query",
        "search",
        "load",
        "read",
        "count",
        "exists",
        "has",
        "fetch",
        "list",
        "select",
        "scan",
        "head",
        "contains",
    )

private fun isReadCall(callee: String): Boolean = READ_CALL_PREFIXES.any { prefix -> callee.startsWith(prefix, ignoreCase = true) }

private val IN_MEMORY_TYPE_SUFFIXES =
    setOf(
        "Channel",
        "Writer",
        "OutputStream",
        "InputStream",
        "Collection",
        "List",
        "Map",
        "Set",
        "Deque",
        "Queue",
        "Stack",
        "Table",
        "Session",
        "StringBuilder",
        "Buffer",
    )

private fun isInMemoryType(actual: String): Boolean {
    val simple = actual.substringAfterLast('.')
    return IN_MEMORY_TYPE_SUFFIXES.any { simple.endsWith(it) }
}

private val DURABLE_RECEIVER_SUFFIXES = setOf("Template", "Client", "Producer")
