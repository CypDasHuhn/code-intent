package dev.cint.rulepacks.intents.dataaccess

import dev.cint.api.CINT_API_VERSION
import dev.cint.api.CallFact
import dev.cint.api.CandidateEmit
import dev.cint.api.CandidateReason
import dev.cint.api.ConfigSchema
import dev.cint.api.ConfigurableRulePack
import dev.cint.api.Emit
import dev.cint.api.Intent
import dev.cint.api.Rule
import dev.cint.api.RuleEmit
import dev.cint.api.SyntaxNode
import dev.cint.api.TypeRef
import dev.cint.api.View
import dev.cint.api.WriteFact
import dev.cint.api.WriteKind
import dev.cint.api.emit
import dev.cint.rulepacks.intents.isTypeQueryableReceiver

const val SEARCH_INTENT: Intent = "SEARCH"
const val LOOKUP_INTENT: Intent = "LOOKUP"
const val DATA_ACCESS_PACK_ID: String = "data-access"
const val SEARCH_RULE_ID: String = "$DATA_ACCESS_PACK_ID:search"
const val LOOKUP_RULE_ID: String = "$DATA_ACCESS_PACK_ID:lookup"

private const val CANDIDATE_CONFIDENCE: Double = 0.4

class DataAccessRulePack(
    private val config: DataAccessConfig = DataAccessConfig(),
) : ConfigurableRulePack {
    override val id: String = DATA_ACCESS_PACK_ID
    override val languages: Set<String> = config.languages.keys
    override val apiVersion: Int = CINT_API_VERSION

    override fun rules(): List<Rule> =
        listOf(
            DataAccessRule(config, SEARCH_RULE_ID, SEARCH_INTENT),
            DataAccessRule(config, LOOKUP_RULE_ID, LOOKUP_INTENT),
        )

    override fun configure(values: Map<String, Any?>): ConfigurableRulePack =
        if (values.isEmpty()) this else DataAccessRulePack(config.withOverrides(values))

    override fun configureAdditive(values: Map<String, Any?>): ConfigurableRulePack =
        if (values.isEmpty()) this else DataAccessRulePack(SCHEMA.decodeAdditive(config, values, id))

    override fun configSchema(): ConfigSchema<*> = SCHEMA

    private companion object {
        val SCHEMA = DataAccessConfig.SCHEMA
    }
}

