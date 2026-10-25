package dev.cint.knowledge

import dev.cint.contracts.knowledge.KnowledgeChange
import dev.cint.contracts.ports.KnowledgeStore
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory

internal fun applyKnowledgeChange(
    store: KnowledgeStore,
    change: KnowledgeChange,
    depth: Int = 0,
): ContractResult<Unit> {
    if (depth > MAX_CHANGE_DEPTH) {
        return ResultFactory.failure(
            ContractError.ValidationError("Knowledge change nesting is too deep", "changes"),
        )
    }
    return when (change) {
        is KnowledgeChange.SetFunctionAnnotation ->
            store.setFunctionAnnotation(change.annotation).discardValue()
        is KnowledgeChange.RemoveFunctionAnnotation ->
            store.removeFunctionAnnotation(change.selector)
        is KnowledgeChange.SetSpanOverride ->
            store.setSpanOverride(change.override).discardValue()
        is KnowledgeChange.RemoveSpanOverride ->
            store.removeSpanOverride(change.selector)
        is KnowledgeChange.SetPatternHypothesis ->
            store.setPatternHypothesis(change.hypothesis).discardValue()
        is KnowledgeChange.RemovePatternHypothesis ->
            store.removePatternHypothesis(change.selector)
        is KnowledgeChange.MaterializeDefaults ->
            store.materializeDefaults(change.defaults)
        is KnowledgeChange.Batch -> {
            for (nested in change.changes) {
                val result = applyKnowledgeChange(store, nested, depth + 1)
                if (result.isFailure) {
                    return result
                }
            }
            ResultFactory.success(Unit)
        }
    }
}

private fun ContractResult<*>.discardValue(): ContractResult<Unit> =
    if (isSuccess) {
        ResultFactory.success(Unit)
    } else {
        ResultFactory.failure(
            errorOrNull ?: ContractError.InternalError("Knowledge mutation failed without an error"),
        )
    }

private const val MAX_CHANGE_DEPTH = 16
