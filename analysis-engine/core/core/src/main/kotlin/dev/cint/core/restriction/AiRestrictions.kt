package dev.cint.core.restriction

import dev.cint.api.Span
import dev.cint.core.artifact.Json
import dev.cint.core.support.hashing.SourceNormalizer
import dev.cint.core.support.hashing.contentHash
import dev.cint.core.syntax.UnitIdentity
import dev.cint.core.syntax.expressionUnits
import dev.cint.core.user.UserAnnotationResolver
import dev.cint.core.user.layout.AnnotationLayout
import dev.cint.core.user.lock.AnnotationFileLock
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

const val AI_RESTRICTIONS_FILE_NAME: String = "ai-restrictions.json"
const val AI_CONFIRMED_CONTRIBUTOR_ID: String = "core:ai-confirmed"
const val AI_CONFIRMED_SOURCE: String = "ai-confirmed"

data class RestrictionOperation(
    val intent: String,
    val startOffset: Int,
    val endOffset: Int,
    val confidence: Double = 0.5,
    val rationale: String? = null,
    val startToken: Int? = null,
    val endToken: Int? = null,
)

data class AiRestrictionRecord(
    val id: String,
    val path: String,
    val span: Span,
    val contentHash: String,
    val remove: List<RestrictionOperation>,
    val add: List<RestrictionOperation>,
    val symbol: String? = null,
    val unitFingerprint: String? = null,
    val fingerprintVersion: Int = UnitIdentity.CURRENT_FINGERPRINT_VERSION,
    val regionOrdinal: Int? = null,
    val regionLanguage: String? = null,
    val regionKey: String? = null,
    val provider: String? = null,
    val model: String? = null,
    val promptVersion: String? = null,
    val createdAt: String? = null,
)

data class ResolvedRestrictionOperation(
    val intent: String,
    val startByte: Int,
    val endByte: Int,
    val span: Span,
    val confidence: Double,
    val rationale: String?,
)

data class ResolvedRestriction(
    val id: String,
    val path: String,
    val anchorStartByte: Int,
    val anchorEndByte: Int,
    val anchorSpan: Span,
    val remove: List<ResolvedRestrictionOperation>,
    val add: List<ResolvedRestrictionOperation>,
    val provider: String?,
    val model: String?,
    val promptVersion: String?,
)

object AiRestrictionsJson {
    fun fileVersion(text: String): Int? {
        val root = Yaml().load<Any?>(text) as? Map<*, *> ?: return null
        return intValue(root["version"])
    }

    fun parse(text: String): List<AiRestrictionRecord> {
        val root = Yaml().load<Any?>(text) ?: return emptyList()
        val entries: List<*> =
            when (root) {
                is Map<*, *> -> {
                    val version = intValue(root["version"])
                    if (version != null && version != 1) return emptyList()
                    root["restrictions"] as? List<*> ?: emptyList<Any?>()
                }

                is List<*> -> {
                    root
                }

                else -> {
                    emptyList<Any?>()
                }
            }
        return entries.mapNotNull(::parseEntry)
    }

    fun encode(records: List<AiRestrictionRecord>): String {
        require(records.all { it.remove.isNotEmpty() }) { "ai restriction requires at least one removal" }
        return Json.render(
            linkedMapOf(
                "version" to 1,
                "restrictions" to
                    records.map { record ->
                        linkedMapOf(
                            "id" to record.id,
                            "path" to record.path,
                            "symbol" to record.symbol,
                            "contentHash" to record.contentHash,
                            "unitFingerprint" to record.unitFingerprint,
                            "fingerprintVersion" to record.fingerprintVersion,
                            "regionOrdinal" to record.regionOrdinal,
                            "regionLanguage" to record.regionLanguage,
                            "regionKey" to record.regionKey,
                            "anchor" to spanMap(record.span),
                            "remove" to record.remove.map(::operationMap),
                            "add" to record.add.map(::operationMap),
                            "provider" to record.provider,
                            "model" to record.model,
                            "promptVersion" to record.promptVersion,
                            "createdAt" to record.createdAt,
                        )
                    },
            ),
        ) + "\n"
    }

