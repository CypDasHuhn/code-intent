package dev.cint.suggest.provider

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.cint.api.CandidateEmit
import dev.cint.suggest.model.CandidateContext
import dev.cint.suggest.session.AgentRequest
import dev.cint.suggest.session.AgentTurn
import dev.cint.suggest.session.AnnotationSessionProvider
import dev.cint.suggest.session.ToolCall
import java.util.concurrent.atomic.AtomicInteger

const val FAKE_PROVIDER_ID: String = "fake"
const val FAKE_MODEL_ID: String = "fake-intent-v1"
const val FAKE_PROMPT_VERSION: String = "fake-prompt-v2"

class FakeIntentSuggestionProvider :
    IntentSuggestionProvider,
    AnnotationSessionProvider {
    override val id: String = FAKE_PROVIDER_ID
    override val model: String = FAKE_MODEL_ID
    override val defaultPromptVersion: String = FAKE_PROMPT_VERSION

    private val mapper = jacksonObjectMapper()
    private val calls = AtomicInteger(0)

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

    override fun complete(request: AgentRequest): AgentTurn {
        val user = request.messages.lastOrNull { it.role == "user" }?.content
        val units =
            user
                ?.let { content -> runCatching { mapper.readTree(content).path("units") }.getOrNull() }
                ?.takeIf { it.isArray }
                ?.mapNotNull { it.path("unitId").asText("").takeIf(String::isNotBlank) }
                .orEmpty()
        val verdicts = units.map { unit -> linkedMapOf("unitId" to unit, "verdict" to "none") }
        val arguments = mapper.writeValueAsString(linkedMapOf("verdicts" to verdicts))
        return AgentTurn.Calls(
            listOf(
                ToolCall(
                    id = "fake-${calls.incrementAndGet()}",
                    name = "submit_verdicts",
                    arguments = arguments,
                ),
            ),
        )
    }
}
