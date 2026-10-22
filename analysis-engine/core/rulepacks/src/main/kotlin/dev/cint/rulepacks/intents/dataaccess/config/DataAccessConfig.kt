package dev.cint.rulepacks.intents.dataaccess.config

import dev.cint.api.ConfigSchema
import dev.cint.api.LanguageConfig
import dev.cint.rulepacks.intents.dataaccess.DATA_ACCESS_PACK_ID

data class DataAccessLanguageConfig(
    val searchCalls: Set<String> = emptySet(),
    val searchPrefixes: Set<String> = emptySet(),
    val searchQualifiers: Set<String> = emptySet(),
    val searchTypes: Set<String> = emptySet(),
    val lookupCalls: Set<String> = emptySet(),
    val lookupQualifiers: Set<String> = emptySet(),
    val lookupTypes: Set<String> = emptySet(),
    val enumCalls: Set<String> = emptySet(),
    val enumTypes: Set<String> = emptySet(),
    val enumTypeSuffixes: Set<String> = emptySet(),
    val enumExclusions: Set<String> = emptySet(),
    val knownMaps: Set<String> = emptySet(),
    val knownMapSuffixes: Set<String> = emptySet(),
    val ambiguousCalls: Set<String> = emptySet(),
    val ambiguousPrefixes: Set<String> = emptySet(),
    val compoundMutationCalls: Set<String> = emptySet(),
    val boundaryPrefixes: Set<String> = emptySet(),
    val indexKinds: Set<String> = emptySet(),
)

data class DataAccessConfig(
    val languages: Map<String, DataAccessLanguageConfig> = defaults(),
) {
    fun forLanguage(language: String): DataAccessLanguageConfig =
        LanguageConfig(languages).forLanguage(language, "kotlin")
            ?: DataAccessLanguageConfig()

    fun withOverrides(values: Map<String, Any?>): DataAccessConfig = SCHEMA.decode(this, values, DATA_ACCESS_PACK_ID)

    companion object {
        internal val SCHEMA: ConfigSchema<DataAccessConfig> = dataAccessConfigSchema()

        fun defaults(): Map<String, DataAccessLanguageConfig> = dataAccessDefaultLanguages()

        fun from(values: Map<String, Any?>): DataAccessConfig = DataAccessConfig().withOverrides(values)
    }
}
