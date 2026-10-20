package dev.cint.integration.host

import dev.cint.api.Annotation
import dev.cint.core.artifact.AnnotationJson
import dev.cint.core.artifact.Json
import dev.cint.core.facts.frontend.OracleOptions
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.NullOracle
import dev.cint.frontend.Frontends
import dev.cint.frontend.SplitAnalyzer
import dev.cint.frontend.VueFrontend
import dev.cint.host.HostConfig
import dev.cint.host.HostRuntime
import dev.cint.integration.assumeFixtureRoot
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val CURRENT_COMMIT = "f22b596"
private const val CURRENT_NOTE = "post-018 current pipeline; validation intentionally widened from the pre-018 014-tree baseline"

private val PRE_018_SNAPSHOT_DIGESTS =
    mapOf(
        "/snapshots/legacy-3f20435.json" to
            "3ddfc74813202046074f1ee0d270e48060917f97cd6fb7b250c06df8a9442c50",
        "/snapshots/pipeline-3f20435.json" to
            "89ea2be21e09b40b417e6c4d014761add594fc11668e48742c072292e8de42b7",
    )

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

private fun snapshotRoot(): Path = assumeFixtureRoot()

private fun intentsJson(intents: Set<String>): String = intents.sorted().joinToString(",", "[", "]") { "\"${Json.escape(it)}\"" }

private fun annotationJson(
    annotation: Annotation,
    includeRule: Boolean,
): String =
    buildString {
        append("{\"role\":\"${AnnotationJson.roleName(annotation.role)}\",")
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
    commit: String,
    note: String? = null,
    build: (SnapshotFixture) -> Pair<List<String>, List<Annotation>>,
): String {
    val root = snapshotRoot()
    return buildString {
        append("{\n")
        append("  \"provenance\": {")
        append("\"commit\":\"$commit\",")
        append("\"kind\":\"$kind\",")
        if (note != null) append("\"note\":\"${Json.escape(note)}\",")
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

class PipelineSnapshotTest {
    private fun currentFixture(
        fixture: SnapshotFixture,
        root: Path,
    ): Pair<List<String>, List<Annotation>> {
        val source = SourceFile(fixture.path, root.resolve(fixture.path).readText())
        val settings = HostConfig.load(null)
        val pipeline = HostRuntime.pipeline(settings)
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

    private fun resourceBytes(path: String): ByteArray =
        PipelineSnapshotTest::class.java
            .getResourceAsStream(path)
            ?.use { it.readBytes() }
            ?: error("missing snapshot resource $path")

    private fun resource(path: String): String = resourceBytes(path).toString(Charsets.UTF_8)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    @Test
    fun currentPipelineMatchesFrozenSnapshot() {
        val root = snapshotRoot()
        val expected = resource("/snapshots/pipeline-current.json")
        val actual =
            renderSnapshot("pipeline", includeRule = true, commit = CURRENT_COMMIT, note = CURRENT_NOTE) { fixture ->
                currentFixture(fixture, root)
            }

        assertTrue(expected.contains("\"commit\":\"$CURRENT_COMMIT\""))
        assertEquals(expected, actual)
    }

    @Test
    fun pre018SnapshotsMatchTheirFrozenContent() {
        for ((path, expected) in PRE_018_SNAPSHOT_DIGESTS) {
            assertEquals(expected, sha256(resourceBytes(path)), path)
        }
    }
}
