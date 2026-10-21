package dev.cint.rulepacks.intents.mutation

import dev.cint.api.CINT_API_VERSION
import dev.cint.api.CallFact
import dev.cint.api.CandidateEmit
import dev.cint.api.CandidateReason
import dev.cint.api.ConfigSchema
import dev.cint.api.ConfigurableRulePack
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

private val LOCAL_CANDIDATE_NAMES =
    setOf(
        "mutate",
        "modify",
        "apply",
        "applyChanges",
        "applyPatch",
        "change",
        "reset",
        "set",
        "clear",
        "remove",
        "delete",
        "add",
        "put",
        "insert",
        "append",
        "prepend",
        "push",
        "pop",
        "shift",
        "unshift",
        "splice",
        "sort",
        "reverse",
        "shuffle",
        "fill",
        "replace",
        "merge",
        "increment",
        "decrement",
        "update",
        "touch",
        "edit",
        "adjust",
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
    override val reads: Set<Intent> = emptySet()

    override fun run(view: View): List<RuleEmit> {
        val language = config.forLanguage(view.language())
        val persistence = PersistenceMatch(language)
        val bookkeeping = if (language.ignoreBookkeeping) bookkeepingRanges(view) else emptyList()
        val result = mutableListOf<RuleEmit>()

        for (write in view.writes()) {
            if (!accepts(write, language.assignmentPatterns)) continue
            if (isBookkeeping(write.node, bookkeeping)) continue
            if (language.ignoreLoopCounters && isLoopCounter(view, write)) continue
            result += Emit.of(LOCAL_VALUE_MODIFY_INTENT, write.node, confidence = LOCAL_CONFIDENCE)
        }

        for (call in view.calls()) {
            val callee = call.callee ?: continue
            if (persistence.established(view, call)) continue
            if (isBookkeeping(call.site, bookkeeping)) continue
            when {
                callee in language.mutableOperations -> {
                    result += call.emit(LOCAL_VALUE_MODIFY_INTENT, confidence = LOCAL_CONFIDENCE)
                }

                localCandidate(callee, language, persistence) -> {
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

    private fun accepts(
        write: WriteFact,
        patterns: Set<String>,
    ): Boolean =
        when (write.kind) {
            WriteKind.ASSIGNMENT -> "assignment" in patterns
            WriteKind.INCREMENT -> "increment" in patterns || "decrement" in patterns
            WriteKind.UNKNOWN -> "assignment" in patterns || "unknown" in patterns
        }

    private fun localCandidate(
        callee: String,
        language: MutationLanguageConfig,
        persistence: PersistenceMatch,
    ): Boolean {
        if (persistence.looksPersistence(callee)) return false
        return LOCAL_CANDIDATE_NAMES.any { name ->
            callee == name || callee.startsWith(name, ignoreCase = true)
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
        if (config.persistencePaths.any { matchesPath(path, it) }) return true
        if (config.persistenceCalls.any { it.contains('.') && matchesPath(path, it) }) return true
        val callee = call.callee ?: return false
        val callHit = config.persistenceCalls.any { matchesName(callee, path, it) }
        if (!callHit) return false
        val receiver = call.receiver ?: return true
        if (matchesQualifier(receiver.text, config.persistenceQualifiers)) return true
        val type = view.receiverType(call) ?: return false
        return matchesType(type.fqName, config.persistenceTypes)
    }

    fun candidate(
        view: View,
        call: CallFact,
    ): Boolean {
        val callee = call.callee ?: return false
        if (callee in config.mutableOperations) return false
        if (established(view, call)) return false
        return looksPersistence(callee) || config.persistenceCalls.any { it == callee }
    }

    fun looksPersistence(callee: String): Boolean =
        config.persistencePrefixes.any { prefix ->
            callee.length > prefix.length && callee.startsWith(prefix, ignoreCase = true)
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
        val text =
            receiver
                .substringBefore('(')
                .trim()
                .removePrefix("this.")
                .removePrefix("self.")
        return qualifiers.any { qualifier ->
            text.equals(qualifier, ignoreCase = true) ||
                text.endsWith(".$qualifier", ignoreCase = true) ||
                text.endsWith("_$qualifier", ignoreCase = true) ||
                text.endsWith(qualifier.replaceFirstChar { it.uppercase() })
        }
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
}

private fun bookkeepingRanges(view: View): List<IntRange> =
    view
        .declarations()
        .filter { it.kind != DeclarationKind.FUNCTION && it.name != null }
        .filter { it.hasAnnotation("Bookkeeping") || it.hasMagicComment("bookkeeping") }
        .flatMap { declaration ->
            listOf(declaration.node.startByte..declaration.node.endByte) +
                declaration.writes().map { it.node.startByte..it.node.endByte }
        }

private fun isBookkeeping(
    node: SyntaxNode,
    ranges: List<IntRange>,
): Boolean =
    ranges.any { range ->
        node.startByte >= range.first && node.endByte <= range.last
    }

private fun isLoopCounter(
    view: View,
    write: WriteFact,
): Boolean {
    val name = writeTarget(write) ?: return false
    if (name.lowercase() !in LOOP_COUNTER_NAMES) return false
    return view.guards().any { guard ->
        guard.kind == GuardKind.LOOP &&
            write.node.startByte >= guard.node.startByte &&
            write.node.endByte <= guard.node.endByte
    }
}

private fun writeTarget(write: WriteFact): String? {
    write.reference?.text?.let { return it.substringAfterLast('.').trim() }
    return WRITE_TARGET.find(write.node.text.trim())?.value?.substringAfterLast('.')
}

private val WRITE_TARGET = Regex("""^[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*""")
