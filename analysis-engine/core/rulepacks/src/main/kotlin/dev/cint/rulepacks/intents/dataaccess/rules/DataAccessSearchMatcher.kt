package dev.cint.rulepacks.intents.dataaccess

import dev.cint.api.CallFact
import dev.cint.api.Intent
import dev.cint.api.RuleEmit
import dev.cint.api.View
import dev.cint.api.emit
import dev.cint.rulepacks.intents.isTypeQueryableReceiver

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
        if (isAmbiguous(callee, language)) return false
        val configured =
            language.searchPrefixes.any { callee.startsWith(it, ignoreCase = true) } ||
                containsName(language.searchCalls, callee)
        if (!configured) return false
        val receiver = call.receiver ?: return false
        return matchesName(receiver.text, language.searchQualifiers) ||
            (isTypeQueryableReceiver(receiver) && typeMatches(view.receiverType(call), language.searchTypes))
    }

    private fun isConfiguredSearchCall(callee: String): Boolean =
        containsName(language.searchCalls, callee) ||
            language.searchPrefixes.any { callee.startsWith(it, ignoreCase = true) }
}
