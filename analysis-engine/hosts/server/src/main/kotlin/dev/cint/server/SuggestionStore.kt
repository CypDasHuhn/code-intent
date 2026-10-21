package dev.cint.server

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal data class SuggestionDecision(
    val candidateId: String,
    val path: String,
    val contentHash: String,
    val intents: Set<String>,
    val reason: String,
    val decision: String,
    val createdAt: String,
    val anchorId: String? = null,
    val startLine: Int = 0,
    val startCol: Int = 0,
    val endLine: Int = 0,
    val endCol: Int = 0,
)

internal class SuggestionStore(
    private val root: Path,
) {
    private val mapper = jacksonObjectMapper()

    fun path(): Path = root.resolve(".cint").resolve("suggestions.json")

    @Synchronized
    fun load(): List<SuggestionDecision> {
        val path = path()
        if (!Files.isRegularFile(path)) return emptyList()
        val entries =
            runCatching {
                mapper.readTree(Files.readString(path))["decisions"]
            }.getOrNull() ?: return emptyList()
        return entries.mapNotNull { node ->
            val candidateId = node["candidateId"]?.asText() ?: return@mapNotNull null
            val decision = node["decision"]?.asText() ?: return@mapNotNull null
            SuggestionDecision(
                candidateId = candidateId,
                path = node["path"]?.asText().orEmpty(),
                contentHash = node["contentHash"]?.asText().orEmpty(),
                intents = node["intents"]?.map { it.asText() }?.toSet().orEmpty(),
                reason = node["reason"]?.asText().orEmpty(),
                decision = decision,
                createdAt = node["createdAt"]?.asText().orEmpty(),
                anchorId = node["anchorId"]?.asText()?.takeIf { it.isNotBlank() },
                startLine = node["startLine"]?.asInt() ?: 0,
                startCol = node["startCol"]?.asInt() ?: 0,
                endLine = node["endLine"]?.asInt() ?: 0,
                endCol = node["endCol"]?.asInt() ?: 0,
            )
        }
    }

    @Synchronized
    fun record(decision: SuggestionDecision) {
        val retained = load().filterNot { it.candidateId == decision.candidateId }
        save(retained + decision)
    }

    fun suppressedIds(): Set<String> =
        load()
            .filter { it.decision == "reject" }
            .mapTo(linkedSetOf()) { it.candidateId }

    private fun save(decisions: List<SuggestionDecision>) {
        val path = path()
        Files.createDirectories(path.parent)
        val payload =
            linkedMapOf(
                "version" to 1,
                "decisions" to
                    decisions.map { decision ->
                        linkedMapOf(
                            "candidateId" to decision.candidateId,
                            "path" to decision.path,
                            "contentHash" to decision.contentHash,
                            "intents" to decision.intents.sorted(),
                            "reason" to decision.reason,
                            "decision" to decision.decision,
                            "createdAt" to decision.createdAt,
                            "anchorId" to decision.anchorId,
                            "startLine" to decision.startLine,
                            "startCol" to decision.startCol,
                            "endLine" to decision.endLine,
                            "endCol" to decision.endCol,
                        )
                    },
            )
        val temporary = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(temporary, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload))
        runCatching {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
