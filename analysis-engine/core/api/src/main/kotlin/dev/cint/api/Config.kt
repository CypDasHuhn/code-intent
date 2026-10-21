package dev.cint.api

/**
 * Additive v4 descriptor surface. The default schema is null, so existing packs
 * and custom [ConfigurableRulePack] implementations remain source-compatible
 * without a [CINT_API_VERSION] bump.
 */
interface ConfigSchemaProvider {
    fun configSchema(): ConfigSchema<*>? = null
}

interface ConfigurableRulePack :
    RulePack,
    ConfigSchemaProvider {
    fun configure(values: Map<String, Any?>): RulePack

    /**
     * Opt-in additive configuration path used for the user overlay. Packs that
     * accept the overlay must override this (normally via
     * [ConfigSchema.decodeAdditive] / `LanguageConfig` additive decoding) so
     * project and default values are unioned instead of replaced. The default
     * rejects additive config rather than silently applying replace semantics;
     * the loader keeps the project-configured pack and reports the unsupported
     * overlay.
     */
    fun configureAdditive(values: Map<String, Any?>): RulePack =
        throw UnsupportedOperationException("rulepack '$id' does not support additive config")
}

enum class ConfigFieldKind {
    STRING,
    BOOLEAN,
    STRINGS,
    STRING_LIST,
    STRING_SETS,
    CUSTOM,
}

enum class ConfigFieldScope {
    PACK,
    LANGUAGE,
}

data class ConfigFieldDescriptor(
    val key: String,
    val kind: ConfigFieldKind,
    val scope: ConfigFieldScope,
    val languages: List<String> = emptyList(),
)

data class ConfigSchemaDescriptor(
    val fields: List<ConfigFieldDescriptor> = emptyList(),
    val language: ConfigSchemaDescriptor? = null,
)

data class RulePackConfigRuleDescriptor(
    val id: String,
    val owns: Intent,
    val reads: Set<Intent>,
)

data class RulePackConfigDescriptor(
    val packId: String,
    val rules: List<RulePackConfigRuleDescriptor>,
    val languages: Set<String>,
    val fields: List<ConfigFieldDescriptor>,
)

fun RulePack.configDescriptor(): RulePackConfigDescriptor {
    val schema = (this as? ConfigSchemaProvider)?.configSchema()?.describe()
    val fields = mutableListOf<ConfigFieldDescriptor>()
    schema?.let {
        fields += it.fields
        val languageFields = it.language?.fields.orEmpty()
        if (languageFields.isNotEmpty()) {
            val supportedLanguages = languages.toList().sorted()
            for (field in languageFields) {
                fields += field.copy(languages = supportedLanguages)
            }
        }
    }
    return RulePackConfigDescriptor(
        packId = id,
        rules = rules().map { RulePackConfigRuleDescriptor(it.id, it.owns, it.reads) },
        languages = languages,
        fields = fields,
    )
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
    val kind: ConfigFieldKind,
    private val get: (C) -> T,
    private val set: (C, T) -> C,
    private val decode: (Any?, String, MutableList<String>) -> T?,
    private val additive: ((C, T) -> C)? = null,
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

    internal fun applyAdditive(
        config: C,
        raw: Any?,
        path: String,
        errors: MutableList<String>,
    ): C {
        val decoded = decode(raw, path, errors) ?: return config
        return additive?.invoke(config, decoded) ?: set(config, decoded)
    }

    companion object {
        fun <C> string(
            key: String,
            get: (C) -> String,
            set: (C, String) -> C,
        ): ConfigField<C, String> = ConfigField(key, ConfigFieldKind.STRING, get, set, ConfigValues::string)

        fun <C> boolean(
            key: String,
            get: (C) -> Boolean,
            set: (C, Boolean) -> C,
        ): ConfigField<C, Boolean> = ConfigField(key, ConfigFieldKind.BOOLEAN, get, set, ConfigValues::boolean)

        fun <C> strings(
            key: String,
            get: (C) -> Set<String>,
            set: (C, Set<String>) -> C,
        ): ConfigField<C, Set<String>> =
            ConfigField(
                key,
                ConfigFieldKind.STRINGS,
                get,
                set,
                ConfigValues::strings,
                additive = { config, value -> set(config, get(config) + value) },
            )

        fun <C> stringList(
            key: String,
            get: (C) -> List<String>,
            set: (C, List<String>) -> C,
        ): ConfigField<C, List<String>> =
            ConfigField(
                key,
                ConfigFieldKind.STRING_LIST,
                get,
                set,
                ConfigValues::stringList,
                additive = { config, value -> set(config, (get(config) + value).distinct()) },
            )

        fun <C> stringSets(
            key: String,
            get: (C) -> Map<String, Set<String>>,
            set: (C, Map<String, Set<String>>) -> C,
        ): ConfigField<C, Map<String, Set<String>>> =
            ConfigField(
                key,
                ConfigFieldKind.STRING_SETS,
                get,
                set,
                ConfigValues::stringSets,
                additive = { config, value ->
                    val merged = linkedMapOf<String, Set<String>>()
                    for ((childKey, childValue) in get(config)) merged[childKey] = childValue
                    for ((childKey, childValue) in value) {
                        merged[childKey] = merged[childKey].orEmpty() + childValue
                    }
                    set(config, merged)
                },
            )

        fun <C, T> custom(
            key: String,
            get: (C) -> T,
            set: (C, T) -> C,
            decode: (Any?, String, MutableList<String>) -> T?,
        ): ConfigField<C, T> = ConfigField(key, ConfigFieldKind.CUSTOM, get, set, decode)
    }
}

