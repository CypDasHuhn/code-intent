package dev.cint.ipc

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

object IpcMethods {
    const val INITIALIZE = "initialize"
    const val SHUTDOWN = "shutdown"
    const val ANALYZE = "cint/analyze"
    const val PUBLISH_TAGS = "cint/publishTags"
    const val PUBLISH_CANDIDATES = "cint/publishCandidates"
    const val SUGGEST = "cint/suggest"
    const val SUGGEST_CANCEL = "cint/suggest/cancel"
    const val SUGGEST_LOG = "cint/suggest/log"
    const val SUGGEST_STREAM = "cint/suggest/stream"
    const val SUGGEST_HISTORY = "cint/suggest/history"
    const val CONFIG_READ = "cint/config/read"
    const val CONFIG_APPEND = "cint/config/append"
    const val CONFIG_REMOVE = "cint/config/remove"
    const val CONFIG_WRITE = "cint/config/write"
    const val CONFIG_SCHEMA = "cint/config/schema"
    const val STATUS = "cint/status"
    const val METRICS = "cint/metrics"
    const val RESTRICTIONS = "cint/restrictions"
    const val RESTRICTIONS_LIST = "cint/restrictions/list"
    const val RESTRICTIONS_REMOVE = "cint/restrictions/remove"
    const val RESTRICTIONS_UPSERT = "cint/restrictions/upsert"
    const val CANDIDATES_DECIDE = "cint/candidates/decide"
    const val DID_OPEN = "textDocument/didOpen"
    const val DID_CHANGE = "textDocument/didChange"
    const val DID_CLOSE = "textDocument/didClose"

    val all: Set<String> =
        setOf(
            INITIALIZE,
            SHUTDOWN,
            ANALYZE,
            SUGGEST,
            SUGGEST_CANCEL,
            SUGGEST_LOG,
            SUGGEST_STREAM,
            SUGGEST_HISTORY,
            CONFIG_READ,
            CONFIG_APPEND,
            CONFIG_REMOVE,
            CONFIG_WRITE,
            CONFIG_SCHEMA,
            STATUS,
            METRICS,
            RESTRICTIONS,
            RESTRICTIONS_LIST,
            RESTRICTIONS_REMOVE,
            RESTRICTIONS_UPSERT,
            CANDIDATES_DECIDE,
            DID_OPEN,
            DID_CHANGE,
            DID_CLOSE,
        )
}

@Serializable
data class IpcSpan(
    val startLine: Int,
    val startCol: Int,
    val endLine: Int,
    val endCol: Int,
    val startByte: Int,
    val endByte: Int,
) {
    init {
        require(startLine >= 0 && startCol >= 0 && endLine >= 0 && endCol >= 0) { "span coordinates must be non-negative" }
        require(startByte >= 0 && endByte >= 0) { "span offsets must be non-negative" }
        require(startLine < endLine || (startLine == endLine && startCol <= endCol)) {
            "span start must not be after span end"
        }
        require(startByte <= endByte) { "span start byte must not be after end byte" }
    }
}

@Serializable
enum class IpcSeverity {
    @SerialName("error")
    ERROR,

    @SerialName("warning")
    WARNING,

    @SerialName("info")
    INFO,
}

@Serializable
data class IpcDiagnostic(
    val code: String,
    val message: String,
    val severity: IpcSeverity = IpcSeverity.ERROR,
    val source: String? = null,
    val span: IpcSpan? = null,
)

@Serializable
data class IpcProvenance(
    val ruleId: String,
    val packId: String? = null,
    val role: String,
    val intents: List<String>,
    val source: String,
    val confidence: Double,
    val startLine: Int,
    val startCol: Int,
    val endLine: Int,
    val endCol: Int,
    val startByte: Int,
    val endByte: Int,
    val evidence: JsonElement? = null,
) {
    init {
        require(ruleId.isNotBlank()) { "provenance ruleId must not be blank" }
        require(confidence.isFinite() && confidence in 0.0..1.0) { "provenance confidence must be between 0 and 1" }
        IpcSpan(startLine, startCol, endLine, endCol, startByte, endByte)
    }

    val span: IpcSpan
        get() = IpcSpan(startLine, startCol, endLine, endCol, startByte, endByte)
}

@Serializable
data class IpcAnnotation(
    val role: String,
    val intents: List<String>,
    val source: String,
    val confidence: Double,
    val startLine: Int,
    val startCol: Int,
    val endLine: Int,
    val endCol: Int,
    val startByte: Int,
    val endByte: Int,
    val nodeAnchor: String? = null,
    val provenance: List<IpcProvenance> = emptyList(),
    val diagnostics: List<IpcDiagnostic> = emptyList(),
) {
    init {
        require(role.isNotBlank()) { "annotation role must not be blank" }
        require(source.isNotBlank()) { "annotation source must not be blank" }
        require(confidence.isFinite() && confidence in 0.0..1.0) { "annotation confidence must be between 0 and 1" }
        IpcSpan(startLine, startCol, endLine, endCol, startByte, endByte)
    }

    val span: IpcSpan
        get() = IpcSpan(startLine, startCol, endLine, endCol, startByte, endByte)
}

