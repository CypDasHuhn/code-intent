package dev.cint.rulepacks.validation

import dev.cint.api.ConfigField
import dev.cint.api.ConfigSchema

data class ValidationConfig(
    val predicates: Set<String> = DEFAULT_PREDICATES,
    val predicatePrefixes: List<String> = DEFAULT_PREDICATE_PREFIXES,
    val magicComment: String = "validation",
    val commentKinds: Set<String> = DEFAULT_COMMENT_KINDS,
    val elvisKinds: Set<String> = DEFAULT_ELVIS_KINDS,
    val coalesceOperators: Set<String> = DEFAULT_COALESCE_OPERATORS,
    val rejectionValues: Set<String> = DEFAULT_REJECTION_VALUES,
) {
    fun withOverrides(values: Map<String, Any?>): ValidationConfig = SCHEMA.decode(this, values, VALIDATION_PACK_ID)

    companion object {
        val DEFAULT_PREDICATES: Set<String> =
            setOf(
                "none",
                "any",
                "all",
                "isEmpty",
                "isNotEmpty",
                "isBlank",
                "isNotBlank",
                "isNullOrBlank",
                "isNullOrEmpty",
                "contains",
                "containsKey",
                "containsValue",
                "startsWith",
                "endsWith",
                "matches",
            )
        val DEFAULT_PREDICATE_PREFIXES: List<String> = listOf("is", "has", "Is", "Has")
        val DEFAULT_COMMENT_KINDS: Set<String> = setOf("comment", "line_comment", "multiline_comment")
        val DEFAULT_ELVIS_KINDS: Set<String> = setOf("elvis_expression")
        val DEFAULT_COALESCE_OPERATORS: Set<String> = setOf("??")
        val DEFAULT_REJECTION_VALUES: Set<String> =
            setOf(
                "null",
                "undefined",
                "false",
                "0",
                "-1",
                "\"\"",
                "''",
                "[]",
                "{}",
                "emptyList()",
                "emptyMap()",
                "emptySet()",
                "listOf()",
                "mapOf()",
                "setOf()",
                "arrayOf()",
                "String.Empty",
            )

        internal val SCHEMA =
            ConfigSchema.of(
                ConfigField.strings<ValidationConfig>(
                    "predicates",
                    { it.predicates },
                    { config, value -> config.copy(predicates = value) },
                ),
                ConfigField.stringList<ValidationConfig>(
                    "predicatePrefixes",
                    { it.predicatePrefixes },
                    { config, value -> config.copy(predicatePrefixes = value) },
                ),
                ConfigField.string<ValidationConfig>(
                    "magicComment",
                    { it.magicComment },
                    { config, value -> config.copy(magicComment = value) },
                ),
                ConfigField.strings<ValidationConfig>(
                    "commentKinds",
                    { it.commentKinds },
                    { config, value -> config.copy(commentKinds = value) },
                ),
                ConfigField.strings<ValidationConfig>(
                    "elvisKinds",
                    { it.elvisKinds },
                    { config, value -> config.copy(elvisKinds = value) },
                ),
                ConfigField.strings<ValidationConfig>(
                    "coalesceOperators",
                    { it.coalesceOperators },
                    { config, value -> config.copy(coalesceOperators = value) },
                ),
                ConfigField.strings<ValidationConfig>(
                    "rejectionValues",
                    { it.rejectionValues },
                    { config, value -> config.copy(rejectionValues = value) },
                ),
            )

        fun from(values: Map<String, Any?>): ValidationConfig = ValidationConfig().withOverrides(values)
    }
}
