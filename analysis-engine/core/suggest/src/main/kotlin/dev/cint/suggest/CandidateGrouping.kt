package dev.cint.suggest

import dev.cint.api.CandidateEmit
import dev.cint.core.candidate.CandidateIdentity
import dev.cint.core.user.UserAnnotationResolver
import dev.cint.suggest.model.CandidateGroup

object CandidateGrouping {
    fun group(
        path: String,
        text: String,
        candidates: List<CandidateEmit>,
    ): List<CandidateGroup> =
        candidates
            .groupBy { candidate ->
                val contentHash = anchorHash(text, candidate)
                Triple(contentHash, candidate.startByte, candidate.endByte)
            }.map { (key, grouped) ->
                val (contentHash, startByte, endByte) = key
                val first = grouped.first()
                CandidateGroup(
                    anchorId = CandidateIdentity.of(path, contentHash, startByte, endByte, emptySet(), "ANCHOR"),
                    path = path,
                    contentHash = contentHash,
                    span = first.span,
                    startByte = startByte,
                    endByte = endByte,
                    candidates = grouped,
                )
            }.sortedWith(compareBy({ it.startByte }, { it.endByte }, { it.contentHash }))

    fun anchorHash(
        text: String,
        candidate: CandidateEmit,
    ): String =
        UserAnnotationResolver
            .selectionText(text, candidate.span)
            ?.let(CandidateIdentity::contentHash)
            ?: CandidateIdentity.contentHash("")
}