data class ConfigDecodeResult<C>(
    val value: C,
    val errors: List<String>,
)

class ConfigSchema<C> internal constructor(
    private val fields: List<ConfigField<C, *>> = emptyList(),
    private val decoder: ((C, Map<String, Any?>, String) -> ConfigDecodeResult<C>)? = null,
    private val additiveDecoder: ((C, Map<String, Any?>, String) -> ConfigDecodeResult<C>)? = null,
    private val languageSchema: ConfigSchema<*>? = null,
) {
    private val fieldsByKey: Map<String, ConfigField<C, *>> = fields.associateBy { it.key }

    init {
        require(fieldsByKey.size == fields.size) { "duplicate config field key" }
        require(decoder == null || fields.isEmpty()) { "custom config schema cannot declare fields" }
    }

    fun describe(): ConfigSchemaDescriptor = describe(ConfigFieldScope.PACK)

    private fun describe(scope: ConfigFieldScope): ConfigSchemaDescriptor =
        ConfigSchemaDescriptor(
            fields = fields.map { ConfigFieldDescriptor(it.key, it.kind, scope) },
            language = languageSchema?.describe(ConfigFieldScope.LANGUAGE),
        )

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

    fun decodeAdditive(
        current: C,
        values: Map<String, Any?>,
        path: String = "",
    ): C {
        val result = decodeAdditiveResult(current, values, path)
        if (result.errors.isNotEmpty()) throw RulePackConfigException(result.errors.joinToString("; "))
        return result.value
    }

    fun decodeAdditiveResult(
        current: C,
        values: Map<String, Any?>,
        path: String = "",
    ): ConfigDecodeResult<C> {
        (additiveDecoder ?: decoder)?.let { return it(current, values, path) }
        val errors = mutableListOf<String>()
        var result = current
        for ((key, raw) in values) {
            val fieldPath = childPath(path, key)
            val field = fieldsByKey[key]
            if (field == null) {
                errors += "$fieldPath: unknown key"
            } else {
                result = field.applyAdditive(result, raw, fieldPath, errors)
            }
        }
        return ConfigDecodeResult(result, errors)
    }

    companion object {
        fun <C> of(vararg fields: ConfigField<C, *>): ConfigSchema<C> = ConfigSchema(fields.toList())

        internal fun <C> custom(
            decoder: (C, Map<String, Any?>, String) -> ConfigDecodeResult<C>,
            additiveDecoder: ((C, Map<String, Any?>, String) -> ConfigDecodeResult<C>)? = null,
            languageSchema: ConfigSchema<*>? = null,
        ): ConfigSchema<C> =
            ConfigSchema(
                decoder = decoder,
                additiveDecoder = additiveDecoder,
                languageSchema = languageSchema,
            )
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
    ): ConfigDecodeResult<LanguageConfig<C>> =
        withOverridesResult(overrides, path) { current, values, childPath ->
            schema.decodeResult(current, values, childPath)
        }

    internal fun withOverridesAdditiveResult(
        overrides: Map<String, Any?>,
        path: String,
        schema: ConfigSchema<C>,
    ): ConfigDecodeResult<LanguageConfig<C>> =
        withOverridesResult(overrides, path) { current, values, childPath ->
            schema.decodeAdditiveResult(current, values, childPath)
        }

    private fun withOverridesResult(
        overrides: Map<String, Any?>,
        path: String,
        decode: (C, Map<String, Any?>, String) -> ConfigDecodeResult<C>,
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
            val result = decode(updated.getValue(key), stringKeyed, "$path.$language")
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
            ConfigSchema.custom(
                decoder = { config, values, path ->
                    val result =
                        LanguageConfig(get(config))
                            .withOverridesResult(values, path, languageSchema)
                    ConfigDecodeResult(set(config, result.value.values), result.errors)
                },
                additiveDecoder = { config, values, path ->
                    val result =
                        LanguageConfig(get(config))
                            .withOverridesAdditiveResult(values, path, languageSchema)
                    ConfigDecodeResult(set(config, result.value.values), result.errors)
                },
                languageSchema = languageSchema,
            )
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
