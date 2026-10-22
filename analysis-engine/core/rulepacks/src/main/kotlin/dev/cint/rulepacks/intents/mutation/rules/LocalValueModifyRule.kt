package dev.cint.rulepacks.intents.mutation.rules
import dev.cint.api.CallFact
import dev.cint.api.CandidateEmit
import dev.cint.api.CandidateReason
import dev.cint.api.DeclarationKind
import dev.cint.api.Emit
import dev.cint.api.Intent
import dev.cint.api.Rule
import dev.cint.api.RuleEmit
import dev.cint.api.View
import dev.cint.api.WriteFact
import dev.cint.api.WriteKind
import dev.cint.api.emit
import dev.cint.rulepacks.bookkeeping.BOOKKEEPING_INTENT
import dev.cint.rulepacks.callback.callbackParameterAt
import dev.cint.rulepacks.intents.mutation.CANDIDATE_CONFIDENCE
import dev.cint.rulepacks.intents.mutation.LOCAL_CANDIDATE_EXACT
import dev.cint.rulepacks.intents.mutation.LOCAL_CONFIDENCE
import dev.cint.rulepacks.intents.mutation.LOCAL_VALUE_MODIFY_INTENT
import dev.cint.rulepacks.intents.mutation.LOCAL_VALUE_MODIFY_RULE_ID
import dev.cint.rulepacks.intents.mutation.LOCAL_VALUE_MODIFY_SOURCE
import dev.cint.rulepacks.intents.mutation.MutationConfig
import dev.cint.rulepacks.intents.mutation.MutationLanguageConfig
import dev.cint.rulepacks.intents.structural.LOCAL_COPY_INTENT

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

internal class LocalValueModifyRule(
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
            if (!effectiveWrite(view, write, language, bookkeeping)) continue
            val local = owners[write.node.startByte to write.node.endByte]?.kind == DeclarationKind.VARIABLE
            result +=
                if (local) {
                    Emit.of(LOCAL_VALUE_MODIFY_INTENT, write.node, confidence = LOCAL_CONFIDENCE)
                } else {
                    writeCandidate(write)
                }
        }

        if (language.mutableLocalDeclarations) {
            val emittedDeclarations = mutableSetOf<Pair<Int, Int>>()
            for (declaration in view.declarations()) {
                val node = mutableLocalDeclarationNode(declaration, view.language()) ?: continue
                if (declaration.writes().none { effectiveWrite(view, it, language, bookkeeping) }) continue
                if (language.ignorePureCopies && LOCAL_COPY_INTENT in view.intentsAt(declaration.node)) continue
                if (emittedDeclarations.add(node.startByte to node.endByte)) {
                    result += Emit.of(LOCAL_VALUE_MODIFY_INTENT, node, confidence = LOCAL_CONFIDENCE)
                }
            }
        }

        for (call in view.calls()) {
            val callee = call.callee ?: continue
            if (callbackParameterAt(view, call) != null) continue
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

    private fun effectiveWrite(
        view: View,
        write: WriteFact,
        language: MutationLanguageConfig,
        bookkeeping: Set<Pair<Int, Int>>,
    ): Boolean {
        if (!accepts(write, language.assignmentPatterns)) return false
        if (language.ignorePureCopies && LOCAL_COPY_INTENT in view.intentsAt(write.node)) return false
        if (isBookkeeping(write.node, bookkeeping)) return false
        if (language.ignoreLoopCounters && isLoopCounter(view, write)) return false
        return true
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

internal fun isLocalCandidate(callee: String): Boolean {
    val lower = callee.lowercase()
    if (lower in LOCAL_CANDIDATE_EXACT.map { it.lowercase() }) return true
    return LOCAL_CANDIDATE_PREFIXES.any { prefix ->
        val normalized = prefix.lowercase()
        callee.length > normalized.length &&
            lower.startsWith(normalized) &&
            callee[normalized.length].isUpperCase()
    }
}
