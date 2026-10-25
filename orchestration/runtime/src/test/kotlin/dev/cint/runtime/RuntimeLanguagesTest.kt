package dev.cint.runtime

import dev.cint.contracts.facts.SemanticRequest
import dev.cint.contracts.facts.SemanticResult
import dev.cint.contracts.ports.HostChannelConfig
import dev.cint.contracts.ports.HostSemanticChannel
import dev.cint.contracts.ports.HostSemanticChannelProvider
import dev.cint.contracts.ports.ProviderMode
import dev.cint.contracts.ports.SemanticProviderConfig
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory
import dev.cint.v2.languages.LanguageOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeLanguagesTest {
    @Test
    fun kotlinKeepsItsManagedDefaultAndNonKotlinCommandsResolve() {
        val runtime =
            RuntimeFactory
                .create(
                    RuntimeConfig(
                        detectLanguages = false,
                        materializeDefaultsOnOpen = false,
                        providerCommands =
                            RuntimeConfig.DEFAULT_PROVIDER_COMMANDS +
                                mapOf("javascript" to "javascript-language-server --stdio"),
                    ),
                ).getOrThrow()

        val languages = runtime.languages().getOrThrow()
        val kotlin = languages.single { it.language == "kotlin" }
        assertEquals(ProviderMode.MANAGED, kotlin.providerMode)
        assertEquals("kotlin-lsp --stdio", kotlin.command)
        assertTrue(kotlin.providerConfigured)
        assertTrue(kotlin.providerAvailable)

        val javascript = languages.single { it.language == "javascript" }
        assertEquals(ProviderMode.MANAGED, javascript.providerMode)
        assertEquals("javascript-language-server --stdio", javascript.command)
        assertTrue(javascript.providerConfigured)
        assertTrue(javascript.providerAvailable)
        runtime.shutdown().getOrThrow()
    }

    @Test
    fun attachedEditorAndMissingConfigurationsAreReported() {
        val runtime =
            RuntimeFactory
                .create(
                    RuntimeConfig(
                        detectLanguages = false,
                        materializeDefaultsOnOpen = false,
                        providerConfigs =
                            mapOf(
                                "typescript" to
                                    SemanticProviderConfig(
                                        mode = ProviderMode.MANAGED,
                                        command = "typescript-language-server --stdio",
                                    ),
                                "javascript" to
                                    SemanticProviderConfig(
                                        mode = ProviderMode.ATTACHED,
                                        endpoint = "unix:/tmp/cint-javascript.sock",
                                    ),
                                "csharp" to SemanticProviderConfig(mode = ProviderMode.MANAGED),
                                "vue" to
                                    SemanticProviderConfig(
                                        mode = ProviderMode.EDITOR,
                                        hostChannelConfig = HostChannelConfig("editor"),
                                    ),
                            ),
                    ),
                ).getOrThrow()

        val languages = runtime.languages().getOrThrow()
        val typescript = languages.single { it.language == "typescript" }
        assertEquals("typescript-language-server --stdio", typescript.command)
        assertTrue(typescript.providerConfigured)
        assertTrue(typescript.providerAvailable)

        val javascript = languages.single { it.language == "javascript" }
        assertEquals(ProviderMode.ATTACHED, javascript.providerMode)
        assertEquals("unix:/tmp/cint-javascript.sock", javascript.endpoint)
        assertTrue(javascript.providerConfigured)
        assertTrue(javascript.providerAvailable)

        val csharp = languages.single { it.language == "csharp" }
        assertEquals(ProviderMode.MANAGED, csharp.providerMode)
        assertFalse(csharp.providerConfigured)
        assertFalse(csharp.providerAvailable)
        assertEquals(listOf("command"), csharp.missingConfiguration)

        val vue = languages.single { it.language == "vue" }
        assertEquals(ProviderMode.EDITOR, vue.providerMode)
        assertEquals("editor", vue.hostChannelType)
        assertTrue(vue.providerConfigured)
        assertFalse(vue.providerAvailable)
        assertEquals(listOf("hostSemanticChannelProvider"), vue.missingConfiguration)
        runtime.shutdown().getOrThrow()
    }

    @Test
    fun editorModeIsAvailableWhenAHostChannelProviderIsInjected() {
        val runtime =
            RuntimeFactory
                .create(
                    RuntimeConfig(
                        detectLanguages = false,
                        materializeDefaultsOnOpen = false,
                        providerConfigs =
                            mapOf(
                                "vue" to
                                    SemanticProviderConfig(
                                        mode = ProviderMode.EDITOR,
                                        hostChannelConfig = HostChannelConfig("editor"),
                                    ),
                            ),
                        languageOptions =
                            LanguageOptions(
                                hostChannelProvider =
                                    object : HostSemanticChannelProvider {
                                        override fun open(config: HostChannelConfig): ContractResult<HostSemanticChannel> =
                                            ResultFactory.success(
                                                object : HostSemanticChannel {
                                                    override fun send(request: SemanticRequest): ContractResult<SemanticResult> =
                                                        ResultFactory.success(SemanticResult.DiagnosticsFor(emptyList()))

                                                    override fun close(): ContractResult<Unit> = ResultFactory.success(Unit)
                                                },
                                            )
                                    },
                            ),
                    ),
                ).getOrThrow()

        val vue = runtime.languages().getOrThrow().single { it.language == "vue" }
        assertTrue(vue.providerConfigured)
        assertTrue(vue.providerAvailable)
        assertTrue(vue.missingConfiguration.isEmpty())
        runtime.shutdown().getOrThrow()
    }
}
