package dev.cint.server.suggest.support

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.cint.host.HostConfig
import dev.cint.host.HostRuntime
import dev.cint.rulepacks.BundledRulePackConfigResolver
import dev.cint.server.SuggestionSettings
import dev.cint.server.SuggestionSettingsLoader
import dev.cint.server.suggestionServiceFrom
import dev.cint.suggest.SuggestionService
import java.nio.charset.StandardCharsets
import java.nio.file.Path

internal val mapper = jacksonObjectMapper()

internal val gapSource =
    """
    fun f(): Boolean {
        // cint: validation

        if (true) {
            work()
        }
        return false
    }
    """.trimIndent()

internal val twoGapSource =
    """
    fun a(): Boolean {
        // cint: validation

        if (true) {
            first()
        }
        return false
    }

    fun b(): Boolean {
        // cint: validation

        if (true) {
            second()
        }
        return false
    }
    """.trimIndent()

internal fun lineCol(
    text: String,
    offset: Int,
): Pair<Int, Int> {
    val bytes = text.toByteArray(StandardCharsets.UTF_8)
    var line = 0
    var col = 0
    for (index in 0 until offset.coerceIn(0, bytes.size)) {
        if (bytes[index].toInt() == '\n'.code) {
            line += 1
            col = 0
        } else {
            col += 1
        }
    }
    return line to col
}

internal fun candidate(
    message: JsonNode,
    intent: String,
): JsonNode = candidates(message, intent).single()

internal fun candidates(
    message: JsonNode,
    intent: String,
): List<JsonNode> = message["params"]["candidates"].filter { it["intents"].any { value -> value.asText() == intent } }

internal fun harness(
    root: Path,
    suggestionSettings: SuggestionSettings? =
        SuggestionSettings(
            enabled = true,
            trigger = "manual",
            provider = "fake",
            model = "fake-intent-v1",
            discoveryEnabled = false,
        ),
    service: SuggestionService? = null,
): Harness {
    val settings = HostConfig.load(null)
    val pipeline = HostRuntime.pipeline(settings, BundledRulePackConfigResolver, root)
    val resolvedSettings = suggestionSettings ?: SuggestionSettingsLoader.load(root)
    return Harness(
        root,
        settings,
        pipeline,
        resolvedSettings,
        service ?: suggestionServiceFrom(resolvedSettings),
    )
}
