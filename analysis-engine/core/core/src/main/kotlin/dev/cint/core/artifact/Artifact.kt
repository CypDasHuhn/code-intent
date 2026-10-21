package dev.cint.core.artifact

import dev.cint.api.Annotation
import dev.cint.api.CandidateEmit

data class FileEntry(
    val path: String,
    val hash: String,
    val annotations: List<Annotation>,
    val candidates: List<CandidateEmit> = emptyList(),
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
                    append("        ")
                    append(AnnotationJson.render(annotation))
                    if (j < entry.annotations.size - 1) append(",")
                    append("\n")
                }
                append("      ],\n")
                append("      \"candidates\": [\n")
                entry.candidates.forEachIndexed { j, candidate ->
                    append("        ")
                    append(CandidateJson.render(candidate))
                    if (j < entry.candidates.size - 1) append(",")
                    append("\n")
                }
                append("      ]\n    }")
                if (i < files.size - 1) append(",")
                append("\n")
            }
            append("  ]\n}\n")
        }
}
