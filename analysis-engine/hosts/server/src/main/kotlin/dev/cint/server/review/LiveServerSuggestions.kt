package dev.cint.server.review

import com.fasterxml.jackson.databind.JsonNode
import dev.cint.server.LiveServer
import dev.cint.server.live.DocumentKey
import dev.cint.server.live.SuggestPass
import dev.cint.server.live.docsFor
import dev.cint.server.live.relativePath
import dev.cint.suggest.CandidateDocument
import dev.cint.suggest.SuggestionOptions
import java.io.BufferedWriter
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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
    val future =
        suggestionExecutor.submit {
            try {
                val current = docs[writer]?.get(uri)
                if (current == null || current.version != doc.version || current.text != doc.text) {
                    respond(writer, id, mapOf("accepted" to false, "stale" to true, "count" to 0))
                    return@submit
                }
                val cached = analyses[key]
                if (cached == null) {
                    respond(writer, id, mapOf("accepted" to false, "stale" to true, "count" to 0))
                    return@submit
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
                            )
                        },
                    )
                providerFutures[key] = providerFuture
                val enriched =
                    try {
                        providerFuture.get(suggestionSettings.timeoutMs, TimeUnit.MILLISECONDS)
                    } catch (_: TimeoutException) {
                        providerFuture.cancel(true)
                        respond(
                            writer,
                            id,
                            mapOf("accepted" to false, "cancelled" to true, "timeout" to true, "count" to 0),
                        )
                        return@submit
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
                    return@submit
                }
                val count =
                    publishCandidates(
                        writer,
                        current,
                        enriched,
                        cached.analysisRevision,
                        expectedStateRevision = pass.baseStateRevision,
                    )
                if (count == null) {
                    respond(writer, id, mapOf("accepted" to false, "cancelled" to true, "count" to 0))
                } else {
                    respond(writer, id, mapOf("accepted" to true, "cancelled" to false, "count" to count))
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
                inFlightFutures.remove(key)
                if (requestId != null) cancelledSuggestions.remove(requestId)
            }
        }
    inFlightFutures[key] = future
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

private fun LiveServer.pruneCancelledSuggestions() {
    val cutoff = System.nanoTime() - TimeUnit.SECONDS.toNanos(60)
    cancelledSuggestions.entries.removeIf { it.value < cutoff }
}
