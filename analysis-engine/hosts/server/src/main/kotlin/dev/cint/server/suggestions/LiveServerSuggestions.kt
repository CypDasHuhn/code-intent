package dev.cint.server.suggestions

import com.fasterxml.jackson.databind.JsonNode
import dev.cint.api.CandidateEmit
import dev.cint.core.support.hashing.contentHash
import dev.cint.frontend.Frontends
import dev.cint.server.LiveServer
import dev.cint.server.live.DocumentKey
import dev.cint.server.live.SessionLog
import dev.cint.server.live.SuggestPass
import dev.cint.server.live.docsFor
import dev.cint.server.live.publishCandidates
import dev.cint.server.live.relativePath
import dev.cint.suggest.model.CandidateDocument
import dev.cint.suggest.model.SuggestionOptions
import dev.cint.suggest.session.SessionLimits
import dev.cint.suggest.session.SessionProgress
import dev.cint.suggest.session.SessionResult
import java.io.BufferedWriter
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal fun LiveServer.suggestionCapability(clientProtocol: Int): Map<String, Any?> {
    val selectedProvider = suggestionSettings.effectiveProvider
    val provider = suggestionService.findProvider(selectedProvider)
    val supportsDiscovery = suggestionService.findSessionProvider(selectedProvider) != null
    return linkedMapOf(
        "enabled" to suggestionSettings.enabled,
        "trigger" to suggestionSettings.trigger,
        "provider" to (selectedProvider ?: provider?.id),
        "model" to (suggestionSettings.model ?: provider?.model),
        "knownIntents" to knownIntents(),
        "discovery" to (suggestionSettings.discoveryEnabled && supportsDiscovery && clientProtocol >= 4),
        "restrictions" to false,
        "cache" to false,
        "maxUnitsPerRequest" to suggestionSettings.discoveryMaxUnitsPerRequest,
    )
}

internal fun LiveServer.knownIntents(): List<String> =
    runCatching {
        packs.intents(Frontends.languages())
    }.getOrDefault(emptyList())

