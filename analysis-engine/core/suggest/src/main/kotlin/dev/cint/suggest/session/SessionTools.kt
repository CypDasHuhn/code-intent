package dev.cint.suggest.session

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.cint.api.Span

internal data class VerdictAcceptance(
    val graded: Set<String>,
    val accepted: Int,
    val rejected: Int,
)

internal class SessionTools(
    private val document: SessionDocument,
    private val activeBatch: List<SessionUnit>,
    private val maxUnits: Int,
    private val maxResultBytes: Int,
    private val sanitize: (String) -> String,
    private val typeLookup: (Int, Int) -> Map<String, Any?>?,
    private val submit: (List<SessionVerdict>) -> VerdictAcceptance,
) {
    private val mapper = jacksonObjectMapper()
    private val units = document.units.associateBy { it.unitId }
    private val annotations = document.annotations
    private val maxUnitCount = maxUnits.coerceAtLeast(1)
    private val maxBytes = maxResultBytes.coerceAtLeast(512)

    fun execute(call: ToolCall): String {
        val arguments = parseArguments(call.arguments)
        return when (call.name) {
            "list_expression_units" -> listUnits(arguments)
            "get_expression" -> expression(arguments)
            "get_parent" -> parent(arguments)
            "get_enclosing_statement" -> enclosing(arguments, function = false)
            "get_enclosing_function" -> enclosing(arguments, function = true)
            "read_range" -> readRange(arguments)
            "lookup_type" -> lookupType(arguments)
            "get_annotations" -> getAnnotations(arguments)
            "submit_verdicts" -> submitVerdicts(arguments)
            else -> error("unknown tool: ${call.name}")
        }
    }

    private fun listUnits(arguments: JsonNode): String {
        val start = arguments.int("startByte")
        val end = arguments.int("endByte")
        val requested = arguments.int("limit")?.coerceAtLeast(1)
        val limit = minOf(requested ?: maxUnitCount, maxUnitCount)
        val source = if (start == null && end == null) activeBatch.asSequence() else document.units.asSequence()
        val selected =
            source
                .filter { unit ->
                    (start == null || unit.endByte > start) &&
                        (end == null || unit.startByte < end)
                }.take(limit)
                .map(::unitSummary)
                .toList()
        return mapper.writeValueAsString(
            linkedMapOf(
                "units" to selected,
                "total" to document.units.size,
                "batch" to activeBatch.size,
                "returned" to selected.size,
            ),
        )
    }

    private fun expression(arguments: JsonNode): String {
        val unit = unit(arguments) ?: return error("unknown unitId")
        return mapper.writeValueAsString(unitSummary(unit, includeText = true))
    }

    private fun parent(arguments: JsonNode): String {
        val unit = unit(arguments) ?: return error("unknown unitId")
        val levels = (arguments.int("levels") ?: 1).coerceIn(1, 16)
        var current = unit.parentUnitId?.let(units::get)
        var remaining = levels
        while (current != null && remaining > 1) {
            current = current.parentUnitId?.let(units::get)
            remaining -= 1
        }
        return mapper.writeValueAsString(
            linkedMapOf(
                "parent" to current?.let { unitSummary(it, includeText = true) },
            ),
        )
    }

    private fun enclosing(
        arguments: JsonNode,
        function: Boolean,
    ): String {
        val unit = unit(arguments) ?: return error("unknown unitId")
        val span = if (function) unit.enclosingFunction else unit.enclosingStatement
        val text = span?.let { spanText(it) }
        return mapper.writeValueAsString(
            linkedMapOf(
                "span" to span?.toMap(),
                "text" to text?.let(sanitize),
            ),
        )
    }

    private fun readRange(arguments: JsonNode): String {
        val start = arguments.int("startByte") ?: return error("startByte is required")
        val end = arguments.int("endByte") ?: return error("endByte is required")
        val bounded =
            SessionText.boundedRange(document.text, start, end, minOf(MAX_RANGE_BYTES, maxBytes))
                ?: return error("range is outside the document")
        val (safeStart, safeEnd) = bounded
        val text =
            document.text.toByteArray(Charsets.UTF_8).let { bytes ->
                String(bytes, safeStart, safeEnd - safeStart, Charsets.UTF_8)
            }
        return mapper.writeValueAsString(
            linkedMapOf(
                "startByte" to safeStart,
                "endByte" to safeEnd,
                "span" to SessionText.spanFor(document.text, safeStart, safeEnd)?.toMap(),
                "text" to sanitize(text),
            ),
        )
    }

    private fun lookupType(arguments: JsonNode): String {
        val start = arguments.int("startByte") ?: return error("startByte is required")
        val end = arguments.int("endByte") ?: return error("endByte is required")
        val type = typeLookup(start, end)?.mapValues { (_, value) -> sanitizeValue(value) }
        return mapper.writeValueAsString(linkedMapOf("type" to type))
    }

    private fun getAnnotations(arguments: JsonNode): String {
        val batchStart = activeBatch.minOfOrNull { it.startByte } ?: 0
        val batchEnd = activeBatch.maxOfOrNull { it.endByte } ?: document.text.toByteArray(Charsets.UTF_8).size
        val start = arguments.int("startByte") ?: batchStart
        val end = arguments.int("endByte") ?: batchEnd
        val overlapping =
            annotations
                .asSequence()
                .filter { it.endByte > start && it.startByte < end }
                .take(maxUnitCount)
                .map(::annotationSummary)
                .toList()
        return mapper.writeValueAsString(
            linkedMapOf(
                "annotations" to overlapping,
                "returned" to overlapping.size,
            ),
        )
    }

    private fun submitVerdicts(arguments: JsonNode): String {
        val verdicts = parseVerdicts(arguments)
        if (verdicts == null) return error("verdicts are required")
        val acceptance = submit(verdicts)
        return mapper.writeValueAsString(
            linkedMapOf(
                "graded" to acceptance.graded.sorted(),
                "accepted" to acceptance.accepted,
                "rejected" to acceptance.rejected,
            ),
        )
    }

    private fun parseVerdicts(arguments: JsonNode): List<SessionVerdict>? {
        val raw =
            when {
                arguments.isArray -> arguments
                arguments.path("verdicts").isArray -> arguments.path("verdicts")
                arguments.path("unitId").asText("").isNotBlank() -> mapper.createArrayNode().add(arguments)
                else -> return null
            }
        return raw.mapNotNull { item ->
            if (!item.isObject) return@mapNotNull null
            val unitId = item.path("unitId").asText("").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val verdict = item.path("verdict").asText("").trim()
            val rawAdds = item.path("add").takeIf(JsonNode::isArray)
            val adds = rawAdds?.mapNotNull(::parseAdd).orEmpty()
            val rawRemoves = item.path("remove").takeIf(JsonNode::isArray)
            val removes = rawRemoves?.mapNotNull(::parseRemove).orEmpty()
            val none = verdict.equals("none", ignoreCase = true)
            if (none) {
                if (
                    adds.isNotEmpty() ||
                    removes.isNotEmpty() ||
                    rawAdds?.size()?.let { it > 0 } == true ||
                    rawRemoves?.size()?.let { it > 0 } == true
                ) {
                    return@mapNotNull null
                }
                return@mapNotNull SessionVerdict(unitId, none = true)
            }
            if (verdict.isNotEmpty()) return@mapNotNull null
            if (adds.isEmpty() && removes.isEmpty()) return@mapNotNull null
            SessionVerdict(unitId, adds, removes)
        }
    }

    private fun parseAdd(node: JsonNode): SessionAdd? {
        if (!node.isObject) return null
        val intent = node.path("intent").asText("").takeIf { it.isNotBlank() } ?: return null
        val start = node.int("startByte") ?: return null
        val end = node.int("endByte") ?: return null
        val confidence = node.double("confidence") ?: return null
        val rationale = node.path("rationale").asText("").takeIf { it.isNotBlank() }
        return SessionAdd(intent, start, end, confidence, rationale)
    }

    private fun parseRemove(node: JsonNode): SessionRemove? {
        if (!node.isObject) return null
        val intent = node.path("intent").asText("").takeIf { it.isNotBlank() } ?: return null
        val start = node.int("startByte") ?: return null
        val end = node.int("endByte") ?: return null
        val rationale = node.path("rationale").asText("").takeIf { it.isNotBlank() }
        return SessionRemove(intent, start, end, rationale)
    }

    private fun unit(arguments: JsonNode): SessionUnit? =
        arguments
            .path("unitId")
            .asText("")
            .takeIf { it.isNotBlank() }
            ?.let(units::get)

    private fun unitSummary(
        unit: SessionUnit,
        includeText: Boolean = false,
    ): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "unitId" to unit.unitId,
            "startByte" to unit.startByte,
            "endByte" to unit.endByte,
            "span" to unit.span.toMap(),
            "parentUnitId" to unit.parentUnitId,
            "existingAnnotations" to unit.existingAnnotations.map(::annotationSummary),
        ).apply {
            if (includeText) put("text", boundedText(sanitize(unit.text)))
        }

    private fun annotationSummary(annotation: SessionAnnotation): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "intents" to annotation.intents.sorted(),
            "startByte" to annotation.startByte,
            "endByte" to annotation.endByte,
            "span" to annotation.span.toMap(),
            "source" to annotation.source,
            "confidence" to annotation.confidence,
            "role" to annotation.role,
            "provenance" to annotation.provenance,
        )

    private fun spanText(span: Span): String? {
        val start = offsetFor(span.startLine, span.startCol)
        val end = offsetFor(span.endLine, span.endCol)
        if (start == null || end == null || end < start) return null
        val bounded = SessionText.boundedRange(document.text, start, end, minOf(MAX_RANGE_BYTES, maxBytes)) ?: return null
        val (safeStart, safeEnd) = bounded
        val bytes = document.text.toByteArray(Charsets.UTF_8)
        return boundedText(String(bytes, safeStart, safeEnd - safeStart, Charsets.UTF_8))
    }

    private fun sanitizeValue(value: Any?): Any? =
        when (value) {
            is String -> sanitize(value)
            is Map<*, *> -> value.entries.associate { (key, item) -> key.toString() to sanitizeValue(item) }
            is Iterable<*> -> value.map(::sanitizeValue)
            is Array<*> -> value.map(::sanitizeValue)
            else -> value
        }

    private fun boundedText(text: String): String =
        if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) text else text.take(maxBytes / 2) + "...[truncated]"

    private fun offsetFor(
        line: Int,
        col: Int,
    ): Int? {
        val bytes = document.text.toByteArray(Charsets.UTF_8)
        var currentLine = 0
        var index = 0
        while (index < bytes.size && currentLine < line) {
            if (bytes[index].toInt() == '\n'.code) currentLine += 1
            index += 1
        }
        if (currentLine != line) return null
        val offset = index + col
        return if (offset <= bytes.size) offset else null
    }

    private fun parseArguments(raw: String): JsonNode {
        val text = raw.trim()
        if (text.isEmpty()) return mapper.createObjectNode()
        return runCatching { mapper.readTree(text) }.getOrDefault(mapper.createObjectNode())
    }

    private fun error(message: String): String = mapper.writeValueAsString(linkedMapOf("error" to message))

    private fun JsonNode.int(field: String): Int? {
        val node = path(field)
        return if (node.isIntegralNumber) node.asInt() else null
    }

    private fun JsonNode.double(field: String): Double? {
        val node = path(field)
        return if (node.isNumber) node.asDouble() else null
    }

    private fun Span.toMap(): Map<String, Any?> =
        linkedMapOf(
            "startLine" to startLine,
            "startCol" to startCol,
            "endLine" to endLine,
            "endCol" to endCol,
        )

    private companion object {
        const val MAX_RANGE_BYTES: Int = 4_000
    }
}
