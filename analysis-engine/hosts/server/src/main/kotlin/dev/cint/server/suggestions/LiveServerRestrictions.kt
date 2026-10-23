package dev.cint.server.suggestions

import com.fasterxml.jackson.databind.JsonNode
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.restriction.AiRestrictionRecord
import dev.cint.core.restriction.AiRestrictionResolver
import dev.cint.core.restriction.AiRestrictionsFile
import dev.cint.core.restriction.RestrictionOperation
import dev.cint.frontend.Frontends
import dev.cint.server.LiveServer
import dev.cint.server.live.DocState
import dev.cint.server.live.DocumentKey
import dev.cint.server.live.relativePath
import dev.cint.server.live.republishOpenDocs
import dev.cint.server.live.restrictionApplies
import java.io.BufferedWriter

internal fun LiveServer.handleRestrictionsList(
    writer: BufferedWriter,
    params: JsonNode?,
    id: JsonNode?,
) {
    if ((clientProtocols[writer] ?: 0) < 4) {
        return sendError(writer, id, "cint/restrictions/list requires protocol 4")
    }
    if (!suggestionSettings.restrictionsEnabled) {
        return sendError(writer, id, "restrictions are disabled")
    }
    val requestedUri = params?.get("uri")?.asText()
    val openDocs =
        docs.entries
            .flatMap { (writer, clientDocs) -> clientDocs.values.map { writer to it } }
            .groupBy { relativePath(it.second.uri) }
    val records =
        AiRestrictionsFile.loadTolerant(root) { error ->
            System.err.println("cint ai restrictions: ${error.message ?: error.javaClass.name}")
        }
    respond(
        writer,
        id,
        linkedMapOf(
            "restrictions" to
                records.map { record ->
                    val doc =
                        requestedUri
                            ?.let { uri ->
                                docs.entries
                                    .flatMap { (client, clientDocs) -> clientDocs.values.map { client to it } }
                                    .firstOrNull { it.second.uri == uri }
                            }?.takeIf { relativePath(it.second.uri) == record.path }
                            ?: openDocs[record.path]?.firstOrNull()
                    recordPayload(record, status(record, doc))
                },
        ),
    )
}

internal fun LiveServer.handleRestrictionsRemove(
    writer: BufferedWriter,
    params: JsonNode?,
    id: JsonNode?,
) {
    if ((clientProtocols[writer] ?: 0) < 4) {
        return sendError(writer, id, "cint/restrictions/remove requires protocol 4")
    }
    if (!suggestionSettings.restrictionsEnabled) {
        return sendError(writer, id, "restrictions are disabled")
    }
    val restrictionId = params?.get("id")?.asText() ?: return sendError(writer, id, "id is required")
    val removed =
        runCatching { AiRestrictionsFile.remove(root, restrictionId) }
            .getOrElse { error ->
                return sendError(writer, id, error.message ?: "failed to remove restriction")
            }
    if (removed) republishOpenDocs(carry = true)
    respond(writer, id, linkedMapOf("accepted" to removed, "removed" to removed))
}

private fun LiveServer.status(
    record: AiRestrictionRecord,
    entry: Pair<BufferedWriter, DocState>?,
): String {
    if (entry == null) return "unknown"
    val (writer, doc) = entry
    return runCatching {
        val frontend = Frontends.forLanguage(doc.language) ?: return "unknown"
        val source = SourceFile(doc.uri, doc.text)
        val tree = frontend.parse(source)
        val resolved =
            AiRestrictionResolver.resolve(
                listOf(record),
                source,
                tree,
                frontend.syntax(tree),
                root,
            )
        val cached = analyses[DocumentKey(writer, doc.uri)]
        val applies =
            resolved.size == 1 &&
                cached != null &&
                restrictionApplies(cached.output.annotations, resolved.single())
        if (applies) "active" else "dormant"
    }.getOrDefault("unknown")
}

private fun recordPayload(
    record: AiRestrictionRecord,
    status: String,
): Map<String, Any?> =
    linkedMapOf<String, Any?>(
        "id" to record.id,
        "path" to record.path,
        "symbol" to record.symbol,
        "contentHash" to record.contentHash,
        "unitFingerprint" to record.unitFingerprint,
        "startLine" to record.span.startLine,
        "startCol" to record.span.startCol,
        "endLine" to record.span.endLine,
        "endCol" to record.span.endCol,
        "remove" to record.remove.map(::operationPayload),
        "add" to record.add.map(::operationPayload),
        "provider" to record.provider,
        "model" to record.model,
        "promptVersion" to record.promptVersion,
        "createdAt" to record.createdAt,
        "status" to status,
    )

private fun operationPayload(operation: RestrictionOperation): Map<String, Any?> =
    linkedMapOf<String, Any?>(
        "intent" to operation.intent,
        "startOffset" to operation.startOffset,
        "endOffset" to operation.endOffset,
        "confidence" to operation.confidence,
        "rationale" to operation.rationale,
    )
