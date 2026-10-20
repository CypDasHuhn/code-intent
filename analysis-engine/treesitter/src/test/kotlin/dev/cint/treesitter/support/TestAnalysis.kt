package dev.cint.treesitter.support

import dev.cint.api.RulePack
import dev.cint.core.config.DEFAULT_PURITY_ANNOTATIONS
import dev.cint.core.config.IntentConfig
import dev.cint.core.config.IntentDefinition
import dev.cint.rulepacks.BookkeepingRulePack
import dev.cint.rulepacks.LoggingRulePack
import dev.cint.rulepacks.ValidationConfig
import dev.cint.rulepacks.ValidationRulePack
import dev.cint.rulepacks.errorhandling.ErrorHandlingRulePack

internal fun testConfig(language: String): IntentConfig =
    IntentConfig(
        language = language,
        intents =
            mapOf(
                "VALIDATION" to
                    IntentDefinition(
                        params = ValidationConfig().definitionParams(),
                    ),
            ),
        pureAnnotations = DEFAULT_PURITY_ANNOTATIONS,
    )

internal fun testPacks(language: String): List<RulePack> =
    listOf(
        LoggingRulePack(),
        ErrorHandlingRulePack.forLanguage(language),
        BookkeepingRulePack(),
        ValidationRulePack(),
    )
