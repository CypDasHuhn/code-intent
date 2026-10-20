package dev.cint.rulepacks.support

import dev.cint.api.RulePack
import dev.cint.api.RulePackConfigException
import dev.cint.rulepacks.errorhandling.ErrorHandlingRulePack

internal fun configStrings(
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
                value.map { it as String }.toSet()
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

internal fun configStringList(
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

internal fun configString(
    value: Any?,
    path: String,
    errors: MutableList<String>,
): String? {
    if (value is String) return value
    errors += "$path: expected a string"
    return null
}

internal fun configBoolean(
    value: Any?,
    path: String,
    errors: MutableList<String>,
): Boolean? {
    if (value is Boolean) return value
    errors += "$path: expected a boolean"
    return null
}

internal fun configStringSets(
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
        val strings = configStrings(rawValue, childPath, errors) ?: continue
        result[rawKey] = strings
    }
    return result
}

/**
 * Supplies the language-specific bundled packs that cannot be registered as
 * single service providers without creating duplicate intent owners. The host
 * composition path filters these by language, drops an extra whose owned intent
 * is already covered by a discovered pack, and validates the combined snapshot
 * through the same loader path as service-discovered packs.
 */
object BundledRulePackSelection {
    val packIds: Set<String> = ErrorHandlingRulePack.languages.mapTo(linkedSetOf()) { ErrorHandlingRulePack.forLanguage(it).id }

    fun constructed(language: String): List<RulePack> {
        val normalized = language.lowercase()
        if (normalized !in ErrorHandlingRulePack.languages) return emptyList()
        return listOf(ErrorHandlingRulePack.forLanguage(normalized))
    }
}
