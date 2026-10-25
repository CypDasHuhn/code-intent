package dev.cint.rulepacks.packs.logging

import dev.cint.contracts.facts.matchesConditionName
import dev.cint.contracts.facts.matchesPackage
import dev.cint.contracts.facts.pathMatches
import dev.cint.contracts.facts.receiverQualifierMatches
import dev.cint.contracts.facts.tokenizedCallee
import dev.cint.contracts.ports.RuleEmission
import dev.cint.contracts.ports.RuleEvaluationView
import dev.cint.contracts.ports.RuleEvaluator
import dev.cint.contracts.ports.RuleSpec
import dev.cint.rulepacks.HEURISTIC_SOURCE
import dev.cint.rulepacks.PackEvaluator
import dev.cint.rulepacks.PackRules
import dev.cint.rulepacks.SYNTAX_SOURCE
import dev.cint.rulepacks.decodeRule
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
private data class LoggingData(
    val exactCalls: Set<String> = emptySet(),
    val methods: Set<String> = emptySet(),
    val receiverNames: Set<String> = emptySet(),
    val receiverQualifiers: Set<String> = emptySet(),
    val guardNames: Set<String> = emptySet(),
    val factories: Set<String> = emptySet(),
    val imports: Set<String> = emptySet(),
)

internal object LoggingPack : PackRules {
    override val packId = "logging"

    override fun create(
        spec: RuleSpec,
        data: JsonObject,
    ): RuleEvaluator = LoggingRule(spec, data.decodeRule())
}

private class LoggingRule(
    spec: RuleSpec,
    private val data: LoggingData,
) : PackEvaluator(spec) {
    override fun evaluate(view: RuleEvaluationView): List<RuleEmission> {
        val loggerSymbols = loggerSymbols(view)
        val hasLoggingImport = view.imports.any { it.matchesPackage(data.imports) }
        val result = mutableListOf<RuleEmission>()
        for (call in view.calls) {
            val tokens = call.tokenizedCallee()
            val receiver = call.receiverPath.orEmpty()
            val receiverName = call.receiverName.orEmpty()
            when {
                call.receiverQualifierMatches(data.receiverQualifiers) &&
                    tokens.any { it in data.exactCalls || it in data.methods } -> {
                    view.emitNode(view.node(call.nodeId), 1.0, SYNTAX_SOURCE)?.let(result::add)
                }

                tokens.any { it in data.exactCalls } && receiver.isEmpty() -> {
                    view.emitNode(view.node(call.nodeId), 1.0, SYNTAX_SOURCE)?.let(result::add)
                }

                tokens.any { it in data.methods } && receiver.isNotEmpty() -> {
                    val evidence =
                        when {
                            receiverName in loggerSymbols -> {
                                0.8 to HEURISTIC_SOURCE
                            }

                            data.receiverNames.any { it.equals(receiverName, ignoreCase = true) } -> {
                                0.6 to HEURISTIC_SOURCE
                            }

                            call.receiverQualifierMatches(data.receiverQualifiers) -> {
                                0.8 to HEURISTIC_SOURCE
                            }

                            hasLoggingImport -> {
                                0.5 to HEURISTIC_SOURCE
                            }

                            else -> {
                                null
                            }
                        } ?: continue
                    view.emitNode(view.node(call.nodeId), evidence.first, evidence.second)?.let(result::add)
                }
            }
        }
        for (guard in view.guards) {
            if (guard.matchesConditionName(data.guardNames)) {
                view.emitNode(view.node(guard.conditionId), 0.7, SYNTAX_SOURCE)?.let(result::add)
            }
        }
        return result.distinctBy { Triple(it.startByte, it.endByte, it.intents) }
    }

    private fun loggerSymbols(view: RuleEvaluationView): Set<String> =
        view.properties
            .mapNotNull { property ->
                val name = property.name ?: return@mapNotNull null
                val initializer = view.node(property.initializerId) ?: return@mapNotNull null
                val call =
                    view.calls.firstOrNull { call ->
                        val node = view.node(call.nodeId) ?: return@firstOrNull false
                        node.startByte == initializer.startByte && node.endByte == initializer.endByte
                    } ?: return@mapNotNull null
                name.takeIf { call.pathMatches(data.factories) }
            }.toSet()
}
