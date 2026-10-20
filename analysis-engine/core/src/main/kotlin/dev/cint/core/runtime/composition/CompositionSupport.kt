package dev.cint.core.runtime.composition

import dev.cint.api.Contribution
import dev.cint.api.Role
import dev.cint.api.Span
import dev.cint.api.SyntaxNode
import dev.cint.core.CoreContributors

internal const val COMPOSITION_KEY_PREFIX = "\u0000composition:"
internal const val RETURN_KEY = "${COMPOSITION_KEY_PREFIX}return"
internal const val DERIVATIVE_KEY = "${COMPOSITION_KEY_PREFIX}derivative"
internal const val SCAFFOLD_KEY = "${COMPOSITION_KEY_PREFIX}scaffold"
internal const val SOURCE_KEY_PREFIX = "${COMPOSITION_KEY_PREFIX}source:"

internal const val RETURN_RULE = CoreContributors.RETURN_CATEGORY
internal const val DERIVATIVE_RULE = CoreContributors.DERIVATIVE_USE
internal const val SCAFFOLD_RULE = CoreContributors.SCAFFOLD

internal val rejectionSentinels =
    setOf("null", "undefined", "false", "0", "-1", "\"\"", "''", "[]", "{}")

internal fun SyntaxNode.raw(
    role: Role,
    intents: Set<String>,
    rule: String,
    source: String,
    confidence: Double,
): Contribution =
    Contribution(
        rule = rule,
        role = role,
        intents = intents,
        span = span(),
        source = source,
        confidence = confidence,
        startByte = startByte,
        endByte = endByte,
    )

internal fun tagRange(
    start: SyntaxNode,
    end: SyntaxNode,
    intents: Set<String>,
    rule: String,
    source: String,
    confidence: Double,
    role: Role = Role.SCAFFOLD,
): Contribution =
    Contribution(
        rule = rule,
        role = role,
        intents = intents,
        span = Span(start.startLine, start.startCol, end.endLine, end.endCol),
        source = source,
        confidence = confidence,
        startByte = start.startByte,
        endByte = end.endByte,
    )
