package dev.cint.rulepacks.intents.dataaccess

import dev.cint.api.CINT_API_VERSION
import dev.cint.api.CallFact
import dev.cint.api.CandidateEmit
import dev.cint.api.CandidateReason
import dev.cint.api.ConfigSchema
import dev.cint.api.ConfigurableRulePack
import dev.cint.api.Intent
import dev.cint.api.Rule
import dev.cint.api.RuleEmit
import dev.cint.api.View

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

internal fun dataAccessCandidate(
    intent: Intent,
    call: CallFact,
    ruleId: String,
): CandidateEmit =
    CandidateEmit.call(
        intent,
        call,
        CandidateReason.PACK_DOMAIN,
        source = "rule:$ruleId",
        confidence = CANDIDATE_CONFIDENCE,
    )

private class DataAccessRule(
    private val config: DataAccessConfig,
    override val id: String,
    override val owns: Intent,
) : Rule {
    override val reads: Set<Intent> = emptySet()

    override fun run(view: View): List<RuleEmit> {
        val language = config.forLanguage(view.language())
        return when (owns) {
            SEARCH_INTENT -> DataAccessSearchMatcher(language).match(view, id)
            LOOKUP_INTENT -> DataAccessLookupMatcher(language).match(view, id)
            else -> emptyList()
        }
    }
}
