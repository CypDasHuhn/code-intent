package dev.cint.v2.server

import dev.cint.contracts.data.AnalysisResult
import dev.cint.contracts.data.Intents
import dev.cint.contracts.facts.SourceInput
import dev.cint.contracts.ports.AnalysisRequest
import dev.cint.contracts.ports.Runtime
import dev.cint.contracts.ports.RuntimePack
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContentHash
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory
import dev.cint.ipc.AnalyzeParams
import dev.cint.ipc.CandidateDecisionParams
import dev.cint.ipc.ConfigOperation
import dev.cint.ipc.ConfigParams
import dev.cint.ipc.DocumentCloseParams
import dev.cint.ipc.DocumentParams
import dev.cint.ipc.IpcErrors
import dev.cint.ipc.IpcHandler
import dev.cint.ipc.IpcMethods
import dev.cint.ipc.IpcNotifications
import dev.cint.ipc.IpcResult
import dev.cint.ipc.IpcResults
import dev.cint.ipc.JsonRpcError
import dev.cint.ipc.JsonRpcErrorCodes
import dev.cint.ipc.JsonRpcMessage
import dev.cint.ipc.MetricsResult
import dev.cint.ipc.ProtocolFingerprint
import dev.cint.ipc.RestrictionRemoveParams
import dev.cint.ipc.RestrictionUpsertParams
import dev.cint.ipc.RestrictionsListParams
import dev.cint.ipc.ServerInfo
import dev.cint.ipc.StatusResult
import dev.cint.ipc.SuggestCancelParams
import dev.cint.ipc.SuggestHistoryParams
import dev.cint.ipc.SuggestLogParams
import dev.cint.ipc.SuggestParams
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

