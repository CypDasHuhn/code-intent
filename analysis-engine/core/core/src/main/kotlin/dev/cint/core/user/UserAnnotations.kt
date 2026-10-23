package dev.cint.core.user

import dev.cint.api.Span
import dev.cint.core.CoreContributors
import org.yaml.snakeyaml.Yaml

/**
 * User records are engine-owned source facts, so the contributor id lives under
 * the existing `core:` reservation. This avoids adding a new reserved pack
 * namespace to the v5 contract.
 */
const val USER_CONTRIBUTOR_ID: String = "${CoreContributors.PREFIX}user-annotations"
const val USER_SOURCE: String = "user"

/**
 * `PURE` and `READONLY` are user-layer directives. They are not reserved from
 * pack owners and are intentionally visible in provenance so clients can inspect
 * the original record; they do not participate in occurrence persistence, so
 * user annotations keep `nodeAnchor = null`.
 */
const val USER_PURE_INTENT: String = "PURE"
const val USER_READONLY_INTENT: String = "READONLY"

data class UserAnnotation(
    val path: String,
    val span: Span,
    val intents: Set<String>,
    val symbol: String? = null,
    val contentHash: String? = null,
    val note: String? = null,
    val createdAt: String? = null,
    val anchorFingerprint: String? = null,
    val unitFingerprint: String? = null,
    val unitFingerprintVersion: Int? = null,
    val regionOrdinal: Int? = null,
    val regionLanguage: String? = null,
    val regionKey: String? = null,
    val startToken: Int? = null,
    val endToken: Int? = null,
    val startTokenOffset: Int? = null,
    val endTokenOffset: Int? = null,
    val startNormalized: Int? = null,
    val endNormalized: Int? = null,
)

object UserAnnotationsJson {
    fun parse(text: String): List<UserAnnotation> {
        val root = Yaml().load<Any?>(text) ?: return emptyList()
        val entries =
            when (root) {
                is List<*> -> root
                is Map<*, *> -> root["annotations"] as? List<*> ?: emptyList<Any?>()
                else -> emptyList<Any?>()
            }
        return entries.mapNotNull(::parseEntry)
    }

    fun encode(annotations: List<UserAnnotation>): String =
        buildString {
            append("{\n")
            append("  \"version\": 1,\n")
            append("  \"annotations\": [")
            if (annotations.isNotEmpty()) append("\n")
            annotations.forEachIndexed { index, annotation ->
                append("    {")
                append("\"path\":\"${escape(annotation.path)}\",")
                annotation.symbol?.let { append("\"symbol\":\"${escape(it)}\",") }
                annotation.contentHash?.let { append("\"contentHash\":\"${escape(it)}\",") }
                append("\"startLine\":${annotation.span.startLine},")
                append("\"startCol\":${annotation.span.startCol},")
                append("\"endLine\":${annotation.span.endLine},")
                append("\"endCol\":${annotation.span.endCol},")
                append("\"intents\":[")
                append(annotation.intents.sorted().joinToString(",") { "\"${escape(it)}\"" })
                append("]")
                annotation.note?.let { append(",\"note\":\"${escape(it)}\"") }
                annotation.createdAt?.let { append(",\"createdAt\":\"${escape(it)}\"") }
                annotation.anchorFingerprint?.let { append(",\"anchorFingerprint\":\"${escape(it)}\"") }
                annotation.unitFingerprint?.let { append(",\"unitFingerprint\":\"${escape(it)}\"") }
                annotation.unitFingerprintVersion?.let { append(",\"unitFingerprintVersion\":$it") }
                annotation.regionOrdinal?.let { append(",\"regionOrdinal\":$it") }
                annotation.regionLanguage?.let { append(",\"regionLanguage\":\"${escape(it)}\"") }
                annotation.regionKey?.let { append(",\"regionKey\":\"${escape(it)}\"") }
                annotation.startToken?.let { append(",\"startToken\":$it") }
                annotation.endToken?.let { append(",\"endToken\":$it") }
                annotation.startTokenOffset?.let { append(",\"startTokenOffset\":$it") }
                annotation.endTokenOffset?.let { append(",\"endTokenOffset\":$it") }
                annotation.startNormalized?.let { append(",\"startNormalized\":$it") }
                annotation.endNormalized?.let { append(",\"endNormalized\":$it") }
                if (index < annotations.size - 1) append("},\n") else append("}")
            }
            if (annotations.isNotEmpty()) append("\n  ")
            append("]\n}\n")
        }

