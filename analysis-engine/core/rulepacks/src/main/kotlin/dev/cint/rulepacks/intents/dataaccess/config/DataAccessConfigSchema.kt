package dev.cint.rulepacks.intents.dataaccess

import dev.cint.api.ConfigField
import dev.cint.api.ConfigSchema
import dev.cint.api.LanguageConfig

private val LANGUAGE_SCHEMA =
    ConfigSchema.of(
        ConfigField.strings<DataAccessLanguageConfig>(
            "searchCalls",
            { it.searchCalls },
            { config, value -> config.copy(searchCalls = value) },
        ),
        ConfigField.strings<DataAccessLanguageConfig>(
            "searchPrefixes",
            { it.searchPrefixes },
            { config, value -> config.copy(searchPrefixes = value) },
        ),
        ConfigField.strings<DataAccessLanguageConfig>(
            "searchQualifiers",
            { it.searchQualifiers },
            { config, value -> config.copy(searchQualifiers = value) },
        ),
        ConfigField.strings<DataAccessLanguageConfig>(
            "searchTypes",
            { it.searchTypes },
            { config, value -> config.copy(searchTypes = value) },
        ),
        ConfigField.strings<DataAccessLanguageConfig>(
            "lookupCalls",
            { it.lookupCalls },
            { config, value -> config.copy(lookupCalls = value) },
        ),
        ConfigField.strings<DataAccessLanguageConfig>(
            "lookupQualifiers",
            { it.lookupQualifiers },
            { config, value -> config.copy(lookupQualifiers = value) },
        ),
        ConfigField.strings<DataAccessLanguageConfig>(
            "lookupTypes",
            { it.lookupTypes },
            { config, value -> config.copy(lookupTypes = value) },
        ),
        ConfigField.strings<DataAccessLanguageConfig>(
            "enumCalls",
            { it.enumCalls },
            { config, value -> config.copy(enumCalls = value) },
        ),
        ConfigField.strings<DataAccessLanguageConfig>(
            "enumTypes",
            { it.enumTypes },
            { config, value -> config.copy(enumTypes = value) },
        ),
        ConfigField.strings<DataAccessLanguageConfig>(
            "enumTypeSuffixes",
            { it.enumTypeSuffixes },
            { config, value -> config.copy(enumTypeSuffixes = value) },
        ),
        ConfigField.strings<DataAccessLanguageConfig>(
            "enumExclusions",
            { it.enumExclusions },
            { config, value -> config.copy(enumExclusions = value) },
        ),
        ConfigField.strings<DataAccessLanguageConfig>(
            "knownMaps",
            { it.knownMaps },
            { config, value -> config.copy(knownMaps = value) },
        ),
        ConfigField.strings<DataAccessLanguageConfig>(
            "knownMapSuffixes",
            { it.knownMapSuffixes },
            { config, value -> config.copy(knownMapSuffixes = value) },
        ),
        ConfigField.strings<DataAccessLanguageConfig>(
            "ambiguousCalls",
            { it.ambiguousCalls },
            { config, value -> config.copy(ambiguousCalls = value) },
        ),
        ConfigField.strings<DataAccessLanguageConfig>(
            "ambiguousPrefixes",
            { it.ambiguousPrefixes },
            { config, value -> config.copy(ambiguousPrefixes = value) },
        ),
        ConfigField.strings<DataAccessLanguageConfig>(
            "compoundMutationCalls",
            { it.compoundMutationCalls },
            { config, value -> config.copy(compoundMutationCalls = value) },
        ),
        ConfigField.strings<DataAccessLanguageConfig>(
            "boundaryPrefixes",
            { it.boundaryPrefixes },
            { config, value -> config.copy(boundaryPrefixes = value) },
        ),
        ConfigField.strings<DataAccessLanguageConfig>(
            "indexKinds",
            { it.indexKinds },
            { config, value -> config.copy(indexKinds = value) },
        ),
    )

internal fun dataAccessConfigSchema(): ConfigSchema<DataAccessConfig> =
    LanguageConfig.schema<DataAccessLanguageConfig, DataAccessConfig>(
        languageSchema = LANGUAGE_SCHEMA,
        get = { it.languages },
        set = { config, languages -> config.copy(languages = languages) },
    )
