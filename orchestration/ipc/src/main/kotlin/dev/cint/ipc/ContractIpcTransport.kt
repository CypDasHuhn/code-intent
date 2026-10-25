package dev.cint.ipc

import dev.cint.contracts.data.AnalysisError
import dev.cint.contracts.data.AnalysisResult
import dev.cint.contracts.data.Annotation
import dev.cint.contracts.data.Candidate
import dev.cint.contracts.data.CandidateReason
import dev.cint.contracts.data.Completeness
import dev.cint.contracts.data.Confidence
import dev.cint.contracts.data.Contribution
import dev.cint.contracts.data.ErrorSeverity
import dev.cint.contracts.data.NodeAnchor
import dev.cint.contracts.data.Provenance
import dev.cint.contracts.data.Role
import dev.cint.contracts.data.Span
import dev.cint.contracts.facts.FactBatch
import dev.cint.contracts.ipc.ConfigAction
import dev.cint.contracts.ipc.IpcMessage
import dev.cint.contracts.ipc.ServerInfo
import dev.cint.contracts.ports.IpcReceive
import dev.cint.contracts.ports.IpcTransport
import dev.cint.contracts.ports.RuntimeStatus
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.util.concurrent.atomic.AtomicLong

class IpcAdapterException(
    message: String,
) : Exception(message)

