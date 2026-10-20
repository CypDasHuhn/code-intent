package dev.cint.integration.host

import dev.cint.api.Annotation
import dev.cint.api.Role
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.NullOracle
import dev.cint.frontend.Frontends
import dev.cint.host.HostConfig
import dev.cint.host.HostPipeline
import dev.cint.rulepacks.support.BundledRulePackSelection
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HostConfigWiringTest {
    @Test
    fun loggingPackConfigDrivesHostDetection() {
        val annotations =
            analyze(
                "kotlin",
                """
                fun f() {
                    audit("logged")
                    println("not configured")
                }
                """.trimIndent(),
                """
                logging:
                  kotlin:
                    printCalls: [audit]
                """.trimIndent(),
            )

        val logging = annotations.filter { it.role == Role.SOURCE_INTENT && "LOGGING" in it.intents }
        assertEquals(1, logging.size)
        assertTrue(logging.single().evidence.contains("audit"))
    }

    @Test
    fun errorHandlingConfigUsesTheLanguagePackSection() {
        val annotations =
            analyze(
                "kotlin",
                """
                fun f() {
                    fail("boom")
                }
                """.trimIndent(),
                """
                error-handling-kotlin:
                  calls: [fail]
                  throws: false
                """.trimIndent(),
            )

        val errorHandling = annotations.filter { it.role == Role.SOURCE_INTENT && "ERROR_HANDLING" in it.intents }
        assertEquals(1, errorHandling.size)
        assertTrue(errorHandling.single().evidence.contains("fail"))
    }

    @Test
    fun validationPackConfigDrivesDetectionAndReturnCategorization() {
        val annotations =
            analyze(
                "kotlin",
                """
                fun ready(): Boolean = true
                fun f(): Int {
                    if (ready()) {
                        return 1
                    }
                    return 0
                }
                """.trimIndent(),
                """
                validation:
                  predicates: [ready]
                  predicatePrefixes: []
                """.trimIndent(),
            )

        val validation = annotations.filter { it.role == Role.SOURCE_INTENT && "VALIDATION" in it.intents }
        assertEquals(1, validation.size)
        assertTrue(validation.single().evidence.contains("ready()"))
        assertTrue(
            annotations.any {
                it.role == Role.SCAFFOLD && "VALIDATION" in it.intents && it.evidence.contains("return 1")
            },
        )
    }

    @Test
    fun configuredValidationMagicCommentDrivesRuleAndReturnCategorization() {
        val annotations =
            analyze(
                "kotlin",
                """
                fun f(): Int {
                    // cint:verify
                    if (true) {
                        return 1
                    }
                    return 0
                }
                """.trimIndent(),
                """
                validation:
                  magicComment: verify
                """.trimIndent(),
            )

        val validation = annotations.filter { it.role == Role.SOURCE_INTENT && "VALIDATION" in it.intents }
        assertEquals(1, validation.size)
        assertTrue(validation.single().evidence.contains("true"))
        assertTrue(
            annotations.any {
                it.role == Role.SCAFFOLD && "VALIDATION" in it.intents && it.evidence.contains("return 1")
            },
        )
    }

    private fun analyze(
        language: String,
        text: String,
        configText: String,
    ): List<Annotation> {
        val configFile = Files.createTempFile("cint-host-config", ".yaml")
        Files.writeString(configFile, configText)
        try {
            val settings = HostConfig.load(configFile.toString(), language)
            val pipeline =
                HostPipeline(
                    constructedPacks = BundledRulePackSelection::constructed,
                    project = settings.project,
                    knownPackIds = BundledRulePackSelection.packIds,
                )
            val frontend = Frontends.require(language)
            val source = SourceFile("Sample.kt", text)
            val tree = frontend.parse(source)
            return pipeline.analyze(
                source,
                tree,
                frontend.syntax(tree),
                NullOracle,
                settings.intentConfig(language),
            )
        } finally {
            Files.deleteIfExists(configFile)
        }
    }
}
