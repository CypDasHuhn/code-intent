package dev.cint.v2.lsp

import dev.cint.contracts.data.Confidence
import dev.cint.contracts.data.Span
import dev.cint.contracts.facts.Diagnostic
import dev.cint.contracts.facts.Location
import dev.cint.contracts.facts.SemanticRequest
import dev.cint.contracts.facts.SemanticRequestKind
import dev.cint.contracts.facts.SemanticResult
import dev.cint.contracts.facts.SymbolInfo
import dev.cint.contracts.facts.SymbolKind
import dev.cint.contracts.facts.TypeRef
import dev.cint.contracts.ports.HostSemanticChannel
import dev.cint.contracts.ports.HostSemanticChannelProvider
import dev.cint.contracts.ports.ProviderMode
import dev.cint.contracts.ports.SemanticProvider
import dev.cint.contracts.ports.SemanticProviderConfig
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory
import dev.cint.contracts.util.Unavailable
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

fun interface LspTypeExtractor {
    fun extract(hover: String): String?
}

object DefaultLspTypeExtractor : LspTypeExtractor {
    private val fence = Regex("```[A-Za-z0-9_+#.-]*")
    private val patterns =
        listOf(
            Regex(""":\s*([A-Za-z_][A-Za-z0-9_.$]*(?:<[^>\n]+>)?)"""),
            Regex("""(?:class|interface|object|enum|typealias|record|struct|trait)\s+([A-Za-z_][A-Za-z0-9_.$]*)"""),
            Regex("""(?:fun|function|def)\s+[A-Za-z_][A-Za-z0-9_]*\s*\([^)]*\)\s*(?::|->)\s*([A-Za-z_][A-Za-z0-9_.$]*(?:<[^>\n]+>)?)"""),
        )

    override fun extract(hover: String): String? {
        if (hover.isBlank()) return null
        val cleaned = hover.replace(fence, "").replace("```", "").trim()
        for (pattern in patterns) {
            val match = pattern.find(cleaned) ?: continue
            return match.groupValues[1].trim()
        }
        return cleaned.takeIf { it.matches(Regex("""[A-Za-z_][A-Za-z0-9_.$]*(?:<[^>\n]+>)?[?]?""")) }
    }
}

