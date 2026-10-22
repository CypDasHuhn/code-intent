package dev.cint.server.suggest

import dev.cint.server.SuggestionSettingsLoader
import dev.cint.server.suggest.support.gapSource
import dev.cint.server.suggest.support.harness
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveSuggestionSettingsTest {
    @Test
    fun suggestionsCapabilityHonorsConfigGateAndHasNoSecrets() {
        val disabledRoot = Files.createTempDirectory("cint-live-suggest-disabled")
        try {
            val disabled = harness(disabledRoot, suggestionSettings = null)
            disabled.initialize()
            val descriptor = disabled.initializeResult().path("suggestions")
            assertFalse(descriptor.path("enabled").asBoolean())
            val keys = descriptor.fieldNames().asSequence().toSet()
            assertEquals(setOf("enabled", "trigger", "provider", "model", "knownIntents"), keys)
            assertTrue(keys.none { it.contains("key", ignoreCase = true) || it.contains("secret", ignoreCase = true) })

            val uri = disabledRoot.resolve("Sample.kt").toString()
            disabled.open(uri, gapSource, version = 1)
            val publishes = disabled.methodCount("cint/publishCandidates")
            disabled.suggest(uri, version = 1, id = 2, requestId = "off")
            assertTrue(disabled.lastResponse(2).path("disabled").asBoolean())
            assertEquals(publishes, disabled.methodCount("cint/publishCandidates"))
        } finally {
            disabledRoot.toFile().deleteRecursively()
        }

        val enabledRoot = Files.createTempDirectory("cint-live-suggest-enabled")
        try {
            val file = enabledRoot.resolve(".cint/suggestions.yml")
            Files.createDirectories(file.parent)
            Files.writeString(
                file,
                """
                suggestions:
                  enabled: true
                  trigger: manual
                  provider: fake
                  model: fake-intent-v1
                """.trimIndent(),
            )
            val enabled = harness(enabledRoot, suggestionSettings = null)
            enabled.initialize()
            val descriptor = enabled.initializeResult().path("suggestions")
            assertTrue(descriptor.path("enabled").asBoolean())
            assertEquals("fake", descriptor.path("provider").asText())
        } finally {
            enabledRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun suggestionSettingsDefaultToDeepSeekWhenEnabledWithoutProvider() {
        val root = Files.createTempDirectory("cint-suggestion-settings-deepseek")
        try {
            val file = root.resolve(".cint/suggestions.yml")
            Files.createDirectories(file.parent)
            Files.writeString(
                file,
                """
                suggestions:
                  enabled: true
                """.trimIndent(),
            )
            val settings = SuggestionSettingsLoader.load(root)
            assertEquals("deepseek", settings.provider)
            assertEquals("deepseek", settings.effectiveProvider)
            assertEquals("deepseek-chat", settings.effectiveModel)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun suggestionSettingsClampConfidenceAndLoadPrivacyOptions() {
        val root = Files.createTempDirectory("cint-suggestion-settings-privacy")
        try {
            val file = root.resolve(".cint/suggestions.yml")
            Files.createDirectories(file.parent)
            Files.writeString(
                file,
                """
                suggestions:
                  enabled: true
                  maxConfidence: 1.0
                  excerptRadiusBytes: 12
                  allowGlobs:
                    - "src/**/*.kt"
                  redactPatterns:
                    - "token=.*"
                """.trimIndent(),
            )
            val settings = SuggestionSettingsLoader.load(root)
            assertEquals(0.84, settings.maxConfidence)
            assertEquals(12, settings.excerptRadiusBytes)
            assertEquals(listOf("src/**/*.kt"), settings.allowGlobs)
            assertEquals(listOf("token=.*"), settings.redactPatterns)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun suggestionsCapabilityResolvesTheSelectedProviderModel() {
        val deepSeekRoot = Files.createTempDirectory("cint-live-suggest-cap-deepseek")
        try {
            val file = deepSeekRoot.resolve(".cint/suggestions.yml")
            Files.createDirectories(file.parent)
            Files.writeString(file, "suggestions:\n  enabled: true\n")
            val harness = harness(deepSeekRoot, suggestionSettings = null)
            harness.initialize()
            val descriptor = harness.initializeResult().path("suggestions")
            assertEquals("deepseek", descriptor.path("provider").asText())
            assertEquals("deepseek-chat", descriptor.path("model").asText())
        } finally {
            deepSeekRoot.toFile().deleteRecursively()
        }

        val fakeRoot = Files.createTempDirectory("cint-live-suggest-cap-fake")
        try {
            val file = fakeRoot.resolve(".cint/suggestions.yml")
            Files.createDirectories(file.parent)
            Files.writeString(file, "suggestions:\n  enabled: true\n  provider: fake\n")
            val harness = harness(fakeRoot, suggestionSettings = null)
            harness.initialize()
            val descriptor = harness.initializeResult().path("suggestions")
            assertEquals("fake", descriptor.path("provider").asText())
            assertEquals("fake-intent-v1", descriptor.path("model").asText())
        } finally {
            fakeRoot.toFile().deleteRecursively()
        }
    }
}
