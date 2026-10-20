package dev.cint.api

interface ConfigurableRulePack : RulePack {
    fun configure(values: Map<String, Any?>): RulePack
}

class RulePackConfigException(
    message: String,
) : IllegalArgumentException(message)

object ConfigValues {
    fun string(
        value: Any?,
        path: String,
        errors: MutableList<String>,
    ): String? {
        if (value is String) return value
        errors += "$path: expected a string"
        return null
    }

    fun boolean(
        value: Any?,
        path: String,
        errors: MutableList<String>,
    ): Boolean? {
        if (value is Boolean) return value
        errors += "$path: expected a boolean"
        return null
    }

    fun strings(
        value: Any?,
        path: String,
        errors: MutableList<String>,
    ): Set<String>? =
        when (value) {
            is String -> {
                setOf(value)
            }

            is List<*> -> {
                if (value.all { it is String }) {
                    value.mapTo(linkedSetOf()) { it as String }
                } else {
                    errors += "$path: expected a string or list of strings"
                    null
                }
            }

            else -> {
                errors += "$path: expected a string or list of strings"
                null
            }
        }

    fun stringList(
        value: Any?,
        path: String,
        errors: MutableList<String>,
    ): List<String>? =
        when (value) {
            is String -> {
                listOf(value)
            }

            is List<*> -> {
                if (value.all { it is String }) {
                    value.map { it as String }
                } else {
                    errors += "$path: expected a string or list of strings"
                    null
                }
            }

            else -> {
                errors += "$path: expected a string or list of strings"
                null
            }
        }

    fun stringSets(
        value: Any?,
        path: String,
        errors: MutableList<String>,
    ): Map<String, Set<String>>? {
        if (value !is Map<*, *>) {
            errors += "$path: expected a map of string lists"
            return null
        }
        val result = linkedMapOf<String, Set<String>>()
        for ((rawKey, rawValue) in value) {
            if (rawKey !is String || rawKey.isBlank()) {
                errors += "$path: config keys must be non-blank strings"
                continue
            }
            val childPath = "$path.$rawKey"
            val strings = strings(rawValue, childPath, errors) ?: continue
            result[rawKey] = strings
        }
        return result
    }
}

class ConfigField<C, T> internal constructor(
    val key: String,
    private val get: (C) -> T,
    private val set: (C, T) -> C,
    private val decode: (Any?, String, MutableList<String>) -> T?,
) {
    internal fun apply(
        config: C,
        raw: Any?,
        path: String,
        errors: MutableList<String>,
    ): C {
        val decoded = decode(raw, path, errors) ?: return config
        return set(config, decoded)
    }

    companion object {
        fun <C> string(
            key: String,
            get: (C) -> String,
            set: (C, String) -> C,
        ): ConfigField<C, String> = ConfigField(key, get, set, ConfigValues::string)

        fun <C> boolean(
            key: String,
            get: (C) -> Boolean,
            set: (C, Boolean) -> C,
        ): ConfigField<C, Boolean> = ConfigField(key, get, set, ConfigValues::boolean)

        fun <C> strings(
            key: String,
            get: (C) -> Set<String>,
            set: (C, Set<String>) -> C,
        ): ConfigField<C, Set<String>> = ConfigField(key, get, set, ConfigValues::strings)

        fun <C> stringList(
            key: String,
            get: (C) -> List<String>,
            set: (C, List<String>) -> C,
        ): ConfigField<C, List<String>> = ConfigField(key, get, set, ConfigValues::stringList)

        fun <C> stringSets(
            key: String,
            get: (C) -> Map<String, Set<String>>,
            set: (C, Map<String, Set<String>>) -> C,
        ): ConfigField<C, Map<String, Set<String>>> = ConfigField(key, get, set, ConfigValues::stringSets)

        fun <C, T> custom(
            key: String,
            get: (C) -> T,
            set: (C, T) -> C,
            decode: (Any?, String, MutableList<String>) -> T?,
        ): ConfigField<C, T> = ConfigField(key, get, set, decode)
    }
}

data class ConfigDecodeResult<C>(
    val value: C,
    val errors: List<String>,
)

