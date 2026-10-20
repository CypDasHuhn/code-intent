package dev.cint.treesitter.support

import dev.cint.api.RulePack
import dev.cint.core.config.DEFAULT_PURITY_ANNOTATIONS
import dev.cint.core.config.IntentConfig
import dev.cint.rulepacks.bookkeeping.BookkeepingRulePack
import dev.cint.rulepacks.errorhandling.ErrorHandlingRulePack
import dev.cint.rulepacks.logging.LoggingRulePack
import dev.cint.rulepacks.validation.ValidationRulePack

internal fun testConfig(language: String): IntentConfig =
    IntentConfig(
        language = language,
        intents = emptyMap(),
        pureAnnotations = DEFAULT_PURITY_ANNOTATIONS,
    )

internal fun testPacks(language: String): List<RulePack> =
    listOf(
        LoggingRulePack(),
        ErrorHandlingRulePack.forLanguage(language),
        BookkeepingRulePack(),
        ValidationRulePack(),
    )
