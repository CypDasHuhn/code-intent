package dev.cint.core.artifact

import dev.cint.api.CandidateEmit

object CandidateJson {
    fun render(candidate: CandidateEmit): String = Json.render(fields(candidate))

    private fun fields(candidate: CandidateEmit): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "intents" to candidate.intents.toList(),
            "source" to candidate.source,
            "confidence" to candidate.confidence,
            "startLine" to candidate.span.startLine,
            "startCol" to candidate.span.startCol,
            "endLine" to candidate.span.endLine,
            "endCol" to candidate.span.endCol,
            "startByte" to candidate.startByte,
            "endByte" to candidate.endByte,
            "reason" to candidate.reason.name,
            "aiEligible" to candidate.aiEligible,
        ).apply {
            candidate.rationale?.let { put("rationale", it) }
            candidate.promptVersion?.let { put("promptVersion", it) }
            candidate.provider?.let { put("provider", it) }
            candidate.model?.let { put("model", it) }
        }
}
