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
