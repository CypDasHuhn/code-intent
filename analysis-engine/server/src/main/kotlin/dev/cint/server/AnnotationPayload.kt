package dev.cint.server

import dev.cint.api.Annotation
import dev.cint.api.Contribution
import dev.cint.api.Role

internal fun annotationJson(annotation: Annotation): Map<String, Any?> =
    mapOf(
        "role" to roleName(annotation.role),
        "intents" to annotation.intents,
        "rule" to annotation.rule,
        "evidence" to annotation.evidence,
        "source" to annotation.source,
        "confidence" to annotation.confidence,
        "startLine" to annotation.span.startLine,
        "startCol" to annotation.span.startCol,
        "endLine" to annotation.span.endLine,
        "endCol" to annotation.span.endCol,
        "startByte" to annotation.startByte,
        "endByte" to annotation.endByte,
        "provenance" to annotation.provenance.map(::contributionJson),
    )

internal fun contributionJson(contribution: Contribution): Map<String, Any?> =
    mapOf(
        "rule" to contribution.rule,
        "role" to roleName(contribution.role),
        "intents" to contribution.intents,
        "evidence" to contribution.evidence,
        "source" to contribution.source,
        "confidence" to contribution.confidence,
        "startLine" to contribution.span.startLine,
        "startCol" to contribution.span.startCol,
        "endLine" to contribution.span.endLine,
        "endCol" to contribution.span.endCol,
        "startByte" to contribution.startByte,
        "endByte" to contribution.endByte,
    )

private fun roleName(role: Role): String =
    when (role) {
        Role.SOURCE_INTENT -> "source-intent"
        Role.DERIVATIVE_USE -> "derivative-use"
        Role.SCAFFOLD -> "scaffold"
    }
