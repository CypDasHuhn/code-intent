package dev.cint.rulepacks

import dev.cint.api.RulePackConfigException

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
        languages[language]
            ?: languages[language.lowercase()]
            ?: languages["kotlin"]
            ?: LoggingLanguageConfig()

    fun withOverrides(values: Map<String, Any?>): LoggingConfig {
        val errors = mutableListOf<String>()
        val updated = languages.toMutableMap()
        for ((language, raw) in values) {
            val key = languages.keys.firstOrNull { it.equals(language, ignoreCase = true) }
            if (key == null) {
                errors += "logging.$language: unknown language"
                continue
            }
            if (raw !is Map<*, *>) {
                errors += "logging.$language: expected a map"
                continue
            }
            var languageConfig = updated.getValue(key)
            for ((rawKey, rawValue) in raw) {
                val path = "logging.$language.$rawKey"
                if (rawKey !is String) {
                    errors += "$path: config keys must be strings"
                    continue
                }
                languageConfig =
                    when (rawKey) {
                        "printCalls" -> {
                            languageConfig.copy(
                                printCalls = strings(rawValue, path, errors) ?: languageConfig.printCalls,
                            )
                        }

                        "printQualifiers" -> {
                            languageConfig.copy(
                                printQualifiers = strings(rawValue, path, errors) ?: languageConfig.printQualifiers,
                            )
                        }

                        "methods" -> {
                            languageConfig.copy(
                                methods = strings(rawValue, path, errors) ?: languageConfig.methods,
                            )
                        }

                        "types" -> {
                            languageConfig.copy(
                                types = strings(rawValue, path, errors) ?: languageConfig.types,
                            )
                        }

                        "factories" -> {
                            languageConfig.copy(
                                factories = strings(rawValue, path, errors) ?: languageConfig.factories,
                            )
                        }

                        "receiverNames" -> {
                            languageConfig.copy(
                                receiverNames = strings(rawValue, path, errors) ?: languageConfig.receiverNames,
                            )
                        }

                        "qualifiers" -> {
                            languageConfig.copy(
                                qualifiers = strings(rawValue, path, errors) ?: languageConfig.qualifiers,
                            )
                        }

                        "guardNames" -> {
                            languageConfig.copy(
                                guardNames = strings(rawValue, path, errors) ?: languageConfig.guardNames,
                            )
                        }

                        "imports" -> {
                            languageConfig.copy(
                                imports = strings(rawValue, path, errors) ?: languageConfig.imports,
                            )
                        }

                        else -> {
                            errors += "$path: unknown key"
                            languageConfig
                        }
                    }
            }
            updated[key] = languageConfig
        }
        if (errors.isNotEmpty()) {
            throw RulePackConfigException(errors.joinToString("; "))
        }
        return LoggingConfig(updated)
    }

    companion object {
        fun from(values: Map<String, Any?>): LoggingConfig = LoggingConfig().withOverrides(values)

        private fun strings(
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

        fun defaults(): Map<String, LoggingLanguageConfig> =
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
    }
}
