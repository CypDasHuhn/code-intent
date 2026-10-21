package dev.cint.suggest

import dev.cint.api.CandidateEmit

object CandidateValidator {
    fun validate(
        input: CandidateEmit,
        candidates: List<CandidateEmit>,
        documentLength: Int,
        knownIntents: Set<String>,
        maxCandidates: Int = 5,
    ): List<CandidateEmit> {
        if (maxCandidates <= 0) return emptyList()
        return candidates
            .filter { candidate ->
                candidate.intents.isNotEmpty() &&
                    candidate.intents.all { it in knownIntents } &&
                    candidate.confidence in 0.0..1.0 &&
                    candidate.startByte >= 0 &&
                    candidate.endByte >= candidate.startByte &&
                    candidate.endByte <= documentLength &&
                    candidate.startByte >= input.startByte &&
                    candidate.endByte <= input.endByte
            }.distinctBy(::identity)
            .take(maxCandidates)
    }

    fun valid(
        candidate: CandidateEmit,
        knownIntents: Set<String>,
        maxCandidates: Int = 5,
    ): Boolean =
        maxCandidates > 0 &&
            validate(
                input = candidate,
                candidates = listOf(candidate),
                documentLength = candidate.endByte,
                knownIntents = knownIntents,
                maxCandidates = maxCandidates,
            ).size == 1

    private fun identity(candidate: CandidateEmit): String =
        listOf(
            candidate.startByte,
            candidate.endByte,
            candidate.intents.sorted().joinToString(","),
            candidate.reason.name,
            candidate.source,
            candidate.confidence,
        ).joinToString("|")
}
