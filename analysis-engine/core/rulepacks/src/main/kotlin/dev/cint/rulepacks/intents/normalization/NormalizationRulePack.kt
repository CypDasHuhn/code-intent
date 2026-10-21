package dev.cint.rulepacks.intents.normalization

import dev.cint.api.CallFact
import dev.cint.api.CandidateEmit
import dev.cint.api.CandidateReason
import dev.cint.api.Intent
import dev.cint.api.Matcher
import dev.cint.api.RuleEmit
import dev.cint.api.SingleIntentPack
import dev.cint.api.SyntaxNode
import dev.cint.api.View
import dev.cint.api.emit

const val NORMALIZATION_INTENT: Intent = "NORMALIZATION"
const val NORMALIZATION_PACK_ID: String = "normalization"
const val NORMALIZATION_RULE_ID: String = "$NORMALIZATION_PACK_ID:normalization"

private const val NORMALIZATION_CONFIDENCE: Double = 0.9
private const val CANDIDATE_CONFIDENCE: Double = 0.4

class NormalizationRulePack(
    config: NormalizationConfig = NormalizationConfig(),
) : SingleIntentPack<NormalizationConfig>(
        id = NORMALIZATION_PACK_ID,
        intent = NORMALIZATION_INTENT,
        languages = config.languages.keys.toSet(),
        config = config,
        schema = NormalizationConfig.SCHEMA,
        ruleId = NORMALIZATION_RULE_ID,
    ) {
    override val matchers: List<Matcher> = listOf(Matcher { view -> NormalizationRule(config.forLanguage(view.language())).match(view) })

    override fun withConfig(config: NormalizationConfig): SingleIntentPack<NormalizationConfig> = NormalizationRulePack(config)
}

private enum class Match {
    ESTABLISHED,
    CANDIDATE,
}

private class NormalizationRule(
    private val config: NormalizationLanguageConfig,
) : Matcher {
    override fun match(view: View): List<RuleEmit> {
        val calls = view.calls()
        val matches = calls.map { config.matchKind(view, it) }
        val established =
            calls.zip(matches).mapNotNull { (call, match) ->
                if (match == Match.ESTABLISHED) call.emit(NORMALIZATION_INTENT, confidence = NORMALIZATION_CONFIDENCE) else null
            }
        val directCandidates =
            calls.zip(matches).mapNotNull { (call, match) ->
                if (match == Match.CANDIDATE) candidate(call) else null
            }
        return established + (directCandidates + chainCandidates(calls, matches)).distinctBy(::candidateIdentity)
    }

    private fun chainCandidates(
        calls: List<CallFact>,
        matches: List<Match?>,
    ): List<CandidateEmit> {
        if (calls.size < 2) return emptyList()
        val callsBySpan = calls.associateBy { it.node.startByte to it.node.endByte }
        val matchesBySpan =
            calls
                .mapIndexed { index, call -> (call.node.startByte to call.node.endByte) to matches[index] }
                .toMap()

        fun receiverCall(call: CallFact): CallFact? = call.receiver?.let { receiver -> callsBySpan[receiver.startByte to receiver.endByte] }

        val receivers =
            calls
                .mapNotNull { call -> receiverCall(call)?.let { it.node.startByte to it.node.endByte } }
                .toSet()

        return calls.mapNotNull { call ->
            val span = call.node.startByte to call.node.endByte
            if (span in receivers) return@mapNotNull null

            val chain = mutableListOf<CallFact>()
            var current: CallFact? = call
            while (current != null && chain.size <= calls.size) {
                chain += current
                current = receiverCall(current)
            }
            if (chain.size < 2) return@mapNotNull null

            val chainMatches = chain.map { matchesBySpan[it.node.startByte to it.node.endByte] }
            if (chainMatches.none { it != null }) return@mapNotNull null
            candidate(call)
        }
    }

    private fun candidate(call: CallFact): CandidateEmit =
        CandidateEmit.of(
            NORMALIZATION_INTENT,
            call.site,
            CandidateReason.PACK_DOMAIN,
            source = "rule:$NORMALIZATION_RULE_ID",
            confidence = CANDIDATE_CONFIDENCE,
            aiEligible = true,
        )

    private fun candidateIdentity(candidate: CandidateEmit): List<Any> =
        listOf(
            candidate.intents,
            candidate.startByte,
            candidate.endByte,
            candidate.reason,
            candidate.source,
            candidate.confidence,
            candidate.aiEligible,
        )
}

private fun NormalizationLanguageConfig.matchKind(
    view: View,
    call: CallFact,
): Match? {
    val callee = call.callee ?: return null
    val configuredName = callee in calls || prefixes.any { it.isNotEmpty() && callee.startsWith(it) }
    if (configuredName) {
        val unqualifiedStatic =
            call.receiver?.let(::isTypeLike) == true &&
                !qualifierMatches(call) &&
                !typeMatches(view, call)
        return if (unqualifiedStatic) Match.CANDIDATE else Match.ESTABLISHED
    }

    val candidateName = callee in candidateCalls || candidatePrefixes.any { it.isNotEmpty() && callee.startsWith(it) }
    if (!candidateName) return null
    return if (qualifierMatches(call) || typeMatches(view, call)) Match.ESTABLISHED else Match.CANDIDATE
}

private fun NormalizationLanguageConfig.qualifierMatches(call: CallFact): Boolean {
    if (qualifiers.isEmpty()) return false
    val callee = call.callee ?: return false
    val receiver = call.receiver?.text
    if (receiver != null && qualifiers.any { receiver == it || receiver.endsWith(".$it") }) return true
    val path = call.path ?: return false
    return qualifiers.any { qualifier -> path == "$qualifier.$callee" || path.endsWith(".$qualifier.$callee") }
}

private fun NormalizationLanguageConfig.typeMatches(
    view: View,
    call: CallFact,
): Boolean {
    if (types.isEmpty()) return false
    val actual = view.receiverType(call)?.fqName ?: return false
    val actualSimple = actual.substringAfterLast('.')
    return types.any { expected ->
        actual == expected ||
            actual.endsWith(".$expected") ||
            (!actual.contains('.') && expected.substringAfterLast('.') == actualSimple)
    }
}

private fun isTypeLike(receiver: SyntaxNode): Boolean =
    receiver.text
        .substringAfterLast('.')
        .firstOrNull()
        ?.isUpperCase() == true
