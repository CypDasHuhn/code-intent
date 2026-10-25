package dev.cint.rulepacks.packs.localcopy

import dev.cint.contracts.data.CandidateReason
import dev.cint.contracts.facts.DeclarationKind
import dev.cint.contracts.facts.WriteKind
import dev.cint.contracts.facts.isFieldName
import dev.cint.contracts.facts.matchesCallee
import dev.cint.contracts.ports.RuleEmission
import dev.cint.contracts.ports.RuleEvaluationView
import dev.cint.contracts.ports.RuleEvaluator
import dev.cint.contracts.ports.RuleSpec
import dev.cint.rulepacks.HEURISTIC_SOURCE
import dev.cint.rulepacks.PackEvaluator
import dev.cint.rulepacks.PackRules
import dev.cint.rulepacks.decodeRule
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
private data class LocalCopyData(
    val localCopyCalls: Set<String> = emptySet(),
    val fieldPrefixes: List<String> = emptyList(),
    val fieldReceiverPrefix: String? = null,
    val fieldUnderscorePrefix: String? = null,
)

internal object LocalCopyPack : PackRules {
    override val packId = "local-copy"

    override fun create(
        spec: RuleSpec,
        data: JsonObject,
    ): RuleEvaluator = LocalCopyRule(spec, data.decodeRule())
}

private class LocalCopyRule(
    spec: RuleSpec,
    private val data: LocalCopyData,
) : PackEvaluator(spec) {
    override fun evaluate(view: RuleEvaluationView): List<RuleEmission> {
        val result = mutableListOf<RuleEmission>()
        val declared = view.properties.mapNotNull { it.name }.toSet()
        val localNames =
            view.declarations
                .filter { it.kind == DeclarationKind.VARIABLE }
                .mapNotNull { it.name }
                .toSet()
        for (write in view.writes) {
            if (write.kind != WriteKind.ASSIGNMENT) continue
            val node = view.node(write.nodeId) ?: continue
            val target = write.targetName ?: continue
            val value = write.valueIdentifier ?: continue
            if (isField(target) && !isField(value) && value !in declared) {
                view.emitNode(node, 0.8, HEURISTIC_SOURCE)?.let(result::add)
            } else if (target in localNames || isField(target)) {
                view.candidateNode(node, reason = CandidateReason.LOW_CONFIDENCE)?.let { result += it }
            }
        }
        for (property in view.properties) {
            val name = property.name ?: continue
            val value = property.initializerIdentifier ?: continue
            if (name == value) continue
            if (isField(name) && !isField(value) && value !in declared) {
                view.emitNode(view.node(property.nodeId), 0.8, HEURISTIC_SOURCE)?.let(result::add)
            }
        }
        for (call in view.calls) {
            if (call.matchesCallee(data.localCopyCalls, ignoreCase = false)) {
                view.emitNode(view.node(call.nodeId), 0.55, HEURISTIC_SOURCE)?.let(result::add)
            }
        }
        return result.distinctBy { Triple(it.startByte, it.endByte, it.intents) }
    }

    private fun isField(name: String): Boolean = isFieldName(name, data.fieldPrefixes, data.fieldReceiverPrefix, data.fieldUnderscorePrefix)
}
