package dev.cint.integration.host

import dev.cint.api.Annotation
import dev.cint.api.Role
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.NullOracle
import dev.cint.frontend.Frontends
import dev.cint.host.HostConfig
import dev.cint.host.HostRuntime
import dev.cint.rulepacks.BundledRulePackConfigResolver
import dev.cint.test.fixtures.text
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HostConfigWiringTest {
    @Test
    fun loggingPackConfigDrivesHostDetection() {
        val text =
            """
            fun f() {
                audit("logged")
                println("not configured")
            }
            """.trimIndent()
        val annotations =
            analyze(
                "kotlin",
                text,
                """
                logging:
                  kotlin:
                    printCalls: [audit]
                """.trimIndent(),
            )

        val logging = annotations.filter { it.role == Role.SOURCE_INTENT && "LOGGING" in it.intents }
        assertEquals(1, logging.size)
        assertTrue(logging.single().text(text).contains("audit"))
    }

    @Test
    fun errorHandlingConfigUsesTheLanguagePackSection() {
        val text =
            """
            fun f() {
                fail("boom")
            }
            """.trimIndent()
        val annotations =
            analyze(
                "kotlin",
                text,
                """
                error-handling-kotlin:
                  calls: [fail]
                  throws: false
                """.trimIndent(),
            )

        val errorHandling = annotations.filter { it.role == Role.SOURCE_INTENT && "ERROR_HANDLING" in it.intents }
        assertEquals(1, errorHandling.size)
        assertTrue(errorHandling.single().text(text).contains("fail"))
    }

    @Test
    fun invalidLegacyLanguageOverrideDoesNotDisableOtherErrorHandlingLanguages() {
        val config =
            """
            error-handling-kotlin:
              calls: [fail]
            error-handling-javascript:
              throws: not-a-boolean
            """.trimIndent()

        val kotlinText =
            """
            fun f() {
                fail("boom")
            }
            """.trimIndent()
        val kotlin =
            analyze(
                "kotlin",
                kotlinText,
                config,
            )
        val javascriptText =
            """
            function f(id) {
                assert(id, "id required");
            }
            """.trimIndent()
        val javascript =
            analyze(
                "javascript",
                javascriptText,
                config,
            )

        assertTrue(
            kotlin.any {
                it.role == Role.SOURCE_INTENT && "ERROR_HANDLING" in it.intents && it.text(kotlinText).contains("fail")
            },
        )
        assertTrue(
            javascript.any {
                it.role == Role.SOURCE_INTENT &&
                    "ERROR_HANDLING" in it.intents &&
                    it.text(javascriptText).contains("assert")
            },
        )
    }

    @Test
    fun validationPackConfigDrivesDetectionAndReturnCategorization() {
        val text =
            """
            fun ready(): Boolean = true
            fun f(): Int {
                if (ready()) {
                    return 1
                }
                return 0
            }
            """.trimIndent()
        val annotations =
            analyze(
                "kotlin",
                text,
                """
                validation:
                  predicates: [ready]
                  predicatePrefixes: []
                """.trimIndent(),
            )

        val validation = annotations.filter { it.role == Role.SOURCE_INTENT && "VALIDATION" in it.intents }
        assertEquals(1, validation.size)
        assertTrue(validation.single().text(text).contains("ready()"))
        assertTrue(
            annotations.any {
                it.role == Role.SCAFFOLD && "VALIDATION" in it.intents && it.text(text).contains("return 1")
            },
        )
    }

    @Test
    fun configuredValidationMagicCommentDrivesRuleAndReturnCategorization() {
        val text =
            """
            fun f(): Int {
                // cint:verify
                if (true) {
                    return 1
                }
                return 0
            }
            """.trimIndent()
        val annotations =
            analyze(
                "kotlin",
                text,
                """
                validation:
                  magicComment: verify
                """.trimIndent(),
            )

        val validation = annotations.filter { it.role == Role.SOURCE_INTENT && "VALIDATION" in it.intents }
        assertEquals(1, validation.size)
        assertTrue(validation.single().text(text).contains("true"))
        assertTrue(
            annotations.any {
                it.role == Role.SCAFFOLD && "VALIDATION" in it.intents && it.text(text).contains("return 1")
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
            val settings = HostConfig.load(configFile.toString())
            val pipeline = HostRuntime.pipeline(settings, BundledRulePackConfigResolver)
            val frontend = Frontends.require(language)
            val source = SourceFile("Sample.kt", text)
            val tree = frontend.parse(source)
            return pipeline
                .analyze(
                    source,
                    tree,
                    frontend.syntax(tree),
                    NullOracle,
                    settings.intentConfig(language),
                ).annotations
        } finally {
            Files.deleteIfExists(configFile)
        }
    }
}
