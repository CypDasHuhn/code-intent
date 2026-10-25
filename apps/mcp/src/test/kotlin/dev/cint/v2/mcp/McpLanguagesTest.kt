package dev.cint.v2.mcp

import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ResultFactory
import dev.cint.runtime.RuntimeComponents
import dev.cint.runtime.RuntimeConfig
import dev.cint.runtime.RuntimeFactory
import dev.cint.runtime.RuntimeLanguageComposition
import dev.cint.v2.testing.fakes.FakeClock
import dev.cint.v2.testing.fakes.FakeIndex
import dev.cint.v2.testing.fakes.FakeIntentPipeline
import dev.cint.v2.testing.fakes.FakeKnowledgeStore
import dev.cint.v2.testing.fakes.FakeRulePackSource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpLanguagesTest {
    @Test
    fun repoLanguagesReportsResolvedKotlinAndConfiguredNonKotlinProviders() {
        val runtime =
            RuntimeFactory
                .create(
                    RuntimeConfig(
                        detectLanguages = false,
                        materializeDefaultsOnOpen = false,
                        providerCommands =
                            RuntimeConfig.DEFAULT_PROVIDER_COMMANDS +
                                mapOf("typescript" to "typescript-language-server --stdio"),
                    ),
                ).getOrThrow()
        val tools = McpRuntimeTools(runtime)

        val result = tools.call("repo.languages", JsonObject(emptyMap()))

        assertTrue(!result.isError)
        val payload = result.structuredContent!!.jsonObject
        assertEquals("configuration-only", payload.getValue("availabilityProbe").jsonPrimitive.content)
        assertTrue(
            payload
                .getValue("availabilityCaveat")
                .jsonPrimitive.content
                .contains("does not start an LSP"),
        )
        val languages = payload.getValue("languages").jsonArray.map { it.jsonObject }
        assertEquals(
            languages.size,
            payload
                .getValue("count")
                .jsonPrimitive.content
                .toInt(),
        )

        val kotlin = languages.single { it.getValue("language").jsonPrimitive.content == "kotlin" }
        assertEquals("MANAGED", kotlin.getValue("providerMode").jsonPrimitive.content)
        assertEquals("kotlin-lsp --stdio", kotlin.getValue("command").jsonPrimitive.content)
        assertTrue(
            kotlin
                .getValue("providerConfigured")
                .jsonPrimitive.content
                .toBoolean(),
        )
        assertTrue(
            kotlin
                .getValue("providerAvailable")
                .jsonPrimitive.content
                .toBoolean(),
        )

        val typescript = languages.single { it.getValue("language").jsonPrimitive.content == "typescript" }
        assertEquals("MANAGED", typescript.getValue("providerMode").jsonPrimitive.content)
        assertEquals("typescript-language-server --stdio", typescript.getValue("command").jsonPrimitive.content)
        assertTrue(
            typescript
                .getValue("providerConfigured")
                .jsonPrimitive.content
                .toBoolean(),
        )
        runtime.shutdown().getOrThrow()
    }

    @Test
    fun analysisRunMissingProviderAddsAConfigurationHint() {
        val providerError =
            ContractError.PreconditionFailed(
                "Managed LSP mode for language 'javascript' requires a command; " +
                    "configure --provider-command javascript=<command> or --provider-config <path>",
            )
        val runtime =
            RuntimeFactory
                .create(
                    RuntimeConfig(),
                    RuntimeComponents(
                        languageComposition =
                            RuntimeLanguageComposition { _, _ -> ResultFactory.failure(providerError) },
                        index = FakeIndex(mutableMapOf("Sample.js" to "function findUser() {}"), FakeClock()),
                        packSourceFactory = { FakeRulePackSource() },
                        knowledgeStore = FakeKnowledgeStore(),
                        pipeline = FakeIntentPipeline(),
                    ),
                ).getOrThrow()
        val tools = McpRuntimeTools(runtime)

        val result =
            tools.call(
                "analysis.run",
                buildJsonObject {
                    put("targetPaths", buildJsonArray { add(JsonPrimitive("Sample.js")) })
                    put("language", "javascript")
                },
            )

        assertTrue(result.isError)
        val payload = result.structuredContent!!.jsonObject
        val error = payload.getValue("error").jsonObject
        assertEquals("PreconditionFailed", error.getValue("code").jsonPrimitive.content)
        assertTrue(
            error
                .getValue("message")
                .jsonPrimitive.content
                .contains("javascript"),
        )
        assertTrue(
            payload
                .getValue("hint")
                .jsonPrimitive.content
                .contains("--provider-config"),
        )
        runtime.shutdown().getOrThrow()
    }
}
