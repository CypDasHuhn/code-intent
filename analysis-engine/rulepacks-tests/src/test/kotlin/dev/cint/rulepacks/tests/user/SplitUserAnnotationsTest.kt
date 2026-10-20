package dev.cint.rulepacks.tests.user

import dev.cint.api.Role
import dev.cint.api.Span
import dev.cint.core.config.IntentConfig
import dev.cint.core.facts.frontend.OracleOptions
import dev.cint.core.facts.frontend.SplitHandler
import dev.cint.core.facts.frontend.SplitRegion
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.runtime.rulepack.RulePackLoader
import dev.cint.core.support.hashing.contentHash
import dev.cint.core.user.USER_CONTRIBUTOR_ID
import dev.cint.core.user.USER_SOURCE
import dev.cint.core.user.UserAnnotation
import dev.cint.core.user.UserAnnotationsJson
import dev.cint.frontend.SplitAnalyzer
import dev.cint.host.HostPipeline
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SplitUserAnnotationsTest {
    @Test
    fun splitAnalysisThreadsUserAnnotationsIntoTheRegionEngine() {
        val root = Files.createTempDirectory("cint-split-user-annotations")
        try {
            val regionText = "fun make(): String = \"x\"\n"
            val startLine = 2
            val region = SplitRegion("kotlin", regionText, startByte = 0, startLine = startLine, startCol = 0)
            val source = SourceFile("Sample.vue", "<template></template>\n\n$regionText")
            val handler =
                object : SplitHandler {
                    override fun regions(file: SourceFile): List<SplitRegion> = listOf(region)
                }
            val file = root.resolve(".cint/user-annotations.json")
            Files.createDirectories(file.parent)
            Files.writeString(
                file,
                UserAnnotationsJson.encode(
                    listOf(
                        UserAnnotation(
                            path = "Sample.vue",
                            span = Span(startLine, 4, startLine, 8),
                            intents = setOf("TEAM_CUSTOM_INTENT"),
                            symbol = "make",
                            contentHash = contentHash("make"),
                        ),
                    ),
                ),
            )
            val pipeline = HostPipeline(loader = RulePackLoader(onFailure = {}), root = root)
            val config = IntentConfig("kotlin", emptyMap(), emptyList())

            val annotations =
                SplitAnalyzer.analyze(
                    source,
                    handler,
                    root,
                    OracleOptions(enabled = false, timeoutMs = 1_000L),
                    pipeline::plan,
                    pipeline::userContributionsFor,
                ) { config }

            val user =
                annotations.first { annotation ->
                    annotation.provenance.any { it.rule == USER_CONTRIBUTOR_ID }
                }
            assertEquals(Role.SOURCE_INTENT, user.role)
            assertEquals(startLine, user.span.startLine)
            assertEquals(setOf("TEAM_CUSTOM_INTENT"), user.intents)
            assertTrue(user.provenance.all { it.source == USER_SOURCE })
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