class ContractIpcAdapter(
    private val codec: NdjsonCodec = NdjsonCodec(),
) {
    fun toWire(
        message: IpcMessage,
        id: JsonRpcId = JsonRpcId.NullId,
    ): JsonRpcMessage =
        when (message) {
            is IpcMessage.Wire -> {
                codec.decode(message.json)
            }

            is IpcMessage.Handshake -> {
                JsonRpcMessage.Request(
                    id,
                    IpcMethods.INITIALIZE,
                    IpcJson
                        .encode(
                            InitializeParams(
                                protocol = requestedProtocol(message),
                                fingerprint = message.fingerprint,
                                client =
                                    dev.cint.ipc.ClientInfo(
                                        message.clientInfo.name,
                                        message.clientInfo.version,
                                        message.clientInfo.capabilities,
                                    ),
                            ),
                        ).asJsonObject(),
                )
            }

            is IpcMessage.HandshakeAck -> {
                JsonRpcMessage.Response(id, IpcJson.encode(message))
            }

            is IpcMessage.AnalyzeRequest -> {
                JsonRpcMessage.Request(
                    JsonRpcId.StringId(message.requestId),
                    IpcMethods.ANALYZE,
                    analyzeParams(message),
                )
            }

            is IpcMessage.AnalyzeResponse -> {
                JsonRpcMessage.Response(id, IpcJson.encode(message))
            }

            is IpcMessage.PublishRequest -> {
                JsonRpcMessage.Notification(
                    IpcMethods.PUBLISH_TAGS,
                    IpcJson
                        .encode(
                            PublishTagsParams(
                                uri =
                                    message.uri ?: message.annotations.firstOrNull()?.path
                                        ?: throw IpcAdapterException("publish requires uri"),
                                version = message.version ?: throw IpcAdapterException("publish requires version"),
                                hash = message.hash ?: throw IpcAdapterException("publish requires hash"),
                                configRevision =
                                    message.configRevision
                                        ?: throw IpcAdapterException("publish requires configRevision"),
                                analysisVersion =
                                    message.analysisVersion
                                        ?: throw IpcAdapterException("publish requires analysisVersion"),
                                annotations =
                                    message.annotations.map { annotation ->
                                        IpcAnnotation(
                                            role = annotation.role.toWireRole(),
                                            intents = annotation.intents.toList(),
                                            source = "annotation",
                                            confidence = annotation.confidence.value,
                                            startLine = annotation.span.startLine,
                                            startCol = annotation.span.startCol,
                                            endLine = annotation.span.endLine,
                                            endCol = annotation.span.endCol,
                                            startByte =
                                                annotation.startByte
                                                    ?: throw IpcAdapterException("publish annotation requires startByte"),
                                            endByte =
                                                annotation.endByte
                                                    ?: throw IpcAdapterException("publish annotation requires endByte"),
                                        )
                                    },
                            ),
                        ).asJsonObject(),
                )
            }

            is IpcMessage.PublishResponse -> {
                JsonRpcMessage.Response(id, IpcJson.encode(message))
            }

            is IpcMessage.SuggestRequest -> {
                JsonRpcMessage.Request(
                    JsonRpcId.StringId(message.requestId),
                    IpcMethods.SUGGEST,
                    IpcJson
                        .encode(
                            SuggestParams(
                                uri = message.path,
                                selection = IpcSpan(0, 0, 0, 0, message.offset, message.offset),
                                context = message.context,
                            ),
                        ).asJsonObject(),
                )
            }

            is IpcMessage.SuggestResponse -> {
                JsonRpcMessage.Response(id, IpcJson.encode(message))
            }

            is IpcMessage.ConfigRequest -> {
                JsonRpcMessage.Request(
                    JsonRpcId.StringId(message.requestId),
                    when (message.action) {
                        ConfigAction.GET -> IpcMethods.CONFIG_READ
                        ConfigAction.SET -> IpcMethods.CONFIG_WRITE
                        ConfigAction.RELOAD -> IpcMethods.CONFIG_WRITE
                    },
                    IpcJson.encode(ConfigParams()).asJsonObject(),
                )
            }

            is IpcMessage.ConfigResponse -> {
                JsonRpcMessage.Response(id, IpcJson.encode(message))
            }

            is IpcMessage.StatusRequest -> {
                JsonRpcMessage.Request(
                    JsonRpcId.StringId(message.requestId),
                    IpcMethods.STATUS,
                    IpcJson.encode(NoParams).asJsonObject(),
                )
            }

            is IpcMessage.StatusResponse -> {
                JsonRpcMessage.Response(id, IpcJson.encode(message))
            }
        }

    fun toContract(
        message: JsonRpcMessage,
        request: IpcMessage? = null,
    ): IpcMessage =
        when (message) {
            is JsonRpcMessage.Response -> responseToContract(message, request)
            is JsonRpcMessage.ErrorResponse -> errorToContract(message, request)
            is JsonRpcMessage.Notification -> IpcMessage.Wire(codec.encode(message))
            is JsonRpcMessage.Request -> IpcMessage.Wire(codec.encode(message))
        }

    private fun requestedProtocol(message: IpcMessage.Handshake): Int =
        message.protocol
            ?: message.schemaVersion.takeIf { it > 1 }
            ?: ProtocolFingerprint.CURRENT_PROTOCOL_VERSION

    private fun analyzeParams(message: IpcMessage.AnalyzeRequest): JsonObject =
        runCatching {
            IpcJson
                .encode(
                    AnalyzeParams(
                        uri = message.targetPaths.firstOrNull(),
                        targetPaths = message.targetPaths,
                        language = message.language,
                        options = message.options,
                        forceFullAnalysis = message.forceFullAnalysis,
                    ),
                ).asJsonObject()
        }.getOrElse {
            IpcJson.encode(AnalyzeParams(targetPaths = listOf("."))).asJsonObject()
        }

    private fun responseToContract(
        message: JsonRpcMessage.Response,
        request: IpcMessage?,
    ): IpcMessage =
        when (request) {
            is IpcMessage.Handshake -> {
                val result =
                    runCatching { IpcJson.json.decodeFromJsonElement<InitializeResult>(message.result) }
                        .getOrElse { throw IpcAdapterException("cannot map initialize result: ${it.message}") }
                IpcMessage.HandshakeAck(
                    accepted = true,
                    schemaVersion = result.protocol,
                    serverInfo = ServerInfo(result.server.name, result.server.version),
                )
            }

            is IpcMessage.AnalyzeRequest -> {
                val result =
                    runCatching { IpcJson.json.decodeFromJsonElement<AnalyzeResult>(message.result) }
                        .getOrElse { throw IpcAdapterException("cannot map V2 AnalyzeResult: ${it.message}") }
                IpcMessage.AnalyzeResponse(request.requestId, result.toContracts(), null)
            }

            is IpcMessage.SuggestRequest -> {
                val result = runCatching { IpcJson.json.decodeFromJsonElement<SuggestResult>(message.result) }.getOrNull()
                if (result == null) {
                    IpcMessage.Wire(codec.encode(message))
                } else {
                    IpcMessage.SuggestResponse(
                        request.requestId,
                        result.suggestions.map { suggestion ->
                            dev.cint.contracts.ipc.Suggestion(
                                span =
                                    Span(
                                        suggestion.span.startLine,
                                        suggestion.span.startCol,
                                        suggestion.span.endLine,
                                        suggestion.span.endCol,
                                    ),
                                intents = suggestion.intents.toSet(),
                                confidence = Confidence.fromDouble(suggestion.confidence),
                                rationale = suggestion.rationale,
                            )
                        },
                        null,
                    )
                }
            }

            is IpcMessage.ConfigRequest -> {
                val result = runCatching { IpcJson.json.decodeFromJsonElement<ConfigResult>(message.result) }.getOrNull()
                if (result == null) {
                    IpcMessage.Wire(codec.encode(message))
                } else {
                    IpcMessage.ConfigResponse(request.requestId, result.config.toString(), null)
                }
            }

            is IpcMessage.StatusRequest -> {
                val result = runCatching { IpcJson.json.decodeFromJsonElement<StatusResult>(message.result) }.getOrNull()
                if (result == null) {
                    IpcMessage.Wire(codec.encode(message))
                } else {
                    IpcMessage.StatusResponse(
                        request.requestId,
                        RuntimeStatus(
                            isInitialized = result.status == "ok",
                            indexedFiles = result.activeDocuments,
                            knowledgeEntries = result.suggestionQueue,
                        ),
                        null,
                    )
                }
            }

            is IpcMessage.PublishRequest -> {
                IpcMessage.PublishResponse(request.requestId, true, null)
            }

            else -> {
                IpcMessage.Wire(codec.encode(message))
            }
        }

    private fun errorToContract(
        message: JsonRpcMessage.ErrorResponse,
        request: IpcMessage?,
    ): IpcMessage =
        when (request) {
            is IpcMessage.Handshake -> {
                IpcMessage.HandshakeAck(false, ProtocolFingerprint.CURRENT_PROTOCOL_VERSION, ServerInfo(), message.error.message)
            }

            is IpcMessage.AnalyzeRequest -> {
                IpcMessage.AnalyzeResponse(request.requestId, null, message.error.message)
            }

            is IpcMessage.PublishRequest -> {
                IpcMessage.PublishResponse(request.requestId, false, message.error.message)
            }

            is IpcMessage.SuggestRequest -> {
                IpcMessage.SuggestResponse(request.requestId, emptyList(), message.error.message)
            }

            is IpcMessage.ConfigRequest -> {
                IpcMessage.ConfigResponse(request.requestId, null, message.error.message)
            }

            is IpcMessage.StatusRequest -> {
                IpcMessage.StatusResponse(request.requestId, null, message.error.message)
            }

            else -> {
                IpcMessage.Wire(codec.encode(message))
            }
        }

    private fun AnalyzeResult.toContracts(): AnalysisResult {
        val annotations = files.flatMap { file -> file.annotations.map { it.toContract() } }
        val candidates = candidates.map { it.toContract() }
        val diagnostics = (this.diagnostics + files.flatMap { it.diagnostics }).map { it.toContract() }
        return AnalysisResult(
            annotations = annotations,
            candidates = candidates,
            facts = FactBatch(syntaxTree = null),
            sourceEpoch = 0,
            completeness = Completeness.COMPLETE,
            errors = diagnostics,
        )
    }

    private fun IpcAnnotation.toContract(): Annotation {
        val contractRole = role.toContractRole()
        val contractSpan = Span(startLine, startCol, endLine, endCol)
        val contributions =
            provenance
                .map { contribution ->
                    Contribution(
                        rule = contribution.ruleId,
                        role = contribution.role.toContractRole(),
                        intents = contribution.intents.toSet(),
                        span =
                            Span(
                                contribution.startLine,
                                contribution.startCol,
                                contribution.endLine,
                                contribution.endCol,
                            ),
                        source = contribution.source,
                        confidence = Confidence.fromDouble(contribution.confidence),
                        startByte = contribution.startByte,
                        endByte = contribution.endByte,
                        provenance = Provenance.RULE,
                    )
                }.ifEmpty {
                    listOf(
                        Contribution(
                            rule = "ipc",
                            role = contractRole,
                            intents = intents.toSet(),
                            span = contractSpan,
                            source = source,
                            confidence = Confidence.fromDouble(confidence),
                            startByte = startByte,
                            endByte = endByte,
                        ),
                    )
                }
        return Annotation(
            role = contractRole,
            intents = intents.toSet(),
            span = contractSpan,
            source = source,
            confidence = Confidence.fromDouble(confidence),
            startByte = startByte,
            endByte = endByte,
            provenance = contributions,
            nodeAnchor = nodeAnchor?.let(NodeAnchor::fromString),
        )
    }

    private fun IpcCandidate.toContract(): Candidate =
        Candidate(
            intents = intents.toSet(),
            span = Span(startLine, startCol, endLine, endCol),
            startByte = startByte,
            endByte = endByte,
            source = origin.ruleId ?: origin.type,
            confidence = Confidence.fromDouble(confidence),
            reason = category.toCandidateReason(),
            aiEligible = origin.type == "ai",
            provider = origin.provider,
            model = origin.model,
        )

    private fun IpcDiagnostic.toContract(): AnalysisError =
        AnalysisError(
            code = code,
            message = message,
            span = span?.let { Span(it.startLine, it.startCol, it.endLine, it.endCol) },
            severity =
                when (severity) {
                    IpcSeverity.ERROR -> ErrorSeverity.ERROR
                    IpcSeverity.WARNING -> ErrorSeverity.WARNING
                    IpcSeverity.INFO -> ErrorSeverity.INFO
                },
        )

    private fun String.toContractRole(): Role =
        when (lowercase().replace('_', '-')) {
            "source-intent" -> Role.SOURCE_INTENT
            "derivative-use" -> Role.DERIVATIVE_USE
            "scaffold" -> Role.SCAFFOLD
            else -> throw IpcAdapterException("unsupported role: $this")
        }

    private fun String.toCandidateReason(): CandidateReason =
        when (this) {
            "pack-domain" -> CandidateReason.PACK_DOMAIN
            "low-confidence" -> CandidateReason.LOW_CONFIDENCE
            "user-hint" -> CandidateReason.PACK_DOMAIN
            else -> CandidateReason.GAP
        }

    private fun Role.toWireRole(): String =
        when (this) {
            Role.SOURCE_INTENT -> "source-intent"
            Role.DERIVATIVE_USE -> "derivative-use"
            Role.SCAFFOLD -> "scaffold"
        }

    private fun JsonElement.asJsonObject(): JsonObject = this as JsonObject
}

