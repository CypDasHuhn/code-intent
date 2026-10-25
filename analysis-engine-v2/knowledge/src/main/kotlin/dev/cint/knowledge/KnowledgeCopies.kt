package dev.cint.knowledge

import dev.cint.contracts.knowledge.FunctionAnnotation
import dev.cint.contracts.knowledge.PatternHypothesis
import dev.cint.contracts.knowledge.SpanOverride
import dev.cint.contracts.util.ContractError
import kotlinx.datetime.Instant

internal fun FunctionAnnotation.defensiveCopy(): FunctionAnnotation =
    copy(intents = intents.toSet())

internal fun SpanOverride.defensiveCopy(): SpanOverride =
    copy(intents = intents.toSet())

internal fun PatternHypothesis.defensiveCopy(): PatternHypothesis =
    copy(
        evidence = evidence.toList(),
        examples = examples.toList(),
        counterexamples = counterexamples.toList(),
        proposedIntents = proposedIntents.toSet(),
    )

internal fun latestInstant(vararg instants: Instant?): Instant =
    instants.filterNotNull().max()

internal fun staleWrite(resource: String): ContractError =
    ContractError.ValidationError(
        "Refusing to replace $resource with an older revision",
        "updatedAt",
    )
