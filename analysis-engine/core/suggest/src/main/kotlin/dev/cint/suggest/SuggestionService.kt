package dev.cint.suggest

import dev.cint.api.CandidateEmit
import dev.cint.api.CandidateReason
import dev.cint.core.candidate.CandidateIdentity
import dev.cint.suggest.model.CandidateContext
import dev.cint.suggest.model.CandidateDocument
import dev.cint.suggest.model.CandidateGroup
import dev.cint.suggest.model.SourceExcerpt
import dev.cint.suggest.model.SuggestionOptions
import dev.cint.suggest.provider.FakeIntentSuggestionProvider
import dev.cint.suggest.provider.IntentSuggestionProvider

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
        if (!SuggestionPrivacy.allows(document.path, options.allowGlobs)) return candidates
        val documentLength = document.text.toByteArray(Charsets.UTF_8).size
        val promptVersion = options.promptVersion ?: provider.defaultPromptVersion
        val merged = linkedMapOf<String, CandidateEmit>()
        for (candidate in candidates) {
            merged[candidateId(document.path, document.text, candidate)] = candidate
        }
        val eligible =
            candidates
                .filter { it.aiEligible }
                .sortedWith(compareBy({ reasonRank(it.reason) }, { it.startByte }, { it.endByte }))
        val enrichable =
            if (options.maxEnrichmentCandidates > 0) {
                eligible.take(options.maxEnrichmentCandidates)
            } else {
                eligible
            }
        for (candidate in enrichable) {
            val excerpt =
                SourceExcerpt
                    .around(
                        document.text,
                        candidate.startByte,
                        candidate.endByte,
                        options.excerptRadiusBytes,
                    ).let { source ->
                        source.copy(
                            text = SuggestionPrivacy.redact(source.text, options.redactPatterns),
                        )
                    }
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
                            language = document.language,
                            sourceExcerpt = excerpt,
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

    fun findProvider(id: String?): IntentSuggestionProvider? = registry.find(id)

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

    private fun reasonRank(reason: CandidateReason): Int =
        when (reason) {
            CandidateReason.GAP -> 0
            CandidateReason.LOW_CONFIDENCE -> 1
            CandidateReason.PACK_DOMAIN -> 2
        }
}