    private fun parseEntry(raw: Any?): AiRestrictionRecord? {
        val entry = raw as? Map<*, *> ?: return null
        val id = stringValue(entry["id"])?.takeIf { it.isNotBlank() } ?: return null
        val path = stringValue(entry["path"])?.takeIf { it.isNotBlank() } ?: return null
        val anchor = entry["anchor"] as? Map<*, *>
        val span = spanValue(entry, anchor) ?: return null
        val contentHash =
            stringValue(entry["contentHash"] ?: anchor?.get("contentHash"))
                ?.takeIf { it.isNotBlank() }
                ?: return null
        val remove = operations(entry["remove"])
        val add = operations(entry["add"])
        if (remove.isEmpty()) return null
        return AiRestrictionRecord(
            id = id,
            path = path,
            span = span,
            contentHash = contentHash,
            remove = remove,
            add = add,
            symbol = stringValue(entry["symbol"] ?: anchor?.get("symbol"))?.takeIf { it.isNotBlank() },
            unitFingerprint =
                stringValue(entry["unitFingerprint"] ?: anchor?.get("unitFingerprint"))
                    ?.takeIf { it.isNotBlank() },
            fingerprintVersion =
                intValue(entry["fingerprintVersion"] ?: anchor?.get("fingerprintVersion"))
                    ?: if ((entry["unitFingerprint"] ?: anchor?.get("unitFingerprint")) == null) {
                        UnitIdentity.CURRENT_FINGERPRINT_VERSION
                    } else {
                        1
                    },
            regionOrdinal = intValue(entry["regionOrdinal"] ?: anchor?.get("regionOrdinal")),
            regionLanguage =
                stringValue(entry["regionLanguage"] ?: anchor?.get("regionLanguage"))
                    ?.takeIf { it.isNotBlank() },
            regionKey =
                stringValue(entry["regionKey"] ?: anchor?.get("regionKey"))
                    ?.takeIf { it.isNotBlank() },
            provider = stringValue(entry["provider"])?.takeIf { it.isNotBlank() },
            model = stringValue(entry["model"])?.takeIf { it.isNotBlank() },
            promptVersion = stringValue(entry["promptVersion"])?.takeIf { it.isNotBlank() },
            createdAt = stringValue(entry["createdAt"])?.takeIf { it.isNotBlank() },
        )
    }

    private fun operations(raw: Any?): List<RestrictionOperation> {
        val entries = raw as? List<*> ?: return emptyList()
        return entries.mapNotNull { value ->
            val operation = value as? Map<*, *> ?: return@mapNotNull null
            val intent = stringValue(operation["intent"])?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val start = intValue(operation["startOffset"] ?: operation["startByte"]) ?: return@mapNotNull null
            val end = intValue(operation["endOffset"] ?: operation["endByte"]) ?: return@mapNotNull null
            if (end < start) return@mapNotNull null
            RestrictionOperation(
                intent = intent,
                startOffset = start,
                endOffset = end,
                confidence = numberValue(operation["confidence"]) ?: 0.5,
                rationale = stringValue(operation["rationale"])?.takeIf { it.isNotBlank() },
                startToken = intValue(operation["startToken"]),
                endToken = intValue(operation["endToken"]),
            )
        }
    }

    private fun spanValue(
        entry: Map<*, *>,
        anchor: Map<*, *>?,
    ): Span? {
        val source = anchor ?: entry
        val startLine = intValue(source["startLine"]) ?: return null
        val startCol = intValue(source["startCol"]) ?: return null
        val endLine = intValue(source["endLine"]) ?: return null
        val endCol = intValue(source["endCol"]) ?: return null
        return Span(startLine, startCol, endLine, endCol)
    }

    private fun spanMap(span: Span): Map<String, Any?> =
        linkedMapOf(
            "startLine" to span.startLine,
            "startCol" to span.startCol,
            "endLine" to span.endLine,
            "endCol" to span.endCol,
        )

