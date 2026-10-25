package dev.cint.v2.testing.corpus

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

data class FrozenCliFile(
    val path: String,
    val hash: String,
    val spans: List<JsonElement>,
    val candidates: List<JsonElement>,
)

data class FrozenCliCorpus(
    val language: String,
    val intents: List<String>,
    val files: List<FrozenCliFile>,
    val raw: JsonObject,
)

data class FrozenProtocolTranscript(
    val name: String,
    val messages: List<JsonObject>,
    val session: JsonObject?,
)

data class FrozenScenario(
    val name: String,
    val source: String,
    val overlay: String?,
)

class FrozenV1Corpus(private val root: Path) {
    private val json = Json { ignoreUnknownKeys = true }

    fun scenarios(): Map<String, FrozenScenario> {
        val data = parseObject(root.resolve("fixtures/scenarios.json"))
        val scenarios = data["scenarios"]?.jsonObject ?: return emptyMap()
        return scenarios.entries
            .sortedBy { it.key }
            .associate { (name, value) ->
                val scenario = value.jsonObject
                name to FrozenScenario(
                    name = name,
                    source = scenario["source"]?.jsonPrimitive?.content.orEmpty(),
                    overlay = scenario["overlay"]?.jsonPrimitive?.contentOrNull,
                )
            }
    }

    fun cli(name: String): FrozenCliCorpus {
        val fileName = if (name.endsWith(".json")) name else "cli-$name.json"
        val data = parseObject(root.resolve(fileName))
        val files = data["files"]?.jsonArray.orEmpty().map { element ->
            val file = element.jsonObject
            FrozenCliFile(
                path = file["path"]?.jsonPrimitive?.content.orEmpty(),
                hash = file["hash"]?.jsonPrimitive?.content.orEmpty(),
                spans = file["spans"]?.jsonArray?.toList().orEmpty(),
                candidates = file["candidates"]?.jsonArray?.toList().orEmpty(),
            )
        }
        return FrozenCliCorpus(
            language = data["language"]?.jsonPrimitive?.content.orEmpty(),
            intents = data["intents"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content },
            files = files,
            raw = data,
        )
    }

    fun protocol(name: String): FrozenProtocolTranscript {
        val baseName = protocolBaseName(name)
        val messages = Files.readAllLines(root.resolve("$baseName.ndjson"))
            .filter { it.isNotBlank() }
            .map { parseObject(it) }
        val sessionPath = root.resolve("$baseName.session.json")
        val session = if (Files.isRegularFile(sessionPath)) parseObject(sessionPath) else null
        return FrozenProtocolTranscript(baseName, messages, session)
    }

    fun source(language: String, fileName: String): String =
        Files.readString(root.resolve("fixtures/sources/$language/$fileName"))

    fun knownDifferences(): JsonObject = parseObject(root.resolve("known-differences.json"))

    fun manifest(): JsonObject = parseObject(root.resolve("manifest.json"))

    fun rendererVisible(): JsonObject = parseObject(root.resolve("renderer-visible-v1.json"))

    fun readText(relativePath: String): String = Files.readString(root.resolve(relativePath))

    fun rootPath(): Path = root

    fun manifestHashMismatches(): List<String> {
        val manifest = manifest()
        val expected = linkedMapOf<String, String>()
        for (section in listOf("files", "fixtures", "contracts")) {
            manifest[section]?.jsonObject?.forEach { (path, hash) ->
                expected[path] = hash.jsonPrimitive.content
            }
        }
        return expected.entries.sortedBy { it.key }.mapNotNull { (path, expectedHash) ->
            val file = root.resolve(path)
            if (!Files.isRegularFile(file)) {
                "missing:$path"
            } else {
                val actual = sha256(Files.readAllBytes(file))
                if (actual == expectedHash) null else "hash:$path"
            }
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun parseObject(path: Path): JsonObject = json.parseToJsonElement(Files.readString(path)).jsonObject

    private fun parseObject(text: String): JsonObject = json.parseToJsonElement(text).jsonObject

    private fun protocolBaseName(name: String): String {
        val trimmed = when {
            name.endsWith(".session.json") -> name.removeSuffix(".session.json")
            name.endsWith(".ndjson") -> name.removeSuffix(".ndjson")
            name.endsWith(".json") -> name.removeSuffix(".json")
            else -> name
        }
        return if (trimmed.startsWith("protocol-")) trimmed else "protocol-$trimmed"
    }
}

