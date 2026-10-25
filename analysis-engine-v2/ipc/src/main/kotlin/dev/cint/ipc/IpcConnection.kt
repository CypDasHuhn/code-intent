package dev.cint.ipc

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

sealed interface IpcResult {
    data class Success(
        val result: JsonElement,
        val notifications: List<JsonRpcMessage> = emptyList(),
    ) : IpcResult

    data class Failure(
        val error: JsonRpcError,
        val notifications: List<JsonRpcMessage> = emptyList(),
    ) : IpcResult
}

object IpcResults {
    fun value(
        result: JsonElement,
        notifications: List<JsonRpcMessage> = emptyList(),
    ): IpcResult = IpcResult.Success(result, notifications)

    inline fun <reified T> of(
        value: T,
        notifications: List<JsonRpcMessage> = emptyList(),
    ): IpcResult = IpcResult.Success(IpcJson.encode(value), notifications)

    fun failure(
        error: JsonRpcError,
        notifications: List<JsonRpcMessage> = emptyList(),
    ): IpcResult = IpcResult.Failure(error, notifications)
}

interface IpcHandler {
    fun initialize(params: InitializeParams): IpcResult

    fun shutdown(): IpcResult = IpcResults.value(JsonPrimitive(true))

    fun analyze(params: AnalyzeParams): IpcResult =
        IpcResults.failure(IpcErrors.protocol("analyze is not implemented"))

    fun suggest(params: SuggestParams): IpcResult =
        IpcResults.failure(IpcErrors.protocol("suggest is not implemented"))

    fun suggestCancel(params: SuggestCancelParams): IpcResult =
        IpcResults.failure(IpcErrors.protocol("suggest/cancel is not implemented"))

    fun suggestLog(params: SuggestLogParams): IpcResult =
        IpcResults.failure(IpcErrors.protocol("suggest/log is not implemented"))

    fun suggestStream(params: SuggestParams): IpcResult = suggest(params)

    fun suggestHistory(params: SuggestHistoryParams): IpcResult =
        IpcResults.failure(IpcErrors.protocol("suggest/history is not implemented"))

    fun config(
        operation: ConfigOperation,
        params: ConfigParams,
    ): IpcResult = IpcResults.failure(IpcErrors.protocol("config is not implemented"))

    fun status(): IpcResult =
        IpcResults.failure(IpcErrors.protocol("status is not implemented"))

    fun metrics(): IpcResult =
        IpcResults.failure(IpcErrors.protocol("metrics is not implemented"))

    fun didOpen(params: DocumentParams): List<JsonRpcMessage> = emptyList()

    fun didChange(params: DocumentParams): List<JsonRpcMessage> = emptyList()

    fun didClose(params: DocumentCloseParams): List<JsonRpcMessage> = emptyList()

    fun restrictionsList(params: RestrictionsListParams): IpcResult =
        IpcResults.failure(IpcErrors.protocol("restrictions/list is not implemented"))

    fun restrictionsRemove(params: RestrictionRemoveParams): IpcResult =
        IpcResults.failure(IpcErrors.protocol("restrictions/remove is not implemented"))

    fun restrictionsUpsert(params: RestrictionUpsertParams): IpcResult =
        IpcResults.failure(IpcErrors.protocol("restrictions/upsert is not implemented"))

    fun candidateDecision(params: CandidateDecisionParams): IpcResult =
        IpcResults.failure(IpcErrors.protocol("candidates/decide is not implemented"))
}

object IpcNotifications {
    inline fun <reified T> of(
        method: String,
        params: T,
    ): JsonRpcMessage.Notification = JsonRpcMessage.Notification(method, IpcJson.encode<T>(params) as JsonObject)

    fun publishTags(params: PublishTagsParams): JsonRpcMessage.Notification =
        of(IpcMethods.PUBLISH_TAGS, params)

    fun publishCandidates(params: PublishCandidatesParams): JsonRpcMessage.Notification =
        of(IpcMethods.PUBLISH_CANDIDATES, params)
}

