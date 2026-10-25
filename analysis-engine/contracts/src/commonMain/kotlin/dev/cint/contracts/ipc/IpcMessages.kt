package dev.cint.contracts.ipc

import dev.cint.contracts.data.AnalysisResult
import dev.cint.contracts.data.Completeness
import dev.cint.contracts.facts.SourceInput
import dev.cint.contracts.knowledge.KnowledgeChange
import dev.cint.contracts.knowledge.KnowledgeSnapshot
import dev.cint.contracts.ports.RuntimeStatus
import dev.cint.contracts.util.currentTimeMillis
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface IpcMessage {
    @Serializable
    @SerialName("AnalyzeRequest")
    data class AnalyzeRequest(
        val requestId: String,
        val targetPaths: List<String> = emptyList(),
        val language: String? = null,
        val options: Map<String, String> = emptyMap(),
        val forceFullAnalysis: Boolean = false,
    ) : IpcMessage

    @Serializable
    @SerialName("AnalyzeResponse")
    data class AnalyzeResponse(
        val requestId: String,
        val result: AnalysisResult?,
        val error: String? = null,
    ) : IpcMessage

    @Serializable
    @SerialName("PublishRequest")
    data class PublishRequest(
        val requestId: String,
        val annotations: List<PublishedAnnotation>,
        val uri: String? = null,
        val version: Int? = null,
        val hash: String? = null,
        val configRevision: Long? = null,
        val analysisVersion: Long? = null,
    ) : IpcMessage

    @Serializable
    @SerialName("PublishResponse")
    data class PublishResponse(
        val requestId: String,
        val success: Boolean,
        val error: String? = null,
    ) : IpcMessage

    @Serializable
    @SerialName("SuggestRequest")
    data class SuggestRequest(
        val requestId: String,
        val path: String,
        val offset: Int,
        val context: String = "",
    ) : IpcMessage

    @Serializable
    @SerialName("SuggestResponse")
    data class SuggestResponse(
        val requestId: String,
        val suggestions: List<Suggestion>,
        val error: String? = null,
    ) : IpcMessage

    @Serializable
    @SerialName("ConfigRequest")
    data class ConfigRequest(
        val requestId: String,
        val action: ConfigAction,
    ) : IpcMessage

    @Serializable
    @SerialName("ConfigResponse")
    data class ConfigResponse(
        val requestId: String,
        val config: String? = null,
        val error: String? = null,
    ) : IpcMessage

    @Serializable
    @SerialName("StatusRequest")
    data class StatusRequest(
        val requestId: String,
    ) : IpcMessage

    @Serializable
    @SerialName("StatusResponse")
    data class StatusResponse(
        val requestId: String,
        val status: RuntimeStatus?,
        val error: String? = null,
    ) : IpcMessage

    @Serializable
    @SerialName("Handshake")
    data class Handshake(
        val schemaVersion: Int = 1,
        val fingerprint: String,
        val clientInfo: ClientInfo,
        val protocol: Int? = null,
    ) : IpcMessage

    @Serializable
    @SerialName("HandshakeAck")
    data class HandshakeAck(
        val accepted: Boolean,
        val schemaVersion: Int = 1,
        val serverInfo: ServerInfo,
        val error: String? = null,
    ) : IpcMessage

    @Serializable
    @SerialName("Wire")
    data class Wire(
        val json: String,
    ) : IpcMessage
}

@Serializable
enum class ConfigAction {
    GET,
    SET,
    RELOAD,
}

@Serializable
data class PublishedAnnotation(
    val path: String,
    val span: dev.cint.contracts.data.Span,
    val intents: Set<dev.cint.contracts.data.Intent>,
    val role: dev.cint.contracts.data.Role,
    val confidence: dev.cint.contracts.data.Confidence,
    val provenance: dev.cint.contracts.data.Provenance,
    val startByte: Int? = null,
    val endByte: Int? = null,
)

@Serializable
data class Suggestion(
    val span: dev.cint.contracts.data.Span,
    val intents: Set<dev.cint.contracts.data.Intent>,
    val confidence: dev.cint.contracts.data.Confidence,
    val rationale: String,
)

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
data class IpcEnvelope(
    val message: IpcMessage,
    val timestamp: Long = currentTimeMillis(),
)
