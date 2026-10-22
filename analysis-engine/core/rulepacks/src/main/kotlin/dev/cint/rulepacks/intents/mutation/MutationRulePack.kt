package dev.cint.rulepacks.intents.mutation

import dev.cint.api.CINT_API_VERSION
import dev.cint.api.CallFact
import dev.cint.api.CandidateEmit
import dev.cint.api.CandidateReason
import dev.cint.api.ConfigSchema
import dev.cint.api.ConfigurableRulePack
import dev.cint.api.DeclarationFact
import dev.cint.api.DeclarationKind
import dev.cint.api.Emit
import dev.cint.api.GuardKind
import dev.cint.api.Intent
import dev.cint.api.Rule
import dev.cint.api.RuleEmit
import dev.cint.api.RulePack
import dev.cint.api.SyntaxNode
import dev.cint.api.View
import dev.cint.api.WriteFact
import dev.cint.api.WriteKind
import dev.cint.api.emit
import dev.cint.rulepacks.bookkeeping.BOOKKEEPING_INTENT
import dev.cint.rulepacks.intents.structural.LOCAL_COPY_INTENT

const val MUTATION_PACK_ID: String = "mutation"
const val DATA_MODIFY_INTENT: Intent = "DATA_MODIFY"
const val LOCAL_VALUE_MODIFY_INTENT: Intent = "LOCAL_VALUE_MODIFY"
const val DATA_MODIFY_RULE_ID: String = "$MUTATION_PACK_ID:data-modify"
const val LOCAL_VALUE_MODIFY_RULE_ID: String = "$MUTATION_PACK_ID:local-value-modify"

private const val DATA_MODIFY_SOURCE: String = "rule:$DATA_MODIFY_RULE_ID"
private const val LOCAL_VALUE_MODIFY_SOURCE: String = "rule:$LOCAL_VALUE_MODIFY_RULE_ID"
private const val ESTABLISHED_CONFIDENCE: Double = 0.9
private const val LOCAL_CONFIDENCE: Double = 0.8
private const val CANDIDATE_CONFIDENCE: Double = 0.4

private val LOOP_COUNTER_NAMES =
    setOf("i", "j", "k", "n", "idx", "index", "counter", "pos", "position")

internal val LOCAL_CANDIDATE_EXACT =
    setOf(
        "mutate",
        "modify",
        "applyChanges",
        "applyPatch",
        "changeState",
        "increment",
        "decrement",
        "touch",
        "edit",
        "adjust",
    ) + LOCAL_ATOMIC_MUTATION_CALLS

private val LOCAL_CANDIDATE_PREFIXES =
    setOf(
        "mutate",
        "modify",
        "update",
        "reset",
        "clear",
        "merge",
        "replace",
        "increment",
        "decrement",
    )

private val COLLECTION_INITIALIZERS =
    listOf(
        "mutableListOf",
        "mutableMapOf",
        "mutableSetOf",
        "arrayListOf",
        "hashMapOf",
        "hashSetOf",
        "linkedMapOf",
        "linkedSetOf",
        "listOf",
        "mapOf",
        "setOf",
        "arrayOf",
        "new ArrayList",
        "new HashMap",
        "new HashSet",
        "new LinkedList",
        "new List",
        "new Map",
        "new Set",
        "new Dictionary",
        "new Queue",
        "new Stack",
        "ArrayList",
        "HashMap",
        "HashSet",
        "LinkedList",
        "Dictionary<",
        "List<",
        "Map<",
        "Set<",
        "Collection<",
        "[]",
        "{}",
    )

class MutationRulePack(
    private val config: MutationConfig = MutationConfig(),
) : ConfigurableRulePack {
    override val id: String = MUTATION_PACK_ID
    override val languages: Set<String> = config.languages.keys
    override val apiVersion: Int = CINT_API_VERSION

    override fun configure(values: Map<String, Any?>): RulePack =
        if (values.isEmpty()) this else MutationRulePack(config.withOverrides(values))

    override fun configureAdditive(values: Map<String, Any?>): RulePack =
        if (values.isEmpty()) this else MutationRulePack(MutationConfig.SCHEMA.decodeAdditive(config, values, id))

    override fun configSchema(): ConfigSchema<*> = MutationConfig.SCHEMA

    override fun rules(): List<Rule> =
        listOf(
            DataModifyRule(config),
            LocalValueModifyRule(config),
        )
}

private class DataModifyRule(
    private val config: MutationConfig,
) : Rule {
    override val id: String = DATA_MODIFY_RULE_ID
    override val owns: Intent = DATA_MODIFY_INTENT
    override val reads: Set<Intent> = emptySet()

    override fun run(view: View): List<RuleEmit> {
        val language = config.forLanguage(view.language())
        val persistence = PersistenceMatch(language)
        return view.calls().mapNotNull { call ->
            when {
                persistence.established(view, call) -> {
                    call.emit(DATA_MODIFY_INTENT, confidence = ESTABLISHED_CONFIDENCE)
                }

                persistence.candidate(view, call) -> {
                    CandidateEmit.call(
                        DATA_MODIFY_INTENT,
                        call,
                        CandidateReason.PACK_DOMAIN,
                        source = DATA_MODIFY_SOURCE,
                        confidence = CANDIDATE_CONFIDENCE,
                    )
                }

                else -> {
                    null
                }
            }
        }
    }
}

