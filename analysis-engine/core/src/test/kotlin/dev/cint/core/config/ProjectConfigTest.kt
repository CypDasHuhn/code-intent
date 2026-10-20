package dev.cint.core.config

import dev.cint.core.frontend.OracleOptions
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectConfigTest {
    @Test
    fun defaultsApplyWithoutAConfigFile() {
        val parsed = parseProjectConfig(null)

        assertTrue(parsed.issues.isEmpty())
        assertTrue(parsed.config.packs.isEmpty())
        assertEquals(DEFAULT_PURITY_ANNOTATIONS, parsed.config.core.purityAnnotations)
        assertEquals(LspPolicy(), parsed.config.core.lsp)
    }

    @Test
    fun corePolicyAndPackSectionsAreSeparate() {
        val parsed =
            parse(
                """
                core:
                  purity:
                    annotations: [CustomPure]
                  lsp:
                    enabled: false
                    timeoutMs: 900
                logging:
                  kotlin:
                    printCalls: [audit]
                """.trimIndent(),
            )

        assertTrue(parsed.issues.isEmpty())
        assertEquals(listOf("CustomPure"), parsed.config.core.purityAnnotations)
        assertEquals(LspPolicy(enabled = false, timeoutMs = 900), parsed.config.core.lsp)
        assertEquals(setOf("logging"), parsed.config.packs.keys)
        assertEquals(setOf("kotlin"), parsed.config.packs.getValue("logging").keys)
        assertEquals(
            mapOf("printCalls" to listOf("audit")),
            parsed.config.packs.getValue("logging").getValue("kotlin"),
        )
    }

    @Test
    fun unknownCoreKeysAreReportedOutsidePackConfig() {
        val parsed =
            parse(
                """
                core:
                  unknownPolicy: true
                logging:
                  kotlin:
                    printCalls: [audit]
                """.trimIndent(),
            )

        val issue = parsed.issues.single()
        assertEquals("core.unknownPolicy", issue.path)
        assertEquals(setOf("logging"), parsed.config.packs.keys)
        assertFalse(parsed.config.packs.getValue("logging").containsKey("unknownPolicy"))
    }

    @Test
    fun malformedCoreAndPackSectionsAreReported() {
        val parsed =
            parse(
                """
                core:
                  lsp:
                    enabled: true
                    timeoutMs: 0
                logging: true
                """.trimIndent(),
            )

        assertEquals(
            setOf("core.lsp.timeoutMs", "logging"),
            parsed.issues.map { it.path }.toSet(),
        )
        assertTrue(parsed.config.packs.isEmpty())
    }

    @Test
    fun fractionalNonFiniteAndOverflowingTimeoutsAreReported() {
        for (value in listOf("1.5", ".inf", "Infinity", "9223372036854775808")) {
            val parsed =
                parse(
                    """
                    core:
                      lsp:
                        timeoutMs: $value
                    """.trimIndent(),
                )

            assertEquals(
                listOf("core.lsp.timeoutMs"),
                parsed.issues.map { it.path },
                value,
            )
            assertEquals(LspPolicy(), parsed.config.core.lsp, value)
        }
    }

    @Test
    fun missingExplicitConfigFileIsReportedWithDefaults() {
        val path = Files.createTempFile("cint-project-config", ".yaml")
        path.deleteIfExists()

        val parsed = parseProjectConfig(path.toString())

        assertEquals(path.toString(), parsed.issues.single().path)
        assertTrue(parsed.config.packs.isEmpty())
        assertEquals(DEFAULT_PURITY_ANNOTATIONS, parsed.config.core.purityAnnotations)
    }

    @Test
    fun frameworkAppliesCorePolicyToIntentConfig() {
        val policy = CorePolicy(purityAnnotations = listOf("CustomPure"))

        val config = loadIntentConfig(null, "kotlin").withCorePolicy(policy)

        assertEquals(listOf("CustomPure"), config.pureAnnotations)
    }

    @Test
    fun frameworkReadsLspPolicy() {
        val policy = CorePolicy(lsp = LspPolicy(enabled = false, timeoutMs = 900))

        assertEquals(OracleOptions(false, 900), policy.oracleOptions())
    }

    private fun parse(text: String): ParsedProjectConfig {
        val path = Files.createTempFile("cint-project-config", ".yaml")
        return try {
            Files.writeString(path, text)
            parseProjectConfig(path.toString())
        } finally {
            path.deleteIfExists()
        }
    }
}
