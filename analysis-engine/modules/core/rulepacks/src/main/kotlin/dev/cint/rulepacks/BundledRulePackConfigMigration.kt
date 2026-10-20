package dev.cint.rulepacks

import dev.cint.api.RulePackConfigIssue
import dev.cint.api.RulePackConfigResolver
import dev.cint.rulepacks.errorhandling.ERROR_HANDLING_PACK_ID
import dev.cint.rulepacks.errorhandling.ErrorHandlingConfig
import dev.cint.rulepacks.errorhandling.ErrorHandlingLanguageConfig

object BundledRulePackConfigMigration {
    private val legacyErrorHandlingIds: Map<String, String> =
        ErrorHandlingConfig.languages.associateWith { "$ERROR_HANDLING_PACK_ID-$it" }

    fun resolve(
        config: Map<String, Map<String, Any?>>,
        discoveredPackIds: Set<String>,
        issues: MutableList<RulePackConfigIssue> = mutableListOf(),
    ): Map<String, Map<String, Any?>> {
        if (ERROR_HANDLING_PACK_ID !in discoveredPackIds) return config

        val errorHandling = linkedMapOf<String, Any?>()
        config[ERROR_HANDLING_PACK_ID]?.let(errorHandling::putAll)
        val migratedIds = linkedSetOf<String>()
        for ((language, legacyId) in legacyErrorHandlingIds) {
            if (legacyId in discoveredPackIds) continue
            val values = config[legacyId] ?: continue
            migratedIds += legacyId
            val result =
                ErrorHandlingLanguageConfig.SCHEMA.decodeResult(
                    ErrorHandlingLanguageConfig.defaults().getValue(language),
                    values,
                    "$ERROR_HANDLING_PACK_ID.$language",
                )
            if (result.errors.isEmpty()) {
                errorHandling[language] = values
            } else {
                issues +=
                    RulePackConfigIssue(
                        ERROR_HANDLING_PACK_ID,
                        "rulepack '$ERROR_HANDLING_PACK_ID' ignored invalid legacy config " +
                            "'$legacyId': ${result.errors.joinToString("; ")}",
                    )
            }
        }

        val packs = linkedMapOf<String, Map<String, Any?>>()
        for ((id, values) in config) {
            if (id == ERROR_HANDLING_PACK_ID || id in migratedIds) continue
            packs[id] = values
        }
        if (errorHandling.isNotEmpty()) packs[ERROR_HANDLING_PACK_ID] = errorHandling
        return packs
    }
}

object BundledRulePackConfigResolver : RulePackConfigResolver {
    override fun resolve(
        config: Map<String, Map<String, Any?>>,
        discoveredPackIds: Set<String>,
        issues: MutableList<RulePackConfigIssue>,
    ): Map<String, Map<String, Any?>> = BundledRulePackConfigMigration.resolve(config, discoveredPackIds, issues)
}