    private fun parseEntry(raw: Any?): UserAnnotation? {
        val entry = raw as? Map<*, *> ?: return null
        val anchor = entry["anchor"] as? Map<*, *>
        val range = entry["range"] as? Map<*, *> ?: anchor?.get("range") as? Map<*, *>
        val path = stringValue(entry["path"])?.takeIf { it.isNotBlank() } ?: return null
        val intents =
            when (val value = entry["intents"]) {
                is List<*> -> {
                    value.mapNotNull(::stringValue).filter { it.isNotBlank() }.toSet()
                }

                is String -> {
                    value
                        .split(',')
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .toSet()
                }

                else -> {
                    emptySet()
                }
            }
        if (intents.isEmpty()) return null
        return UserAnnotation(
            path = path,
            span =
                Span(
                    startLine = intValue(entry, range, "startLine") ?: 0,
                    startCol = intValue(entry, range, "startCol") ?: 0,
                    endLine = intValue(entry, range, "endLine") ?: 0,
                    endCol = intValue(entry, range, "endCol") ?: 0,
                ),
            intents = intents,
            symbol = stringValue(entry["symbol"] ?: anchor?.get("symbol"))?.takeIf { it.isNotBlank() },
            contentHash =
                stringValue(entry["contentHash"] ?: anchor?.get("contentHash"))
                    ?.takeIf { it.isNotBlank() },
            note = stringValue(entry["note"])?.takeIf { it.isNotBlank() },
            createdAt = stringValue(entry["createdAt"])?.takeIf { it.isNotBlank() },
            anchorFingerprint =
                stringValue(entry["anchorFingerprint"] ?: anchor?.get("anchorFingerprint"))
                    ?.takeIf { it.isNotBlank() },
            unitFingerprint =
                stringValue(entry["unitFingerprint"] ?: anchor?.get("unitFingerprint"))
                    ?.takeIf { it.isNotBlank() },
            unitFingerprintVersion =
                numberValue(entry["unitFingerprintVersion"] ?: anchor?.get("unitFingerprintVersion")),
            regionOrdinal = numberValue(entry["regionOrdinal"] ?: anchor?.get("regionOrdinal")),
            regionLanguage =
                stringValue(entry["regionLanguage"] ?: anchor?.get("regionLanguage"))
                    ?.takeIf { it.isNotBlank() },
            regionKey =
                stringValue(entry["regionKey"] ?: anchor?.get("regionKey"))
                    ?.takeIf { it.isNotBlank() },
            startToken = numberValue(entry["startToken"] ?: anchor?.get("startToken")),
            endToken = numberValue(entry["endToken"] ?: anchor?.get("endToken")),
            startTokenOffset = numberValue(entry["startTokenOffset"] ?: anchor?.get("startTokenOffset")),
            endTokenOffset = numberValue(entry["endTokenOffset"] ?: anchor?.get("endTokenOffset")),
            startNormalized = numberValue(entry["startNormalized"] ?: anchor?.get("startNormalized")),
            endNormalized = numberValue(entry["endNormalized"] ?: anchor?.get("endNormalized")),
        )
    }

    private fun intValue(
        entry: Map<*, *>,
        range: Map<*, *>?,
        key: String,
    ): Int? = numberValue(entry[key] ?: range?.get(key))

    private fun numberValue(value: Any?): Int? =
        when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }

    private fun stringValue(value: Any?): String? = value as? String

    private fun escape(value: String): String =
        buildString {
            for (character in value) {
                when (character) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(character)
                }
            }
        }
}