    private fun operationMap(operation: RestrictionOperation): Map<String, Any?> =
        linkedMapOf(
            "intent" to operation.intent,
            "startOffset" to operation.startOffset,
            "endOffset" to operation.endOffset,
            "confidence" to operation.confidence,
            "rationale" to operation.rationale,
            "startToken" to operation.startToken,
            "endToken" to operation.endToken,
        )

    private fun intValue(value: Any?): Int? =
        when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }

    private fun numberValue(value: Any?): Double? =
        when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }

    private fun stringValue(value: Any?): String? = value as? String
}

object AiRestrictionsFile {
    fun path(root: Path): Path = root.resolve(AnnotationLayout.DIRECTORY).resolve(AI_RESTRICTIONS_FILE_NAME)

    fun load(root: Path): List<AiRestrictionRecord> {
        val path = path(root)
        if (!Files.isRegularFile(path)) return emptyList()
        return AiRestrictionsJson.parse(Files.readString(path))
    }

    fun loadTolerant(
        root: Path,
        onError: (Throwable) -> Unit = {},
    ): List<AiRestrictionRecord> =
        runCatching { load(root) }.getOrElse { error ->
            onError(error)
            emptyList()
        }

    fun add(
        root: Path,
        record: AiRestrictionRecord,
    ): List<AiRestrictionRecord> {
        require(record.remove.isNotEmpty()) { "ai restriction requires at least one removal" }
        return synchronized(AnnotationFileLock.lockFor(root)) {
            AnnotationFileLock.withFileLockToken(root) { token ->
                val existing = loadForWrite(root)
                val updated = existing.filterNot { it.id == record.id } + record
                writeLocked(root, updated, token)
                updated
            }
        }
    }

    fun remove(
        root: Path,
        id: String,
    ): Boolean =
        synchronized(AnnotationFileLock.lockFor(root)) {
            AnnotationFileLock.withFileLockToken(root) { token ->
                val existing = loadForWrite(root)
                val updated = existing.filterNot { it.id == id }
                if (updated.size == existing.size) {
                    false
                } else {
                    writeLocked(root, updated, token)
                    true
                }
            }
        }

    private fun loadForWrite(root: Path): List<AiRestrictionRecord> {
        val path = path(root)
        if (!Files.isRegularFile(path)) return emptyList()
        val text = Files.readString(path)
        val version = AiRestrictionsJson.fileVersion(text)
        if (version != null && version != 1) {
            throw IllegalStateException("unsupported ai-restrictions file version: $version")
        }
        return AiRestrictionsJson.parse(text)
    }

