package dev.cint.suggest

import dev.cint.api.CandidateEmit

const val FAKE_PROVIDER_ID: String = "fake"
const val FAKE_MODEL_ID: String = "fake-intent-v1"
const val FAKE_PROMPT_VERSION: String = "fake-prompt-v1"

data class CandidateContext(
    val path: String,
    val version: Int,
    val candidate: CandidateEmit,
    val documentLength: Int,
    val enclosingFunction: String? = null,
    val neighboringIntents: Set<String> = emptySet(),
    val knownIntents: Set<String> = emptySet(),
    val promptVersion: String = FAKE_PROMPT_VERSION,
)

interface IntentSuggestionProvider {
    val id: String
    val model: String

    fun suggest(context: CandidateContext): List<CandidateEmit>
}

class FakeIntentSuggestionProvider : IntentSuggestionProvider {
    override val id: String = FAKE_PROVIDER_ID
    override val model: String = FAKE_MODEL_ID

    override fun suggest(context: CandidateContext): List<CandidateEmit> {
        val candidate = context.candidate
        if (!candidate.aiEligible || candidate.intents.isEmpty()) return emptyList()
        return listOf(
            candidate.copy(
                source = "ai:$id",
                confidence = maxOf(candidate.confidence, 0.75),
                aiEligible = false,
                rationale = "Fake provider enriched ${candidate.reason.name.lowercase()} candidate.",
                promptVersion = context.promptVersion,
                provider = id,
                model = model,
            ),
        )
    }
}
