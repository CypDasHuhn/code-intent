package dev.cint.rulepacks

import dev.cint.api.Emit
import dev.cint.api.Rule
import dev.cint.api.RulePack
import dev.cint.api.SyntaxNode
import dev.cint.api.View

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

    companion object {
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

class LoggingRulePack(
    private val config: LoggingConfig = LoggingConfig(),
) : RulePack {
    override val id: String = "logging"
    override val languages: Set<String> = config.languages.keys.toSet()

    override fun rules(): List<Rule> = listOf(LoggingRule(config))
}

class LoggingRule(
    private val config: LoggingConfig,
) : Rule {
    override val id: String = "logging:logging"
    override val owns: String = "LOGGING"
    override val reads: Set<String> = emptySet()

    override fun run(view: View): List<Emit> {
        val language = config.forLanguage(view.language())
        return print(view, language) + loggerInteraction(view, language) + loggingState(view, language)
    }

    private fun print(
        view: View,
        language: LoggingLanguageConfig,
    ): List<Emit> =
        view.calls().mapNotNull { call ->
            val callee = call.callee ?: return@mapNotNull null
            if (callee !in language.printCalls) return@mapNotNull null
            val receiver = call.receiver
            if (receiver != null && receiver.text !in language.printQualifiers) return@mapNotNull null
            emit(call.site, "treesitter", 1.0)
        }

    private fun loggerInteraction(
        view: View,
        language: LoggingLanguageConfig,
    ): List<Emit> {
        val loggerSymbols =
            view
                .properties()
                .mapNotNull { property ->
                    val initializer = property.initializer ?: return@mapNotNull null
                    val path = callPath(view, initializer) ?: return@mapNotNull null
                    val matches = language.factories.any { path == it || path.endsWith(".$it") }
                    if (matches) property.name else null
                }.toSet()
        val importsMatch =
            view.imports().any { import ->
                language.imports.any { import == it || import.startsWith("$it.") }
            }

        return view.calls().mapNotNull { call ->
            val callee = call.callee ?: return@mapNotNull null
            if (language.methods.isNotEmpty() && callee !in language.methods) return@mapNotNull null
            val receiver = call.receiver ?: return@mapNotNull null
            val receiverName = receiver.text.substringAfterLast('.')

            val heuristic =
                when {
                    receiverName in loggerSymbols ->
                        Match("call-on-logger-symbol", "heuristic", 0.8)
                    language.receiverNames.any { it.equals(receiverName, ignoreCase = true) } ->
                        Match("receiver-name", "heuristic", 0.6)
                    language.qualifiers.any { receiver.text == it || receiver.text.endsWith(".$it") } ->
                        Match("qualified-static", "heuristic", 0.8)
                    importsMatch ->
                        Match("name-import-gated", "heuristic", 0.5)
                    else ->
                        null
                }

            val match =
                if (heuristic == null || heuristic.rule == "receiver-name" || heuristic.rule == "name-import-gated") {
                    val type = view.typeAt(receiver)
                    when {
                        type == null -> heuristic
                        typeMatches(type.fqName, language.types) ->
                            Match("typed-receiver", type.source, type.confidence)
                        else -> null
                    }
                } else {
                    heuristic
                } ?: return@mapNotNull null

            emit(call.site, match.source, match.confidence)
        }
    }

    private fun loggingState(
        view: View,
        language: LoggingLanguageConfig,
    ): List<Emit> {
        if (language.guardNames.isEmpty()) return emptyList()
        return view.guardConditions().mapNotNull { condition ->
            if (language.guardNames.any { condition.text.contains(it, ignoreCase = true) }) {
                emit(condition, "treesitter", 0.7)
            } else {
                null
            }
        }
    }

    private fun callPath(
        view: View,
        node: SyntaxNode,
    ): String? {
        val call =
            view.calls().firstOrNull {
                it.node.startByte == node.startByte && it.node.endByte == node.endByte
            } ?: return null
        val callee = call.callee ?: return null
        val receiver = call.receiver
        return if (receiver != null) "${receiver.text}.$callee" else callee
    }

    private fun typeMatches(
        actual: String,
        configured: Set<String>,
    ): Boolean {
        val actualSimple = actual.substringAfterLast('.')
        return configured.any { expected ->
            actual == expected ||
                actual.endsWith(".$expected") ||
                (!actual.contains('.') && expected.substringAfterLast('.') == actualSimple)
        }
    }

    private fun emit(
        node: SyntaxNode,
        source: String,
        confidence: Double,
    ): Emit =
        Emit(
            intents = setOf(owns),
            evidence = node.text.replace("\n", " ").take(160),
            span = node.span(),
            source = source,
            confidence = confidence,
            startByte = node.startByte,
            endByte = node.endByte,
        )

    private data class Match(
        val rule: String,
        val source: String,
        val confidence: Double,
    )
}
