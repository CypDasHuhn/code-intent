package dev.cint.core.artifact

import dev.cint.api.Annotation
import dev.cint.api.Contribution
import dev.cint.api.Role

data class FileEntry(
    val path: String,
    val hash: String,
    val annotations: List<Annotation>,
)

object Artifact {
    fun render(
        language: String,
        intents: List<String>,
        files: List<FileEntry>,
    ): String =
        buildString {
            append("{\n")
            append("  \"language\": \"${Json.escape(language)}\",\n")
            append("  \"intents\": [")
            append(intents.joinToString(", ") { "\"${Json.escape(it)}\"" })
            append("],\n")
            append("  \"files\": [\n")
            files.forEachIndexed { i, entry ->
                append("    {\n")
                append("      \"path\": \"${Json.escape(entry.path)}\",\n")
                append("      \"hash\": \"${Json.escape(entry.hash)}\",\n")
                append("      \"spans\": [\n")
                entry.annotations.forEachIndexed { j, annotation ->
                    append("        {\"role\":\"${roleName(annotation.role)}\",")
                    append("\"intents\":[")
                    append(annotation.intents.joinToString(",") { "\"${Json.escape(it)}\"" })
                    append("],")
                    append("\"evidence\":\"${Json.escape(annotation.evidence)}\",")
                    append("\"source\":\"${Json.escape(annotation.source)}\",")
                    append("\"confidence\":${annotation.confidence},")
                    append("\"startLine\":${annotation.span.startLine},\"startCol\":${annotation.span.startCol},")
                    append("\"endLine\":${annotation.span.endLine},\"endCol\":${annotation.span.endCol},")
                    append("\"startByte\":${annotation.startByte},\"endByte\":${annotation.endByte},")
                    append("\"provenance\":[")
                    append(annotation.provenance.joinToString(",") { contributionJson(it) })
                    append("]}")
                    if (j < entry.annotations.size - 1) append(",")
                    append("\n")
                }
                append("      ]\n    }")
                if (i < files.size - 1) append(",")
                append("\n")
            }
            append("  ]\n}\n")
        }

    private fun contributionJson(contribution: Contribution): String =
        buildString {
            append("{\"rule\":\"${Json.escape(contribution.rule)}\",")
            append("\"role\":\"${roleName(contribution.role)}\",")
            append("\"intents\":[")
            append(contribution.intents.joinToString(",") { "\"${Json.escape(it)}\"" })
            append("],")
            append("\"evidence\":\"${Json.escape(contribution.evidence)}\",")
            append("\"source\":\"${Json.escape(contribution.source)}\",")
            append("\"confidence\":${contribution.confidence},")
            append("\"startLine\":${contribution.span.startLine},\"startCol\":${contribution.span.startCol},")
            append("\"endLine\":${contribution.span.endLine},\"endCol\":${contribution.span.endCol},")
            append("\"startByte\":${contribution.startByte},\"endByte\":${contribution.endByte}}")
        }

    private fun roleName(role: Role): String =
        when (role) {
            Role.SOURCE_INTENT -> "source-intent"
            Role.DERIVATIVE_USE -> "derivative-use"
            Role.SCAFFOLD -> "scaffold"
        }
}