@Serializable
data class CandidateOrigin(
    val type: String,
    val ruleId: String? = null,
    val provider: String? = null,
    val model: String? = null,
    val promptVersion: String? = null,
) {
    init {
        require(type in setOf("rule", "ai", "heuristic")) { "candidate origin type must be rule, ai, or heuristic" }
    }
}

@Serializable
enum class CandidateOperationKind {
    ADD,
    REMOVE,
    REPLACE,
}

@Serializable
data class CandidateOperation(
    val kind: CandidateOperationKind,
    val intents: List<String> = emptyList(),
    val startLine: Int,
    val startCol: Int,
    val endLine: Int,
    val endCol: Int,
    val startByte: Int,
    val endByte: Int,
    val confidence: Double,
    val origin: CandidateOrigin? = null,
    val intent: String? = null,
    val source: String? = null,
    val rationale: String? = null,
) {
    init {
        require(intents.isNotEmpty() || intent != null) { "operation requires intents or legacy intent" }
        require(confidence.isFinite() && confidence in 0.0..1.0) { "operation confidence must be between 0 and 1" }
        IpcSpan(startLine, startCol, endLine, endCol, startByte, endByte)
    }

    val span: IpcSpan
        get() = IpcSpan(startLine, startCol, endLine, endCol, startByte, endByte)
}

@Serializable
data class IpcCandidate(
    val candidateId: String,
    val path: String,
    val contentHash: String,
    val intents: List<String>,
    val startLine: Int,
    val startCol: Int,
    val endLine: Int,
    val endCol: Int,
    val startByte: Int,
    val endByte: Int,
    val confidence: Double,
    val origin: CandidateOrigin,
    val category: String,
    val fingerprints: Map<String, String> = emptyMap(),
    val operations: List<CandidateOperation> = emptyList(),
) {
    init {
        require(candidateId.isNotBlank()) { "candidateId must not be blank" }
        require(path.isNotBlank()) { "candidate path must not be blank" }
        require(category.isNotBlank()) { "candidate category must not be blank" }
        require(confidence.isFinite() && confidence in 0.0..1.0) { "candidate confidence must be between 0 and 1" }
        IpcSpan(startLine, startCol, endLine, endCol, startByte, endByte)
    }

    val span: IpcSpan
        get() = IpcSpan(startLine, startCol, endLine, endCol, startByte, endByte)
}

@Serializable
data class PublishTagsParams(
    val uri: String,
    val version: Int,
    val hash: String,
    val configRevision: Long,
    val analysisVersion: Long,
    val annotations: List<IpcAnnotation>,
    val diagnostics: List<IpcDiagnostic> = emptyList(),
)

@Serializable
data class PublishCandidatesParams(
    val uri: String,
    val version: Int,
    val analysisRevision: Long,
    val proposalRevision: Long,
    val proposals: List<IpcCandidate>,
)

@Serializable
data class AnalyzeParams(
    val uri: String? = null,
    val targetPaths: List<String> = emptyList(),
    val language: String? = null,
    val options: Map<String, String> = emptyMap(),
    val forceFullAnalysis: Boolean = false,
) {
    init {
        require(!uri.isNullOrBlank() || targetPaths.isNotEmpty()) { "analyze requires uri or targetPaths" }
    }
}

@Serializable
data class AnalyzedFile(
    val path: String,
    val hash: String? = null,
    val annotations: List<IpcAnnotation> = emptyList(),
    val diagnostics: List<IpcDiagnostic> = emptyList(),
)

@Serializable
data class AnalyzeResult(
    val files: List<AnalyzedFile> = emptyList(),
    val candidates: List<IpcCandidate> = emptyList(),
    val diagnostics: List<IpcDiagnostic> = emptyList(),
)

@Serializable
data class SuggestParams(
    val uri: String? = null,
    val selection: IpcSpan? = null,
    val version: Int? = null,
    val context: String = "",
    val requestId: String? = null,
) {
    init {
        require(!uri.isNullOrBlank() || selection != null) { "suggest requires uri or selection" }
    }
}

@Serializable
data class SuggestSelection(
    val uri: String,
    val selection: IpcSpan,
)

@Serializable
data class SuggestCancelParams(
    val requestId: String,
    val uri: String? = null,
)

@Serializable
data class SuggestLogParams(
    val uri: String,
)

@Serializable
data class SuggestItem(
    val span: IpcSpan,
    val intents: List<String>,
    val confidence: Double,
    val rationale: String,
)

@Serializable
data class SuggestResult(
    val accepted: Boolean = false,
    val disabled: Boolean = false,
    val cancelled: Boolean = false,
    val count: Int = 0,
    val restrictions: Int = 0,
    val suggestions: List<SuggestItem> = emptyList(),
)

@Serializable
data class SuggestProgress(
    val requestId: String,
    val completed: Int,
    val total: Int,
    val suggestions: List<SuggestItem> = emptyList(),
)

