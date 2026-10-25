package dev.cint.knowledge

import dev.cint.contracts.data.Intent
import dev.cint.contracts.data.Intents
import dev.cint.contracts.data.Provenance
import dev.cint.contracts.knowledge.FunctionAnnotation
import dev.cint.contracts.knowledge.KnowledgeSnapshot
import dev.cint.contracts.knowledge.PackDefault
import dev.cint.contracts.knowledge.PatternHypothesis
import dev.cint.contracts.knowledge.SpanOverride
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractError
import kotlinx.datetime.Instant

internal fun cancellationError(cancellation: CancellationToken): ContractError? = cancellation.throwIfCancelled().errorOrNull

internal fun validateFunctionAnnotation(annotation: FunctionAnnotation): ContractError? =
    validateIntents(annotation.intents)
        ?: validateScopePath(annotation)
        ?: validateTimestamps(annotation.createdAt, annotation.updatedAt)

private fun validateScopePath(annotation: FunctionAnnotation): ContractError? =
    if (annotation.scope == dev.cint.contracts.knowledge.AnnotationScope.FILE &&
        annotation.path.isNullOrBlank()
    ) {
        ContractError.ValidationError("FILE-scoped annotations require a path", "path")
    } else {
        null
    }

internal fun validateSpanOverride(override: SpanOverride): ContractError? =
    validateIntents(override.intents)
        ?: validateTimestamps(override.createdAt, override.updatedAt)

internal fun validatePatternHypothesis(hypothesis: PatternHypothesis): ContractError? =
    validateIntents(hypothesis.proposedIntents, requireNonEmpty = false)
        ?: validateTimestamps(hypothesis.createdAt, hypothesis.updatedAt)

internal fun validatePackDefault(default: PackDefault): ContractError? {
    if (default.packId.isBlank()) {
        return ContractError.ValidationError("PackDefault.packId must not be blank", "packId")
    }
    if (default.packVersion.isBlank()) {
        return ContractError.ValidationError("PackDefault.packVersion must not be blank", "packVersion")
    }
    return validateIntents(default.intents)
}

internal fun validateSnapshot(snapshot: KnowledgeSnapshot): ContractError? {
    snapshot.functionAnnotations.forEach { annotation ->
        validateFunctionAnnotation(annotation)?.let { return it }
    }
    val duplicateFunctions =
        snapshot.functionAnnotations
            .groupingBy { it.identityKey }
            .eachCount()
            .filterValues { it > 1 }
    if (duplicateFunctions.isNotEmpty()) {
        return ContractError.ValidationError(
            "Duplicate function selectors: ${duplicateFunctions.keys.sorted().joinToString(", ")}",
            "functionAnnotations",
        )
    }
    snapshot.spanOverrides.forEach { override ->
        validateSpanOverride(override)?.let { return it }
    }
    val duplicateSpans =
        snapshot.spanOverrides
            .groupingBy { it.selector.identityKey }
            .eachCount()
            .filterValues { it > 1 }
    if (duplicateSpans.isNotEmpty()) {
        return ContractError.ValidationError(
            "Duplicate span selectors: ${duplicateSpans.keys.sorted().joinToString(", ")}",
            "spanOverrides",
        )
    }
    snapshot.patternHypotheses.forEach { hypothesis ->
        validatePatternHypothesis(hypothesis)?.let { return it }
    }
    val duplicatePatterns =
        snapshot.patternHypotheses
            .groupingBy { it.selector.identityKey }
            .eachCount()
            .filterValues { it > 1 }
    if (duplicatePatterns.isNotEmpty()) {
        return ContractError.ValidationError(
            "Duplicate pattern selectors: ${duplicatePatterns.keys.sorted().joinToString(", ")}",
            "patternHypotheses",
        )
    }

    snapshot.packOrigins.forEach { (selectorKey, _) ->
        val annotation =
            snapshot.functionAnnotations.firstOrNull {
                it.selector.identityKey == selectorKey && it.provenance == Provenance.PACK_DEFAULT
            }
                ?: return ContractError.ValidationError(
                    "Pack origin references an unknown pack-default selector: $selectorKey",
                    "packOrigins",
                )
        if (annotation.scope == dev.cint.contracts.knowledge.AnnotationScope.FILE) {
            return ContractError.ValidationError(
                "Pack origin cannot reference a FILE-scoped annotation: $selectorKey",
                "packOrigins",
            )
        }
    }
    return null
}

private fun validateIntents(
    intents: Set<Intent>,
    requireNonEmpty: Boolean = true,
): ContractError? {
    if (requireNonEmpty && intents.isEmpty()) {
        return ContractError.ValidationError("At least one intent is required", "intents")
    }
    val unknown = intents.filterNot(Intents::isKnown).sorted()
    if (unknown.isNotEmpty()) {
        return ContractError.ValidationError(
            "Unknown intent(s): ${unknown.joinToString(", ")}",
            "intents",
        )
    }
    return null
}

private fun validateTimestamps(
    createdAt: Instant,
    updatedAt: Instant,
): ContractError? =
    if (createdAt > updatedAt) {
        ContractError.ValidationError("createdAt must not be after updatedAt", "timestamps")
    } else {
        null
    }
