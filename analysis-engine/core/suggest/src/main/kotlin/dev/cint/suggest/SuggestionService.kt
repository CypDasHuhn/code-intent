package dev.cint.suggest

import dev.cint.api.CandidateEmit
import dev.cint.core.candidate.CandidateIdentity

data class CandidateDocument(
    val path: String,
    val version: Int,
    val text: String,
    val knownIntents: Set<String>,
    val neighboringIntents: Set<String> = emptySet(),
)

data class SuggestionOptions(
    val providerId: String? = null,
    val model: String? = null,
    val maxCandidates: Int = 5,
    val promptVersion: String? = null,
)

class SuggestionProviderRegistry(
    providers: List<IntentSuggestionProvider> = listOf(FakeIntentSuggestionProvider()),
) {
    private val providers = providers.associateBy { it.id }

    fun find(id: String?): IntentSuggestionProvider? = id?.let(providers::get)
}

class SuggestionService(
    private val registry: SuggestionProviderRegistry = SuggestionProviderRegistry(),
) {
    fun enrich(
        candidates: List<CandidateEmit>,
        document: CandidateDocument,
        options: SuggestionOptions,
    ): List<CandidateEmit> {
        val provider = registry.find(options.providerId) ?: return candidates
        val documentLength = document.text.toByteArray(Charsets.UTF_8).size
        val promptVersion = options.promptVersion ?: FAKE_PROMPT_VERSION
        val merged = linkedMapOf<String, CandidateEmit>()
        for (candidate in candidates) {
            merged[candidateId(document.path, document.text, candidate)] = candidate
        }
        for (candidate in candidates) {
            if (!candidate.aiEligible) continue
            val proposals =
                runCatching {
                    provider.suggest(
                        CandidateContext(
                            path = document.path,
                            version = document.version,
                            candidate = candidate,
                            documentLength = documentLength,
                            neighboringIntents = document.neighboringIntents,
                            knownIntents = document.knownIntents,
                            promptVersion = promptVersion,
                        ),
                    )
                }.getOrDefault(emptyList())
            val validated =
                CandidateValidator.validate(
                    input = candidate,
                    candidates = proposals,
                    documentLength = documentLength,
                    knownIntents = document.knownIntents,
                    maxCandidates = options.maxCandidates,
                )
            for (proposal in validated) {
                merged[candidateId(document.path, document.text, proposal)] = proposal
            }
        }
        return merged.values.sortedWith(
            compareBy<CandidateEmit>(
                { it.startByte },
                { it.endByte },
                { it.intents.sorted().joinToString(",") },
                { it.reason.name },
                { it.source },
            ),
        )
    }

    fun group(
        document: CandidateDocument,
        candidates: List<CandidateEmit>,
    ): List<CandidateGroup> = CandidateGrouping.group(document.path, document.text, candidates)

    fun anchorHash(
        text: String,
        candidate: CandidateEmit,
    ): String = CandidateGrouping.anchorHash(text, candidate)

    private fun candidateId(
        path: String,
        text: String,
        candidate: CandidateEmit,
    ): String =
        CandidateIdentity.of(
            path,
            CandidateGrouping.anchorHash(text, candidate),
            candidate.startByte,
            candidate.endByte,
            candidate.intents,
            candidate.reason.name,
        )
}
