package dev.cint.suggest.provider

import dev.cint.api.CandidateEmit
import dev.cint.suggest.model.CandidateContext

interface IntentSuggestionProvider {
    val id: String
    val model: String
    val defaultPromptVersion: String get() = FAKE_PROMPT_VERSION

    fun suggest(context: CandidateContext): List<CandidateEmit>
}
