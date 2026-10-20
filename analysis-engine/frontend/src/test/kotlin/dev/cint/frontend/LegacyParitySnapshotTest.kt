package dev.cint.frontend

import dev.cint.api.Annotation
import dev.cint.api.Role
import dev.cint.core.artifact.Json
import dev.cint.core.facts.frontend.OracleOptions
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.NullOracle
import dev.cint.host.HostConfig
import dev.cint.host.HostPipeline
import dev.cint.rulepacks.support.BundledRulePackSelection
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val SNAPSHOT_COMMIT = "3f20435"

private data class SnapshotFixture(
    val path: String,
    val language: String,
)

private val snapshotFixtures =
    listOf(
        SnapshotFixture("csharp/Sample.cs", "csharp"),
        SnapshotFixture("javascript/Sample.js", "javascript"),
        SnapshotFixture("kotlin/Bookkeeping.kt", "kotlin"),
        SnapshotFixture("kotlin/Catches.kt", "kotlin"),
        SnapshotFixture("kotlin/ErrorHandling.kt", "kotlin"),
        SnapshotFixture("kotlin/Heuristics.kt", "kotlin"),
        SnapshotFixture("kotlin/LoggerInteractions.kt", "kotlin"),
        SnapshotFixture("kotlin/PurityAssist.kt", "kotlin"),
        SnapshotFixture("kotlin/Scaffolds.kt", "kotlin"),
        SnapshotFixture("typescript/Sample.ts", "typescript"),
        SnapshotFixture("vue/Sample.vue", "vue"),
    )

private fun snapshotRoot(): Path =
    generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isDirectory(it.resolve("testing-grounds")) }
        .resolve("testing-grounds")

private fun roleName(role: Role): String =
    when (role) {
        Role.SOURCE_INTENT -> "source-intent"
        Role.DERIVATIVE_USE -> "derivative-use"
        Role.SCAFFOLD -> "scaffold"
    }

private fun intentsJson(intents: Set<String>): String = intents.sorted().joinToString(",", "[", "]") { "\"${Json.escape(it)}\"" }

private fun annotationJson(
    annotation: Annotation,
    includeRule: Boolean,
): String =
    buildString {
        append("{\"role\":\"${roleName(annotation.role)}\",")
        append("\"intents\":${intentsJson(annotation.intents)},")
        if (includeRule) append("\"rule\":\"${Json.escape(annotation.rule)}\",")
        append("\"evidence\":\"${Json.escape(annotation.evidence)}\",")
        append("\"span\":{\"startLine\":${annotation.span.startLine},")
        append("\"startCol\":${annotation.span.startCol},")
        append("\"endLine\":${annotation.span.endLine},")
        append("\"endCol\":${annotation.span.endCol}},")
        append("\"source\":\"${Json.escape(annotation.source)}\",")
        append("\"confidence\":${annotation.confidence},")
        append("\"startByte\":${annotation.startByte},")
        append("\"endByte\":${annotation.endByte}}")
    }

private fun annotationOrder(): Comparator<Annotation> =
    compareBy<Annotation> { it.startByte }
        .thenBy { it.endByte }
        .thenBy { it.role.ordinal }
        .thenBy { it.intents.sorted().joinToString("\u0000") }
        .thenBy { it.evidence }
        .thenBy { it.source }
        .thenBy { it.confidence }

private fun renderSnapshot(
    kind: String,
    includeRule: Boolean,
    build: (SnapshotFixture) -> Pair<List<String>, List<Annotation>>,
): String {
    val root = snapshotRoot()
    return buildString {
        append("{\n")
        append("  \"provenance\": {")
        append("\"commit\":\"$SNAPSHOT_COMMIT\",")
        append("\"kind\":\"$kind\",")
        append("\"fields\":[\"role\",\"intents\",\"evidence\",\"span\",\"source\",\"confidence\",\"startByte\",\"endByte\"]")
        if (includeRule) append(",\"rule\":true")
        append(",\"fixtures\":")
        append(snapshotFixtures.joinToString(",", "[", "]") { "\"${Json.escape(it.path)}\"" })
        append(",\"returnRoleNormalization\":\"legacy return-* source annotations compared as scaffold\"")
        append(",\"vue\":\"Vue script regions analyzed through SplitAnalyzer with region language config\"")
        append("},\n")
        append("  \"files\": [\n")
        snapshotFixtures.forEachIndexed { index, fixture ->
            val (languages, annotations) = build(fixture)
            append("    {\"path\":\"${Json.escape(fixture.path)}\",")
            append("\"languages\":${languages.joinToString(",", "[", "]") { "\"${Json.escape(it)}\"" }},")
            append("\"annotations\":[")
            append(
                annotations.sortedWith(annotationOrder()).joinToString(",") {
                    annotationJson(it, includeRule)
                },
            )
            append("]}")
            if (index < snapshotFixtures.size - 1) append(",")
            append("\n")
        }
        append("  ]\n}\n")
    }
}

class LegacyParitySnapshotTest {
    private fun currentFixture(
        fixture: SnapshotFixture,
        root: Path,
    ): Pair<List<String>, List<Annotation>> {
        val source = SourceFile(fixture.path, root.resolve(fixture.path).readText())
        val settings = HostConfig.load(null, fixture.language)
        val pipeline =
            HostPipeline(
                constructedPacks = BundledRulePackSelection::constructed,
                project = settings.project,
                knownPackIds = BundledRulePackSelection.packIds,
            )
        val options = OracleOptions(enabled = false, timeoutMs = 1_000)
        return if (fixture.language == "vue") {
            val annotations =
                SplitAnalyzer.analyze(source, VueFrontend, root, options, pipeline::plan) { language ->
                    settings.intentConfig(language)
                }
            VueFrontend.regions(source).map { it.language } to annotations
        } else {
            val frontend = Frontends.require(fixture.language)
            val tree = frontend.parse(source)
            listOf(fixture.language) to
                pipeline.analyze(
                    source,
                    tree,
                    frontend.syntax(tree),
                    NullOracle,
                    settings.intentConfig(fixture.language),
                )
        }
    }

    private fun resource(path: String): String =
        LegacyParitySnapshotTest::class.java
            .getResourceAsStream(path)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("missing snapshot resource $path")

    @Test
    fun legacyPre014SnapshotFromCommit3f20435MatchesCurrentHostPipeline() {
        val root = snapshotRoot()
        val expected = resource("/snapshots/legacy-pre014.json")
        val actual = renderSnapshot("legacy", includeRule = false) { fixture -> currentFixture(fixture, root) }

        assertTrue(expected.contains("\"commit\":\"3f20435\""))
        assertEquals(expected, actual)
    }

    @Test
    fun pipelinePre014SnapshotFromCommit3f20435KeepsRuleIdentity() {
        val root = snapshotRoot()
        val expected = resource("/snapshots/pipeline-pre014.json")
        val actual = renderSnapshot("pipeline", includeRule = true) { fixture -> currentFixture(fixture, root) }

        assertTrue(expected.contains("\"commit\":\"3f20435\""))
        assertEquals(expected, actual)
    }
}
