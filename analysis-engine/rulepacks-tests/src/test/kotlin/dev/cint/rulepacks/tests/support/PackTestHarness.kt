package dev.cint.rulepacks.tests.support

import dev.cint.api.Annotation
import dev.cint.api.Contribution
import dev.cint.api.Intent
import dev.cint.api.Role
import dev.cint.api.RulePack
import dev.cint.api.Span
import dev.cint.core.config.IntentConfig
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.NullOracle
import dev.cint.core.facts.oracle.TypeOracle
import dev.cint.core.runtime.scheduler.RuleEngine
import dev.cint.core.syntax.SyntaxModel
import dev.cint.core.syntax.SyntaxTree
import dev.cint.core.test.testConfig
import dev.cint.test.fixtures.TestFixtures
import dev.cint.test.fixtures.defaultFileName
import dev.cint.test.fixtures.parseFile
import dev.cint.test.fixtures.syntaxModel
import org.junit.Assume.assumeTrue
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal data class PackInput(
    val source: SourceFile,
    val tree: SyntaxTree,
    val syntax: SyntaxModel,
    val config: IntentConfig,
)

internal data class PackAnalysis(
    val input: PackInput,
    val annotations: List<Annotation>,
) {
    fun withIntent(intent: Intent): List<Annotation> = annotations.filter { intent in it.intents }

    fun source(intent: Intent): List<Annotation> = annotations.filter { it.role == Role.SOURCE_INTENT && intent in it.intents }

    fun contributions(intent: Intent): List<Contribution> =
        annotations
            .filter { it.role == Role.SOURCE_INTENT }
            .flatMap { it.provenance }
            .filter { intent in it.intents }

    fun evidence(intent: Intent): List<String> = source(intent).map { it.evidence }

    fun spans(intent: Intent): List<Span> = source(intent).map { it.span }

    fun assertSourceCount(
        intent: Intent,
        count: Int,
    ): PackAnalysis {
        assertEquals(count, source(intent).size, "$intent source annotations")
        return this
    }

    fun assertNoSource(intent: Intent): PackAnalysis {
        assertTrue(source(intent).isEmpty(), "unexpected $intent source annotations: ${evidence(intent)}")
        return this
    }

    fun assertSourceEvidence(
        intent: Intent,
        expected: String,
    ): Annotation {
        val annotation = source(intent).firstOrNull { it.evidence == expected }
        assertTrue(annotation != null, "missing $intent source annotation '$expected' in ${evidence(intent)}")
        return annotation
    }

    fun assertSourceSpan(
        intent: Intent,
        startByte: Int,
        endByte: Int,
    ): Annotation {
        val annotation = source(intent).firstOrNull { it.startByte == startByte && it.endByte == endByte }
        assertTrue(
            annotation != null,
            "missing $intent source span $startByte:$endByte in ${spans(intent)}",
        )
        return annotation
    }

    fun assertSourceSpans(
        intent: Intent,
        expected: Set<Span>,
    ): PackAnalysis {
        assertEquals(expected, spans(intent).toSet(), "$intent source spans")
        return this
    }
}

internal fun parsePackInput(
    language: String,
    text: String,
    name: String = defaultFileName(language),
    config: IntentConfig = testConfig(language),
): PackInput {
    val source = SourceFile(name, text)
    val tree = parseFile(language, source)
    return PackInput(source, tree, syntaxModel(language, tree), config)
}

internal fun analyzePack(
    input: PackInput,
    packs: List<RulePack>,
    oracle: TypeOracle = NullOracle,
): PackAnalysis =
    PackAnalysis(
        input,
        RuleEngine().analyze(
            input.source,
            input.tree,
            input.syntax,
            oracle,
            input.config,
            packs,
        ),
    )

internal fun analyzePack(
    language: String,
    text: String,
    packs: List<RulePack>,
    oracle: TypeOracle = NullOracle,
    name: String = defaultFileName(language),
): PackAnalysis = analyzePack(parsePackInput(language, text, name), packs, oracle)

internal fun assumeFixtureRoot(): Path {
    val root = TestFixtures.rootOrNull()
    assumeTrue(
        "testing-grounds not found; set -Dcint.testingGrounds=<path> or CINT_TESTING_GROUNDS=<path>",
        root != null,
    )
    return requireNotNull(root)
}

internal fun fixture(relative: String): String = TestFixtures.read(assumeFixtureRoot(), relative)
