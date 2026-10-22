package dev.cint.suggest.provider

import dev.cint.api.CandidateEmit
import dev.cint.suggest.model.CandidateContext

const val FAKE_PROVIDER_ID: String = "fake"
const val FAKE_MODEL_ID: String = "fake-intent-v1"
const val FAKE_PROMPT_VERSION: String = "fake-prompt-v1"

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
                promptVersion = context.promptVersion ?: defaultPromptVersion,
                provider = id,
                model = model,
            ),
        )
    }
}
