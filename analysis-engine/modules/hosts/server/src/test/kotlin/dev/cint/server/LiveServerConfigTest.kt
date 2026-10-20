package dev.cint.server

import dev.cint.core.facts.frontend.OracleOptions
import dev.cint.core.runtime.rulepack.RulePackLoader
import dev.cint.host.HostConfig
import dev.cint.host.HostPipeline
import dev.cint.host.HostSettings
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class LiveServerConfigTest {
    @Test
    fun liveOracleKeepsItsDefaultTimeoutWithoutExplicitConfig() {
        val settings = HostConfig.load(null)
        val server = server(settings)

        assertEquals(OracleOptions(enabled = true, timeoutMs = LIVE_LSP_TIMEOUT_MS), server.oracleOptions())
        assertEquals(OracleOptions(enabled = false, timeoutMs = LIVE_LSP_TIMEOUT_MS), server.oracleOptions(false))
    }

    @Test
    fun liveOracleHonorsAnExplicitConfiguredTimeout() {
        val file = Files.createTempFile("cint-live-lsp", ".yaml")
        Files.writeString(
            file,
            """
            core:
              lsp:
                timeoutMs: 900
            """.trimIndent(),
        )
        try {
            val server = server(HostConfig.load(file.toString()))

            assertEquals(OracleOptions(enabled = true, timeoutMs = 900), server.oracleOptions())
            assertEquals(OracleOptions(enabled = false, timeoutMs = 900), server.oracleOptions(false))
        } finally {
            Files.deleteIfExists(file)
        }
    }

    private fun server(settings: HostSettings): LiveServer =
        LiveServer(
            root = Path.of("."),
            socketPath = Path.of("/tmp/opencode/cint-013-test.sock"),
            useLsp = true,
            defaultLanguage = "kotlin",
            packs = HostPipeline(loader = RulePackLoader(onFailure = {})),
            settings = settings,
        )
}