class LspSemanticProvider private constructor(
    private val config: SemanticProviderConfig,
    override val language: String,
    private val languageId: String,
    private val session: LspSession?,
    private val editorChannel: HostSemanticChannel?,
    private val ownsEditorChannel: Boolean,
    private val typeExtractor: LspTypeExtractor,
) : SemanticProvider {
    private val shutdown = AtomicBoolean(false)
    private val pulledDiagnostics = ConcurrentHashMap<String, PulledDiagnostics>()
    private val editorExecutor: ExecutorService? =
        if (editorChannel != null) {
            Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "cint-lsp-editor").apply { isDaemon = true }
            }
        } else {
            null
        }

    fun openDocument(
        uri: String,
        text: String,
    ): ContractResult<Unit> = openDocument(uri, text, 1)

    override fun openDocument(
        uri: String,
        text: String,
        version: Long,
    ): ContractResult<Unit> {
        pulledDiagnostics.remove(uri)
        val channel = editorChannel
        if (channel != null) return channel.openDocument(uri, text, version)
        val activeSession = session ?: return ResultFactory.success(Unit)
        return activeSession.openDocument(uri, text, version, languageId)
    }

    override fun changeDocument(
        uri: String,
        text: String,
        version: Long,
    ): ContractResult<Unit> {
        pulledDiagnostics.remove(uri)
        val channel = editorChannel
        if (channel != null) return channel.changeDocument(uri, text, version)
        val activeSession = session ?: return ResultFactory.success(Unit)
        return activeSession.changeDocument(uri, text, version, languageId)
    }

    override fun closeDocument(uri: String): ContractResult<Unit> {
        pulledDiagnostics.remove(uri)
        val channel = editorChannel
        if (channel != null) return channel.closeDocument(uri)
        val activeSession = session ?: return ResultFactory.success(Unit)
        return activeSession.closeDocument(uri)
    }

    override fun request(request: SemanticRequest): ContractResult<SemanticResult> {
        if (shutdown.get()) {
            return ResultFactory.failure(ContractError.DependencyUnavailable("lsp", "LSP provider is shut down"))
        }
        if (editorChannel != null) return requestEditor(request)
        val activeSession =
            session ?: return ResultFactory.failure(ContractError.DependencyUnavailable("lsp", "LSP session is not available"))
        val operation = operationFor(request.kind)
        if (request.deadline.isExpired) {
            return ResultFactory.failure(ContractError.Timeout(operation, 0))
        }
        val document =
            when (val snapshot = activeSession.documentFor(request.uri, request.version)) {
                is dev.cint.contracts.util.Success -> snapshot.contents
                is dev.cint.contracts.util.Failure -> return ResultFactory.failure(snapshot.error)
            }
        val position = positionAt(document.text, request.offset, request.offsetUnit)
        val timeout = timeoutFor(request)
        return try {
            when (request.kind) {
                SemanticRequestKind.TYPE_AT -> typeAt(activeSession, request, position, timeout)
                SemanticRequestKind.SYMBOL_AT -> symbolAt(activeSession, request, position, timeout)
                SemanticRequestKind.DEFINITION_OF -> definitionOf(activeSession, request, position, timeout)
                SemanticRequestKind.DIAGNOSTICS_FOR -> diagnosticsFor(activeSession, request, timeout)
                SemanticRequestKind.HOVER_AT -> hoverAt(activeSession, request, position, timeout)
                SemanticRequestKind.REFERENCES_AT -> referencesAt(activeSession, request, position, timeout)
            }
        } catch (error: LspResultException) {
            ResultFactory.failure(ContractError.SerializationError(error.message ?: "Malformed LSP result"))
        }
    }

    private fun requestEditor(request: SemanticRequest): ContractResult<SemanticResult> {
        val channel =
            editorChannel
                ?: return ResultFactory.failure(ContractError.DependencyUnavailable("lsp", "Editor channel is missing"))
        val executor =
            editorExecutor
                ?: return ResultFactory.failure(ContractError.DependencyUnavailable("lsp", "Editor executor is not available"))
        val operation = operationFor(request.kind)
        if (request.deadline.isExpired) {
            return ResultFactory.failure(ContractError.Timeout(operation, 0))
        }
        val timeout = timeoutFor(request)
        val future = CompletableFuture.supplyAsync({ channel.send(request) }, executor)
        return try {
            future.get(timeout, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            ResultFactory.failure(ContractError.Timeout(operation, timeout))
        } catch (_: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            ResultFactory.failure(ContractError.Cancelled(operation))
        } catch (error: ExecutionException) {
            ResultFactory.failure(
                ContractError.InternalError(
                    "Editor semantic channel failed",
                    error.cause?.message ?: error.message ?: error::class.simpleName,
                ),
            )
        }
    }

    private fun typeAt(
        session: LspSession,
        request: SemanticRequest,
        position: LspPosition,
        timeout: Long,
    ): ContractResult<SemanticResult> {
        if (!session.supports("hoverProvider")) {
            return ResultFactory.success(
                SemanticResult.TypeAt(null, Unavailable.notSupported("LSP server does not support hover")),
            )
        }
        val hover = hoverText(session, request.uri, position, timeout)
        if (hover.isFailure) return ResultFactory.failure(hover.errorOrNull ?: unknownFailure(operationFor(request.kind)))
        val type = typeExtractor.extract(hover.getValue() as? String ?: "")
        return ResultFactory.success(
            SemanticResult.TypeAt(
                type?.let { TypeRef(it, "lsp", Confidence.fromDouble(0.95)) },
            ),
        )
    }

    private fun hoverAt(
        session: LspSession,
        request: SemanticRequest,
        position: LspPosition,
        timeout: Long,
    ): ContractResult<SemanticResult> {
        if (!session.supports("hoverProvider")) {
            return ResultFactory.success(
                SemanticResult.HoverAt(null, Unavailable.notSupported("LSP server does not support hover")),
            )
        }
        val hover = hoverText(session, request.uri, position, timeout)
        if (hover.isFailure) return ResultFactory.failure(hover.errorOrNull ?: unknownFailure(operationFor(request.kind)))
        return ResultFactory.success(SemanticResult.HoverAt(hover.getValue() as? String))
    }

    private fun hoverText(
        session: LspSession,
        uri: String,
        position: LspPosition,
        timeout: Long,
    ): ContractResult<Any?> =
        session
            .rpc(
                "textDocument/hover",
                linkedMapOf(
                    "textDocument" to linkedMapOf("uri" to uri),
                    "position" to linkedMapOf("line" to position.line, "character" to position.character),
                ),
                timeout,
                "textDocument/hover",
            ).map { value -> hoverTextOf(value) }

    private fun symbolAt(
        session: LspSession,
        request: SemanticRequest,
        position: LspPosition,
        timeout: Long,
    ): ContractResult<SemanticResult> {
        if (!session.supports("documentSymbolProvider")) {
            return ResultFactory.success(
                SemanticResult.SymbolAt(null, Unavailable.notSupported("LSP server does not support document symbols")),
            )
        }
        val response =
            session.rpc(
                "textDocument/documentSymbol",
                linkedMapOf("textDocument" to linkedMapOf("uri" to request.uri)),
                timeout,
                "textDocument/documentSymbol",
            )
        if (response.isFailure) return ResultFactory.failure(response.errorOrNull ?: unknownFailure(operationFor(request.kind)))
        val symbol = findSymbol(response.getValue(), position)
        return ResultFactory.success(SemanticResult.SymbolAt(symbol))
    }

    private fun definitionOf(
        session: LspSession,
        request: SemanticRequest,
        position: LspPosition,
        timeout: Long,
    ): ContractResult<SemanticResult> {
        if (!session.supports("definitionProvider")) {
            return ResultFactory.success(
                SemanticResult.DefinitionOf(emptyList(), Unavailable.notSupported("LSP server does not support definitions")),
            )
        }
        val response =
            session.rpc(
                "textDocument/definition",
                positionParams(request.uri, position),
                timeout,
                "textDocument/definition",
            )
        if (response.isFailure) return ResultFactory.failure(response.errorOrNull ?: unknownFailure(operationFor(request.kind)))
        return ResultFactory.success(SemanticResult.DefinitionOf(parseLocations(response.getValue())))
    }

    private fun referencesAt(
        session: LspSession,
        request: SemanticRequest,
        position: LspPosition,
        timeout: Long,
    ): ContractResult<SemanticResult> {
        if (!session.supports("referencesProvider")) {
            return ResultFactory.success(
                SemanticResult.ReferencesAt(emptyList(), Unavailable.notSupported("LSP server does not support references")),
            )
        }
        val params = positionParams(request.uri, position)
        params["context"] = linkedMapOf("includeDeclaration" to true)
        val response = session.rpc("textDocument/references", params, timeout, "textDocument/references")
        if (response.isFailure) return ResultFactory.failure(response.errorOrNull ?: unknownFailure(operationFor(request.kind)))
        return ResultFactory.success(SemanticResult.ReferencesAt(parseLocations(response.getValue())))
    }

    private fun diagnosticsFor(
        session: LspSession,
        request: SemanticRequest,
        timeout: Long,
    ): ContractResult<SemanticResult> {
        session.diagnosticsFor(request.uri, request.version)?.let {
            return ResultFactory.success(SemanticResult.DiagnosticsFor(it))
        }
        if (!session.supports("diagnosticProvider")) {
            return ResultFactory.success(SemanticResult.DiagnosticsFor(emptyList()))
        }
        val response =
            session.rpc(
                "textDocument/diagnostic",
                linkedMapOf("textDocument" to linkedMapOf("uri" to request.uri)),
                timeout,
                "textDocument/diagnostic",
            )
        if (response.isFailure) return ResultFactory.failure(response.errorOrNull ?: unknownFailure(operationFor(request.kind)))
        return try {
            ResultFactory.success(SemanticResult.DiagnosticsFor(parseDiagnosticReport(response.getValue(), request)))
        } catch (error: LspResultException) {
            ResultFactory.failure(ContractError.SerializationError(error.message ?: "Malformed LSP diagnostic report"))
        }
    }

    private fun parseDiagnosticReport(
        value: Any?,
        request: SemanticRequest,
    ): List<Diagnostic> {
        val report = value.asObjectOrNull() ?: throw LspResultException("Expected an LSP diagnostic report")
        when (val kind = report.string("kind")) {
            "unchanged" -> {
                val cached = pulledDiagnostics[request.uri]
                return if (cached != null && cached.version == request.version) cached.diagnostics else emptyList()
            }

            "full", "partial", null -> {}

            else -> {
                throw LspResultException("Unsupported LSP diagnostic report kind: $kind")
            }
        }
        val items = report.listOrNull("items") ?: throw LspResultException("Expected document diagnostic items")
        val diagnostics = items.mapNotNull { parseDiagnosticValue(it) }
        pulledDiagnostics[request.uri] =
            PulledDiagnostics(request.version, report.string("resultId"), diagnostics)
        return diagnostics
    }

    override fun shutdown(): ContractResult<Unit> {
        if (!shutdown.compareAndSet(false, true)) return ResultFactory.success(Unit)
        return try {
            if (editorChannel != null) {
                if (ownsEditorChannel) editorChannel.close() else ResultFactory.success(Unit)
            } else {
                session?.close()
                ResultFactory.success(Unit)
            }
        } catch (error: Exception) {
            ResultFactory.failure(
                ContractError.InternalError(
                    "Failed to shut down LSP provider",
                    error.message ?: error::class.simpleName,
                ),
            )
        } finally {
            editorExecutor?.shutdownNow()
        }
    }

    private fun timeoutFor(request: SemanticRequest): Long {
        val configured = config.timeoutMs.coerceAtLeast(1)
        val remaining = request.deadline.timeRemainingMs
        return if (remaining <= 0) 1 else minOf(configured, remaining).coerceAtLeast(1)
    }

    private fun positionParams(
        uri: String,
        position: LspPosition,
    ): LinkedHashMap<String, Any?> =
        linkedMapOf(
            "textDocument" to linkedMapOf("uri" to uri),
            "position" to linkedMapOf("line" to position.line, "character" to position.character),
        )

    private fun operationFor(kind: SemanticRequestKind): String =
        when (kind) {
            SemanticRequestKind.TYPE_AT -> "textDocument/hover"
            SemanticRequestKind.SYMBOL_AT -> "textDocument/documentSymbol"
            SemanticRequestKind.DEFINITION_OF -> "textDocument/definition"
            SemanticRequestKind.DIAGNOSTICS_FOR -> "textDocument/diagnostic"
            SemanticRequestKind.HOVER_AT -> "textDocument/hover"
            SemanticRequestKind.REFERENCES_AT -> "textDocument/references"
        }

    private fun unknownFailure(operation: String): ContractError =
        ContractError.InternalError("LSP request $operation failed without an error")

    private data class PulledDiagnostics(
        val version: Long,
        val resultId: String?,
        val diagnostics: List<Diagnostic>,
    )

    companion object {
        fun create(
            config: SemanticProviderConfig,
            language: String,
            hostChannel: HostSemanticChannel? = null,
            hostChannelProvider: HostSemanticChannelProvider? = null,
            transport: LspTransport? = null,
            typeExtractor: LspTypeExtractor = DefaultLspTypeExtractor,
        ): ContractResult<LspSemanticProvider> {
            if (language.isBlank()) {
                return ResultFactory.failure(
                    ContractError.PreconditionFailed("LSP provider requires a descriptor-supplied language id"),
                )
            }
            val languageId = config.languageOverrides[language] ?: language
            return when (config.mode) {
                ProviderMode.EDITOR -> createEditor(config, language, languageId, hostChannel, hostChannelProvider, typeExtractor)
                ProviderMode.MANAGED -> createManaged(config, language, languageId, transport, typeExtractor)
                ProviderMode.ATTACHED -> createAttached(config, language, languageId, transport, typeExtractor)
            }
        }

        fun create(
            language: String,
            config: SemanticProviderConfig,
            hostChannel: HostSemanticChannel? = null,
            hostChannelProvider: HostSemanticChannelProvider? = null,
            transport: LspTransport? = null,
            typeExtractor: LspTypeExtractor = DefaultLspTypeExtractor,
        ): ContractResult<LspSemanticProvider> = create(config, language, hostChannel, hostChannelProvider, transport, typeExtractor)

        private fun createEditor(
            config: SemanticProviderConfig,
            language: String,
            languageId: String,
            hostChannel: HostSemanticChannel?,
            hostChannelProvider: HostSemanticChannelProvider?,
            typeExtractor: LspTypeExtractor,
        ): ContractResult<LspSemanticProvider> {
            val ownsChannel = hostChannel == null
            val channel =
                hostChannel ?: run {
                    val channelConfig =
                        config.hostChannelConfig
                            ?: return ResultFactory.failure(
                                ContractError.PreconditionFailed(
                                    "Editor semantic mode requires hostChannelConfig or an injected HostSemanticChannel",
                                ),
                            )
                    val provider =
                        hostChannelProvider
                            ?: return ResultFactory.failure(
                                ContractError.PreconditionFailed(
                                    "Editor semantic mode requires a HostSemanticChannelProvider for '${channelConfig.type}'",
                                ),
                            )
                    when (val opened = provider.open(channelConfig)) {
                        is dev.cint.contracts.util.Success -> opened.contents
                        is dev.cint.contracts.util.Failure -> return ResultFactory.failure(opened.error)
                    }
                }
            return ResultFactory.success(
                LspSemanticProvider(config, language, languageId, null, channel, ownsChannel, typeExtractor),
            )
        }

        private fun createManaged(
            config: SemanticProviderConfig,
            language: String,
            languageId: String,
            suppliedTransport: LspTransport?,
            typeExtractor: LspTypeExtractor,
        ): ContractResult<LspSemanticProvider> {
            val transport =
                suppliedTransport ?: run {
                    val command =
                        config.command?.takeIf { it.isNotBlank() }
                            ?: return ResultFactory.failure(
                                ContractError.PreconditionFailed("Managed LSP mode requires a command"),
                            )
                    val parsed =
                        try {
                            CommandLine.parse(command)
                        } catch (error: Exception) {
                            return ResultFactory.failure(
                                ContractError.InvalidInput(error.message ?: "Invalid managed LSP command", command),
                            )
                        }
                    val endpoint = config.endpoint?.takeIf { it.isNotBlank() && it != "stdio" }
                    try {
                        if (endpoint == null) {
                            StdioTransport(parsed)
                        } else {
                            ManagedEndpointTransport(parsed, LspEndpoint.parse(endpoint), config.initTimeoutMs)
                        }
                    } catch (error: Exception) {
                        return ResultFactory.failure(
                            ContractError.DependencyUnavailable(
                                "lsp",
                                "Failed to start managed LSP command '$command': ${error.message ?: error::class.simpleName}",
                            ),
                        )
                    }
                }
            return startSession(config, language, languageId, transport, typeExtractor)
        }

        private fun createAttached(
            config: SemanticProviderConfig,
            language: String,
            languageId: String,
            suppliedTransport: LspTransport?,
            typeExtractor: LspTypeExtractor,
        ): ContractResult<LspSemanticProvider> {
            val transport =
                suppliedTransport ?: run {
                    val endpoint =
                        config.endpoint?.takeIf { it.isNotBlank() }
                            ?: return ResultFactory.failure(
                                ContractError.PreconditionFailed("Attached LSP mode requires an endpoint"),
                            )
                    try {
                        when (val parsed = LspEndpoint.parse(endpoint)) {
                            is LspEndpoint.Unix -> UnixSocketTransport(parsed.path)
                            is LspEndpoint.Tcp -> TcpSocketTransport(parsed.host, parsed.port)
                        }
                    } catch (error: Exception) {
                        return ResultFactory.failure(
                            ContractError.DependencyUnavailable(
                                "lsp",
                                "Failed to connect to LSP endpoint '$endpoint': ${error.message ?: error::class.simpleName}",
                            ),
                        )
                    }
                }
            return startSession(config, language, languageId, transport, typeExtractor)
        }

        private fun startSession(
            config: SemanticProviderConfig,
            language: String,
            languageId: String,
            transport: LspTransport,
            typeExtractor: LspTypeExtractor,
        ): ContractResult<LspSemanticProvider> {
            val rootUri =
                try {
                    config.rootPath
                        ?.takeIf { it.isNotBlank() }
                        ?.let {
                            Path
                                .of(it)
                                .toAbsolutePath()
                                .normalize()
                                .toUri()
                                .toString()
                        }
                } catch (error: Exception) {
                    transport.close()
                    return ResultFactory.failure(
                        ContractError.InvalidInput(
                            "Invalid LSP root path '${config.rootPath}': ${error.message ?: error::class.simpleName}",
                            config.rootPath,
                        ),
                    )
                }
            val session =
                try {
                    LspSession(
                        transport,
                        LspSessionOptions(
                            languageId = languageId,
                            rootUri = rootUri,
                            requestTimeoutMs = config.timeoutMs,
                            initTimeoutMs = config.initTimeoutMs,
                        ),
                    )
                } catch (error: Exception) {
                    transport.close()
                    return ResultFactory.failure(
                        ContractError.DependencyUnavailable(
                            "lsp",
                            "Failed to create LSP session: ${error.message ?: error::class.simpleName}",
                        ),
                    )
                }
            val initialized =
                try {
                    session.initialize()
                } catch (error: Exception) {
                    session.close()
                    transport.close()
                    return ResultFactory.failure(
                        ContractError.DependencyUnavailable(
                            "lsp",
                            "LSP initialization failed: ${error.message ?: error::class.simpleName}",
                        ),
                    )
                }
            if (initialized.isFailure) {
                val error = initialized.errorOrNull ?: ContractError.DependencyUnavailable("lsp", "LSP initialization failed")
                session.close()
                val diagnostics = transport.diagnosticText()
                val withContext =
                    if (error is ContractError.DependencyUnavailable && !diagnostics.isNullOrBlank()) {
                        error.copy(reason = "${error.reason}; server stderr: $diagnostics")
                    } else {
                        error
                    }
                return ResultFactory.failure(withContext)
            }
            return ResultFactory.success(
                LspSemanticProvider(config, language, languageId, session, null, false, typeExtractor),
            )
        }
    }
}

