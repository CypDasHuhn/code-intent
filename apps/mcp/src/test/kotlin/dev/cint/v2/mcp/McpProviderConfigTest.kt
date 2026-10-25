package dev.cint.v2.mcp

import dev.cint.contracts.ports.ProviderMode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpProviderConfigTest {
    private val temporaryRoots = mutableListOf<Path>()

    @AfterTest
    fun cleanUp() {
        temporaryRoots.forEach { runCatching { it.toFile().deleteRecursively() } }
    }

    @Test
    fun providerConfigFileParsesManagedAttachedAndEditorEntries() {
        val file =
            providerConfigFile(
                """
                {
                  "providers": {
                    "kotlin": {"mode": "managed", "command": "kotlin-lsp --stdio"},
                    "typescript": {"mode": "attached", "endpoint": "unix:/tmp/cint-ts.sock"},
                    "vue": {
                      "mode": "editor",
                      "hostChannelConfig": {"type": "editor", "config": {"socket": "/tmp/cint-editor.sock"}}
                    }
                  }
                }
                """.trimIndent(),
            )

        val options = parseOptions(arrayOf("--provider-config", file.toString()))

        assertEquals(setOf("kotlin", "typescript", "vue"), options.providerConfigs.keys)
        assertEquals(ProviderMode.MANAGED, options.providerConfigs.getValue("kotlin").mode)
        assertEquals("kotlin-lsp --stdio", options.providerConfigs.getValue("kotlin").command)
        assertEquals(ProviderMode.ATTACHED, options.providerConfigs.getValue("typescript").mode)
        assertEquals("unix:/tmp/cint-ts.sock", options.providerConfigs.getValue("typescript").endpoint)
        assertEquals(ProviderMode.EDITOR, options.providerConfigs.getValue("vue").mode)
        assertEquals(
            "editor",
            options.providerConfigs
                .getValue("vue")
                .hostChannelConfig
                ?.type,
        )
        assertTrue(options.providerCommands.isEmpty())
    }

    @Test
    fun repeatedFlagsSelectLanguagesAndPreserveTheKotlinDefault() {
        val root = temporaryRoot()

        val options =
            parseOptions(
                arrayOf(
                    "--root",
                    root.toString(),
                    "--provider-command",
                    "typescript=typescript-language-server --stdio",
                    "--provider-mode",
                    "javascript=attached",
                    "--provider-endpoint",
                    "javascript=unix:/tmp/cint-js.sock",
                ),
            )

        assertEquals("typescript-language-server --stdio", options.providerCommands["typescript"])
        assertEquals(ProviderMode.MANAGED, options.providerConfigs.getValue("typescript").mode)
        assertEquals(
            "typescript-language-server --stdio",
            options.providerConfigs.getValue("typescript").command,
        )
        assertEquals(ProviderMode.ATTACHED, options.providerConfigs.getValue("javascript").mode)
        assertEquals("unix:/tmp/cint-js.sock", options.providerConfigs.getValue("javascript").endpoint)

        val config = options.toRuntimeConfig()
        assertEquals("kotlin-lsp --stdio", config.providerCommands.getValue("kotlin"))
        assertEquals(
            "typescript-language-server --stdio",
            config.providerCommands.getValue("typescript"),
        )
        assertEquals(root.toString(), config.languageOptions.rootPath)
    }

    @Test
    fun flagsOverrideFileEntriesAndLanguageKeysAreCaseInsensitive() {
        val file =
            providerConfigFile(
                """
                {
                  "providers": {
                    "typescript": {"mode": "attached", "endpoint": "unix:/tmp/from-file.sock"}
                  }
                }
                """.trimIndent(),
            )

        val options =
            parseOptions(
                arrayOf(
                    "--provider-config=$file",
                    "--provider-command",
                    "TYPESCRIPT=typescript-language-server --stdio",
                    "--provider-mode",
                    "typescript=managed",
                ),
            )

        assertEquals(ProviderMode.MANAGED, options.providerConfigs.getValue("typescript").mode)
        assertEquals(
            "typescript-language-server --stdio",
            options.providerConfigs.getValue("typescript").command,
        )
        assertEquals(
            "unix:/tmp/from-file.sock",
            options.providerConfigs.getValue("typescript").endpoint,
        )
        assertFalse(options.providerConfigs.containsKey("TYPESCRIPT"))
    }

    @Test
    fun invalidProviderConfigurationFailsClearly() {
        val missingSeparator =
            assertFailsWith<IllegalArgumentException> {
                parseOptions(arrayOf("--provider-command", "typescript"))
            }
        assertTrue(missingSeparator.message!!.contains("LANGUAGE="), missingSeparator.message)

        val blankValue =
            assertFailsWith<IllegalArgumentException> {
                parseOptions(arrayOf("--provider-command", "typescript="))
            }
        assertTrue(blankValue.message!!.contains("must not be blank"), blankValue.message)

        val badMode =
            assertFailsWith<IllegalArgumentException> {
                parseOptions(arrayOf("--provider-mode", "typescript=none"))
            }
        assertTrue(badMode.message!!.contains("unsupported provider mode"), badMode.message)

        val missingFile =
            assertFailsWith<IllegalArgumentException> {
                parseOptions(arrayOf("--provider-config", "/definitely/missing/providers.json"))
            }
        assertTrue(missingFile.message!!.contains("unable to read provider config"), missingFile.message)

        val malformed =
            providerConfigFile(
                """
                {
                  "providers": {
                    "typescript": {"mode": "managed", "command": ""}
                  }
                }
                """.trimIndent(),
            )
        val blankCommand =
            assertFailsWith<IllegalArgumentException> {
                parseOptions(arrayOf("--provider-config", malformed.toString()))
            }
        assertTrue(blankCommand.message!!.contains("command for language 'typescript'"), blankCommand.message)
    }

    private fun providerConfigFile(content: String): Path {
        val file = temporaryRoot().resolve("providers.json")
        Files.writeString(file, content)
        return file
    }

    private fun temporaryRoot(): Path {
        val root = Files.createTempDirectory("cint-mcp-099")
        temporaryRoots.add(root)
        return root
    }
}
