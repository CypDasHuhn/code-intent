package dev.cint.rulepacks.validation

import dev.cint.api.Intent
import dev.cint.api.Matcher
import dev.cint.api.SingleIntentPack
import dev.cint.rulepacks.bookkeeping.BOOKKEEPING_INTENT
import dev.cint.rulepacks.intents.mutation.DATA_MODIFY_INTENT
import dev.cint.rulepacks.intents.mutation.LOCAL_VALUE_MODIFY_INTENT

const val VALIDATION_INTENT: Intent = "VALIDATION"
const val VALIDATION_PACK_ID: String = "validation"
const val VALIDATION_RULE_ID: String = "$VALIDATION_PACK_ID:validation"

private val MUTATION_INTENTS = setOf(DATA_MODIFY_INTENT, LOCAL_VALUE_MODIFY_INTENT, BOOKKEEPING_INTENT)

class ValidationRulePack(
    config: ValidationConfig = ValidationConfig(),
) : SingleIntentPack<ValidationConfig>(
        id = VALIDATION_PACK_ID,
        intent = VALIDATION_INTENT,
        languages = setOf("csharp", "javascript", "kotlin", "typescript"),
        config = config,
        schema = ValidationConfig.SCHEMA,
        ruleId = VALIDATION_RULE_ID,
    ) {
    override val reads: Set<Intent> = MUTATION_INTENTS

    override val matchers: List<Matcher> = listOf(Matcher { view -> ValidationRule(config).match(view) })

    override fun withConfig(config: ValidationConfig): SingleIntentPack<ValidationConfig> = ValidationRulePack(config)
}
