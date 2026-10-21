package dev.cint.rulepacks.intents.transformation

import dev.cint.api.CallFact
import dev.cint.api.CandidateEmit
import dev.cint.api.CandidateReason
import dev.cint.api.Emit
import dev.cint.api.Intent
import dev.cint.api.Matcher
import dev.cint.api.RuleEmit
import dev.cint.api.SingleIntentPack
import dev.cint.api.View
import dev.cint.api.emit

const val DATA_TRANSFORMATION_INTENT: Intent = "DATA_TRANSFORMATION"
const val DATA_TRANSFORMATION_PACK_ID: String = "data-transformation"
const val DATA_TRANSFORMATION_RULE_ID: String = "$DATA_TRANSFORMATION_PACK_ID:data-transformation"

private const val ESTABLISHED_CONFIDENCE: Double = 0.9
private const val CANDIDATE_CONFIDENCE: Double = 0.4

class TransformationRulePack(
    config: TransformationConfig = TransformationConfig(),
) : SingleIntentPack<TransformationConfig>(
        id = DATA_TRANSFORMATION_PACK_ID,
        intent = DATA_TRANSFORMATION_INTENT,
        languages = config.languages.keys.toSet(),
        config = config,
        schema = TransformationConfig.SCHEMA,
        ruleId = DATA_TRANSFORMATION_RULE_ID,
    ) {
    override val matchers: List<Matcher> =
        listOf(Matcher { view -> TransformationRule(config.forLanguage(view.language())).match(view) })

    override fun withConfig(config: TransformationConfig): SingleIntentPack<TransformationConfig> = TransformationRulePack(config)
}

private class TransformationRule(
    private val config: TransformationLanguageConfig,
) : Matcher {
    override fun match(view: View): List<RuleEmit> {
        val selected = linkedMapOf<Pair<Int, Int>, Pair<CallFact, Boolean>>()
        for (call in view.calls()) {
            val callee = call.callee ?: continue
            val configured = callee in config.calls
            val established = configured && hasReceiverEvidence(view, call)
            val candidate =
                when {
                    configured -> {
                        !established
                    }

                    else -> {
                        callee in config.candidateCalls ||
                            config.candidatePrefixes.any { it.isNotEmpty() && callee.startsWith(it) }
                    }
                }
            if (!established && !candidate) continue
            val key = call.node.startByte to call.node.endByte
            val previous = selected[key]
            if (previous == null || (!previous.second && established)) {
                selected[key] = call to established
            }
        }

        return selected.values.map { (call, established) ->
            if (established) {
                call.emit(DATA_TRANSFORMATION_INTENT, confidence = ESTABLISHED_CONFIDENCE)
            } else {
                CandidateEmit.call(
                    DATA_TRANSFORMATION_INTENT,
                    call,
                    CandidateReason.LOW_CONFIDENCE,
                    source = "rule:$DATA_TRANSFORMATION_RULE_ID",
                    confidence = CANDIDATE_CONFIDENCE,
                    aiEligible = true,
                )
            }
        }
    }

    private fun hasReceiverEvidence(
        view: View,
        call: CallFact,
    ): Boolean {
        val receiver = call.receiver ?: return false
        if (qualifierMatches(receiver.text)) return true
        val type = view.receiverType(call) ?: return true
        return typeMatches(type.fqName)
    }

    private fun qualifierMatches(receiver: String): Boolean {
        val segments = receiver.split('.').map { it.substringBefore('(').substringBefore('[').trim() }
        return config.qualifiers.any { qualifier ->
            segments.any { it.equals(qualifier, ignoreCase = true) }
        }
    }

    private fun typeMatches(actual: String): Boolean {
        val simple = actual.substringAfterLast('.')
        return config.types.any { expected ->
            actual == expected ||
                actual.endsWith(".$expected") ||
                simple.equals(expected.substringAfterLast('.'), ignoreCase = true)
        }
    }
}