class IpcConnection(
    private val handler: IpcHandler,
    private val expectedFingerprint: String = ProtocolFingerprint.CURRENT,
    private val codec: NdjsonCodec = NdjsonCodec(),
    private val requireHandshake: Boolean = true,
    private val recentIdCapacity: Int = DEFAULT_RECENT_ID_CAPACITY,
) {
    private val decoder = codec.newDecoder()
    private val pendingRequestIds = linkedSetOf<String>()
    private val recentRequestIds = RecentRequestIds(recentIdCapacity)

    @Volatile
    var initialized: Boolean = false
        private set

    @Volatile
    var closed: Boolean = false
        private set

    @Volatile
    var negotiatedProtocol: Int? = null
        private set

    val isLegacy: Boolean
        get() = negotiatedProtocol?.let { it < ProtocolFingerprint.CURRENT_PROTOCOL_VERSION } ?: false

    val pending: Set<String>
        @Synchronized get() = pendingRequestIds.toSet()

    @Synchronized
    fun feed(chunk: ByteArray): List<JsonRpcMessage> = decoder.push(chunk).flatMap(::handleFrame)

    fun feed(text: String): List<JsonRpcMessage> = feed(text.toByteArray(Charsets.UTF_8))

    @Synchronized
    fun finish(): List<JsonRpcMessage> = decoder.finish().flatMap(::handleFrame)

    @Synchronized
    fun disconnect(): List<JsonRpcMessage> {
        if (closed) return emptyList()
        val trailing = decoder.finish().flatMap(::handleFrame)
        closed = true
        val abandoned = pendingRequestIds.toList()
        pendingRequestIds.clear()
        val failures =
            abandoned.map { key ->
                val id = key.toRequestId()
                JsonRpcMessage.ErrorResponse(
                    id,
                    IpcErrors.connectionClosed("disconnected before ${id.display()} completed"),
                )
            }
        return trailing + failures
    }

    private fun handleFrame(frame: NdjsonFrame): List<JsonRpcMessage> =
        when (frame) {
            is NdjsonFrame.Invalid -> listOf(JsonRpcMessage.ErrorResponse(JsonRpcId.NullId, frame.error))
            is NdjsonFrame.Text -> handleLine(frame.line)
        }

    private fun handleLine(line: String): List<JsonRpcMessage> {
        if (closed) {
            return listOf(JsonRpcMessage.ErrorResponse(JsonRpcId.NullId, IpcErrors.connectionClosed()))
        }
        val message =
            try {
                codec.decode(line)
            } catch (error: IpcProtocolException) {
                return listOf(JsonRpcMessage.ErrorResponse(JsonRpcId.NullId, error.rpcError))
            }
        return when (message) {
            is JsonRpcMessage.Request -> handleRequest(message)
            is JsonRpcMessage.Notification -> handleNotification(message)
            is JsonRpcMessage.Response ->
                listOf(
                    JsonRpcMessage.ErrorResponse(
                        message.id,
                        IpcErrors.protocol("client sent a response; only requests and notifications are accepted"),
                    ),
                )
            is JsonRpcMessage.ErrorResponse ->
                listOf(JsonRpcMessage.ErrorResponse(message.id, IpcErrors.protocol("client sent an error response")))
        }
    }

    private fun handleRequest(request: JsonRpcMessage.Request): List<JsonRpcMessage> {
        val idKey = request.id.key
        if (idKey in pendingRequestIds || recentRequestIds.contains(idKey)) {
            return listOf(JsonRpcMessage.ErrorResponse(request.id, IpcErrors.duplicateId(request.id)))
        }
        if (request.method !in IpcMethods.all) {
            return listOf(JsonRpcMessage.ErrorResponse(request.id, IpcErrors.methodNotFound(request.method)))
        }
        if (requireHandshake && !initialized && request.method != IpcMethods.INITIALIZE) {
            return listOf(JsonRpcMessage.ErrorResponse(request.id, IpcErrors.handshakeRequired(request.method)))
        }
        if (initialized && request.method == IpcMethods.INITIALIZE) {
            return listOf(
                JsonRpcMessage.ErrorResponse(
                    request.id,
                    IpcErrors.protocol("initialize may only be sent once per connection"),
                ),
            )
        }

        pendingRequestIds += idKey
        val result =
            try {
                dispatch(request.method, request.params)
            } catch (error: IpcProtocolException) {
                IpcResult.Failure(error.rpcError)
            } catch (error: Exception) {
                IpcResult.Failure(
                    JsonRpcError(
                        JsonRpcErrorCodes.INTERNAL_ERROR,
                        error.message ?: "internal error",
                        JsonObject(mapOf("kind" to JsonPrimitive("internal-error"))),
                    ),
                )
            } finally {
                pendingRequestIds -= idKey
                recentRequestIds.remember(idKey)
            }
        return toMessages(request.id, result)
    }

    private fun handleNotification(notification: JsonRpcMessage.Notification): List<JsonRpcMessage> {
        if (notification.method !in IpcMethods.all) return emptyList()
        if (requireHandshake && !initialized) return emptyList()
        return try {
            dispatchNotification(notification.method, notification.params)
        } catch (error: IpcProtocolException) {
            emptyList()
        } catch (error: Exception) {
            emptyList()
        }
    }

    private fun dispatch(
        method: String,
        params: JsonObject,
    ): IpcResult =
        when (method) {
            IpcMethods.INITIALIZE -> initialize(params)
            IpcMethods.SHUTDOWN -> {
                IpcJson.decode<NoParams>(params)
                handler.shutdown()
            }
            IpcMethods.ANALYZE -> handler.analyze(IpcJson.decode<AnalyzeParams>(params))
            IpcMethods.SUGGEST -> handler.suggest(IpcJson.decode<SuggestParams>(params))
            IpcMethods.SUGGEST_CANCEL -> handler.suggestCancel(IpcJson.decode<SuggestCancelParams>(params))
            IpcMethods.SUGGEST_LOG -> handler.suggestLog(IpcJson.decode<SuggestLogParams>(params))
            IpcMethods.SUGGEST_STREAM -> handler.suggestStream(IpcJson.decode<SuggestParams>(params))
            IpcMethods.SUGGEST_HISTORY -> handler.suggestHistory(IpcJson.decode<SuggestHistoryParams>(params))
            IpcMethods.CONFIG_READ -> handler.config(ConfigOperation.READ, IpcJson.decode<ConfigParams>(params))
            IpcMethods.CONFIG_APPEND -> handler.config(ConfigOperation.APPEND, IpcJson.decode<ConfigParams>(params))
            IpcMethods.CONFIG_REMOVE -> handler.config(ConfigOperation.REMOVE, IpcJson.decode<ConfigParams>(params))
            IpcMethods.CONFIG_WRITE -> handler.config(ConfigOperation.WRITE, IpcJson.decode<ConfigParams>(params))
            IpcMethods.CONFIG_SCHEMA -> handler.config(ConfigOperation.SCHEMA, IpcJson.decode<ConfigParams>(params))
            IpcMethods.STATUS -> {
                IpcJson.decode<NoParams>(params)
                handler.status()
            }
            IpcMethods.METRICS -> {
                IpcJson.decode<NoParams>(params)
                handler.metrics()
            }
            IpcMethods.RESTRICTIONS,
            IpcMethods.RESTRICTIONS_LIST,
            -> handler.restrictionsList(IpcJson.decode<RestrictionsListParams>(params))
            IpcMethods.RESTRICTIONS_REMOVE -> handler.restrictionsRemove(IpcJson.decode<RestrictionRemoveParams>(params))
            IpcMethods.RESTRICTIONS_UPSERT -> handler.restrictionsUpsert(IpcJson.decode<RestrictionUpsertParams>(params))
            IpcMethods.CANDIDATES_DECIDE -> handler.candidateDecision(IpcJson.decode<CandidateDecisionParams>(params))
            else -> IpcResult.Failure(IpcErrors.methodNotFound(method))
        }

    private fun dispatchNotification(
        method: String,
        params: JsonObject,
    ): List<JsonRpcMessage> =
        when (method) {
            IpcMethods.DID_OPEN -> handler.didOpen(IpcJson.decode<DocumentParams>(params))
            IpcMethods.DID_CHANGE -> handler.didChange(IpcJson.decode<DocumentParams>(params))
            IpcMethods.DID_CLOSE -> handler.didClose(IpcJson.decode<DocumentCloseParams>(params))
            else -> emptyList()
        }

    private fun initialize(params: JsonObject): IpcResult {
        val initialize = IpcJson.decode<InitializeParams>(params)
        if (initialize.protocol !in MIN_PROTOCOL..ProtocolFingerprint.CURRENT_PROTOCOL_VERSION) {
            return IpcResult.Failure(
                JsonRpcError(
                    JsonRpcErrorCodes.PROTOCOL_ERROR,
                    "unsupported protocol: ${initialize.protocol}; supported $MIN_PROTOCOL..${ProtocolFingerprint.CURRENT_PROTOCOL_VERSION}",
                    JsonObject(
                        mapOf(
                            "kind" to JsonPrimitive("unsupported-protocol"),
                            "details" to
                                JsonObject(
                                    mapOf(
                                        "requested" to JsonPrimitive(initialize.protocol),
                                        "minimum" to JsonPrimitive(MIN_PROTOCOL),
                                        "maximum" to JsonPrimitive(ProtocolFingerprint.CURRENT_PROTOCOL_VERSION),
                                    ),
                                ),
                        ),
                    ),
                ),
            )
        }
        if (
            initialize.protocol >= ProtocolFingerprint.CURRENT_PROTOCOL_VERSION &&
            initialize.fingerprint != expectedFingerprint
        ) {
            return IpcResult.Failure(
                JsonRpcError(
                    JsonRpcErrorCodes.PROTOCOL_ERROR,
                    "protocol fingerprint mismatch",
                    JsonObject(
                        mapOf(
                            "kind" to JsonPrimitive("fingerprint-mismatch"),
                            "details" to
                                JsonObject(
                                    mapOf(
                                        "expected" to JsonPrimitive(expectedFingerprint),
                                        "actual" to JsonPrimitive(initialize.fingerprint),
                                    ),
                                ),
                        ),
                    ),
                ),
            )
        }
        val result = handler.initialize(initialize)
        if (result is IpcResult.Success) {
            initialized = true
            negotiatedProtocol = initialize.protocol
            if (initialize.protocol < ProtocolFingerprint.CURRENT_PROTOCOL_VERSION) {
                return result.copy(result = legacyInitializeResult(result.result, initialize.protocol))
            }
        }
        return result
    }

    private fun legacyInitializeResult(
        result: JsonElement,
        protocol: Int,
    ): JsonElement {
        val objectValue = result as? JsonObject ?: return result
        val reportedProtocol =
            (objectValue["protocol"] as? JsonPrimitive)?.content?.toIntOrNull()
                ?.takeIf { it in MIN_PROTOCOL until ProtocolFingerprint.CURRENT_PROTOCOL_VERSION }
                ?: protocol
        return buildJsonObject {
            put("protocol", reportedProtocol)
            for ((key, value) in objectValue) {
                if (key in LEGACY_EXCLUDED_INITIALIZE_FIELDS) continue
                if (value is JsonNull) continue
                put(key, value)
            }
        }
    }

    private fun toMessages(
        id: JsonRpcId,
        result: IpcResult,
    ): List<JsonRpcMessage> =
        when (result) {
            is IpcResult.Success -> result.notifications + JsonRpcMessage.Response(id, result.result)
            is IpcResult.Failure -> result.notifications + JsonRpcMessage.ErrorResponse(id, result.error)
        }

    private fun String.toRequestId(): JsonRpcId =
        when {
            startsWith("n:") -> removePrefix("n:").toLongOrNull()?.let(JsonRpcId::NumberId) ?: JsonRpcId.StringId(this)
            startsWith("s:") -> JsonRpcId.StringId(removePrefix("s:"))
            else -> JsonRpcId.NullId
        }

    private class RecentRequestIds(
        private val capacity: Int,
    ) {
        private val ids = linkedSetOf<String>()

        fun contains(id: String): Boolean = id in ids

        fun remember(id: String) {
            ids.remove(id)
            ids.add(id)
            while (ids.size > capacity) {
                ids.remove(ids.first())
            }
        }
    }

    private companion object {
        const val MIN_PROTOCOL = 1
        const val DEFAULT_RECENT_ID_CAPACITY = 256
        val LEGACY_EXCLUDED_INITIALIZE_FIELDS = setOf("protocol", "fingerprint", "server", "capabilities")
    }
}
