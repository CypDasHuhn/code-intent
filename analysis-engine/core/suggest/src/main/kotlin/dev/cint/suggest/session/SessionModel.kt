package dev.cint.suggest.session

import dev.cint.api.CandidateEmit
import dev.cint.api.Span
import dev.cint.suggest.model.CandidateProposal

data class SessionLimits(
    val sessionTimeoutMs: Long = 120_000L,
    val requestTimeoutMs: Long = 10_000L,
    val maxTurns: Int = 50,
    val maxToolCalls: Int = 200,
    val maxUnitsPerRequest: Int = 24,
    val maxTokensPerRequest: Int = 2048,
    val maxCandidates: Int = 0,
    val maxConfidence: Double = 0.84,
)

data class SessionAnnotation(
    val intents: Set<String>,
    val startByte: Int,
    val endByte: Int,
    val span: Span,
    val source: String,
    val confidence: Double,
    val role: String,
    val provenance: List<String> = emptyList(),
)

data class SessionUnit(
    val unitId: String,
    val path: String,
    val language: String,
    val startByte: Int,
    val endByte: Int,
    val span: Span,
    val text: String,
    val parentUnitId: String? = null,
    val parentSpan: Span? = null,
    val enclosingStatement: Span,
    val enclosingFunction: Span? = null,
    val existingAnnotations: List<SessionAnnotation> = emptyList(),
)

data class SessionDocument(
    val path: String,
    val version: Int,
    val text: String,
    val language: String?,
    val units: List<SessionUnit>,
    val annotations: List<SessionAnnotation>,
    val knownIntents: Set<String>,
)

data class SessionAdd(
    val intent: String,
    val startByte: Int,
    val endByte: Int,
    val confidence: Double,
    val rationale: String?,
)

data class SessionRemove(
    val intent: String,
    val startByte: Int,
    val endByte: Int,
    val rationale: String?,
)

data class SessionVerdict(
    val unitId: String,
    val adds: List<SessionAdd> = emptyList(),
    val removes: List<SessionRemove> = emptyList(),
    val none: Boolean = false,
)

data class SessionProgress(
    val graded: Int,
    val total: Int,
    val reused: Int = 0,
    val activity: String? = null,
)

data class SessionEvent(
    val type: String,
    val message: String,
    val unitId: String? = null,
    val tool: String? = null,
)

data class SessionResult(
    val candidates: List<CandidateEmit>,
    val proposals: List<CandidateProposal> = emptyList(),
    val total: Int,
    val graded: Int,
    val reused: Int = 0,
    val additions: Int = 0,
    val toolCalls: Int = 0,
    val turns: Int = 0,
    val incomplete: Boolean = false,
    val budgetExhausted: Boolean = false,
    val skipped: Boolean = false,
    val uncovered: List<String> = emptyList(),
    val events: List<SessionEvent> = emptyList(),
)

data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: Map<String, Any?>,
)

data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

sealed interface AgentTurn {
    data class Calls(
        val calls: List<ToolCall>,
        val content: String? = null,
    ) : AgentTurn

    data class Message(
        val content: String?,
    ) : AgentTurn

    data class Failure(
        val reason: String,
    ) : AgentTurn
}

data class AgentMessage(
    val role: String,
    val content: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
)

data class AgentRequest(
    val messages: List<AgentMessage>,
    val tools: List<ToolDefinition>,
    val promptVersion: String,
    val maxTokens: Int,
    val timeoutMs: Long,
)

interface AnnotationSessionProvider {
    val id: String
    val model: String
    val defaultPromptVersion: String

    fun complete(request: AgentRequest): AgentTurn
}
