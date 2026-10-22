package dev.cint.rulepacks.intents.dataaccess.rules

import dev.cint.api.CallFact
import dev.cint.api.Intent
import dev.cint.api.RuleEmit
import dev.cint.api.View
import dev.cint.api.emit
import dev.cint.rulepacks.intents.dataaccess.SEARCH_INTENT
import dev.cint.rulepacks.intents.dataaccess.config.DataAccessLanguageConfig
import dev.cint.rulepacks.intents.dataaccess.dataAccessCandidate
import dev.cint.rulepacks.intents.support.isTypeQueryableReceiver

internal class DataAccessSearchMatcher(
    private val language: DataAccessLanguageConfig,
) {
    fun match(
        view: View,
        ruleId: String,
    ): List<RuleEmit> {
        val result = mutableListOf<RuleEmit>()
        for (call in view.calls()) {
            val callee = call.callee ?: continue
            if (isLookupOnly(callee)) continue
            when {
                isEstablishedSearch(view, call, callee) -> result += call.emit(SEARCH_INTENT)
                isAmbiguous(callee, language) -> result += candidate(SEARCH_INTENT, call, ruleId)
                isConfiguredSearchCall(callee) -> result += candidate(SEARCH_INTENT, call, ruleId)
            }
        }
        return result
    }

    private fun candidate(
        intent: Intent,
        call: CallFact,
        ruleId: String,
    ) = dataAccessCandidate(intent, call, ruleId)

    private fun isEstablishedSearch(
        view: View,
        call: CallFact,
        callee: String,
    ): Boolean {
        if (isLookupOnly(callee)) return false
        if (isAmbiguous(callee, language)) return false
        val configured =
            matchesConfiguredPrefix(callee, language.searchPrefixes) ||
                containsName(language.searchCalls, callee)
        if (!configured) return false
        val receiver = call.receiver ?: return false
        return matchesName(receiver.text, language.searchQualifiers) ||
            (isTypeQueryableReceiver(receiver) && typeMatches(view.receiverType(call), language.searchTypes))
    }

    private fun isConfiguredSearchCall(callee: String): Boolean =
        !isLookupOnly(callee) &&
            (
                containsName(language.searchCalls, callee) ||
                    matchesConfiguredPrefix(callee, language.searchPrefixes)
            )

    private fun isLookupOnly(callee: String): Boolean =
        containsName(language.lookupCalls, callee) && !containsName(language.searchCalls, callee)
}
