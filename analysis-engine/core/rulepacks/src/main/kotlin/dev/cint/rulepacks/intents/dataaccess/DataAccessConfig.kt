package dev.cint.rulepacks.intents.dataaccess

import dev.cint.api.ConfigField
import dev.cint.api.ConfigSchema
import dev.cint.api.LanguageConfig

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
        private val SEARCH_CALLS =
            setOf(
                "findAll",
                "findOne",
                "findFirst",
                "findAny",
                "search",
                "query",
                "filter",
                "where",
                "select",
                "from",
                "join",
                "orderBy",
                "groupBy",
                "createQuery",
                "createNativeQuery",
                "executeQuery",
                "findMany",
                "findUnique",
                "findUniqueOrThrow",
                "findFirstOrThrow",
                "getResultList",
                "getSingleResult",
                "firstOrDefault",
                "singleOrDefault",
                "toListAsync",
                "findAsync",
                "scan",
            )
        private val SEARCH_PREFIXES = setOf("findBy", "search", "query", "findAllBy")
        private val SEARCH_QUALIFIERS =
            setOf(
                "repository",
                "repo",
                "dao",
                "query",
                "builder",
                "client",
                "api",
                "db",
                "database",
                "store",
                "mapper",
                "session",
                "entityManager",
                "jdbc",
                "collection",
                "service",
                "prisma",
                "context",
            )
        private val SEARCH_TYPES =
            setOf(
                "Repository",
                "Dao",
                "Query",
                "QueryBuilder",
                "JdbcTemplate",
                "EntityManager",
                "SearchClient",
            )
        private val LOOKUP_CALLS =
            setOf(
                "getValue",
                "getOrDefault",
                "getOrElse",
                "lookup",
                "forKey",
                "getByKey",
            )
        private val LOOKUP_QUALIFIERS =
            setOf(
                "map",
                "dictionary",
                "dict",
                "cache",
                "config",
                "configuration",
                "enum",
                "enumMap",
                "table",
                "registry",
                "index",
            )
        private val LOOKUP_TYPES =
            setOf(
                "Map",
                "HashMap",
                "LinkedHashMap",
                "TreeMap",
                "Dictionary",
                "EnumMap",
                "Enum",
                "Configuration",
                "Config",
                "Properties",
            )
        private val ENUM_CALLS = setOf("valueOf", "fromCode", "fromValue", "fromName", "fromString", "fromKey")
        private val ENUM_TYPES = emptySet<String>()
        private val ENUM_TYPE_SUFFIXES = setOf("Enum", "Status", "State", "Kind", "Mode", "Code", "Type")
        private val ENUM_EXCLUSIONS =
            setOf(
                "String",
                "Integer",
                "Long",
                "Boolean",
                "Double",
                "Float",
                "Byte",
                "Short",
                "Char",
                "Character",
                "BigDecimal",
                "BigInteger",
                "UUID",
                "Object",
            )
        private val KNOWN_MAPS =
            setOf(
                "statusById",
                "statusByCode",
                "byId",
                "byCode",
                "byName",
                "codeMap",
                "idMap",
                "lookupTable",
                "translationTable",
                "configMap",
                "constantMap",
                "enumMap",
            )
        private val KNOWN_MAP_SUFFIXES = setOf("ById", "ByCode", "ByName", "Map", "Lookup", "Table")
        private val AMBIGUOUS_CALLS = setOf("get", "find", "fetch", "load", "read", "resolve")
        private val AMBIGUOUS_PREFIXES = setOf("get", "find", "fetch", "load", "read", "resolve")
        private val COMPOUND_MUTATION_CALLS =
            setOf(
                "findOneAndUpdate",
                "findOneAndDelete",
                "findOneAndReplace",
                "findOneAndIncrement",
                "findOneAndDecrement",
                "findOneAndModify",
                "findAndModify",
                "getAndUpdate",
                "getAndIncrement",
                "getAndDecrement",
            )
        private val BOUNDARY_PREFIXES = setOf("findBy", "getBy", "loadBy")

        private fun common(indexKinds: Set<String>): DataAccessLanguageConfig =
            DataAccessLanguageConfig(
                searchCalls = SEARCH_CALLS,
                searchPrefixes = SEARCH_PREFIXES,
                searchQualifiers = SEARCH_QUALIFIERS,
                searchTypes = SEARCH_TYPES,
                lookupCalls = LOOKUP_CALLS,
                lookupQualifiers = LOOKUP_QUALIFIERS,
                lookupTypes = LOOKUP_TYPES,
                enumCalls = ENUM_CALLS,
                enumTypes = ENUM_TYPES,
                enumTypeSuffixes = ENUM_TYPE_SUFFIXES,
                enumExclusions = ENUM_EXCLUSIONS,
                knownMaps = KNOWN_MAPS,
                knownMapSuffixes = KNOWN_MAP_SUFFIXES,
                ambiguousCalls = AMBIGUOUS_CALLS,
                ambiguousPrefixes = AMBIGUOUS_PREFIXES,
                compoundMutationCalls = COMPOUND_MUTATION_CALLS,
                boundaryPrefixes = BOUNDARY_PREFIXES,
                indexKinds = indexKinds,
            )

        private val LANGUAGE_DEFAULTS: Map<String, DataAccessLanguageConfig> =
            linkedMapOf(
                "kotlin" to common(setOf("indexing_expression", "indexing_suffix")),
                "javascript" to common(setOf("subscript_expression")),
                "typescript" to common(setOf("subscript_expression")),
                "csharp" to common(setOf("element_access_expression")),
            )

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

        internal val SCHEMA =
            LanguageConfig.schema<DataAccessLanguageConfig, DataAccessConfig>(
                languageSchema = LANGUAGE_SCHEMA,
                get = { it.languages },
                set = { config, languages -> config.copy(languages = languages) },
            )

        fun defaults(): Map<String, DataAccessLanguageConfig> = LANGUAGE_DEFAULTS

        fun from(values: Map<String, Any?>): DataAccessConfig = DataAccessConfig().withOverrides(values)
    }
}
