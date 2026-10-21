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
import dev.cint.api.emit

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
                    containsName(language.lookupCalls, callee) -> result += candidate(LOOKUP_INTENT, call)
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
        if (language.searchPrefixes.any { callee.startsWith(it, ignoreCase = true) }) return true
        if (!containsName(language.searchCalls, callee)) return false
        val receiver = call.receiver ?: return true
        return matchesName(receiver.text, language.searchQualifiers) ||
            typeMatches(view.receiverType(call), language.searchTypes)
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
        val receiver = call.receiver
        val knownReceiver =
            receiver != null &&
                (isKnownMap(receiver.text, language) || matchesName(receiver.text, language.lookupQualifiers))
        val typed = receiver != null && typeMatches(view.receiverType(call), language.lookupTypes)
        if (containsName(language.lookupCalls, callee)) {
            return receiver == null || knownReceiver || typed
        }
        return isAmbiguous(callee, language) && (knownReceiver || typed)
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
        return root
            .descendants()
            .filter { it.kind in language.indexKinds }
            .mapNotNull { node ->
                val receiver = indexReceiver(node) ?: return@mapNotNull null
                if (!isKnownMap(receiver, language) && !matchesName(receiver, language.lookupQualifiers)) {
                    return@mapNotNull null
                }
                Emit.of(LOOKUP_INTENT, node)
            }.toList()
    }

    private fun indexReceiver(node: SyntaxNode): String? {
        val bracket = node.text.indexOf('[')
        if (bracket <= 0) return null
        return node.text
            .substring(0, bracket)
            .trim()
            .ifEmpty { null }
    }

    private fun isKnownMap(
        receiver: String,
        language: DataAccessLanguageConfig,
    ): Boolean =
        matchesName(receiver, language.knownMaps) ||
            language.knownMapSuffixes.any { receiverName(receiver).endsWith(it, ignoreCase = true) }

    private fun matchesName(
        receiver: String,
        names: Set<String>,
    ): Boolean {
        val normalized = receiver.trim()
        val segments = receiverSegments(normalized)
        return names.any { expected ->
            val name = expected.trim()
            normalized.equals(name, ignoreCase = true) ||
                segments.any { it.equals(name, ignoreCase = true) || it.endsWith(name, ignoreCase = true) }
        }
    }

    private fun receiverName(receiver: String): String = receiverSegments(receiver).lastOrNull().orEmpty()

    private fun receiverSegments(receiver: String): List<String> =
        receiver
            .split('.')
            .map { it.substringBefore('(').substringBefore('[').trim() }
            .filter { it.isNotEmpty() }

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
        val simple = actual.substringAfterLast('.')
        return configured.any { expected ->
            actual.equals(expected, ignoreCase = true) ||
                actual.endsWith(".$expected", ignoreCase = true) ||
                simple.equals(expected.substringAfterLast('.'), ignoreCase = true)
        }
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
