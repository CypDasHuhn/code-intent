package dev.cint.v2.server

import dev.cint.contracts.ports.ProviderMode
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProviderConfigTest {
    @Test
    fun loadsManagedProviderConfigFromProjectRoot() {
        val root = Files.createTempDirectory("cint-provider-config")
        try {
            val configDirectory = Files.createDirectories(root.resolve(".cint"))
            Files.writeString(
                configDirectory.resolve("providers.json"),
                """
                {
                  "providers": {
                    "CSharp": {
                      "command": "roslyn-language-server --stdio",
                      "rootPath": "redispatch-api",
                      "timeoutMs": 10000,
                      "initTimeoutMs": 120000
                    }
                  }
                }
                """.trimIndent(),
            )

            val config = loadProviderConfigs(root).getValue("csharp")

            assertEquals(ProviderMode.MANAGED, config.mode)
            assertEquals("roslyn-language-server --stdio", config.command)
            assertEquals(root.resolve("redispatch-api").toString(), config.rootPath)
            assertEquals(10_000, config.timeoutMs)
            assertEquals(120_000, config.initTimeoutMs)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun missingProviderConfigIsAllowed() {
        val root = Files.createTempDirectory("cint-provider-config-missing")
        try {
            assertTrue(loadProviderConfigs(root).isEmpty())
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
