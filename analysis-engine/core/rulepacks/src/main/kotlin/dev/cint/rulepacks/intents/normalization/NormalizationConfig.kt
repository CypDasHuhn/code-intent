package dev.cint.rulepacks.intents.normalization

import dev.cint.api.ConfigField
import dev.cint.api.ConfigSchema
import dev.cint.api.LanguageConfig

data class NormalizationLanguageConfig(
    val calls: Set<String> = emptySet(),
    val instanceCalls: Set<String> = emptySet(),
    val prefixes: List<String> = emptyList(),
    val qualifiers: Set<String> = emptySet(),
    val types: Set<String> = emptySet(),
    val candidateCalls: Set<String> = emptySet(),
    val candidatePrefixes: List<String> = emptyList(),
    val chainCalls: Set<String> = emptySet(),
)

data class NormalizationConfig(
    val languages: Map<String, NormalizationLanguageConfig> = defaults(),
) {
    fun forLanguage(language: String): NormalizationLanguageConfig =
        LanguageConfig(languages).forLanguage(language, "kotlin")
            ?: NormalizationLanguageConfig()

    fun withOverrides(values: Map<String, Any?>): NormalizationConfig = SCHEMA.decode(this, values, NORMALIZATION_PACK_ID)

    companion object {
        private val LANGUAGE_SCHEMA =
            ConfigSchema.of(
                ConfigField.strings<NormalizationLanguageConfig>(
                    "calls",
                    { it.calls },
                    { config, value -> config.copy(calls = value) },
                ),
                ConfigField.strings<NormalizationLanguageConfig>(
                    "instanceCalls",
                    { it.instanceCalls },
                    { config, value -> config.copy(instanceCalls = value) },
                ),
                ConfigField.stringList<NormalizationLanguageConfig>(
                    "prefixes",
                    { it.prefixes },
                    { config, value -> config.copy(prefixes = value) },
                ),
                ConfigField.strings<NormalizationLanguageConfig>(
                    "qualifiers",
                    { it.qualifiers },
                    { config, value -> config.copy(qualifiers = value) },
                ),
                ConfigField.strings<NormalizationLanguageConfig>(
                    "types",
                    { it.types },
                    { config, value -> config.copy(types = value) },
                ),
                ConfigField.strings<NormalizationLanguageConfig>(
                    "candidateCalls",
                    { it.candidateCalls },
                    { config, value -> config.copy(candidateCalls = value) },
                ),
                ConfigField.stringList<NormalizationLanguageConfig>(
                    "candidatePrefixes",
                    { it.candidatePrefixes },
                    { config, value -> config.copy(candidatePrefixes = value) },
                ),
                ConfigField.strings<NormalizationLanguageConfig>(
                    "chainCalls",
                    { it.chainCalls },
                    { config, value -> config.copy(chainCalls = value) },
                ),
            )

        internal val SCHEMA =
            LanguageConfig.schema<NormalizationLanguageConfig, NormalizationConfig>(
                languageSchema = LANGUAGE_SCHEMA,
                get = { it.languages },
                set = { config, languages -> config.copy(languages = languages) },
            )

        fun from(values: Map<String, Any?>): NormalizationConfig = NormalizationConfig().withOverrides(values)

        private val KOTLIN =
            NormalizationLanguageConfig(
                calls =
                    setOf(
                        "trim",
                        "trimStart",
                        "trimEnd",
                        "lowercase",
                        "uppercase",
                        "toLowerCase",
                        "toUpperCase",
                        "toInt",
                        "toLong",
                        "toShort",
                        "toByte",
                        "toDouble",
                        "toFloat",
                        "toBigDecimal",
                        "toBigInteger",
                        "toPath",
                        "toUri",
                        "parseInt",
                        "parseLong",
                        "parseDouble",
                        "parseFloat",
                        "parseBoolean",
                        "fromString",
                        "normalize",
                        "canonicalize",
                        "decodeFromString",
                    ),
                instanceCalls =
                    setOf(
                        "trim",
                        "trimStart",
                        "trimEnd",
                        "lowercase",
                        "uppercase",
                        "toLowerCase",
                        "toUpperCase",
                        "toInt",
                        "toLong",
                        "toShort",
                        "toByte",
                        "toDouble",
                        "toFloat",
                        "toBigDecimal",
                        "toBigInteger",
                        "toPath",
                        "toUri",
                    ),
                prefixes = listOf("parse", "normalize", "canonicalize"),
                qualifiers =
                    setOf(
                        "String",
                        "Json",
                        "Integer",
                        "Long",
                        "Short",
                        "Byte",
                        "Double",
                        "Float",
                        "Boolean",
                        "BigDecimal",
                        "BigInteger",
                        "LocalDate",
                        "LocalDateTime",
                        "LocalTime",
                        "Instant",
                        "OffsetDateTime",
                        "ZonedDateTime",
                        "Duration",
                        "Period",
                        "UUID",
                        "Path",
                        "Paths",
                        "Files",
                        "DateTimeFormatter",
                        "Charsets",
                    ),
                types =
                    setOf(
                        "java.time.LocalDate",
                        "java.time.LocalDateTime",
                        "java.time.LocalTime",
                        "java.time.Instant",
                        "java.time.OffsetDateTime",
                        "java.time.ZonedDateTime",
                        "java.util.UUID",
                        "java.nio.file.Path",
                        "java.math.BigDecimal",
                        "kotlinx.serialization.json.Json",
                    ),
                candidateCalls = setOf("format", "stringify"),
                candidatePrefixes = listOf("format", "pretty", "display"),
                chainCalls =
                    setOf(
                        "replace",
                        "replaceAll",
                        "replaceFirst",
                        "removePrefix",
                        "removeSuffix",
                        "padStart",
                        "padEnd",
                    ),
            )

        private val JAVASCRIPT =
            NormalizationLanguageConfig(
                calls =
                    setOf(
                        "trim",
                        "trimStart",
                        "trimEnd",
                        "toLowerCase",
                        "toUpperCase",
                        "toLocaleLowerCase",
                        "toLocaleUpperCase",
                        "normalize",
                        "parseInt",
                        "parseFloat",
                        "String",
                        "Number",
                        "Boolean",
                        "encodeURI",
                        "encodeURIComponent",
                        "decodeURI",
                        "decodeURIComponent",
                    ),
                instanceCalls =
                    setOf(
                        "trim",
                        "trimStart",
                        "trimEnd",
                        "toLowerCase",
                        "toUpperCase",
                        "toLocaleLowerCase",
                        "toLocaleUpperCase",
                    ),
                prefixes = listOf("parse", "normalize", "canonicalize"),
                qualifiers = setOf("JSON", "Number", "Date", "Intl", "String", "Buffer", "URL", "URLSearchParams", "path"),
                types = setOf("Date", "URL", "URLSearchParams"),
                candidateCalls = setOf("format", "formatDate", "formatDateTime", "formatTime", "stringify", "toISOString", "toJSON"),
                candidatePrefixes = listOf("format", "pretty", "display"),
                chainCalls = setOf("replace", "replaceAll", "padStart", "padEnd"),
            )

        private val CSHARP =
            NormalizationLanguageConfig(
                calls =
                    setOf(
                        "Trim",
                        "TrimStart",
                        "TrimEnd",
                        "ToLower",
                        "ToUpper",
                        "ToLowerInvariant",
                        "ToUpperInvariant",
                        "Normalize",
                        "Canonicalize",
                        "Parse",
                        "ParseExact",
                        "GetFullPath",
                        "GetRelativePath",
                        "EscapeDataString",
                        "UnescapeDataString",
                        "ToInt16",
                        "ToInt32",
                        "ToInt64",
                        "ToDouble",
                        "ToDecimal",
                        "ToBoolean",
                        "ToDateTime",
                    ),
                instanceCalls =
                    setOf(
                        "Trim",
                        "TrimStart",
                        "TrimEnd",
                        "ToLower",
                        "ToUpper",
                        "ToLowerInvariant",
                        "ToUpperInvariant",
                    ),
                prefixes = listOf("Parse", "Normalize", "Canonicalize"),
                qualifiers =
                    setOf(
                        "Convert",
                        "Path",
                        "Uri",
                        "DateTime",
                        "DateTimeOffset",
                        "DateOnly",
                        "TimeOnly",
                        "Guid",
                        "Enum",
                        "JsonSerializer",
                        "int",
                        "long",
                        "short",
                        "double",
                        "decimal",
                        "float",
                        "bool",
                        "Int16",
                        "Int32",
                        "Int64",
                        "Double",
                        "Decimal",
                        "Boolean",
                    ),
                types =
                    setOf(
                        "System.DateTime",
                        "System.DateTimeOffset",
                        "System.DateOnly",
                        "System.TimeOnly",
                        "System.Guid",
                        "System.Uri",
                        "System.IO.Path",
                        "System.Decimal",
                    ),
                candidateCalls = setOf("Format", "FormatDate", "FormatDateTime", "FormatTime", "TryParse", "TryParseExact"),
                candidatePrefixes = listOf("Format", "Pretty", "Display", "TryParse"),
                chainCalls = setOf("Replace", "PadLeft", "PadRight"),
            )

        private val LANGUAGE_DEFAULTS: Map<String, NormalizationLanguageConfig> =
            linkedMapOf(
                "kotlin" to KOTLIN,
                "javascript" to JAVASCRIPT,
                "typescript" to JAVASCRIPT,
                "csharp" to CSHARP,
            )

        fun defaults(): Map<String, NormalizationLanguageConfig> = LANGUAGE_DEFAULTS
    }
}