object LspProviderFactory {
    fun create(
        config: SemanticProviderConfig,
        language: String,
        hostChannel: HostSemanticChannel? = null,
        hostChannelProvider: HostSemanticChannelProvider? = null,
        transport: LspTransport? = null,
        typeExtractor: LspTypeExtractor = DefaultLspTypeExtractor,
    ): ContractResult<LspSemanticProvider> =
        LspSemanticProvider.create(config, language, hostChannel, hostChannelProvider, transport, typeExtractor)

    fun create(
        language: String,
        config: SemanticProviderConfig,
        hostChannel: HostSemanticChannel? = null,
        hostChannelProvider: HostSemanticChannelProvider? = null,
        transport: LspTransport? = null,
        typeExtractor: LspTypeExtractor = DefaultLspTypeExtractor,
    ): ContractResult<LspSemanticProvider> =
        LspSemanticProvider.create(config, language, hostChannel, hostChannelProvider, transport, typeExtractor)
}

internal class LspResultException(
    message: String,
) : RuntimeException(message)

private fun hoverTextOf(value: Any?): String? =
    when (value) {
        null -> {
            null
        }

        is String -> {
            value
        }

        is Map<*, *> -> {
            when {
                value["contents"] != null -> hoverTextOf(value["contents"])
                value["value"] is String -> value["value"] as String
                else -> null
            }
        }

        is List<*> -> {
            value.mapNotNull { hoverTextOf(it) }.joinToString("\n").takeIf { it.isNotBlank() }
        }

        else -> {
            null
        }
    }

