package dev.cint.rulepacks.intents.mutation
import dev.cint.api.ConfigField
import dev.cint.api.ConfigSchema
import dev.cint.api.LanguageConfig
import dev.cint.rulepacks.intents.mutation.defaults.DEFAULT_ASSIGNMENT_PATTERNS
import dev.cint.rulepacks.intents.mutation.defaults.DEFAULT_MUTABLE_OPERATIONS
import dev.cint.rulepacks.intents.mutation.defaults.DEFAULT_MUTABLE_RECEIVER_NAMES
import dev.cint.rulepacks.intents.mutation.defaults.DEFAULT_MUTABLE_TYPES
import dev.cint.rulepacks.intents.mutation.defaults.DEFAULT_PERSISTENCE_CALLS
import dev.cint.rulepacks.intents.mutation.defaults.DEFAULT_PERSISTENCE_PATHS
import dev.cint.rulepacks.intents.mutation.defaults.DEFAULT_PERSISTENCE_PREFIXES
import dev.cint.rulepacks.intents.mutation.defaults.DEFAULT_PERSISTENCE_QUALIFIERS
import dev.cint.rulepacks.intents.mutation.defaults.DEFAULT_PERSISTENCE_TYPES
import dev.cint.rulepacks.intents.mutation.defaults.LANGUAGE_DEFAULTS

internal val DURABLE_COMPOUND_MUTATION_CALLS =
    setOf(
        "findOneAndUpdate",
        "findOneAndDelete",
        "findOneAndReplace",
        "findOneAndIncrement",
        "findOneAndDecrement",
        "findOneAndModify",
        "findAndModify",
    )

internal val LOCAL_ATOMIC_MUTATION_CALLS =
    setOf(
        "getAndUpdate",
        "getAndIncrement",
        "getAndDecrement",
    )

data class MutationLanguageConfig(
    val persistenceCalls: Set<String> = DEFAULT_PERSISTENCE_CALLS,
    val persistencePaths: Set<String> = DEFAULT_PERSISTENCE_PATHS,
    val persistenceQualifiers: Set<String> = DEFAULT_PERSISTENCE_QUALIFIERS,
    val persistenceTypes: Set<String> = DEFAULT_PERSISTENCE_TYPES,
    val persistencePrefixes: Set<String> = DEFAULT_PERSISTENCE_PREFIXES,
    val mutableOperations: Set<String> = DEFAULT_MUTABLE_OPERATIONS,
    val mutableTypes: Set<String> = DEFAULT_MUTABLE_TYPES,
    val mutableReceiverNames: Set<String> = DEFAULT_MUTABLE_RECEIVER_NAMES,
    val assignmentPatterns: Set<String> = DEFAULT_ASSIGNMENT_PATTERNS,
    val ignoreLoopCounters: Boolean = true,
    val ignoreBookkeeping: Boolean = true,
    val ignorePureCopies: Boolean = true,
    val mutableLocalDeclarations: Boolean = false,
) {
    fun withOverrides(
        values: Map<String, Any?>,
        language: String,
    ): MutationLanguageConfig = SCHEMA.decode(this, values, "$MUTATION_PACK_ID.${language.lowercase()}")

    companion object {
        internal val SCHEMA =
            ConfigSchema.of(
                ConfigField.strings<MutationLanguageConfig>(
                    "persistenceCalls",
                    { it.persistenceCalls },
                    { config, value -> config.copy(persistenceCalls = value) },
                ),
                ConfigField.strings<MutationLanguageConfig>(
                    "persistencePaths",
                    { it.persistencePaths },
                    { config, value -> config.copy(persistencePaths = value) },
                ),
                ConfigField.strings<MutationLanguageConfig>(
                    "persistenceQualifiers",
                    { it.persistenceQualifiers },
                    { config, value -> config.copy(persistenceQualifiers = value) },
                ),
                ConfigField.strings<MutationLanguageConfig>(
                    "persistenceTypes",
                    { it.persistenceTypes },
                    { config, value -> config.copy(persistenceTypes = value) },
                ),
                ConfigField.strings<MutationLanguageConfig>(
                    "persistencePrefixes",
                    { it.persistencePrefixes },
                    { config, value -> config.copy(persistencePrefixes = value) },
                ),
                ConfigField.strings<MutationLanguageConfig>(
                    "mutableOperations",
                    { it.mutableOperations },
                    { config, value -> config.copy(mutableOperations = value) },
                ),
                ConfigField.strings<MutationLanguageConfig>(
                    "mutableTypes",
                    { it.mutableTypes },
                    { config, value -> config.copy(mutableTypes = value) },
                ),
                ConfigField.strings<MutationLanguageConfig>(
                    "mutableReceiverNames",
                    { it.mutableReceiverNames },
                    { config, value -> config.copy(mutableReceiverNames = value) },
                ),
                ConfigField.strings<MutationLanguageConfig>(
                    "assignmentPatterns",
                    { it.assignmentPatterns },
                    { config, value -> config.copy(assignmentPatterns = value) },
                ),
                ConfigField.boolean<MutationLanguageConfig>(
                    "ignoreLoopCounters",
                    { it.ignoreLoopCounters },
                    { config, value -> config.copy(ignoreLoopCounters = value) },
                ),
                ConfigField.boolean<MutationLanguageConfig>(
                    "ignoreBookkeeping",
                    { it.ignoreBookkeeping },
                    { config, value -> config.copy(ignoreBookkeeping = value) },
                ),
                ConfigField.boolean<MutationLanguageConfig>(
                    "ignorePureCopies",
                    { it.ignorePureCopies },
                    { config, value -> config.copy(ignorePureCopies = value) },
                ),
                ConfigField.boolean<MutationLanguageConfig>(
                    "mutableLocalDeclarations",
                    { it.mutableLocalDeclarations },
                    { config, value -> config.copy(mutableLocalDeclarations = value) },
                ),
            )

        fun defaults(): Map<String, MutationLanguageConfig> = LANGUAGE_DEFAULTS
    }
}

data class MutationConfig(
    val languages: Map<String, MutationLanguageConfig> = MutationLanguageConfig.defaults(),
) {
    fun forLanguage(language: String): MutationLanguageConfig =
        LanguageConfig(languages).forLanguage(language, "kotlin") ?: MutationLanguageConfig()

    fun withOverrides(values: Map<String, Any?>): MutationConfig = SCHEMA.decode(this, values, MUTATION_PACK_ID)

    companion object {
        internal val SCHEMA =
            LanguageConfig.schema<MutationLanguageConfig, MutationConfig>(
                languageSchema = MutationLanguageConfig.SCHEMA,
                get = { it.languages },
                set = { config, languages -> config.copy(languages = languages) },
            )

        fun defaults(): Map<String, MutationLanguageConfig> = MutationLanguageConfig.defaults()

        fun from(values: Map<String, Any?>): MutationConfig = MutationConfig().withOverrides(values)
    }
}
