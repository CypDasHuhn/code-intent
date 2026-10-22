package dev.cint.rulepacks.intents.mutation.rules
import dev.cint.api.CallFact
import dev.cint.api.CandidateEmit
import dev.cint.api.CandidateReason
import dev.cint.api.Intent
import dev.cint.api.Rule
import dev.cint.api.RuleEmit
import dev.cint.api.View
import dev.cint.api.emit
import dev.cint.rulepacks.intents.mutation.CANDIDATE_CONFIDENCE
import dev.cint.rulepacks.intents.mutation.DATA_MODIFY_INTENT
import dev.cint.rulepacks.intents.mutation.DATA_MODIFY_RULE_ID
import dev.cint.rulepacks.intents.mutation.DATA_MODIFY_SOURCE
import dev.cint.rulepacks.intents.mutation.ESTABLISHED_CONFIDENCE
import dev.cint.rulepacks.intents.mutation.MutationConfig
import dev.cint.rulepacks.intents.mutation.MutationLanguageConfig
import dev.cint.rulepacks.intents.support.callbackParameterAt
import dev.cint.rulepacks.intents.support.containsConfiguredTarget

internal class DataModifyRule(
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
                durableCallback(view, call, language) -> {
                    call.emit(DATA_MODIFY_INTENT, confidence = ESTABLISHED_CONFIDENCE)
                }

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

    private fun durableCallback(
        view: View,
        call: CallFact,
        language: MutationLanguageConfig,
    ): Boolean {
        val callback = callbackParameterAt(view, call) ?: return false
        val default = callback.default ?: return false
        return containsConfiguredTarget(default, language.persistencePaths + language.persistenceCalls)
    }
}
