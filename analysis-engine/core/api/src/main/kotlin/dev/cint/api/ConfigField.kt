package dev.cint.api

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
