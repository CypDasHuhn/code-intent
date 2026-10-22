package dev.cint.rulepacks.intents.mutation

import dev.cint.api.CINT_API_VERSION
import dev.cint.api.ConfigSchema
import dev.cint.api.ConfigurableRulePack
import dev.cint.api.Intent
import dev.cint.api.Rule
import dev.cint.api.RulePack
import dev.cint.rulepacks.intents.mutation.rules.DataModifyRule
import dev.cint.rulepacks.intents.mutation.rules.LocalValueModifyRule

const val MUTATION_PACK_ID: String = "mutation"
const val DATA_MODIFY_INTENT: Intent = "DATA_MODIFY"
const val LOCAL_VALUE_MODIFY_INTENT: Intent = "LOCAL_VALUE_MODIFY"
const val DATA_MODIFY_RULE_ID: String = "$MUTATION_PACK_ID:data-modify"
const val LOCAL_VALUE_MODIFY_RULE_ID: String = "$MUTATION_PACK_ID:local-value-modify"

internal const val DATA_MODIFY_SOURCE: String = "rule:$DATA_MODIFY_RULE_ID"
internal const val LOCAL_VALUE_MODIFY_SOURCE: String = "rule:$LOCAL_VALUE_MODIFY_RULE_ID"
internal const val ESTABLISHED_CONFIDENCE: Double = 0.9
internal const val LOCAL_CONFIDENCE: Double = 0.8
internal const val CANDIDATE_CONFIDENCE: Double = 0.4

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
