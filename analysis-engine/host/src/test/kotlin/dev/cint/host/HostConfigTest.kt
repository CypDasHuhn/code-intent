package dev.cint.host

import dev.cint.core.facts.frontend.OracleOptions
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HostConfigTest {
    @Test
    fun validCustomConfigCarriesPackAndCoreSettings() {
        val file = Files.createTempFile("cint-config", ".yaml")
        Files.writeString(
            file,
            """
            core:
              purity:
                annotations: [CustomPure]
            logging:
              kotlin:
                printCalls: [audit]
            error-handling-kotlin:
              calls: [boom]
            """.trimIndent(),
        )

        try {
            val settings = HostConfig.load(file.toString(), "kotlin")

            assertTrue(settings.issues.isEmpty())
            assertEquals(setOf("logging", "error-handling-kotlin"), settings.project.packs.keys)
            assertEquals(
                listOf("CustomPure"),
                settings.intentConfig("kotlin").pureAnnotations,
            )
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun coreLspPolicyReachesHostOracleOptions() {
        val file = Files.createTempFile("cint-config-lsp", ".yaml")
        Files.writeString(
            file,
            """
            core:
              lsp:
                enabled: false
                timeoutMs: 900
            logging:
              kotlin:
                printCalls: [audit]
            """.trimIndent(),
        )

        try {
            val settings = HostConfig.load(file.toString(), "kotlin")

            assertEquals(OracleOptions(enabled = false, timeoutMs = 900), settings.oracleOptions())
            assertEquals(OracleOptions(enabled = false, timeoutMs = 900), settings.oracleOptions(useLsp = false))
            assertEquals(
                OracleOptions(enabled = false, timeoutMs = 900),
                settings.oracleOptions(defaultTimeoutMs = 750),
            )
            assertEquals(setOf("logging"), settings.project.packs.keys)

            Files.writeString(
                file,
                """
                core:
                  lsp:
                    enabled: true
                    timeoutMs: 1234
                """.trimIndent(),
            )
            val enabled = HostConfig.load(file.toString(), "kotlin")

            assertEquals(OracleOptions(enabled = true, timeoutMs = 1234), enabled.oracleOptions())
            assertEquals(OracleOptions(enabled = false, timeoutMs = 1234), enabled.oracleOptions(useLsp = false))
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun noCustomConfigUsesEmptyProjectDefaults() {
        val settings = HostConfig.load(null, "kotlin")

        assertTrue(settings.issues.isEmpty())
        assertTrue(settings.project.packs.isEmpty())
        assertTrue(
            settings.project.core.purityAnnotations
                .contains("Pure"),
        )
        assertEquals(OracleOptions(enabled = true, timeoutMs = 5_000), settings.oracleOptions())
        assertEquals(OracleOptions(enabled = true, timeoutMs = 750), settings.oracleOptions(defaultTimeoutMs = 750))
    }

    @Test
    fun missingCustomConfigIsAHardFailure() {
        val file = Files.createTempDirectory("cint-config-missing").resolve("absent.yaml")

        assertFailsWith<IllegalArgumentException> {
            HostConfig.load(file.toString(), "kotlin")
        }
    }

    @Test
    fun invalidCustomConfigIsAHardFailure() {
        val file = Files.createTempFile("cint-config-invalid", ".yaml")
        Files.writeString(file, "core: [")
        try {
            assertFailsWith<IllegalStateException> {
                HostConfig.load(file.toString(), "kotlin")
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