internal class SessionHandler(
    private val runtime: Runtime,
    private val options: ServerOptions,
    private val session: ClientSession,
    private val server: LiveServer,
    private val gate: RuntimeGate,
) : IpcHandler {
    private val documents = DocumentStore(options.defaultLanguage)
    private val jobs = ConcurrentHashMap<String, AnalysisJob>()
    private val analyses =
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "cint-analysis").apply { isDaemon = true }
        }

    @Volatile
    private var legacy = false

    @Volatile
    private var closed = false

    @Volatile
    var shutdownRequested: Boolean = false
        private set

    override fun initialize(params: dev.cint.ipc.InitializeParams): IpcResult {
        legacy = params.protocol < ProtocolFingerprint.CURRENT_PROTOCOL_VERSION
        val packs = gate.call { runtime.packs() }.getOrElse(emptyList())
        val knownIntents = knownIntents(packs)
        val config =
            buildJsonObject {
                put("packs", JsonArray(packs.map(::packJson)))
                put("overlay", JsonObject(emptyMap()))
            }
        val suggestions =
            if (legacy) {
                legacySuggestions(params.protocol)
            } else {
                buildJsonObject {
                    put("enabled", false)
                    put("trigger", "manual")
                    put("provider", JsonNull)
                    put("model", JsonNull)
                    put("knownIntents", JsonArray(knownIntents.map(::JsonPrimitive)))
                    put("discovery", false)
                    put("restrictions", false)
                    put("cache", false)
                    put("maxUnitsPerRequest", 0)
                }
            }
        val capabilities =
            buildJsonObject {
                put("analyze", true)
                put("documents", true)
                put("status", true)
                put("metrics", true)
                put("config", false)
                put("suggest", false)
                put("restrictions", false)
                put("candidates", false)
            }
        return IpcResults.of(
            dev.cint.ipc.InitializeResult(
                root = options.root.toString(),
                languages = options.languages,
                config = config,
                suggestions = suggestions,
                server = ServerInfo(name = "cint-server", version = options.version),
                capabilities = capabilities,
            ),
        )
    }

    override fun analyze(params: AnalyzeParams): IpcResult {
        val uri = params.uri?.takeIf { it.isNotBlank() } ?: params.targetPaths.firstOrNull()
        if (uri == null) {
            throw IpcErrors.invalidParams("analyze requires uri or targetPaths")
        }
        val document = documents.current(uri)
        if (document != null) {
            val job = schedule(document, analysisRequest(params, document))
            val result = await(job)
            if (result.isFailure) {
                return failure(result.errorOrNull!!)
            }
            if (legacy) return IpcResults.value(JsonPrimitive(true))
            val hash = ContentHash.compute(document.text).value
            return IpcResults.of(analyzeResultOf(uri, hash, result.getOrThrow()))
        }

        val targets = params.targetPaths.ifEmpty { listOf(uri) }
        val notifications = mutableListOf<JsonRpcMessage>()
        val results = mutableListOf<Pair<String, AnalysisResult>>()
        for (target in targets) {
            val analysisTarget = pathOf(target)
            val hash = contentHash(analysisTarget)
            if (hash.isFailure) {
                return failure(hash.errorOrNull!!)
            }
            val request = analysisRequest(params, analysisTarget)
            val result = analyzeNonOpen(request)
            if (result.isFailure) {
                return failure(result.errorOrNull!!)
            }
            val analysis = result.getOrThrow()
            val published = gate.call { runtime.publish(analysis, CancellationToken.from { closed }) }
            if (published.isFailure) {
                return failure(published.errorOrNull!!)
            }
            val revision = documents.nextRevision(target)
            notifications += publishMessages(target, 0, hash.getOrThrow(), revision, analysis)
            results += target to analysis
        }
        if (legacy) {
            return IpcResults.value(JsonPrimitive(true), notifications)
        }
        return IpcResults.of(analyzeResultForTargets(results), notifications)
    }

    override fun didOpen(params: DocumentParams): List<JsonRpcMessage> {
        val document = documents.open(params) ?: return emptyList()
        schedule(document, analysisRequest(document))
        return emptyList()
    }

    override fun didChange(params: DocumentParams): List<JsonRpcMessage> {
        val document = documents.change(params) ?: return emptyList()
        schedule(document, analysisRequest(document))
        return emptyList()
    }

    override fun didClose(params: DocumentCloseParams): List<JsonRpcMessage> {
        jobs.remove(params.uri)?.cancel()
        documents.close(params.uri)
        return emptyList()
    }

    override fun suggest(params: SuggestParams): IpcResult = unsupported(IpcMethods.SUGGEST)

    override fun suggestCancel(params: SuggestCancelParams): IpcResult = unsupported(IpcMethods.SUGGEST_CANCEL)

    override fun suggestLog(params: SuggestLogParams): IpcResult = unsupported(IpcMethods.SUGGEST_LOG)

    override fun suggestStream(params: SuggestParams): IpcResult = unsupported(IpcMethods.SUGGEST_STREAM)

    override fun suggestHistory(params: SuggestHistoryParams): IpcResult = unsupported(IpcMethods.SUGGEST_HISTORY)

    override fun config(
        operation: ConfigOperation,
        params: ConfigParams,
    ): IpcResult {
        if (legacy && operation in LEGACY_CONFIG_MUTATIONS) {
            documents.bumpRevisions()
        }
        return unsupported(
            when (operation) {
                ConfigOperation.READ -> IpcMethods.CONFIG_READ
                ConfigOperation.APPEND -> IpcMethods.CONFIG_APPEND
                ConfigOperation.REMOVE -> IpcMethods.CONFIG_REMOVE
                ConfigOperation.WRITE -> IpcMethods.CONFIG_WRITE
                ConfigOperation.SCHEMA -> IpcMethods.CONFIG_SCHEMA
            },
        )
    }

    override fun status(): IpcResult {
        val status = gate.call { runtime.status(CancellationToken.from { closed }) }
        if (status.isFailure) return failure(status.errorOrNull!!)
        status.getOrThrow()
        return IpcResults.of(
            StatusResult(
                status = "ok",
                version = options.version,
                uptimeMs = server.uptimeMs(),
                activeDocuments = server.activeDocuments(),
                suggestionQueue = 0,
            ),
        )
    }

    override fun metrics(): IpcResult {
        val status = gate.call { runtime.status(CancellationToken.from { closed }) }
        if (status.isFailure) return failure(status.errorOrNull!!)
        val text =
            buildString {
                appendLine("# TYPE cint_active_documents gauge")
                appendLine("cint_active_documents ${server.activeDocuments()}")
                appendLine("# TYPE cint_indexed_files gauge")
                appendLine("cint_indexed_files ${status.getOrThrow().indexedFiles}")
                appendLine("# TYPE cint_uptime_ms gauge")
                appendLine("cint_uptime_ms ${server.uptimeMs()}")
            }
        return IpcResults.of(MetricsResult(text))
    }

    override fun restrictionsList(params: RestrictionsListParams): IpcResult = unsupported(IpcMethods.RESTRICTIONS)

    override fun restrictionsRemove(params: RestrictionRemoveParams): IpcResult = unsupported(IpcMethods.RESTRICTIONS_REMOVE)

    override fun restrictionsUpsert(params: RestrictionUpsertParams): IpcResult = unsupported(IpcMethods.RESTRICTIONS_UPSERT)

    override fun candidateDecision(params: CandidateDecisionParams): IpcResult = unsupported(IpcMethods.CANDIDATES_DECIDE)

    override fun shutdown(): IpcResult {
        shutdownRequested = true
        return IpcResults.value(JsonPrimitive(true))
    }

    fun activeDocumentCount(): Int = documents.activeCount()

    fun close() {
        if (closed) return
        closed = true
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        analyses.shutdownNow()
        runCatching { analyses.awaitTermination(options.closeGraceMs, TimeUnit.MILLISECONDS) }
    }

    private fun schedule(
        document: OpenDocument,
        request: AnalysisRequest,
    ): AnalysisJob {
        jobs.remove(document.uri)?.cancel()
        val job = AnalysisJob(document, request)
        jobs[document.uri] = job
        try {
            analyses.execute { runAnalysis(job) }
        } catch (_: Exception) {
            job.cancel()
        }
        return job
    }

    private fun runAnalysis(job: AnalysisJob) {
        try {
            val document = job.document
            val cancellation = cancellationFor(job)
            val source =
                SourceInput.InMemory(
                    text = document.text,
                    language = document.languageId,
                    version = document.version.toLong(),
                    path = pathOf(document.uri),
                    documentId = document.uri,
                )
            val analysis =
                try {
                    gate.call { runtime.analyzeSource(source, job.request, cancellation) }
                } catch (error: Exception) {
                    ResultFactory.failure(ContractError.InternalError(error.message ?: "analysis failed"))
                }
            if (analysis.isFailure) {
                job.complete(analysis)
                return
            }
            if (!documents.isCurrent(document.uri, document.generation) || cancellation.isCancelled) {
                job.complete(ResultFactory.failure(ContractError.Cancelled("analysis")))
                return
            }
            val result = analysis.getOrThrow()
            if (!documents.isCurrent(document.uri, document.generation)) {
                job.complete(ResultFactory.failure(ContractError.Cancelled("analysis")))
                return
            }
            val published = gate.call { runtime.publish(result, cancellation) }
            if (published.isFailure) {
                job.complete(ResultFactory.failure(published.errorOrNull!!))
                return
            }
            val delivered =
                runCatching {
                    documents.publishIfCurrent(document.uri, document.generation) { revision ->
                        session.deliver(publishMessages(document, result, revision))
                    }
                }.getOrDefault(false)
            if (!delivered) {
                job.complete(ResultFactory.failure(ContractError.Cancelled("analysis")))
                return
            }
            job.complete(ResultFactory.success(result))
        } catch (error: Exception) {
            job.complete(ResultFactory.failure(ContractError.InternalError(error.message ?: "analysis failed")))
        } finally {
            jobs.remove(job.document.uri, job)
        }
    }

    private fun cancellationFor(job: AnalysisJob): CancellationToken =
        CancellationToken.from {
            closed ||
                job.cancelled.get() ||
                !documents.isCurrent(job.document.uri, job.document.generation)
        }

    private fun await(job: AnalysisJob): ContractResult<AnalysisResult> {
        val deadline = System.currentTimeMillis() + options.analysisTimeoutMs
        while (true) {
            if (closed) {
                job.cancel()
                return ResultFactory.failure(ContractError.Cancelled("analysis"))
            }
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) {
                job.cancel()
                return ResultFactory.failure(
                    ContractError.Timeout("analysis", options.analysisTimeoutMs),
                )
            }
            try {
                return job.future.get(minOf(remaining, 100), TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                job.cancel()
                return ResultFactory.failure(ContractError.Cancelled("analysis"))
            } catch (error: ExecutionException) {
                job.cancel()
                return ResultFactory.failure(
                    ContractError.InternalError(error.cause?.message ?: "analysis failed"),
                )
            }
        }
    }

    private fun failure(error: ContractError): IpcResult = IpcResults.failure(errorOf(error))

    private fun analyzeNonOpen(request: AnalysisRequest): ContractResult<AnalysisResult> {
        val cancelled = AtomicBoolean(false)
        val future = CompletableFuture<ContractResult<AnalysisResult>>()
        try {
            analyses.execute {
                val token = CancellationToken.from { closed || cancelled.get() }
                future.complete(gate.call { runtime.analyze(request, token) })
            }
        } catch (error: Exception) {
            return ResultFactory.failure(ContractError.InternalError(error.message ?: "analysis failed"))
        }
        return try {
            awaitFuture(future, "analysis")
        } finally {
            if (!future.isDone) {
                cancelled.set(true)
                future.cancel(true)
            }
        }
    }

    private fun <T> awaitFuture(
        future: CompletableFuture<ContractResult<T>>,
        operation: String,
    ): ContractResult<T> {
        val deadline = System.currentTimeMillis() + options.analysisTimeoutMs
        while (true) {
            if (closed) return ResultFactory.failure(ContractError.Cancelled(operation))
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) {
                return ResultFactory.failure(ContractError.Timeout(operation, options.analysisTimeoutMs))
            }
            try {
                return future.get(minOf(remaining, 100), TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return ResultFactory.failure(ContractError.Cancelled(operation))
            } catch (error: ExecutionException) {
                return ResultFactory.failure(
                    ContractError.InternalError(error.cause?.message ?: "analysis failed"),
                )
            }
        }
    }

    private fun contentHash(target: String): ContractResult<String> {
        val path =
            try {
                val raw = if (target.startsWith("file:")) Path.of(URI(target)) else Path.of(target)
                if (raw.isAbsolute) raw else options.root.resolve(raw).normalize()
            } catch (error: Exception) {
                return ResultFactory.failure(
                    ContractError.InvalidInput("Invalid analysis target '$target'", target),
                )
            }
        return try {
            val value =
                if (legacy) {
                    legacyContentHash(Files.readString(path))
                } else {
                    ContentHash.compute(Files.readAllBytes(path)).value
                }
            ResultFactory.success(value)
        } catch (_: Exception) {
            ResultFactory.failure(ContractError.NotFound("analysis-target", target))
        }
    }

    private fun publishMessages(
        document: OpenDocument,
        analysis: AnalysisResult,
        revision: Long,
    ): List<JsonRpcMessage> {
        val hash = hashOf(document.text)
        return publishMessages(document.uri, document.version, hash, revision, analysis, document.text)
    }

    private fun publishMessages(
        uri: String,
        version: Int,
        hash: String,
        revision: Long,
        analysis: AnalysisResult,
        text: String? = null,
    ): List<JsonRpcMessage> =
        if (legacy) {
            legacyPublishMessages(uri, version, hash, revision, analysis, text, documents.configRevision(uri))
        } else {
            listOf(
                IpcNotifications.publishTags(tagsParamsOf(uri, version, hash, revision, analysis)),
                IpcNotifications.publishCandidates(candidatesParamsOf(uri, version, hash, revision, analysis)),
            )
        }

    private fun hashOf(text: String): String =
        if (legacy) {
            legacyContentHash(text)
        } else {
            ContentHash.compute(text).value
        }

    private fun analysisRequest(
        params: AnalyzeParams,
        document: OpenDocument,
    ): AnalysisRequest =
        AnalysisRequest(
            targetPaths = listOf(document.uri),
            language = params.language ?: document.languageId,
            options = params.options + ("rootPath" to (params.options["rootPath"] ?: options.root.toString())),
            forceFullAnalysis = params.forceFullAnalysis,
        )

    private fun analysisRequest(document: OpenDocument): AnalysisRequest =
        AnalysisRequest(
            targetPaths = listOf(document.uri),
            language = document.languageId,
            options = mapOf("rootPath" to options.root.toString()),
        )

    private fun analysisRequest(
        params: AnalyzeParams,
        target: String,
    ): AnalysisRequest =
        AnalysisRequest(
            targetPaths = listOf(target),
            language = params.language ?: options.defaultLanguage,
            options = params.options + ("rootPath" to (params.options["rootPath"] ?: options.root.toString())),
            forceFullAnalysis = params.forceFullAnalysis,
        )

    private fun knownIntents(packs: List<RuntimePack>): List<String> {
        val available = (packs.flatMap { it.manifest.providesIntents } + Intents.UNTAGGED).toSet()
        return if (legacy) {
            LEGACY_KNOWN_INTENTS.filter { it in available }.ifEmpty { LEGACY_KNOWN_INTENTS }.sorted()
        } else {
            available.sorted()
        }
    }

    private fun packJson(pack: RuntimePack): JsonObject =
        buildJsonObject {
            put("packId", pack.manifest.id)
            put("name", pack.manifest.name)
            put("version", pack.manifest.version)
            put(
                "languages",
                JsonArray(
                    pack.manifest.languages
                        .sorted()
                        .map(::JsonPrimitive),
                ),
            )
            put("fields", JsonArray(emptyList()))
            put(
                "rules",
                JsonArray(
                    pack.playbook.rules.map { rule ->
                        buildJsonObject {
                            put("id", rule.id)
                            put("owns", rule.owns)
                            put("reads", JsonArray(rule.reads.map(::JsonPrimitive)))
                        }
                    },
                ),
            )
        }

    private fun legacySuggestions(protocol: Int): JsonObject =
        buildJsonObject {
            val enhanced = protocol >= 4
            put("enabled", false)
            put("trigger", "manual")
            put("provider", JsonNull)
            put("model", JsonNull)
            put("knownIntents", JsonArray(LEGACY_KNOWN_INTENTS.sorted().map(::JsonPrimitive)))
            put("discovery", false)
            put("restrictions", enhanced)
            put("cache", enhanced)
            put("maxUnitsPerRequest", 24)
        }

    private fun errorOf(error: ContractError): JsonRpcError {
        val (code, kind) =
            when (error) {
                is ContractError.ValidationError -> JsonRpcErrorCodes.INVALID_PARAMS to "validation-error"
                is ContractError.InvalidInput -> JsonRpcErrorCodes.INVALID_PARAMS to "invalid-input"
                is ContractError.SerializationError -> JsonRpcErrorCodes.INVALID_PARAMS to "serialization-error"
                is ContractError.NotFound -> JsonRpcErrorCodes.PROTOCOL_ERROR to "not-found"
                is ContractError.AlreadyExists -> JsonRpcErrorCodes.PROTOCOL_ERROR to "already-exists"
                is ContractError.PreconditionFailed -> JsonRpcErrorCodes.PROTOCOL_ERROR to "precondition-failed"
                is ContractError.DependencyUnavailable -> JsonRpcErrorCodes.PROTOCOL_ERROR to "dependency-unavailable"
                is ContractError.Timeout -> JsonRpcErrorCodes.PROTOCOL_ERROR to "timeout"
                is ContractError.Cancelled -> JsonRpcErrorCodes.PROTOCOL_ERROR to "cancelled"
                is ContractError.InternalError -> JsonRpcErrorCodes.INTERNAL_ERROR to "internal-error"
            }
        return JsonRpcError(
            code,
            "analysis failed: $kind",
            buildJsonObject {
                put("kind", kind)
                put("details", detailsOf(error))
            },
        )
    }

    private fun detailsOf(error: ContractError): JsonObject =
        buildJsonObject {
            when (error) {
                is ContractError.ValidationError -> {
                    error.field?.let { put("field", it) }
                }

                is ContractError.InvalidInput -> {
                    error.input?.let { put("input", it) }
                }

                is ContractError.NotFound -> {
                    put("resource", error.resource)
                    put("identifier", error.identifier)
                }

                is ContractError.AlreadyExists -> {
                    put("resource", error.resource)
                    put("identifier", error.identifier)
                }

                is ContractError.DependencyUnavailable -> {
                    put("dependency", error.dependency)
                }

                is ContractError.Timeout -> {
                    put("operation", error.operation)
                    put("timeoutMs", error.timeoutMs)
                }

                is ContractError.Cancelled -> {
                    put("operation", error.operation)
                }

                is ContractError.PreconditionFailed,
                is ContractError.InternalError,
                is ContractError.SerializationError,
                -> {
                    Unit
                }
            }
        }

    private fun unsupported(method: String): IpcResult =
        IpcResults.failure(
            JsonRpcError(
                JsonRpcErrorCodes.PROTOCOL_ERROR,
                "unsupported in the v2 server: $method",
                buildJsonObject {
                    put("kind", "unsupported-operation")
                    put(
                        "details",
                        buildJsonObject {
                            put("method", method)
                        },
                    )
                },
            ),
        )

    private class AnalysisJob(
        val document: OpenDocument,
        val request: AnalysisRequest,
    ) {
        val cancelled = AtomicBoolean(false)
        val future = CompletableFuture<ContractResult<AnalysisResult>>()

        fun cancel() {
            cancelled.set(true)
            future.complete(ResultFactory.failure(ContractError.Cancelled("analysis")))
        }

        fun complete(result: ContractResult<AnalysisResult>) {
            future.complete(result)
        }
    }

    private companion object {
        val LEGACY_CONFIG_MUTATIONS =
            setOf(
                ConfigOperation.APPEND,
                ConfigOperation.REMOVE,
                ConfigOperation.WRITE,
            )

        val LEGACY_KNOWN_INTENTS =
            listOf(
                "BOOKKEEPING",
                "DATA_MODIFY",
                "DATA_TRANSFORMATION",
                "ERROR_HANDLING",
                "LOCAL_COPY",
                "LOCAL_VALUE_MODIFY",
                "LOGGING",
                "LOOKUP",
                "NORMALIZATION",
                "RETURN_VALUE",
                "SEARCH",
                "SERVICE_DELEGATION",
                "SERVICE_INIT",
                "UNTAGGED",
                "VALIDATION",
            )
    }
}

private fun pathOf(uri: String): String =
    if (uri.startsWith("file:")) {
        runCatching { Path.of(URI(uri)).toString() }.getOrDefault(uri)
    } else {
        uri
    }