private class LocalValueModifyRule(
    private val config: MutationConfig,
) : Rule {
    override val id: String = LOCAL_VALUE_MODIFY_RULE_ID
    override val owns: Intent = LOCAL_VALUE_MODIFY_INTENT
    override val reads: Set<Intent> = setOf(BOOKKEEPING_INTENT, LOCAL_COPY_INTENT)

    override fun run(view: View): List<RuleEmit> {
        val language = config.forLanguage(view.language())
        val persistence = PersistenceMatch(language)
        val bookkeeping = if (language.ignoreBookkeeping) bookkeepingSpans(view) else emptySet()
        val owners = declarationWrites(view)
        val result = mutableListOf<RuleEmit>()

        for (write in view.writes()) {
            if (!accepts(write, language.assignmentPatterns)) continue
            if (language.ignorePureCopies && LOCAL_COPY_INTENT in view.intentsAt(write.node)) continue
            if (isBookkeeping(write.node, bookkeeping)) continue
            if (language.ignoreLoopCounters && isLoopCounter(view, write)) continue
            val local = owners[write.node.startByte to write.node.endByte]?.kind == DeclarationKind.VARIABLE
            result +=
                if (local) {
                    Emit.of(LOCAL_VALUE_MODIFY_INTENT, write.node, confidence = LOCAL_CONFIDENCE)
                } else {
                    writeCandidate(write)
                }
        }

        for (call in view.calls()) {
            val callee = call.callee ?: continue
            if (persistence.established(view, call)) continue
            if (persistence.persistentReceiver(view, call)) continue
            if (persistence.candidate(view, call)) continue
            val mutable = callee in language.mutableOperations && isMutableReceiver(view, call, language)
            when {
                mutable -> {
                    result += call.emit(LOCAL_VALUE_MODIFY_INTENT, confidence = LOCAL_CONFIDENCE)
                }

                callee in language.mutableOperations || isLocalCandidate(callee) -> {
                    result +=
                        CandidateEmit.call(
                            LOCAL_VALUE_MODIFY_INTENT,
                            call,
                            CandidateReason.PACK_DOMAIN,
                            source = LOCAL_VALUE_MODIFY_SOURCE,
                            confidence = CANDIDATE_CONFIDENCE,
                        )
                }
            }
        }
        return result
    }

    private fun writeCandidate(write: WriteFact): CandidateEmit =
        CandidateEmit.of(
            LOCAL_VALUE_MODIFY_INTENT,
            write.node,
            CandidateReason.PACK_DOMAIN,
            source = LOCAL_VALUE_MODIFY_SOURCE,
            confidence = CANDIDATE_CONFIDENCE,
        )

    private fun accepts(
        write: WriteFact,
        patterns: Set<String>,
    ): Boolean =
        when (write.kind) {
            WriteKind.ASSIGNMENT -> {
                "assignment" in patterns
            }

            WriteKind.INCREMENT -> {
                val text = write.node.text
                ("increment" in patterns && "++" in text) ||
                    ("decrement" in patterns && "--" in text)
            }

            WriteKind.UNKNOWN -> {
                "assignment" in patterns || "unknown" in patterns
            }
        }

    private fun isMutableReceiver(
        view: View,
        call: CallFact,
        language: MutationLanguageConfig,
    ): Boolean {
        val receiver = call.receiver ?: return false
        val name =
            receiver.text
                .substringBefore('(')
                .substringAfterLast('.')
                .trim()
        if (language.mutableReceiverNames.any { it.equals(name, ignoreCase = true) }) return true
        val type = view.receiverType(call)
        if (type != null && matchesMutableType(type.fqName, language.mutableTypes)) return true
        val initializer =
            view
                .properties()
                .firstOrNull { it.name == name }
                ?.initializer
                ?.text
        return initializer != null && COLLECTION_INITIALIZERS.any { initializer.contains(it) }
    }

    private fun matchesMutableType(
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
}

private class PersistenceMatch(
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

private fun isLocalCandidate(callee: String): Boolean {
    val lower = callee.lowercase()
    if (lower in LOCAL_CANDIDATE_EXACT.map { it.lowercase() }) return true
    return LOCAL_CANDIDATE_PREFIXES.any { prefix ->
        val normalized = prefix.lowercase()
        callee.length > normalized.length &&
            lower.startsWith(normalized) &&
            callee[normalized.length].isUpperCase()
    }
}

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

private fun declarationWrites(view: View): Map<Pair<Int, Int>, DeclarationFact> =
    view
        .declarations()
        .flatMap { declaration ->
            declaration.writes().map { write -> (write.node.startByte to write.node.endByte) to declaration }
        }.toMap()

private fun bookkeepingSpans(view: View): Set<Pair<Int, Int>> =
    view
        .annotations(BOOKKEEPING_INTENT)
        .mapTo(mutableSetOf()) { it.startByte to it.endByte }

private fun isBookkeeping(
    node: SyntaxNode,
    spans: Set<Pair<Int, Int>>,
): Boolean = (node.startByte to node.endByte) in spans

private fun isLoopCounter(
    view: View,
    write: WriteFact,
): Boolean {
    val name = writeTarget(write) ?: return false
    if (name.lowercase() !in LOOP_COUNTER_NAMES) return false
    return view.guards().any { guard ->
        if (guard.kind != GuardKind.LOOP) return@any false
        val header = guard.node.text.trimStart()
        val forLoop = header.startsWith("for") || header.startsWith("foreach")
        forLoop &&
            write.node.startByte >= guard.node.startByte &&
            write.node.endByte <= guard.node.endByte
    }
}

private fun writeTarget(write: WriteFact): String? {
    write.reference?.text?.let { return it.substringAfterLast('.').trim() }
    val text =
        write.node.text
            .trim()
            .removePrefix("++")
            .removePrefix("--")
            .trim()
    return WRITE_TARGET.find(text)?.value?.substringAfterLast('.')
}

private val WRITE_TARGET = Regex("""^[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*""")
