package dev.cint.rulepacks.logging

import dev.cint.api.Emit
import dev.cint.api.Matcher
import dev.cint.api.Rule
import dev.cint.api.SingleIntentPack
import dev.cint.api.SyntaxNode
import dev.cint.api.View

class LoggingRulePack(
    config: LoggingConfig = LoggingConfig(),
) : SingleIntentPack<LoggingConfig>(
        id = "logging",
        intent = "LOGGING",
        languages = config.languages.keys.toSet(),
        config = config,
        schema = LoggingConfig.SCHEMA,
        ruleId = "logging:logging",
    ) {
    override val matchers: List<Matcher> = listOf(Matcher { view -> LoggingRule(config).run(view) })

    override fun withConfig(config: LoggingConfig): SingleIntentPack<LoggingConfig> = LoggingRulePack(config)
}

class LoggingRule(
    private val config: LoggingConfig,
) : Rule {
    override val id: String = "logging:logging"
    override val owns: String = "LOGGING"
    override val reads: Set<String> = emptySet()

    override fun run(view: View): List<Emit> {
        val language = config.forLanguage(view.language())
        return buildList {
            addAll(this@LoggingRule.print(view, language))
            addAll(this@LoggingRule.loggerInteraction(view, language))
            addAll(this@LoggingRule.loggingState(view, language))
        }
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
                    receiverName in loggerSymbols -> {
                        Match("call-on-logger-symbol", "heuristic", 0.8)
                    }

                    language.receiverNames.any { it.equals(receiverName, ignoreCase = true) } -> {
                        Match("receiver-name", "heuristic", 0.6)
                    }

                    language.qualifiers.any { receiver.text == it || receiver.text.endsWith(".$it") } -> {
                        Match("qualified-static", "heuristic", 0.8)
                    }

                    importsMatch -> {
                        Match("name-import-gated", "heuristic", 0.5)
                    }

                    else -> {
                        null
                    }
                }

            val match =
                if (heuristic == null || heuristic.rule == "receiver-name" || heuristic.rule == "name-import-gated") {
                    val type = view.typeAt(receiver)
                    when {
                        type == null -> {
                            heuristic
                        }

                        typeMatches(type.fqName, language.types) -> {
                            Match("typed-receiver", type.source, type.confidence)
                        }

                        else -> {
                            null
                        }
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
