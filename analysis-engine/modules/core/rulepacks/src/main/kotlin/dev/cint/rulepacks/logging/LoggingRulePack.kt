package dev.cint.rulepacks.logging

import dev.cint.api.Emit
import dev.cint.api.Intent
import dev.cint.api.Matcher
import dev.cint.api.SingleIntentPack
import dev.cint.api.View
import dev.cint.api.emit

const val LOGGING_INTENT: Intent = "LOGGING"
const val LOGGING_PACK_ID: String = "logging"
const val LOGGING_RULE_ID: String = "$LOGGING_PACK_ID:logging"

private const val HEURISTIC_SOURCE: String = "heuristic"

class LoggingRulePack(
    config: LoggingConfig = LoggingConfig(),
) : SingleIntentPack<LoggingConfig>(
        id = LOGGING_PACK_ID,
        intent = LOGGING_INTENT,
        languages = config.languages.keys.toSet(),
        config = config,
        schema = LoggingConfig.SCHEMA,
        ruleId = LOGGING_RULE_ID,
    ) {
    override val matchers: List<Matcher> = listOf(Matcher { view -> LoggingRule(config).match(view) })

    override fun withConfig(config: LoggingConfig): SingleIntentPack<LoggingConfig> = LoggingRulePack(config)
}

private class LoggingRule(
    private val config: LoggingConfig,
) : Matcher {
    override fun match(view: View): List<Emit> {
        val language = config.forLanguage(view.language())
        return print(view, language) +
            loggerInteraction(view, language) +
            loggingState(view, language)
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
            call.emit(LOGGING_INTENT)
        }

    private fun loggerInteraction(
        view: View,
        language: LoggingLanguageConfig,
    ): List<Emit> {
        val loggerSymbols = loggerSymbols(view, language)
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
                    receiverName in loggerSymbols -> Heuristic.FACTORY
                    language.receiverNames.any { it.equals(receiverName, ignoreCase = true) } -> Heuristic.RECEIVER_NAME
                    language.qualifiers.any { receiver.text == it || receiver.text.endsWith(".$it") } -> Heuristic.QUALIFIED
                    importsMatch -> Heuristic.IMPORTED
                    else -> null
                }

            if (heuristic != null && !heuristic.consultsOracle) {
                return@mapNotNull call.emit(LOGGING_INTENT, HEURISTIC_SOURCE, heuristic.confidence)
            }

            val type = view.receiverType(call)
            if (type == null) {
                return@mapNotNull heuristic?.let { call.emit(LOGGING_INTENT, HEURISTIC_SOURCE, it.confidence) }
            }
            if (!typeMatches(type.fqName, language.types)) return@mapNotNull null
            call.emit(LOGGING_INTENT, type.source, type.confidence)
        }
    }

    private fun loggingState(
        view: View,
        language: LoggingLanguageConfig,
    ): List<Emit> {
        if (language.guardNames.isEmpty()) return emptyList()
        return view.guardConditions().mapNotNull { condition ->
            if (language.guardNames.any { condition.text.contains(it, ignoreCase = true) }) {
                Emit.of(LOGGING_INTENT, condition, confidence = 0.7)
            } else {
                null
            }
        }
    }

    private fun loggerSymbols(
        view: View,
        language: LoggingLanguageConfig,
    ): Set<String> {
        val callsBySpan = view.calls().associateBy { it.node.startByte to it.node.endByte }
        return view
            .properties()
            .mapNotNull { property ->
                val name = property.name ?: return@mapNotNull null
                val initializer = property.initializer ?: return@mapNotNull null
                val path = callsBySpan[initializer.startByte to initializer.endByte]?.path ?: return@mapNotNull null
                if (language.factories.any { path == it || path.endsWith(".$it") }) name else null
            }.toSet()
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

    private enum class Heuristic(
        val confidence: Double,
        val consultsOracle: Boolean = false,
    ) {
        FACTORY(0.8),
        RECEIVER_NAME(0.6, consultsOracle = true),
        QUALIFIED(0.8),
        IMPORTED(0.5, consultsOracle = true),
    }
}
