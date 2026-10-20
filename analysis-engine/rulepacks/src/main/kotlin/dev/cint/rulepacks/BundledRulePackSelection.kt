package dev.cint.rulepacks

import dev.cint.api.RulePack
import dev.cint.rulepacks.errorhandling.ErrorHandlingRulePack

/**
 * Supplies the language-specific bundled packs that cannot be registered as
 * single service providers without creating duplicate intent owners. The host
 * composition path filters these by language, drops an extra whose owned intent
 * is already covered by a discovered pack, and validates the combined snapshot
 * through the same loader path as service-discovered packs.
 */
object BundledRulePackSelection {
    fun constructed(language: String): List<RulePack> {
        val normalized = language.lowercase()
        if (normalized !in ErrorHandlingRulePack.languages) return emptyList()
        return listOf(ErrorHandlingRulePack.forLanguage(normalized))
    }
}
