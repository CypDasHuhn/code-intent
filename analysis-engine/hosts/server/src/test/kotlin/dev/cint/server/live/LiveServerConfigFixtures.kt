package dev.cint.server.live

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.cint.api.CINT_API_VERSION
import dev.cint.api.ConfigField
import dev.cint.api.ConfigSchema
import dev.cint.api.ConfigurableRulePack
import dev.cint.api.Intent
import dev.cint.api.Rule
import dev.cint.api.RuleEmit
import dev.cint.api.RulePack
import dev.cint.api.RulePackConfigException
import dev.cint.api.View
import dev.cint.core.runtime.rulepack.RulePackLoader
import dev.cint.host.HostPipeline
import dev.cint.host.HostSettings
import dev.cint.host.PackConfigOverlay
import dev.cint.server.LiveServer
import java.nio.file.Files
import java.nio.file.Path

private class ServerLegacyRule : Rule {
    override val id: String = "legacy-server:rule"
    override val owns: Intent = "LEGACY_SERVER"
    override val reads: Set<Intent> = emptySet()

    override fun run(view: View): List<RuleEmit> = emptyList()
}

class ServerLegacyPack(
    private val calls: Set<String> = setOf("default"),
) : ConfigurableRulePack {
    companion object {
        var lastCalls: Set<String>? = null
            private set

        fun reset() {
            lastCalls = null
        }
    }

    override val id: String = "legacy-server"
    override val languages: Set<String> = setOf("testlang")
    override val apiVersion: Int = CINT_API_VERSION

    override fun configure(values: Map<String, Any?>): RulePack {
        val decoded =
            when (val raw = values["calls"]) {
                null -> calls
                is String -> setOf(raw)
                is List<*> -> raw.map { it as String }.toSet()
                else -> throw RulePackConfigException("legacy-server.calls: expected strings")
            }
        lastCalls = decoded
        return ServerLegacyPack(decoded)
    }

    override fun configSchema(): ConfigSchema<*> =
        ConfigSchema.of(
            ConfigField.strings<ServerLegacyPack>(
                "calls",
                { it.calls },
                { _, value -> ServerLegacyPack(value) },
            ),
        )

    override fun rules(): List<Rule> = listOf(ServerLegacyRule())
}

internal fun jsonString(value: String): String = jacksonObjectMapper().writeValueAsString(value)

internal fun loggingLines(
    server: LiveServer,
    source: String,
): Set<Int> =
    server
        .analyzeForTest("Test.kt", source, "kotlin")
        .filter { "LOGGING" in it.intents }
        .map { it.span.startLine }
        .toSet()

internal fun writeOverlay(
    root: Path,
    text: String,
) {
    val path = PackConfigOverlay.path(root)
    Files.createDirectories(path.parent)
    Files.writeString(path, text)
}

internal fun server(
    settings: HostSettings,
    pipeline: HostPipeline = HostPipeline(loader = RulePackLoader(onFailure = {})),
    root: Path = Path.of("."),
    useLsp: Boolean = true,
): LiveServer =
    LiveServer(
        root = root,
        socketPath = Path.of("/tmp/opencode/cint-013-test.sock"),
        useLsp = useLsp,
        defaultLanguage = "kotlin",
        packs = pipeline,
        settings = settings,
    )
