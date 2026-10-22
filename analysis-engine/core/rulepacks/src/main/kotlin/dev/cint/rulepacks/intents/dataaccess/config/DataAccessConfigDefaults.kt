package dev.cint.rulepacks.intents.dataaccess.config

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

internal fun dataAccessDefaultLanguages(): Map<String, DataAccessLanguageConfig> = LANGUAGE_DEFAULTS