internal fun LiveServer.handleSuggest(
    writer: BufferedWriter,
    params: JsonNode?,
    id: JsonNode?,
) {
    if (!suggestionSettings.enabled) {
        return respond(
            writer,
            id,
            mapOf("accepted" to false, "disabled" to true, "count" to 0),
        )
    }
    val uri = params?.get("uri")?.asText() ?: return sendError(writer, id, "uri is required")
    val doc = docsFor(writer)[uri] ?: return sendError(writer, id, "document is not open: $uri")
    val version = params.get("version")?.asInt() ?: doc.version
    if (version != doc.version) {
        return respond(
            writer,
            id,
            mapOf("accepted" to false, "stale" to true, "count" to 0),
        )
    }
    val requestId = params.get("requestId")?.asText()
    pruneCancelledSuggestions()
    if (requestId != null && cancelledSuggestions.remove(requestId) != null) {
        return respond(
            writer,
            id,
            mapOf("accepted" to false, "cancelled" to true, "count" to 0),
        )
    }
    val key = DocumentKey(writer, uri)
    val pass =
        SuggestPass(
            requestId = requestId,
            version = version,
            baseStateRevision = candidateStateRevisions[key] ?: 0L,
        )
    val previous = inFlightSuggestions.putIfAbsent(key, pass)
    if (previous != null) {
        return respond(
            writer,
            id,
            mapOf("accepted" to false, "busy" to true, "count" to 0),
        )
    }
    val discoveryProvider = suggestionService.findSessionProvider(suggestionSettings.effectiveProvider)
    val clientProtocol = clientProtocols[writer] ?: 0
    val discovery = suggestionSettings.discoveryEnabled && discoveryProvider != null && clientProtocol >= 4
    val task =
        FutureTask(
            Callable {
                try {
                    if (pass.cancelled.get()) {
                        respond(writer, id, mapOf("accepted" to false, "cancelled" to true, "count" to 0))
                        return@Callable
                    }
                    val current = docs[writer]?.get(uri)
                    if (current == null || current.version != doc.version || current.text != doc.text) {
                        respond(writer, id, mapOf("accepted" to false, "stale" to true, "count" to 0))
                        return@Callable
                    }
                    val cached = analyses[key]
                    if (
                        cached == null ||
                        cached.version != current.version ||
                        cached.textHash != contentHash(current.text)
                    ) {
                        respond(writer, id, mapOf("accepted" to false, "stale" to true, "count" to 0))
                        return@Callable
                    }
                    val known = knownIntents().toSet()
                    val document =
                        CandidateDocument(
                            path = relativePath(uri),
                            version = current.version,
                            text = current.text,
                            knownIntents = known,
                            neighboringIntents =
                                cached.output.annotations
                                    .flatMap { it.intents }
                                    .toSet(),
                            language = current.language,
                        )
                    val providerFuture =
                        providerExecutor.submit(
                            Callable {
                                if (discovery) {
                                    discoveryPass(writer, current, cached, known, pass)
                                } else {
                                    SuggestOutcome(
                                        candidates =
                                            suggestionService.enrich(
                                                candidates = cached.output.candidates,
                                                document = document,
                                                options =
                                                    SuggestionOptions(
                                                        providerId = suggestionSettings.effectiveProvider,
                                                        model = suggestionSettings.effectiveModel,
                                                        maxCandidates = suggestionSettings.maxCandidates,
                                                        maxEnrichmentCandidates = suggestionSettings.maxEnrichmentCandidates,
                                                        promptVersion = suggestionSettings.promptVersion,
                                                        excerptRadiusBytes = suggestionSettings.excerptRadiusBytes,
                                                        allowGlobs = suggestionSettings.allowGlobs,
                                                        redactPatterns = suggestionSettings.redactPatterns,
                                                    ),
                                            ),
                                        session = null,
                                    )
                                }
                            },
                        )
                    val registered =
                        providerFutures.compute(key) { _, existing ->
                            if (inFlightSuggestions[key] === pass) providerFuture else existing
                        } === providerFuture
                    if (!registered) {
                        providerFuture.cancel(true)
                        respond(writer, id, mapOf("accepted" to false, "cancelled" to true, "count" to 0))
                        return@Callable
                    }
                    val outcome =
                        try {
                            val timeout =
                                if (discovery) {
                                    maxOf(
                                        suggestionSettings.timeoutMs,
                                        suggestionSettings.sessionTimeoutMs + suggestionSettings.timeoutMs,
                                    )
                                } else {
                                    suggestionSettings.timeoutMs
                                }
                            providerFuture.get(timeout, TimeUnit.MILLISECONDS)
                        } catch (_: TimeoutException) {
                            providerFuture.cancel(true)
                            respond(
                                writer,
                                id,
                                mapOf("accepted" to false, "cancelled" to true, "timeout" to true, "count" to 0),
                            )
                            return@Callable
                        } catch (error: ExecutionException) {
                            throw error.cause ?: error
                        } finally {
                            providerFutures.remove(key, providerFuture)
                        }
                    val latest = analyses[key]
                    val cancelled =
                        pass.cancelled.get() ||
                            docs[writer]?.get(uri) != current ||
                            latest?.analysisRevision != cached.analysisRevision
                    if (cancelled) {
                        respond(writer, id, mapOf("accepted" to false, "cancelled" to true, "count" to 0))
                        return@Callable
                    }
                    outcome.session?.let { sessionLogs[key] = SessionLog(it) }
                    val count =
                        publishCandidates(
                            writer,
                            current,
                            outcome.candidates,
                            cached.analysisRevision,
                            expectedStateRevision = pass.baseStateRevision,
                        )
                    if (count == null) {
                        respond(writer, id, mapOf("accepted" to false, "cancelled" to true, "count" to 0))
                    } else {
                        respond(writer, id, suggestResponse(count, outcome.session))
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    respond(writer, id, mapOf("accepted" to false, "cancelled" to true, "count" to 0))
                } catch (error: Exception) {
                    if (pass.cancelled.get()) {
                        respond(writer, id, mapOf("accepted" to false, "cancelled" to true, "count" to 0))
                    } else {
                        sendError(writer, id, error.message ?: error.toString())
                    }
                } finally {
                    inFlightSuggestions.remove(key, pass)
                    if (requestId != null) cancelledSuggestions.remove(requestId)
                }
            },
        )
    suggestionExecutor.execute(task)
}

private fun LiveServer.discoveryPass(
    writer: BufferedWriter,
    current: dev.cint.server.live.DocState,
    cached: dev.cint.server.live.CachedAnalysis,
    known: Set<String>,
    pass: SuggestPass,
): SuggestOutcome {
    val document =
        sessionDocument(
            doc = current,
            cached = cached,
            knownIntents = known,
            includeLeaves = suggestionSettings.discoveryIncludeLeaves,
        ) ?: return SuggestOutcome(cached.output.candidates, null)
    val typeLookup = sessionTypeLookup(current, document)
    val result =
        suggestionService.discover(
            document = document,
            options =
                SuggestionOptions(
                    providerId = suggestionSettings.effectiveProvider,
                    model = suggestionSettings.effectiveModel,
                    maxCandidates = suggestionSettings.maxCandidates,
                    promptVersion = suggestionSettings.promptVersion,
                    allowGlobs = suggestionSettings.allowGlobs,
                    redactPatterns = suggestionSettings.redactPatterns,
                ),
            limits =
                SessionLimits(
                    sessionTimeoutMs = suggestionSettings.sessionTimeoutMs,
                    requestTimeoutMs = suggestionSettings.timeoutMs,
                    maxTurns = suggestionSettings.maxTurns,
                    maxToolCalls = suggestionSettings.maxToolCalls,
                    maxUnitsPerRequest = suggestionSettings.discoveryMaxUnitsPerRequest,
                    maxTokensPerRequest = suggestionSettings.maxTokensPerRequest,
                    maxCandidates = suggestionSettings.maxCandidates,
                    maxConfidence = suggestionSettings.maxConfidence,
                ),
            typeLookup = typeLookup,
            onProgress = { progress ->
                val latest = docs[writer]?.get(current.uri)
                if (!pass.cancelled.get() && latest?.version == current.version && latest.text == current.text) {
                    publishProgress(writer, current, progress)
                }
            },
            isCancelled = {
                val latest = docs[writer]?.get(current.uri)
                pass.cancelled.get() ||
                    Thread.currentThread().isInterrupted ||
                    latest == null ||
                    latest.version != current.version ||
                    latest.text != current.text
            },
        )
    return SuggestOutcome(cached.output.candidates + result.candidates, result)
}

private fun LiveServer.publishProgress(
    writer: BufferedWriter,
    doc: dev.cint.server.live.DocState,
    progress: SessionProgress,
) {
    write(
        writer,
        mapOf(
            "jsonrpc" to "2.0",
            "method" to "cint/suggest/progress",
            "params" to
                linkedMapOf(
                    "uri" to doc.uri,
                    "version" to doc.version,
                    "graded" to progress.graded,
                    "reused" to progress.reused,
                    "total" to progress.total,
                    "activity" to progress.activity,
                ),
        ),
    )
}

private fun suggestResponse(
    count: Int,
    session: SessionResult?,
): Map<String, Any?> =
    linkedMapOf<String, Any?>(
        "accepted" to true,
        "cancelled" to false,
        "count" to count,
    ).apply {
        if (session != null) {
            put("total", session.total)
            put("graded", session.graded)
            put("reused", session.reused)
            put("additions", session.additions)
            put("toolCalls", session.toolCalls)
            put("turns", session.turns)
            put("incomplete", session.incomplete)
            put("budgetExhausted", session.budgetExhausted)
            put("skipped", session.skipped)
            put("uncovered", session.uncovered)
        }
    }

internal fun LiveServer.cancelInFlightSuggestion(
    writer: BufferedWriter,
    uri: String,
) {
    val key = DocumentKey(writer, uri)
    val pass = inFlightSuggestions[key] ?: return
    pass.cancelled.set(true)
    inFlightSuggestions.remove(key, pass)
    providerFutures.remove(key)?.cancel(true)
}

internal fun LiveServer.handleSuggestCancel(
    writer: BufferedWriter,
    params: JsonNode?,
    id: JsonNode?,
) {
    val uri = params?.get("uri")?.asText()
    val requestId = params?.get("requestId")?.asText()
    if (requestId != null) {
        cancelledSuggestions[requestId] = System.nanoTime()
        pruneCancelledSuggestions()
    }
    val keys =
        if (uri != null) {
            listOf(DocumentKey(writer, uri))
        } else {
            inFlightSuggestions.keys.filter { it.owner === writer }
        }
    for (key in keys) {
        val pass = inFlightSuggestions[key]
        if (pass != null && (requestId == null || pass.requestId == requestId)) {
            pass.cancelled.set(true)
            providerFutures[key]?.cancel(true)
        }
    }
    respond(writer, id, true)
}

internal fun LiveServer.handleSuggestLog(
    writer: BufferedWriter,
    params: JsonNode?,
    id: JsonNode?,
) {
    if ((clientProtocols[writer] ?: 0) < 4) {
        return sendError(writer, id, "cint/suggest/log requires protocol 4")
    }
    val uri = params?.get("uri")?.asText() ?: return sendError(writer, id, "uri is required")
    val key = DocumentKey(writer, uri)
    val result = sessionLogs[key]?.result
    respond(
        writer,
        id,
        linkedMapOf<String, Any?>(
            "uri" to uri,
            "total" to (result?.total ?: 0),
            "graded" to (result?.graded ?: 0),
            "reused" to (result?.reused ?: 0),
            "additions" to (result?.additions ?: 0),
            "toolCalls" to (result?.toolCalls ?: 0),
            "turns" to (result?.turns ?: 0),
            "incomplete" to (result?.incomplete ?: false),
            "budgetExhausted" to (result?.budgetExhausted ?: false),
            "uncovered" to result?.uncovered.orEmpty(),
            "events" to result?.events.orEmpty(),
        ),
    )
}

private data class SuggestOutcome(
    val candidates: List<CandidateEmit>,
    val session: SessionResult?,
)

private fun LiveServer.pruneCancelledSuggestions() {
    val cutoff = System.nanoTime() - TimeUnit.SECONDS.toNanos(60)
    cancelledSuggestions.entries.removeIf { it.value < cutoff }
}
