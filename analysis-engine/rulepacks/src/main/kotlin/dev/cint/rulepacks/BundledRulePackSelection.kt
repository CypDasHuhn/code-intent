package dev.cint.rulepacks

import dev.cint.api.RulePack
import dev.cint.api.RulePackConfigException
import dev.cint.rulepacks.errorhandling.ErrorHandlingRulePack

/**
 * Supplies the language-specific bundled packs that cannot be registered as
 * single service providers without creating duplicate intent owners. The host
 * composition path filters these by language, drops an extra whose owned intent
 * is already covered by a discovered pack, and validates the combined snapshot
 * through the same loader path as service-discovered packs.
 */
object BundledRulePackSelection {
    val packIds: Set<String> = ErrorHandlingRulePack.languages.mapTo(linkedSetOf()) { ErrorHandlingRulePack.forLanguage(it).id }

    fun constructed(language: String): List<RulePack> {
        val normalized = language.lowercase()
        if (normalized !in ErrorHandlingRulePack.languages) return emptyList()
        return listOf(ErrorHandlingRulePack.forLanguage(normalized))
    }

    fun validationIntentParams(projectPacks: Map<String, Map<String, Any?>>): Map<String, List<String>> =
        try {
            ValidationConfig.from(projectPacks[VALIDATION_PACK_ID].orEmpty()).definitionParams()
        } catch (_: RulePackConfigException) {
            ValidationConfig().definitionParams()
        }
}
