package dev.cint.frontend

import dev.cint.api.Annotation
import dev.cint.api.Role
import dev.cint.core.config.loadIntentConfig
import dev.cint.core.engine.AnalysisEngine
import dev.cint.core.frontend.OracleOptions
import dev.cint.core.host.HostConfig
import dev.cint.core.host.HostPipeline
import dev.cint.core.model.SourceFile
import dev.cint.core.oracle.NullOracle
import dev.cint.core.rules.IntentBlocks
import dev.cint.rulepacks.BundledRulePackSelection
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
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

    private data class FixtureCase(
        val path: String,
        val language: String,
    )

    private val fixtureCases =
        listOf(
            FixtureCase("csharp/Sample.cs", "csharp"),
            FixtureCase("javascript/Sample.js", "javascript"),
            FixtureCase("kotlin/Bookkeeping.kt", "kotlin"),
            FixtureCase("kotlin/Catches.kt", "kotlin"),
            FixtureCase("kotlin/ErrorHandling.kt", "kotlin"),
            FixtureCase("kotlin/Heuristics.kt", "kotlin"),
            FixtureCase("kotlin/LoggerInteractions.kt", "kotlin"),
            FixtureCase("kotlin/PurityAssist.kt", "kotlin"),
            FixtureCase("kotlin/Scaffolds.kt", "kotlin"),
            FixtureCase("typescript/Sample.ts", "typescript"),
            FixtureCase("vue/Sample.vue", "vue"),
        )

    @Test
    fun hostDefaultsMatchTheLegacyEngineOverTheFixtures() {
        val root = fixtureRoot()
        val actualFiles =
            Files.walk(root).use { paths ->
                paths
                    .filter { Files.isRegularFile(it) }
                    .map { root.relativize(it).toString() }
                    .sorted()
                    .toList()
            }
        assertEquals(fixtureCases.map { it.path }.sorted(), actualFiles)
        assertEquals(11, fixtureCases.size)

        for (case in fixtureCases) {
            val text = root.resolve(case.path).readText()
            val source = SourceFile(case.path, text)
            val settings = HostConfig.load(null, case.language)
            val pipeline =
                HostPipeline(
                    constructedPacks = BundledRulePackSelection::constructed,
                    project = settings.project,
                    knownPackIds = BundledRulePackSelection.packIds,
                )
            val params = BundledRulePackSelection.validationIntentParams(settings.project.packs)
            val expected: List<Annotation>
            val actual: List<Annotation>
            if (case.language == "vue") {
                val options = OracleOptions(enabled = false, timeoutMs = 1_000)
                expected = SplitAnalyzer.analyze(source, VueFrontend, root, null, options)
                actual =
                    SplitAnalyzer.analyze(source, VueFrontend, root, options, pipeline::plan) { language ->
                        settings.intentConfig(language, params)
                    }
            } else {
                val config = settings.intentConfig(case.language, params)
                val frontend = Frontends.require(case.language)
                val tree = frontend.parse(source)
                val syntax = frontend.syntax(tree)
                expected =
                    AnalysisEngine(IntentBlocks.build(loadIntentConfig(null, case.language))).analyze(
                        source,
                        tree,
                        syntax,
                        NullOracle,
                        loadIntentConfig(null, case.language),
                    )
                actual = pipeline.analyze(source, tree, syntax, NullOracle, config)
            }
            assertEquals(shape(expected), shape(actual), case.path)
            assertEquals(sourceShape(expected), sourceShape(actual), case.path)
        }
    }

    private fun shape(
        annotations: List<Annotation>,
        includeRole: Boolean = false,
    ): List<List<Any>> =
        annotations.map { annotation ->
            buildList {
                if (includeRole) add(annotation.role)
                add(annotation.intents)
                add(annotation.evidence)
                add(annotation.span)
                add(annotation.source)
                add(annotation.confidence)
                add(annotation.startByte)
                add(annotation.endByte)
            }
        }.sortedBy { it.toString() }

    private fun sourceShape(annotations: List<Annotation>): List<List<Any>> =
        shape(
            annotations.filter { it.role == Role.SOURCE_INTENT && it.source != "cfg" },
            includeRole = true,
        )

    private fun fixtureRoot(): Path =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isDirectory(it.resolve("testing-grounds")) }
            .resolve("testing-grounds")

    private fun analyze(
        language: String,
        text: String,
        configText: String,
    ): List<Annotation> {
        val configFile = Files.createTempFile("cint-host-config", ".yaml")
        Files.writeString(configFile, configText)
        try {
            val settings = HostConfig.load(configFile.toString(), language)
            val validationParams = BundledRulePackSelection.validationIntentParams(settings.project.packs)
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
                settings.intentConfig(language, validationParams),
            )
        } finally {
            Files.deleteIfExists(configFile)
        }
    }
}
