package com.semanticpainter.core.artifact

import com.semanticpainter.core.model.TagKind
import com.semanticpainter.core.model.TaggedSpan

data class FileEntry(
    val path: String,
    val hash: String,
    val spans: List<TaggedSpan>,
)

object Artifact {
    fun render(
        feature: String,
        tag: String,
        language: String,
        files: List<FileEntry>,
    ): String = buildString {
        append("{\n")
        append("  \"feature\": \"${Json.escape(feature)}\",\n")
        append("  \"tag\": \"${Json.escape(tag)}\",\n")
        append("  \"language\": \"${Json.escape(language)}\",\n")
        append("  \"files\": [\n")
        files.forEachIndexed { i, entry ->
            append("    {\n")
            append("      \"path\": \"${Json.escape(entry.path)}\",\n")
            append("      \"hash\": \"${Json.escape(entry.hash)}\",\n")
            append("      \"spans\": [\n")
            entry.spans.forEachIndexed { j, span ->
                val kind = when (span.kind) {
                    TagKind.INTERACTION -> "interaction"
                    TagKind.CONTROL_FLOW -> "control-flow"
                }
                append("        {\"tag\":\"${Json.escape(span.tag)}\",")
                append("\"kind\":\"$kind\",")
                append("\"rule\":\"${Json.escape(span.rule)}\",")
                append("\"evidence\":\"${Json.escape(span.evidence)}\",")
                append("\"source\":\"${Json.escape(span.source)}\",")
                append("\"confidence\":${span.confidence},")
                append("\"startLine\":${span.span.startLine},\"startCol\":${span.span.startCol},")
                append("\"endLine\":${span.span.endLine},\"endCol\":${span.span.endCol}}")
                if (j < entry.spans.size - 1) append(",")
                append("\n")
            }
            append("      ]\n    }")
            if (i < files.size - 1) append(",")
            append("\n")
        }
        append("  ]\n}\n")
    }
}