@Serializable
data class SuggestHistoryParams(
    val uri: String,
    val limit: Int = 20,
)

@Serializable
enum class ConfigOperation {
    READ,
    APPEND,
    REMOVE,
    WRITE,
    SCHEMA,
}

@Serializable
data class ConfigParams(
    val packId: String? = null,
    val language: String? = null,
    val field: String? = null,
    val value: String? = null,
    val config: JsonObject? = null,
    val sources: Boolean? = null,
)

@Serializable
data class ConfigResult(
    val config: JsonObject = JsonObject(emptyMap()),
    val sources: List<String> = emptyList(),
    val schema: JsonObject? = null,
)

@Serializable
data class StatusResult(
    val status: String = "ok",
    val version: String,
    val uptimeMs: Long = 0,
    val activeDocuments: Int = 0,
    val suggestionQueue: Int = 0,
)

@Serializable
data class MetricsResult(
    val text: String,
)

@Serializable
data class DocumentParams(
    val uri: String,
    val text: String,
    val version: Int,
    val languageId: String,
)

@Serializable
data class DocumentCloseParams(
    val uri: String,
)

@Serializable
data class RestrictionsListParams(
    val uri: String? = null,
    val scope: String? = null,
)

@Serializable
data class RestrictionRemoveParams(
    val id: String,
)

@Serializable
data class RestrictionOperation(
    val intent: String,
    val confidence: Double,
    val rationale: String? = null,
    val startOffset: Int = 0,
    val endOffset: Int = 0,
) {
    init {
        require(intent.isNotBlank()) { "restriction operation intent must not be blank" }
        require(confidence.isFinite() && confidence in 0.0..1.0) { "restriction operation confidence must be between 0 and 1" }
        require(startOffset >= 0 && endOffset >= startOffset) { "restriction operation offsets are invalid" }
    }
}

@Serializable
data class Restriction(
    val id: String,
    val path: String,
    val startLine: Int,
    val startCol: Int,
    val endLine: Int,
    val endCol: Int,
    val contentHash: String? = null,
    val status: String? = null,
    val symbol: String? = null,
    val unitFingerprint: String? = null,
    val provider: String? = null,
    val model: String? = null,
    val promptVersion: String? = null,
    val createdAt: String? = null,
    val add: List<RestrictionOperation> = emptyList(),
    val remove: List<RestrictionOperation> = emptyList(),
    val scope: String? = null,
    val condition: JsonObject? = null,
) {
    init {
        require(id.isNotBlank()) { "restriction id must not be blank" }
        require(path.isNotBlank()) { "restriction path must not be blank" }
        IpcSpan(startLine, startCol, endLine, endCol, 0, 0)
    }
}

@Serializable
data class RestrictionsListResult(
    val restrictions: List<Restriction> = emptyList(),
)

@Serializable
data class RestrictionRemoveResult(
    val accepted: Boolean,
    val removed: Boolean,
)

@Serializable
data class RestrictionUpsertParams(
    val restriction: Restriction,
)

@Serializable
data class CandidateDecisionParams(
    val candidateId: String,
    val decision: String,
    val uri: String? = null,
    val reason: String? = null,
)

@Serializable
data object NoParams

@Serializable
data class ClientInfo(
    val name: String,
    val version: String,
    val capabilities: List<String> = emptyList(),
)

@Serializable
data class ServerInfo(
    val name: String = "code-intent",
    val version: String = "0.1.0",
    val capabilities: List<String> = emptyList(),
)

@Serializable
data class InitializeParams(
    val protocol: Int = ProtocolFingerprint.LEGACY_PROTOCOL_VERSION,
    val fingerprint: String? = null,
    val client: ClientInfo? = null,
    val capabilities: List<String> = emptyList(),
)

@Serializable
data class InitializeResult(
    val protocol: Int = ProtocolFingerprint.CURRENT_PROTOCOL_VERSION,
    val fingerprint: String = ProtocolFingerprint.CURRENT,
    val root: String? = null,
    val languages: List<String> = emptyList(),
    val config: JsonObject? = null,
    val suggestions: JsonObject? = null,
    val server: ServerInfo = ServerInfo(),
    val capabilities: JsonObject? = null,
)

object IpcJson {
    val json: kotlinx.serialization.json.Json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = false
        isLenient = false
        allowSpecialFloatingPointValues = false
        encodeDefaults = true
        explicitNulls = true
    }

    val legacyJson: kotlinx.serialization.json.Json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = false
        isLenient = false
        allowSpecialFloatingPointValues = false
        encodeDefaults = false
        explicitNulls = false
    }

    inline fun <reified T> decode(params: JsonObject): T =
        try {
            json.decodeFromJsonElement<T>(params)
        } catch (error: Exception) {
            throw IpcErrors.invalidParams("invalid params for ${T::class.simpleName}: ${error.message ?: "decode failure"}")
        }

    inline fun <reified T> encode(value: T): JsonElement =
        json.encodeToJsonElement<T>(value)

    inline fun <reified T> encodeLegacy(value: T): JsonElement =
        legacyJson.encodeToJsonElement<T>(value)
}