class ConfigSchema<C> internal constructor(
    private val fields: List<ConfigField<C, *>> = emptyList(),
    private val decoder: ((C, Map<String, Any?>, String) -> ConfigDecodeResult<C>)? = null,
) {
    private val fieldsByKey: Map<String, ConfigField<C, *>> = fields.associateBy { it.key }

    init {
        require(fieldsByKey.size == fields.size) { "duplicate config field key" }
        require(decoder == null || fields.isEmpty()) { "custom config schema cannot declare fields" }
    }

    fun decode(
        defaults: C,
        values: Map<String, Any?>,
        path: String = "",
    ): C {
        val result = decodeResult(defaults, values, path)
        if (result.errors.isNotEmpty()) throw RulePackConfigException(result.errors.joinToString("; "))
        return result.value
    }

    fun decodeResult(
        defaults: C,
        values: Map<String, Any?>,
        path: String = "",
    ): ConfigDecodeResult<C> {
        decoder?.let { return it(defaults, values, path) }
        val errors = mutableListOf<String>()
        var result = defaults
        for ((key, raw) in values) {
            val fieldPath = childPath(path, key)
            val field = fieldsByKey[key]
            if (field == null) {
                errors += "$fieldPath: unknown key"
            } else {
                result = field.apply(result, raw, fieldPath, errors)
            }
        }
        return ConfigDecodeResult(result, errors)
    }

    companion object {
        fun <C> of(vararg fields: ConfigField<C, *>): ConfigSchema<C> = ConfigSchema(fields.toList())

        internal fun <C> custom(decoder: (C, Map<String, Any?>, String) -> ConfigDecodeResult<C>): ConfigSchema<C> =
            ConfigSchema(decoder = decoder)
    }
}

data class LanguageConfig<C>(
    val values: Map<String, C>,
) {
    val languages: Set<String> get() = values.keys

    fun forLanguage(
        language: String,
        fallback: String? = null,
    ): C? =
        values.entries.firstOrNull { it.key.equals(language, ignoreCase = true) }?.value
            ?: fallback?.let { values[it] }

    fun withOverrides(
        overrides: Map<String, Any?>,
        path: String,
        schema: ConfigSchema<C>,
    ): LanguageConfig<C> {
        val result = withOverridesResult(overrides, path, schema)
        if (result.errors.isNotEmpty()) throw RulePackConfigException(result.errors.joinToString("; "))
        return result.value
    }

    internal fun withOverridesResult(
        overrides: Map<String, Any?>,
        path: String,
        schema: ConfigSchema<C>,
    ): ConfigDecodeResult<LanguageConfig<C>> {
        val errors = mutableListOf<String>()
        val updated = values.toMutableMap()
        for ((language, raw) in overrides) {
            val key = values.keys.firstOrNull { it.equals(language, ignoreCase = true) }
            if (key == null) {
                errors += "$path.$language: unknown language"
                continue
            }
            if (raw !is Map<*, *>) {
                errors += "$path.$language: expected a map"
                continue
            }
            val stringKeyed = linkedMapOf<String, Any?>()
            for ((rawKey, rawValue) in raw) {
                if (rawKey !is String) {
                    errors += "$path.$language.$rawKey: config keys must be strings"
                    continue
                }
                stringKeyed[rawKey] = rawValue
            }
            val result = schema.decodeResult(updated.getValue(key), stringKeyed, "$path.$language")
            errors += result.errors
            updated[key] = result.value
        }
        return ConfigDecodeResult(LanguageConfig(updated), errors)
    }

    companion object {
        fun <C> of(vararg entries: Pair<String, C>): LanguageConfig<C> = LanguageConfig(linkedMapOf(*entries))

        fun <C, P> schema(
            languageSchema: ConfigSchema<C>,
            get: (P) -> Map<String, C>,
            set: (P, Map<String, C>) -> P,
        ): ConfigSchema<P> =
            ConfigSchema.custom { config, values, path ->
                val result =
                    LanguageConfig(get(config))
                        .withOverridesResult(values, path, languageSchema)
                ConfigDecodeResult(set(config, result.value.values), result.errors)
            }
    }
}

private fun childPath(
    path: String,
    key: String,
): String = if (path.isEmpty()) key else "$path.$key"

data class RulePackConfigIssue(
    val packId: String,
    val message: String,
)

fun interface RulePackConfigResolver {
    fun resolve(
        config: Map<String, Map<String, Any?>>,
        discoveredPackIds: Set<String>,
        issues: MutableList<RulePackConfigIssue>,
    ): Map<String, Map<String, Any?>>

    companion object {
        val NONE: RulePackConfigResolver = RulePackConfigResolver { config, _, _ -> config }
    }
}
