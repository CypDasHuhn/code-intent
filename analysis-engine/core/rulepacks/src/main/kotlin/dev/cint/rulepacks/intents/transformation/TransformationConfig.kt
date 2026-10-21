package dev.cint.rulepacks.intents.transformation

import dev.cint.api.ConfigField
import dev.cint.api.ConfigSchema
import dev.cint.api.LanguageConfig

private val DEFAULT_QUALIFIERS =
    setOf(
        "list",
        "lists",
        "items",
        "values",
        "collection",
        "collections",
        "stream",
        "streams",
        "sequence",
        "sequences",
        "array",
        "arrays",
        "set",
        "sets",
        "map",
        "maps",
        "entries",
        "elements",
        "nodes",
        "results",
        "rows",
        "records",
        "children",
    )

private val DEFAULT_TYPES =
    setOf(
        "kotlin.collections.Iterable",
        "kotlin.collections.Collection",
        "kotlin.collections.List",
        "kotlin.collections.Set",
        "kotlin.collections.Map",
        "kotlin.collections.MutableList",
        "kotlin.collections.MutableSet",
        "kotlin.collections.MutableMap",
        "kotlin.sequences.Sequence",
        "java.lang.Iterable",
        "java.util.Collection",
        "java.util.List",
        "java.util.Set",
        "java.util.Map",
        "java.util.stream.Stream",
        "System.Collections.IEnumerable",
        "System.Collections.Generic.IEnumerable",
        "System.Collections.Generic.List",
        "System.Collections.Generic.IList",
        "System.Collections.Generic.ICollection",
        "System.Collections.Generic.IReadOnlyList",
        "System.Collections.Generic.IReadOnlyCollection",
        "System.Array",
    )

data class TransformationLanguageConfig(
    val calls: Set<String> = emptySet(),
    val candidateCalls: Set<String> = emptySet(),
    val candidatePrefixes: List<String> = emptyList(),
    val qualifiers: Set<String> = DEFAULT_QUALIFIERS,
    val types: Set<String> = DEFAULT_TYPES,
)

data class TransformationConfig(
    val languages: Map<String, TransformationLanguageConfig> = defaults(),
) {
    fun forLanguage(language: String): TransformationLanguageConfig =
        LanguageConfig(languages).forLanguage(language, "kotlin")
            ?: TransformationLanguageConfig()

    fun withOverrides(values: Map<String, Any?>): TransformationConfig = SCHEMA.decode(this, values, DATA_TRANSFORMATION_PACK_ID)

    companion object {
        private val LANGUAGE_SCHEMA =
            ConfigSchema.of(
                ConfigField.strings<TransformationLanguageConfig>(
                    "calls",
                    { it.calls },
                    { config, value -> config.copy(calls = value) },
                ),
                ConfigField.strings<TransformationLanguageConfig>(
                    "candidateCalls",
                    { it.candidateCalls },
                    { config, value -> config.copy(candidateCalls = value) },
                ),
                ConfigField.stringList<TransformationLanguageConfig>(
                    "candidatePrefixes",
                    { it.candidatePrefixes },
                    { config, value -> config.copy(candidatePrefixes = value) },
                ),
                ConfigField.strings<TransformationLanguageConfig>(
                    "qualifiers",
                    { it.qualifiers },
                    { config, value -> config.copy(qualifiers = value) },
                ),
                ConfigField.strings<TransformationLanguageConfig>(
                    "types",
                    { it.types },
                    { config, value -> config.copy(types = value) },
                ),
            )

        internal val SCHEMA =
            LanguageConfig.schema<TransformationLanguageConfig, TransformationConfig>(
                languageSchema = LANGUAGE_SCHEMA,
                get = { it.languages },
                set = { config, languages -> config.copy(languages = languages) },
            )

        fun from(values: Map<String, Any?>): TransformationConfig = TransformationConfig().withOverrides(values)

        private val KOTLIN =
            TransformationLanguageConfig(
                calls =
                    setOf(
                        "map",
                        "mapNotNull",
                        "mapValues",
                        "mapKeys",
                        "flatMap",
                        "filter",
                        "filterNot",
                        "filterIsInstance",
                        "filterNotNull",
                        "fold",
                        "foldIndexed",
                        "reduce",
                        "reduceIndexed",
                        "collect",
                        "collectLatest",
                        "distinct",
                        "distinctBy",
                        "sorted",
                        "sortedBy",
                        "sortedWith",
                        "sortedDescending",
                        "sortedByDescending",
                        "groupBy",
                        "groupingBy",
                        "associate",
                        "associateBy",
                        "associateWith",
                        "partition",
                        "take",
                        "takeWhile",
                        "takeLast",
                        "drop",
                        "dropWhile",
                        "dropLast",
                        "chunked",
                        "windowed",
                        "zip",
                        "zipWithNext",
                        "flatten",
                        "all",
                        "any",
                        "none",
                    ),
                candidateCalls = setOf("transform", "process", "reshape", "convert", "apply", "adjust"),
                candidatePrefixes = listOf("transform", "process", "reshape", "convert"),
            )

        private val JAVASCRIPT =
            TransformationLanguageConfig(
                calls =
                    setOf(
                        "map",
                        "flatMap",
                        "filter",
                        "reduce",
                        "reduceRight",
                        "sort",
                        "toSorted",
                        "reverse",
                        "toReversed",
                        "splice",
                        "toSpliced",
                        "slice",
                        "concat",
                        "flat",
                        "groupBy",
                        "partition",
                        "take",
                        "drop",
                        "every",
                        "some",
                    ),
                candidateCalls = setOf("transform", "process", "reshape", "convert", "apply", "adjust"),
                candidatePrefixes = listOf("transform", "process", "reshape", "convert"),
            )

        private val CSHARP =
            TransformationLanguageConfig(
                calls =
                    setOf(
                        "Select",
                        "SelectMany",
                        "Where",
                        "Aggregate",
                        "GroupBy",
                        "Distinct",
                        "DistinctBy",
                        "OrderBy",
                        "OrderByDescending",
                        "ThenBy",
                        "ThenByDescending",
                        "Take",
                        "TakeWhile",
                        "TakeLast",
                        "Skip",
                        "SkipWhile",
                        "SkipLast",
                        "Chunk",
                        "Zip",
                        "Concat",
                        "Cast",
                        "OfType",
                        "AsEnumerable",
                        "ToList",
                        "ToArray",
                        "ToDictionary",
                        "ToLookup",
                        "Partition",
                        "All",
                        "Any",
                        "TrueForAll",
                    ),
                candidateCalls = setOf("Transform", "Process", "Reshape", "Convert", "Apply", "Adjust"),
                candidatePrefixes = listOf("Transform", "Process", "Reshape", "Convert"),
            )

        private val LANGUAGE_DEFAULTS: Map<String, TransformationLanguageConfig> =
            linkedMapOf(
                "kotlin" to KOTLIN,
                "javascript" to JAVASCRIPT,
                "typescript" to JAVASCRIPT,
                "csharp" to CSHARP,
            )

        fun defaults(): Map<String, TransformationLanguageConfig> = LANGUAGE_DEFAULTS
    }
}
