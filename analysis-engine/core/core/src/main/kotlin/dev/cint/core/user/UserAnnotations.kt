package dev.cint.core.user

import dev.cint.api.Span
import dev.cint.core.CoreContributors
import org.yaml.snakeyaml.Yaml

/**
 * User records are engine-owned source facts, so the contributor id lives under
 * the existing `core:` reservation. This avoids adding a new reserved pack
 * namespace to the v4 contract.
 */
const val USER_CONTRIBUTOR_ID: String = "${CoreContributors.PREFIX}user-annotations"
const val USER_SOURCE: String = "user"

/**
 * `PURE` is a user-layer directive. It is not reserved from pack owners and is
 * intentionally visible in provenance so clients can inspect the original
 * record; it does not participate in occurrence persistence, so user
 * annotations keep `nodeAnchor = null`.
 */
const val USER_PURE_INTENT: String = "PURE"

data class UserAnnotation(
    val path: String,
    val span: Span,
    val intents: Set<String>,
    val symbol: String? = null,
    val contentHash: String? = null,
    val note: String? = null,
    val createdAt: String? = null,
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
                append("],")
                annotation.note?.let { append("\"note\":\"${escape(it)}\",") }
                annotation.createdAt?.let { append("\"createdAt\":\"${escape(it)}\",") }
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
