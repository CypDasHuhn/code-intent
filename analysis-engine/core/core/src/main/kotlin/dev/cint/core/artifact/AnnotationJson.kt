package dev.cint.core.artifact

import dev.cint.api.Annotation
import dev.cint.api.Contribution
import dev.cint.api.Role

object AnnotationJson {
    fun render(annotation: Annotation): String = Json.render(fields(annotation))

    private fun fields(annotation: Annotation): Map<String, Any?> =
        linkedMapOf(
            "role" to roleName(annotation.role),
            "intents" to annotation.intents.toList(),
            "source" to annotation.source,
            "confidence" to annotation.confidence,
            "startLine" to annotation.span.startLine,
            "startCol" to annotation.span.startCol,
            "endLine" to annotation.span.endLine,
            "endCol" to annotation.span.endCol,
            "startByte" to annotation.startByte,
            "endByte" to annotation.endByte,
            "nodeAnchor" to annotation.nodeAnchor?.id,
            "provenance" to annotation.provenance.map { contributionFields(it) },
        )

    private fun contributionFields(contribution: Contribution): Map<String, Any?> =
        linkedMapOf(
            "rule" to contribution.rule,
            "role" to roleName(contribution.role),
            "intents" to contribution.intents.toList(),
            "source" to contribution.source,
            "confidence" to contribution.confidence,
            "startLine" to contribution.span.startLine,
            "startCol" to contribution.span.startCol,
            "endLine" to contribution.span.endLine,
            "endCol" to contribution.span.endCol,
            "startByte" to contribution.startByte,
            "endByte" to contribution.endByte,
        ).apply {
            contribution.provider?.let { put("provider", it) }
            contribution.model?.let { put("model", it) }
            contribution.promptVersion?.let { put("promptVersion", it) }
        }

    fun roleName(role: Role): String =
        when (role) {
            Role.SOURCE_INTENT -> "source-intent"
            Role.DERIVATIVE_USE -> "derivative-use"
            Role.SCAFFOLD -> "scaffold"
        }
}