    private fun writeLocked(
        root: Path,
        records: List<AiRestrictionRecord>,
        token: String,
    ) {
        AnnotationFileLock.assertOwned(root, token)
        val path = path(root)
        Files.createDirectories(path.parent)
        val temporary = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(temporary, AiRestrictionsJson.encode(records))
        try {
            AnnotationFileLock.assertOwned(root, token)
        } catch (error: Throwable) {
            runCatching { Files.deleteIfExists(temporary) }
            throw error
        }
        runCatching {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

object AiRestrictionResolver {
    fun resolve(
        records: List<AiRestrictionRecord>,
        file: dev.cint.core.facts.model.SourceFile,
        tree: dev.cint.core.syntax.SyntaxTree,
        syntax: dev.cint.core.syntax.SyntaxModel,
        root: Path?,
    ): List<ResolvedRestriction> =
        records.mapNotNull { record ->
            if (!UserAnnotationResolver.pathMatches(record.path, file.path, root)) return@mapNotNull null
            resolveRecord(record, file, tree, syntax)
        }

    private fun resolveRecord(
        record: AiRestrictionRecord,
        file: dev.cint.core.facts.model.SourceFile,
        tree: dev.cint.core.syntax.SyntaxTree,
        syntax: dev.cint.core.syntax.SyntaxModel,
    ): ResolvedRestriction? {
        val anchor = anchor(record, file, tree, syntax) ?: return null
        val anchorText = UserAnnotationResolver.selectionText(file.text, anchor) ?: return null
        val exactAnchor = contentHash(anchorText) == normalizedHash(record.contentHash)
        val operations =
            record.remove.mapNotNull { operation ->
                resolve(operation, file, anchor.first, anchor.second, adding = false, exactAnchor)
            }
        val additions =
            record.add.mapNotNull { operation ->
                resolve(operation, file, anchor.first, anchor.second, adding = true, exactAnchor)
            }
        if (operations.isEmpty()) return null
        val anchorSpan = UserAnnotationResolver.spanFor(file.text, anchor.first, anchor.second) ?: return null
        return ResolvedRestriction(
            id = record.id,
            path = record.path,
            anchorStartByte = anchor.first,
            anchorEndByte = anchor.second,
            anchorSpan = anchorSpan,
            remove = operations,
            add = additions,
            provider = record.provider,
            model = record.model,
            promptVersion = record.promptVersion,
        )
    }

    fun unitFingerprint(
        file: dev.cint.core.facts.model.SourceFile,
        tree: dev.cint.core.syntax.SyntaxTree,
        syntax: dev.cint.core.syntax.SyntaxModel,
        startByte: Int,
        endByte: Int,
        fingerprintVersion: Int = UnitIdentity.CURRENT_FINGERPRINT_VERSION,
    ): String? =
        UnitIdentity.unitFingerprint(
            file,
            tree,
            syntax,
            startByte,
            endByte,
            fingerprintVersion,
        )

    fun tokenOffsetsFor(
        text: String,
        anchorStart: Int,
        start: Int,
        end: Int,
    ): Pair<Int, Int>? {
        val tokens = SourceNormalizer.tokenSpans(text, stripComments = true)
        if (tokens.isEmpty() || end <= start) return null
        val anchorIndex =
            tokens.indexOfFirst { it.endByte > anchorStart }.let { if (it < 0) tokens.size else it }
        val startIndex = tokens.indexOfFirst { it.endByte > start }
        val endIndex = tokens.indexOfLast { it.startByte < end } + 1
        if (startIndex < 0 || endIndex <= startIndex) return null
        if (tokens[startIndex].startByte != start || tokens[endIndex - 1].endByte != end) return null
        return (startIndex - anchorIndex) to (endIndex - anchorIndex)
    }

    private fun anchor(
        record: AiRestrictionRecord,
        file: dev.cint.core.facts.model.SourceFile,
        tree: dev.cint.core.syntax.SyntaxTree,
        syntax: dev.cint.core.syntax.SyntaxModel,
    ): Pair<Int, Int>? {
        val hash = normalizedHash(record.contentHash)
        val units = syntax.expressionUnits(tree)
        val candidates =
            (units.map { it.startByte to it.endByte } + namedRanges(file, tree, syntax))
                .distinct()
                .filter { range ->
                    UserAnnotationResolver.selectionText(file.text, range)?.let { contentHash(it) == hash } == true
                }
        val stored = UserAnnotationResolver.byteRange(file.text, record.span)
        val storedMatches =
            stored != null &&
                UserAnnotationResolver.selectionText(file.text, stored)?.let { contentHash(it) == hash } == true
        val fingerprint = record.unitFingerprint?.takeIf { it != hash }
        if (fingerprint != null) {
            val matches =
                units.filter { unit ->
                    fingerprintFor(file, tree, syntax, units, unit, record.fingerprintVersion) == fingerprint
                }
            val matching = matches.singleOrNull()
            if (matching != null) {
                val storedRange = UserAnnotationResolver.byteRange(file.text, record.span)
                if (storedRange != null) {
                    val storedUnit =
                        units.firstOrNull {
                            it.startByte == storedRange.first && it.endByte == storedRange.second
                        }
                    if (
                        storedUnit != null &&
                        fingerprintFor(file, tree, syntax, units, storedUnit, record.fingerprintVersion) == fingerprint
                    ) {
                        return matching.startByte to matching.endByte
                    }
                    val storedTextMatches =
                        UserAnnotationResolver.selectionText(file.text, storedRange)?.let {
                            contentHash(it) == hash
                        } == true
                    if (storedTextMatches && storedRange != (matching.startByte to matching.endByte)) return null
                }
                return matching.startByte to matching.endByte
            }
            if (record.fingerprintVersion <= 1) {
                return legacyAnchor(record, file, tree, syntax, candidates, stored, storedMatches)
            }
            return null
        }
        return legacyAnchor(record, file, tree, syntax, candidates, stored, storedMatches)
    }

    private fun legacyAnchor(
        record: AiRestrictionRecord,
        file: dev.cint.core.facts.model.SourceFile,
        tree: dev.cint.core.syntax.SyntaxTree,
        syntax: dev.cint.core.syntax.SyntaxModel,
        candidates: List<Pair<Int, Int>>,
        stored: Pair<Int, Int>?,
        storedMatches: Boolean,
    ): Pair<Int, Int>? {
        val symbolFiltered =
            if (record.symbol == null) {
                candidates
            } else {
                candidates.filter { range ->
                    UserAnnotationResolver.symbolAt(file, tree, syntax, range.first) == record.symbol
                }
            }
        if (storedMatches) {
            val matchingStored = symbolFiltered.singleOrNull()
            return matchingStored?.takeIf { it == stored }
        }
        return symbolFiltered.singleOrNull()
    }

    private fun fingerprintFor(
        file: dev.cint.core.facts.model.SourceFile,
        tree: dev.cint.core.syntax.SyntaxTree,
        syntax: dev.cint.core.syntax.SyntaxModel,
        units: List<dev.cint.core.syntax.ExpressionUnit>,
        unit: dev.cint.core.syntax.ExpressionUnit,
        fingerprintVersion: Int,
    ): String = UnitIdentity.fingerprint(file, tree, syntax, units, unit, fingerprintVersion)

    private fun namedRanges(
        file: dev.cint.core.facts.model.SourceFile,
        tree: dev.cint.core.syntax.SyntaxTree,
        syntax: dev.cint.core.syntax.SyntaxModel,
    ): List<Pair<Int, Int>> =
        buildList {
            syntax.functions(tree).forEach { add(it.node.startByte to it.node.endByte) }
            syntax.properties(tree).forEach { add(it.node.startByte to it.node.endByte) }
            syntax.declarations(tree).forEach { add(it.node.startByte to it.node.endByte) }
        }

    private fun resolve(
        operation: RestrictionOperation,
        file: dev.cint.core.facts.model.SourceFile,
        anchorStart: Int,
        anchorEnd: Int,
        adding: Boolean,
        exactAnchor: Boolean,
    ): ResolvedRestrictionOperation? {
        val mapped = mappedRange(operation, file.text, anchorStart, exactAnchor) ?: return null
        val start = mapped.first
        val end = mapped.second
        if (start < 0 || end <= start) return null
        if (adding && (start < anchorStart || end > anchorEnd)) return null
        if (!adding && (start >= anchorEnd || end <= anchorStart)) return null
        val span = UserAnnotationResolver.spanFor(file.text, start, end) ?: return null
        return ResolvedRestrictionOperation(
            intent = operation.intent,
            startByte = start,
            endByte = end,
            span = span,
            confidence = operation.confidence,
            rationale = operation.rationale,
        )
    }

    private fun mappedRange(
        operation: RestrictionOperation,
        text: String,
        anchorStart: Int,
        exactAnchor: Boolean,
    ): Pair<Int, Int>? {
        val startToken = operation.startToken
        val endToken = operation.endToken
        if (startToken != null && endToken != null) {
            val tokens = SourceNormalizer.tokenSpans(text, stripComments = true)
            if (tokens.isEmpty()) return null
            val anchorIndex =
                tokens.indexOfFirst { it.endByte > anchorStart }.let { if (it < 0) tokens.size else it }
            val startIndex = anchorIndex + startToken
            val endIndex = anchorIndex + endToken
            if (startIndex < 0 || endIndex > tokens.size || endIndex <= startIndex) return null
            return tokens[startIndex].startByte to tokens[endIndex - 1].endByte
        }
        if (!exactAnchor) return null
        return (anchorStart + operation.startOffset) to (anchorStart + operation.endOffset)
    }

    private fun normalizedHash(value: String): String = value.removePrefix("sha256:")
}
