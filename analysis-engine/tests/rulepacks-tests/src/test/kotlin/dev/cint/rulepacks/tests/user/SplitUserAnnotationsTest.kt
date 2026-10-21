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
    fun splitAnalysisClipsRangesThatStartBeforeTheRegion() {
        val root = Files.createTempDirectory("cint-split-user-annotations-clip")
        try {
            val regionText = "fun make(): String = \"x\"\n"
            val startLine = 2
            val region = SplitRegion("kotlin", regionText, startByte = 0, startLine = startLine, startCol = 0)
            val source = SourceFile("Sample.vue", "<template></template>\n\n$regionText")
            val handler =
                object : SplitHandler {
                    override fun regions(file: SourceFile): List<SplitRegion> = listOf(region)
                }
            writeRecord(
                root,
                UserAnnotation(
                    path = "Sample.vue",
                    span = Span(1, 0, 3, 0),
                    intents = setOf("TEAM_CUSTOM_INTENT"),
                ),
            )
            val pipeline = HostPipeline(loader = RulePackLoader(onFailure = {}), root = root)

            val annotations = analyze(source, handler, root, pipeline)

            val user = userAnnotation(annotations)
            assertEquals(startLine, user.span.startLine)
            assertEquals(startLine, user.span.endLine)
            assertEquals(0, user.span.startCol)
            assertEquals(regionText.trimEnd('\n').length, user.span.endCol)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun splitAnalysisClipsHashedRangesThatStartBeforeTheRegion() {
        val root = Files.createTempDirectory("cint-split-user-annotations-hashed-clip")
        try {
            val prefix = "<template>π</template>\n\n"
            val regionText = "fun make(): String = \"π\"\n"
            val startLine = 2
            val region = SplitRegion("kotlin", regionText, startByte = 0, startLine = startLine, startCol = 0)
            val source = SourceFile("Sample.vue", prefix + regionText)
            val handler =
                object : SplitHandler {
                    override fun regions(file: SourceFile): List<SplitRegion> = listOf(region)
                }
            val endCol = regionText.trimEnd('\n').toByteArray(Charsets.UTF_8).size
            writeRecord(
                root,
                UserAnnotation(
                    path = "Sample.vue",
                    span = Span(0, 0, startLine, endCol),
                    intents = setOf("TEAM_CUSTOM_INTENT"),
                    contentHash = contentHash(prefix + regionText.trimEnd('\n')),
                ),
            )
            val pipeline = HostPipeline(loader = RulePackLoader(onFailure = {}), root = root)

            val annotations = analyze(source, handler, root, pipeline)

            val user = userAnnotation(annotations)
            assertEquals(startLine, user.span.startLine)
            assertEquals(startLine, user.span.endLine)
            assertEquals(0, user.span.startCol)
            assertEquals(endCol, user.span.endCol)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun splitAnalysisClipsHashedRangesThatEndOnTheEndTagLine() {
        val root = Files.createTempDirectory("cint-split-user-annotations-hashed-end-tag")
        try {
            val prefix = "<template></template>\n\n"
            val regionText = "fun make(): String = \"x\"\n"
            val endTag = "</script>"
            val startLine = 2
            val region = SplitRegion("kotlin", regionText, startByte = 0, startLine = startLine, startCol = 0)
            val source = SourceFile("Sample.vue", prefix + regionText + endTag + "\n")
            val handler =
                object : SplitHandler {
                    override fun regions(file: SourceFile): List<SplitRegion> = listOf(region)
                }
            val endCol = regionText.trimEnd('\n').toByteArray(Charsets.UTF_8).size
            writeRecord(
                root,
                UserAnnotation(
                    path = "Sample.vue",
                    span = Span(startLine, 0, startLine + 1, endTag.length),
                    intents = setOf("TEAM_CUSTOM_INTENT"),
                    contentHash = contentHash(regionText + endTag),
                ),
            )
            val pipeline = HostPipeline(loader = RulePackLoader(onFailure = {}), root = root)

            val annotations = analyze(source, handler, root, pipeline)

            val user = userAnnotation(annotations)
            assertEquals(startLine, user.span.startLine)
            assertEquals(startLine, user.span.endLine)
            assertEquals(0, user.span.startCol)
            assertEquals(endCol, user.span.endCol)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun splitAnalysisDropsAHashedRangeThatIsStaleOnTheHostSpan() {
        val root = Files.createTempDirectory("cint-split-user-annotations-stale-clip")
        try {
            val regionText = "fun make(): String = \"x\"\n"
            val startLine = 2
            val region = SplitRegion("kotlin", regionText, startByte = 0, startLine = startLine, startCol = 0)
            val source = SourceFile("Sample.vue", "<template></template>\n\n$regionText")
            val handler =
                object : SplitHandler {
                    override fun regions(file: SourceFile): List<SplitRegion> = listOf(region)
                }
            writeRecord(
                root,
                UserAnnotation(
                    path = "Sample.vue",
                    span = Span(0, 0, startLine, regionText.trimEnd('\n').length),
                    intents = setOf("TEAM_CUSTOM_INTENT"),
                    contentHash = contentHash(regionText.trimEnd('\n')),
                ),
            )
            val pipeline = HostPipeline(loader = RulePackLoader(onFailure = {}), root = root)

            val annotations = analyze(source, handler, root, pipeline)

            assertTrue(annotations.none { it.provenance.any { contribution -> contribution.rule == USER_CONTRIBUTOR_ID } })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun splitAnalysisDropsRangesOnTheTrailingEndTagLine() {
        val root = Files.createTempDirectory("cint-split-user-annotations-end-tag")
        try {
            val regionText = "fun make(): String = \"x\"\n"
            val startLine = 2
            val region = SplitRegion("kotlin", regionText, startByte = 0, startLine = startLine, startCol = 0)
            val source = SourceFile("Sample.vue", "<template></template>\n\n$regionText")
            val handler =
                object : SplitHandler {
                    override fun regions(file: SourceFile): List<SplitRegion> = listOf(region)
                }
            writeRecord(
                root,
                UserAnnotation(
                    path = "Sample.vue",
                    span = Span(startLine + 1, 0, startLine + 1, 5),
                    intents = setOf("TEAM_CUSTOM_INTENT"),
                ),
            )
            val pipeline = HostPipeline(loader = RulePackLoader(onFailure = {}), root = root)

            val annotations = analyze(source, handler, root, pipeline)

            assertTrue(annotations.none { it.provenance.any { contribution -> contribution.rule == USER_CONTRIBUTOR_ID } })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

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
            val config = IntentConfig("kotlin", emptyList())

            val annotations =
                SplitAnalyzer
                    .analyze(
                        source,
                        handler,
                        root,
                        OracleOptions(enabled = false, timeoutMs = 1_000L),
                        pipeline::plan,
                        pipeline::userContributionsFor,
                    ) { config }
                    .annotations

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

    private fun analyze(
        source: SourceFile,
        handler: SplitHandler,
        root: java.nio.file.Path,
        pipeline: HostPipeline,
    ): List<dev.cint.api.Annotation> =
        SplitAnalyzer
            .analyze(
                source,
                handler,
                root,
                OracleOptions(enabled = false, timeoutMs = 1_000L),
                pipeline::plan,
                pipeline::userContributionsFor,
            ) { IntentConfig("kotlin", emptyList()) }
            .annotations

    private fun userAnnotation(annotations: List<dev.cint.api.Annotation>): dev.cint.api.Annotation =
        annotations.first { annotation ->
            annotation.provenance.any { it.rule == USER_CONTRIBUTOR_ID }
        }

    private fun writeRecord(
        root: java.nio.file.Path,
        record: UserAnnotation,
    ) {
        val file = root.resolve(".cint/user-annotations.json")
        Files.createDirectories(file.parent)
        Files.writeString(file, UserAnnotationsJson.encode(listOf(record)))
    }
}
