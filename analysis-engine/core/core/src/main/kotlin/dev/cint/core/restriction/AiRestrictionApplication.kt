package dev.cint.core.restriction

import dev.cint.api.Contribution
import dev.cint.api.Role
import dev.cint.core.user.USER_CONTRIBUTOR_ID
import dev.cint.core.user.USER_PURE_INTENT
import dev.cint.core.user.USER_READONLY_INTENT
import dev.cint.core.user.USER_SOURCE

private val DIRECTIVE_INTENTS = setOf(USER_PURE_INTENT, USER_READONLY_INTENT)

object AiRestrictionApplication {
    fun apply(
        settled: Map<String, List<Contribution>>,
        restrictions: List<ResolvedRestriction>,
    ): Map<String, List<Contribution>> {
        if (restrictions.isEmpty()) return settled
        val contributions = settled.values.flatten().toMutableList()
        for (restriction in restrictions) {
            val removals = applyRemovals(contributions, restriction)
            if (removals == 0) continue
            for (operation in restriction.add) {
                if (operation.intent in DIRECTIVE_INTENTS) continue
                val duplicate =
                    contributions.any { contribution ->
                        contribution.role == Role.SOURCE_INTENT &&
                            operation.intent in contribution.intents &&
                            contribution.startByte == operation.startByte &&
                            contribution.endByte == operation.endByte
                    }
                if (duplicate) continue
                contributions +=
                    Contribution(
                        rule = AI_CONFIRMED_CONTRIBUTOR_ID,
                        role = Role.SOURCE_INTENT,
                        intents = setOf(operation.intent),
                        span = operation.span,
                        source = AI_CONFIRMED_SOURCE,
                        confidence = operation.confidence.coerceIn(0.0, 1.0),
                        startByte = operation.startByte,
                        endByte = operation.endByte,
                        provider = restriction.provider,
                        model = restriction.model,
                        promptVersion = restriction.promptVersion,
                    )
            }
        }
        val result = LinkedHashMap<String, MutableList<Contribution>>()
        for (contribution in contributions) {
            result.getOrPut(contribution.rule) { mutableListOf() }.add(contribution)
        }
        return result
    }

    private fun applyRemovals(
        contributions: MutableList<Contribution>,
        restriction: ResolvedRestriction,
    ): Int {
        var applied = 0
        for (operation in restriction.remove) {
            if (operation.intent in DIRECTIVE_INTENTS) continue
            val index =
                contributions.indexOfFirst { contribution ->
                    removable(contribution, operation)
                }
            if (index < 0) continue
            val contribution = contributions[index]
            val remaining = contribution.intents - operation.intent
            if (remaining.isEmpty()) {
                contributions.removeAt(index)
            } else {
                contributions[index] = contribution.copy(intents = remaining)
            }
            applied += 1
        }
        return applied
    }

    private fun removable(
        contribution: Contribution,
        operation: ResolvedRestrictionOperation,
    ): Boolean =
        contribution.role == Role.SOURCE_INTENT &&
            contribution.rule != USER_CONTRIBUTOR_ID &&
            contribution.source != USER_SOURCE &&
            contribution.rule != AI_CONFIRMED_CONTRIBUTOR_ID &&
            operation.intent in contribution.intents &&
            contribution.startByte == operation.startByte &&
            contribution.endByte == operation.endByte &&
            DIRECTIVE_INTENTS.none { it in contribution.intents }
}
