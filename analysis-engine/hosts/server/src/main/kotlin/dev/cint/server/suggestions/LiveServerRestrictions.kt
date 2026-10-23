package dev.cint.server.suggestions

import com.fasterxml.jackson.databind.JsonNode
import dev.cint.core.facts.frontend.LanguageFrontend
import dev.cint.core.facts.frontend.SplitHandler
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.restriction.AiRestrictionRecord
import dev.cint.core.restriction.AiRestrictionResolver
import dev.cint.core.restriction.AiRestrictionsFile
import dev.cint.core.restriction.ResolvedRestriction
import dev.cint.core.restriction.RestrictionOperation
import dev.cint.core.user.UserAnnotationResolver
import dev.cint.frontend.Frontends
import dev.cint.frontend.SplitAnchorResolver
import dev.cint.server.LiveServer
import dev.cint.server.live.DocState
import dev.cint.server.live.DocumentKey
import dev.cint.server.live.relativePath
import dev.cint.server.live.republishOpenDocs
import dev.cint.server.live.restrictionApplies
import dev.cint.server.live.shift
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
        val resolved = resolveForStatus(record, doc, source, frontend)
        val cached = analyses[DocumentKey(writer, doc.uri)]
        val applies =
            cached != null &&
                resolved.any { restrictionApplies(cached.output.annotations, it) }
        if (applies) "active" else "dormant"
    }.getOrDefault("unknown")
}

internal fun LiveServer.resolveForStatus(
    record: AiRestrictionRecord,
    doc: DocState,
    source: SourceFile,
    frontend: LanguageFrontend,
): List<ResolvedRestriction> {
    if (frontend is SplitHandler && record.unitFingerprint != null) {
        val regions =
            SplitAnchorResolver.leafRegions(
                source,
                frontend as dev.cint.core.facts.frontend.SplitHandler,
            )
        val selected =
            when {
                record.regionKey != null -> {
                    val matches =
                        regions.filter {
                            it.identityKey == record.regionKey && it.language == record.regionLanguage
                        }
                    if (matches.size != 1) return emptyList()
                    matches
                }

                record.regionOrdinal != null -> {
                    val ordinal = record.regionOrdinal ?: return emptyList()
                    val match =
                        regions
                            .getOrNull(ordinal)
                            ?.takeIf { it.language == record.regionLanguage }
                    if (match == null) return emptyList()
                    listOf(match)
                }

                else -> {
                    val range = UserAnnotationResolver.byteRange(doc.text, record.span)
                    val matches =
                        if (range == null) {
                            emptyList()
                        } else {
                            regions.filter { region ->
                                val end = region.startByte + region.text.toByteArray(Charsets.UTF_8).size
                                range.first >= region.startByte && range.second <= end
                            }
                        }
                    when {
                        matches.isEmpty() -> {
                            null
                        }

                        matches.size > 1 -> {
                            return emptyList()
                        }

                        (regions.count { it.identityKey == matches.single().identityKey }) > 1 -> {
                            return emptyList()
                        }

                        else -> {
                            matches
                        }
                    }
                }
            }
        if (selected == null) {
            val tree = frontend.parse(source)
            return AiRestrictionResolver.resolve(
                listOf(record),
                source,
                tree,
                frontend.syntax(tree),
                root,
            )
        }
        val resolved =
            selected
                .mapNotNull { region ->
                    val regionSource = SourceFile(doc.uri, region.text)
                    val regionFrontend = Frontends.forLanguage(region.language) ?: return@mapNotNull null
                    val tree = regionFrontend.parse(regionSource)
                    AiRestrictionResolver
                        .resolve(
                            listOf(record),
                            regionSource,
                            tree,
                            regionFrontend.syntax(tree),
                            root,
                        ).map { it.shift(region) }
                        .takeIf { it.isNotEmpty() }
                }.flatten()
        return resolved
    }
    val tree = frontend.parse(source)
    return AiRestrictionResolver.resolve(
        listOf(record),
        source,
        tree,
        frontend.syntax(tree),
        root,
    )
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