private class DataAccessRule(
    private val config: DataAccessConfig,
    override val id: String,
    override val owns: Intent,
) : Rule {
    override val reads: Set<Intent> = emptySet()

    override fun run(view: View): List<RuleEmit> {
        val language = config.forLanguage(view.language())
        return when (owns) {
            SEARCH_INTENT -> search(view, language)
            LOOKUP_INTENT -> lookup(view, language)
            else -> emptyList()
        }
    }

    private fun search(
        view: View,
        language: DataAccessLanguageConfig,
    ): List<RuleEmit> {
        val result = mutableListOf<RuleEmit>()
        for (call in view.calls()) {
            val callee = call.callee ?: continue
            when {
                isEstablishedSearch(view, call, callee, language) -> result += call.emit(SEARCH_INTENT)
                isAmbiguous(callee, language) -> result += candidate(SEARCH_INTENT, call)
                isConfiguredSearchCall(callee, language) -> result += candidate(SEARCH_INTENT, call)
            }
        }
        return result
    }

    private fun lookup(
        view: View,
        language: DataAccessLanguageConfig,
    ): List<RuleEmit> {
        val result = mutableListOf<RuleEmit>()
        for (call in view.calls()) {
            val callee = call.callee ?: continue
            when {
                isEstablishedLookup(view, call, callee, language) -> result += call.emit(LOOKUP_INTENT)

                isBoundaryLookup(callee, language) ||
                    isAmbiguous(callee, language) ||
                    containsName(language.lookupCalls, callee) ||
                    isEnumCandidate(view, call, callee, language) -> result += candidate(LOOKUP_INTENT, call)
            }
        }
        result += knownMapIndexes(view, language)
        return result
    }

    private fun candidate(
        intent: Intent,
        call: CallFact,
    ): CandidateEmit =
        CandidateEmit.call(
            intent,
            call,
            CandidateReason.PACK_DOMAIN,
            source = "rule:$id",
            confidence = CANDIDATE_CONFIDENCE,
        )

    private fun isEstablishedSearch(
        view: View,
        call: CallFact,
        callee: String,
        language: DataAccessLanguageConfig,
    ): Boolean {
        if (isAmbiguous(callee, language)) return false
        val configured =
            language.searchPrefixes.any { callee.startsWith(it, ignoreCase = true) } ||
                containsName(language.searchCalls, callee)
        if (!configured) return false
        val receiver = call.receiver ?: return false
        return matchesName(receiver.text, language.searchQualifiers) ||
            (isTypeQueryableReceiver(receiver) && typeMatches(view.receiverType(call), language.searchTypes))
    }

    private fun isConfiguredSearchCall(
        callee: String,
        language: DataAccessLanguageConfig,
    ): Boolean =
        containsName(language.searchCalls, callee) ||
            language.searchPrefixes.any { callee.startsWith(it, ignoreCase = true) }

    private fun isEstablishedLookup(
        view: View,
        call: CallFact,
        callee: String,
        language: DataAccessLanguageConfig,
    ): Boolean {
        val receiver = call.receiver ?: return false
        val enumCall = containsName(language.enumCalls, callee)
        val lookupCall = containsName(language.lookupCalls, callee)
        if (!enumCall && !lookupCall && !isAmbiguous(callee, language)) return false
        val knownReceiver =
            isKnownMap(receiver.text, language) || matchesName(receiver.text, language.lookupQualifiers)
        val enumReceiver = if (enumCall) isEnumReceiver(view, call, language) else false
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
        language: DataAccessLanguageConfig,
    ): Boolean =
        containsName(language.enumCalls, callee) &&
            (call.receiver == null || isEnumReceiver(view, call, language) || isEnumStyleReceiver(view, call, language))

    private fun isEnumStyleReceiver(
        view: View,
        call: CallFact,
        language: DataAccessLanguageConfig,
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
        language: DataAccessLanguageConfig,
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

    private fun isBoundaryLookup(
        callee: String,
        language: DataAccessLanguageConfig,
    ): Boolean = language.boundaryPrefixes.any { callee.startsWith(it, ignoreCase = true) }

    private fun isAmbiguous(
        callee: String,
        language: DataAccessLanguageConfig,
    ): Boolean {
        if (containsName(language.lookupCalls, callee)) return false
        if (containsName(language.searchCalls, callee)) return false
        if (language.searchPrefixes.any { callee.startsWith(it, ignoreCase = true) }) return false
        return containsName(language.ambiguousCalls, callee) ||
            language.ambiguousPrefixes.any { callee.startsWith(it, ignoreCase = true) }
    }

    private fun knownMapIndexes(
        view: View,
        language: DataAccessLanguageConfig,
    ): List<Emit> {
        if (language.indexKinds.isEmpty()) return emptyList()
        val root = rootNode(view) ?: return emptyList()
        val writeTargets = view.writes().mapNotNull(::writeTarget)
        return root
            .descendants()
            .filter { node -> isIndexNode(node, language) }
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
            WriteKind.INCREMENT -> write.node.children.firstOrNull { child -> child.text.trim().none { it == '+' || it == '-' } }
            else -> null
        }

    private fun isIndexNode(
        node: SyntaxNode,
        language: DataAccessLanguageConfig,
    ): Boolean =
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

    private fun isKnownMap(
        receiver: String,
        language: DataAccessLanguageConfig,
    ): Boolean =
        matchesName(receiver, language.knownMaps) ||
            language.knownMapSuffixes.any { hasTokenSuffix(receiverNameTokens(receiver), tokenize(it)) }

    private fun matchesName(
        receiver: String,
        names: Set<String>,
    ): Boolean {
        val segments = receiverSegments(receiver)
        val allTokens = segments.flatten()
        return names.any { expected ->
            val expectedTokens = tokenize(expected)
            when {
                expectedTokens.isEmpty() -> {
                    false
                }

                expectedTokens.size == 1 -> {
                    allTokens.any { it == expectedTokens.single() }
                }

                else -> {
                    segments.any { containsTokens(it, expectedTokens) } ||
                        containsTokens(allTokens, expectedTokens)
                }
            }
        }
    }

    private fun containsTokens(
        tokens: List<String>,
        expected: List<String>,
    ): Boolean = tokens.windowed(expected.size).any { it == expected }

    private fun hasTokenSuffix(
        tokens: List<String>,
        suffix: List<String>,
    ): Boolean = suffix.isNotEmpty() && tokens.size >= suffix.size && tokens.takeLast(suffix.size) == suffix

    private fun receiverName(receiver: String): String =
        receiver
            .split('.')
            .lastOrNull()
            ?.substringBefore('(')
            ?.substringBefore('[')
            ?.trim()
            .orEmpty()

    private fun receiverNameTokens(receiver: String): List<String> = receiverSegments(receiver).lastOrNull().orEmpty()

    private fun receiverSegments(receiver: String): List<List<String>> =
        receiver
            .split('.')
            .map { tokenize(it.substringBefore('(').substringBefore('[')) }
            .filter { it.isNotEmpty() }

    private fun tokenize(value: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()

        fun flush() {
            if (current.isNotEmpty()) {
                tokens += current.toString().lowercase()
                current.clear()
            }
        }

        for (index in value.indices) {
            val character = value[index]
            if (!character.isLetterOrDigit()) {
                flush()
                continue
            }
            if (current.isNotEmpty()) {
                val previous = value[index - 1]
                val next = value.getOrNull(index + 1)
                val boundary =
                    ((previous.isLowerCase() || previous.isDigit()) && character.isUpperCase()) ||
                        (
                            previous.isUpperCase() &&
                                character.isUpperCase() &&
                                next != null &&
                                next.isLowerCase()
                        )
                if (boundary) flush()
            }
            current.append(character)
        }
        flush()
        return tokens
    }

    private fun containsName(
        names: Set<String>,
        value: String,
    ): Boolean = names.any { it.equals(value, ignoreCase = true) }

    private fun typeMatches(
        type: TypeRef?,
        configured: Set<String>,
    ): Boolean {
        if (type == null) return false
        val actual = type.fqName
        return configured.any { raw ->
            val expected = raw.trim()
            when {
                expected.isEmpty() -> false
                expected.contains('.') -> actual == expected || actual.endsWith(".$expected")
                else -> hasTokenSuffix(tokenize(actual.substringAfterLast('.')), tokenize(expected))
            }
        }
    }

    private fun matchesTypeSuffix(
        type: TypeRef?,
        suffixes: Set<String>,
    ): Boolean {
        val actual = type?.fqName?.substringAfterLast('.') ?: return false
        val tokens = tokenize(actual)
        return suffixes.any { hasTokenSuffix(tokens, tokenize(it)) }
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
