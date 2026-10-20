package dev.cint.rulepacks.logging

import dev.cint.api.ConfigField
import dev.cint.api.ConfigSchema
import dev.cint.api.LanguageConfig

data class LoggingLanguageConfig(
    val printCalls: Set<String> = emptySet(),
    val printQualifiers: Set<String> = emptySet(),
    val methods: Set<String> = emptySet(),
    val types: Set<String> = emptySet(),
    val factories: Set<String> = emptySet(),
    val receiverNames: Set<String> = emptySet(),
    val qualifiers: Set<String> = emptySet(),
    val guardNames: Set<String> = emptySet(),
    val imports: Set<String> = emptySet(),
)

data class LoggingConfig(
    val languages: Map<String, LoggingLanguageConfig> = defaults(),
) {
    fun forLanguage(language: String): LoggingLanguageConfig =
        LanguageConfig(languages).forLanguage(language, "kotlin")
            ?: LoggingLanguageConfig()

    fun withOverrides(values: Map<String, Any?>): LoggingConfig = SCHEMA.decode(this, values, LOGGING_PACK_ID)

    companion object {
        private val LANGUAGE_SCHEMA =
            ConfigSchema.of(
                ConfigField.strings<LoggingLanguageConfig>(
                    "printCalls",
                    { it.printCalls },
                    { config, value -> config.copy(printCalls = value) },
                ),
                ConfigField.strings<LoggingLanguageConfig>(
                    "printQualifiers",
                    { it.printQualifiers },
                    { config, value -> config.copy(printQualifiers = value) },
                ),
                ConfigField.strings<LoggingLanguageConfig>("methods", { it.methods }, { config, value -> config.copy(methods = value) }),
                ConfigField.strings<LoggingLanguageConfig>("types", { it.types }, { config, value -> config.copy(types = value) }),
                ConfigField.strings<LoggingLanguageConfig>(
                    "factories",
                    { it.factories },
                    { config, value -> config.copy(factories = value) },
                ),
                ConfigField.strings<LoggingLanguageConfig>(
                    "receiverNames",
                    { it.receiverNames },
                    { config, value -> config.copy(receiverNames = value) },
                ),
                ConfigField.strings<LoggingLanguageConfig>(
                    "qualifiers",
                    { it.qualifiers },
                    { config, value -> config.copy(qualifiers = value) },
                ),
                ConfigField.strings<LoggingLanguageConfig>(
                    "guardNames",
                    { it.guardNames },
                    { config, value -> config.copy(guardNames = value) },
                ),
                ConfigField.strings<LoggingLanguageConfig>("imports", { it.imports }, { config, value -> config.copy(imports = value) }),
            )

        internal val SCHEMA =
            LanguageConfig.schema<LoggingLanguageConfig, LoggingConfig>(
                languageSchema = LANGUAGE_SCHEMA,
                get = { it.languages },
                set = { config, languages -> config.copy(languages = languages) },
            )

        fun from(values: Map<String, Any?>): LoggingConfig = LoggingConfig().withOverrides(values)

        private val LANGUAGE_DEFAULTS: Map<String, LoggingLanguageConfig> =
            mapOf(
                "kotlin" to
                    LoggingLanguageConfig(
                        printCalls = setOf("println", "print"),
                        printQualifiers = setOf("System.out", "System.err"),
                        methods = setOf("info", "debug", "warn", "error", "trace", "log", "verbose", "fatal"),
                        types =
                            setOf(
                                "org.slf4j.Logger",
                                "org.apache.logging.log4j.Logger",
                                "java.util.logging.Logger",
                            ),
                        factories =
                            setOf(
                                "LoggerFactory.getLogger",
                                "KotlinLogging.logger",
                                "LogManager.getLogger",
                                "Logger.getLogger",
                            ),
                        receiverNames = setOf("log", "logger", "LOG", "LOGGER", "Logger"),
                        qualifiers = setOf("System.out", "System.err"),
                        guardNames = setOf("loggingEnabled", "logEnabled", "debugEnabled", "logging"),
                        imports = setOf("org.slf4j", "mu.KotlinLogging", "java.util.logging"),
                    ),
                "javascript" to
                    LoggingLanguageConfig(
                        printCalls = setOf("log", "error", "warn", "info", "debug", "trace"),
                        printQualifiers = setOf("console"),
                        methods = setOf("log", "info", "warn", "error", "debug", "trace", "verbose", "fatal"),
                        factories = setOf("createLogger", "pino"),
                        receiverNames = setOf("log", "logger", "LOG", "LOGGER", "Logger"),
                        guardNames = setOf("loggingEnabled", "logEnabled", "debugEnabled", "logging"),
                    ),
                "typescript" to
                    LoggingLanguageConfig(
                        printCalls = setOf("log", "error", "warn", "info", "debug", "trace"),
                        printQualifiers = setOf("console"),
                        methods = setOf("log", "info", "warn", "error", "debug", "trace", "verbose", "fatal"),
                        factories = setOf("createLogger", "pino"),
                        receiverNames = setOf("log", "logger", "LOG", "LOGGER", "Logger"),
                        guardNames = setOf("loggingEnabled", "logEnabled", "debugEnabled", "logging"),
                    ),
                "csharp" to
                    LoggingLanguageConfig(
                        printCalls = setOf("WriteLine", "Write", "WriteError"),
                        printQualifiers = setOf("Console", "Console.Error", "Debug", "Trace"),
                        methods =
                            setOf(
                                "Log",
                                "LogInformation",
                                "LogWarning",
                                "LogError",
                                "LogDebug",
                                "LogTrace",
                                "Debug",
                                "Info",
                                "Warn",
                                "Error",
                            ),
                        factories =
                            setOf(
                                "CreateLogger",
                                "GetLogger",
                                "LoggerFactory.Create",
                                "Log.ForContext",
                            ),
                        receiverNames = setOf("log", "logger", "LOG", "LOGGER", "Logger", "_log", "_logger"),
                        guardNames = setOf("loggingEnabled", "IsEnabled"),
                        imports = setOf("Microsoft.Extensions.Logging", "Serilog", "NLog", "log4net"),
                    ),
            )

        fun defaults(): Map<String, LoggingLanguageConfig> = LANGUAGE_DEFAULTS
    }
}
