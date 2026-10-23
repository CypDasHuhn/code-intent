package dev.cint.server.cache

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.cint.suggest.cache.CachedVerdict
import dev.cint.suggest.cache.CachedVerdictOperation
import dev.cint.suggest.cache.VerdictCache
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

data class AiVerdictCacheEnvironment(
    val engineVersion: String,
    val deterministicHash: String,
    val aiEnv: String,
    val provider: String?,
    val model: String?,
    val promptVersion: String,
    val vocabularyVersion: String,
)

object AiVerdictCacheStore {
    private const val FILE_VERSION: Int = 1
    private const val FILE_NAME: String = "ai-verdicts.json"
    private val mapper = jacksonObjectMapper()

    fun path(root: Path): Path = root.resolve(".cint").resolve("cache").resolve(FILE_NAME)

    fun load(
        root: Path,
        environment: AiVerdictCacheEnvironment,
        maxEntries: Int,
    ): List<CachedVerdict> {
        val path = path(root)
        if (!Files.isRegularFile(path)) return emptyList()
        val parsed =
            runCatching {
                val rootNode = mapper.readTree(Files.readString(path))
                val fileVersion = rootNode["version"]?.asInt()
                if (fileVersion != FILE_VERSION) {
                    if (fileVersion != null) {
                        System.err.println("cint ai verdict cache: ignoring file version $fileVersion")
                    }
                    return emptyList()
                }
                if (!environmentMatches(rootNode["environment"], environment)) return emptyList()
                rootNode["entries"]?.mapNotNull(::entry)
                    ?: emptyList()
            }.getOrElse { error ->
                System.err.println("cint ai verdict cache: ${error.message ?: error.javaClass.name}")
                emptyList()
            }
        return parsed
            .filter { it.aiEnv == environment.aiEnv }
            .sortedByDescending { it.lastUsed }
            .take(maxEntries.coerceAtLeast(0))
    }

    fun save(
        root: Path,
        environment: AiVerdictCacheEnvironment,
        entries: List<CachedVerdict>,
    ) {
        val path = path(root)
        Files.createDirectories(path.parent)
        val payload =
            linkedMapOf<String, Any?>(
                "version" to FILE_VERSION,
                "environment" to
                    linkedMapOf(
                        "engineVersion" to environment.engineVersion,
                        "deterministicHash" to environment.deterministicHash,
                        "aiEnv" to environment.aiEnv,
                        "provider" to environment.provider,
                        "model" to environment.model,
                        "promptVersion" to environment.promptVersion,
                        "vocabularyVersion" to environment.vocabularyVersion,
                    ),
                "entries" to entries.sortedBy { it.key }.map(::entryMap),
            )
        val temporary = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(temporary, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload))
        runCatching {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun environmentMatches(
        node: JsonNode?,
        environment: AiVerdictCacheEnvironment,
    ): Boolean {
        if (node == null || node.isMissingNode) return false
        return node["engineVersion"]?.asText() == environment.engineVersion &&
            node["deterministicHash"]?.asText() == environment.deterministicHash &&
            node["aiEnv"]?.asText() == environment.aiEnv &&
            node["provider"]?.asText(null) == environment.provider &&
            node["model"]?.asText(null) == environment.model &&
            node["promptVersion"]?.asText() == environment.promptVersion &&
            node["vocabularyVersion"]?.asText() == environment.vocabularyVersion
    }

    private fun entry(node: JsonNode): CachedVerdict? {
        val key = node["key"]?.asText()?.takeIf { it.isNotBlank() } ?: return null
        val aiEnv = node["aiEnv"]?.asText()?.takeIf { it.isNotBlank() } ?: return null
        return CachedVerdict(
            key = key,
            aiEnv = aiEnv,
            path = node["path"]?.asText().orEmpty(),
            language = node["language"]?.asText().orEmpty(),
            nodeKind = node["nodeKind"]?.asText().orEmpty(),
            textDigest = node["textDigest"]?.asText().orEmpty(),
            rawTextDigest = node["rawTextDigest"]?.asText().orEmpty(),
            importsDigest = node["importsDigest"]?.asText().orEmpty(),
            symbolDigest = node["symbolDigest"]?.asText().orEmpty(),
            signatureDigest = node["signatureDigest"]?.asText().orEmpty(),
            referencesDigest = node["referencesDigest"]?.asText().orEmpty(),
            existingIntentDigest = node["existingIntentDigest"]?.asText().orEmpty(),
            adds = operations(node["adds"]),
            removes = operations(node["removes"]),
            none = node["none"]?.asBoolean() ?: false,
            anchorFingerprint = node["anchorFingerprint"]?.asText().orEmpty(),
            startByte = node["startByte"]?.asInt() ?: 0,
            endByte = node["endByte"]?.asInt() ?: 0,
            createdAt = node["createdAt"]?.asLong() ?: 0L,
            lastUsed = node["lastUsed"]?.asLong() ?: 0L,
        )
    }

    private fun operations(node: JsonNode?): List<CachedVerdictOperation> =
        node
            ?.mapNotNull { operation ->
                val intent = operation["intent"]?.asText()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                CachedVerdictOperation(
                    intent = intent,
                    startOffset = operation["startOffset"]?.asInt() ?: return@mapNotNull null,
                    endOffset = operation["endOffset"]?.asInt() ?: return@mapNotNull null,
                    confidence = operation["confidence"]?.asDouble() ?: 0.5,
                    startToken = operation["startToken"]?.asInt(),
                    endToken = operation["endToken"]?.asInt(),
                )
            }.orEmpty()

    private fun entryMap(entry: CachedVerdict): Map<String, Any?> =
        linkedMapOf(
            "key" to entry.key,
            "aiEnv" to entry.aiEnv,
            "path" to entry.path,
            "language" to entry.language,
            "nodeKind" to entry.nodeKind,
            "textDigest" to entry.textDigest,
            "rawTextDigest" to entry.rawTextDigest,
            "importsDigest" to entry.importsDigest,
            "symbolDigest" to entry.symbolDigest,
            "signatureDigest" to entry.signatureDigest,
            "referencesDigest" to entry.referencesDigest,
            "existingIntentDigest" to entry.existingIntentDigest,
            "adds" to entry.adds.map(::operationMap),
            "removes" to entry.removes.map(::operationMap),
            "none" to entry.none,
            "anchorFingerprint" to entry.anchorFingerprint,
            "startByte" to entry.startByte,
            "endByte" to entry.endByte,
            "createdAt" to entry.createdAt,
            "lastUsed" to entry.lastUsed,
        )

    private fun operationMap(operation: CachedVerdictOperation): Map<String, Any?> =
        linkedMapOf(
            "intent" to operation.intent,
            "startOffset" to operation.startOffset,
            "endOffset" to operation.endOffset,
            "confidence" to operation.confidence,
            "startToken" to operation.startToken,
            "endToken" to operation.endToken,
        )
}