private fun parseLocations(value: Any?): List<Location> {
    if (value == null) return emptyList()
    val items =
        when (value) {
            is List<*> -> value
            is Map<*, *> -> listOf(value)
            else -> throw LspResultException("Expected an LSP location or location array")
        }
    return items.mapNotNull { item ->
        val objectValue = item.asObjectOrNull() ?: return@mapNotNull null
        val uri = objectValue.string("uri") ?: objectValue.string("targetUri") ?: return@mapNotNull null
        val range =
            objectValue["range"]
                ?: objectValue["targetSelectionRange"]
                ?: objectValue["targetRange"]
                ?: return@mapNotNull null
        val span = parseSpan(range) ?: return@mapNotNull null
        Location(uri, span)
    }
}

private data class SymbolCandidate(
    val info: SymbolInfo,
    val area: Long,
)

private fun findSymbol(
    value: Any?,
    position: LspPosition,
): SymbolInfo? {
    val items =
        when (value) {
            null -> return null
            is List<*> -> value
            else -> throw LspResultException("Expected an LSP document symbol array")
        }
    return findSymbolIn(items, position, null)?.info
}

private fun findSymbolIn(
    items: List<*>,
    position: LspPosition,
    container: String?,
): SymbolCandidate? {
    var best: SymbolCandidate? = null
    for (item in items) {
        val objectValue = item.asObjectOrNull() ?: continue
        val name = objectValue.string("name") ?: continue
        val range =
            objectValue["range"]
                ?: objectValue.objectOrNull("location")?.get("range")
                ?: continue
        val span = parseSpan(range) ?: continue
        if (!contains(span, position)) continue
        val containerName = container ?: objectValue.string("containerName")
        val kind = mapSymbolKind(objectValue.int("kind"))
        val info =
            SymbolInfo(
                name = name,
                kind = kind,
                containerName = containerName,
                fqName = containerName?.let { "$it.$name" } ?: name,
            )
        val candidate = SymbolCandidate(info, span.endOffset - span.startOffset)
        if (best == null || candidate.area < best.area) best = candidate
        val children = objectValue.listOrNull("children")
        if (children != null) {
            val nested = findSymbolIn(children, position, name)
            if (nested != null && nested.area < best.area) best = nested
        }
    }
    return best
}

private fun contains(
    span: Span,
    position: LspPosition,
): Boolean {
    val offset = (position.line.toLong() shl 32) or (position.character.toLong() and 0xFFFF_FFFFL)
    return span.startOffset <= offset && span.endOffset >= offset
}

private fun mapSymbolKind(kind: Int?): SymbolKind =
    when (kind) {
        2 -> SymbolKind.MODULE
        3 -> SymbolKind.NAMESPACE
        4 -> SymbolKind.PACKAGE
        5 -> SymbolKind.CLASS
        6 -> SymbolKind.METHOD
        7 -> SymbolKind.PROPERTY
        8 -> SymbolKind.FIELD
        9 -> SymbolKind.METHOD
        10 -> SymbolKind.ENUM
        11 -> SymbolKind.INTERFACE
        12 -> SymbolKind.FUNCTION
        13 -> SymbolKind.VARIABLE
        14 -> SymbolKind.PROPERTY
        16 -> SymbolKind.VARIABLE
        22 -> SymbolKind.FIELD
        23 -> SymbolKind.CLASS
        26 -> SymbolKind.TYPE_PARAMETER
        else -> SymbolKind.VARIABLE
    }