interface IpcWireChannel {
    fun readLine(): String?

    fun writeLine(line: String)

    fun close()
}

class ContractIpcTransport(
    private val channel: IpcWireChannel,
    private val codec: NdjsonCodec = NdjsonCodec(),
    private val adapter: ContractIpcAdapter = ContractIpcAdapter(codec),
    private val nextGeneratedId: AtomicLong = AtomicLong(1),
) : IpcTransport {
    private val pendingLock = Any()
    private val pending = mutableMapOf<String, IpcMessage>()

    @Volatile
    var negotiatedProtocol: Int? = null
        private set

    override fun send(
        message: IpcMessage,
        cancellation: CancellationToken,
    ): ContractResult<Unit> {
        if (cancellation.isCancelled) return ResultFactory.failure(ContractError.Cancelled("ipc.send"))
        var pendingKey: String? = null
        return try {
            val id = idFor(message)
            val wire = adapter.toWire(message, id)
            val line = codec.encodeLine(wire)
            if (wire is JsonRpcMessage.Request) {
                pendingKey = wire.id.key
                synchronized(pendingLock) { pending[wire.id.key] = message }
            }
            channel.writeLine(line)
            ResultFactory.success(Unit)
        } catch (error: IpcAdapterException) {
            pendingKey?.let { key -> synchronized(pendingLock) { pending.remove(key) } }
            ResultFactory.failure(ContractError.InvalidInput(error.message ?: "unsupported IPC mapping"))
        } catch (error: Exception) {
            pendingKey?.let { key -> synchronized(pendingLock) { pending.remove(key) } }
            ResultFactory.failure(ContractError.InternalError(error.message ?: "failed to write IPC frame"))
        }
    }

    override fun receive(cancellation: CancellationToken): ContractResult<IpcReceive> {
        if (cancellation.isCancelled) return ResultFactory.failure(ContractError.Cancelled("ipc.receive"))
        val line =
            try {
                channel.readLine()
            } catch (error: Exception) {
                return ResultFactory.failure(ContractError.InternalError(error.message ?: "failed to read IPC frame"))
            } ?: return ResultFactory.success(IpcReceive.Closed)
        if (line.isBlank()) return ResultFactory.success(IpcReceive.Empty)
        val wire =
            try {
                codec.decode(line)
            } catch (error: IpcProtocolException) {
                return ResultFactory.failure(ContractError.SerializationError(error.message ?: "invalid IPC frame"))
            }
        val responseId =
            when (wire) {
                is JsonRpcMessage.Response -> wire.id
                is JsonRpcMessage.ErrorResponse -> wire.id
                else -> null
            }
        val request = responseId?.let { id -> synchronized(pendingLock) { pending.remove(id.key) } }
        val contract =
            try {
                adapter.toContract(wire, request)
            } catch (error: IpcAdapterException) {
                return ResultFactory.failure(ContractError.SerializationError(error.message ?: "unsupported IPC mapping"))
            } catch (error: Exception) {
                return ResultFactory.failure(ContractError.InternalError(error.message ?: "failed to adapt IPC frame"))
            }
        if (contract is IpcMessage.HandshakeAck && contract.accepted) {
            negotiatedProtocol = contract.schemaVersion
        }
        return ResultFactory.success(IpcReceive.Message(contract))
    }

    override fun close(): ContractResult<Unit> =
        try {
            synchronized(pendingLock) { pending.clear() }
            channel.close()
            ResultFactory.success(Unit)
        } catch (error: Exception) {
            ResultFactory.failure(ContractError.InternalError(error.message ?: "failed to close IPC channel"))
        }

    private fun idFor(message: IpcMessage): JsonRpcId =
        when (message) {
            is IpcMessage.Handshake -> JsonRpcId.NumberId(nextGeneratedId.getAndIncrement())
            is IpcMessage.AnalyzeRequest -> JsonRpcId.StringId(message.requestId)
            is IpcMessage.PublishRequest -> JsonRpcId.NullId
            is IpcMessage.SuggestRequest -> JsonRpcId.StringId(message.requestId)
            is IpcMessage.ConfigRequest -> JsonRpcId.StringId(message.requestId)
            is IpcMessage.StatusRequest -> JsonRpcId.StringId(message.requestId)
            is IpcMessage.Wire -> JsonRpcId.NullId
            else -> JsonRpcId.NullId
        }
}
