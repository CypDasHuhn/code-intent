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
import dev.cint.frontend.Frontends
import java.nio.file.Files
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
    val frontend = Frontends.require(language)
    val tree = frontend.parse(source)
    return PackInput(source, tree, frontend.syntax(tree), config)
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

internal fun fixturePath(relative: String): Path {
    val candidates =
        listOf(
            Path.of("..", "..", "testing-grounds", relative),
            Path.of("..", "testing-grounds", relative),
            Path.of("testing-grounds", relative),
        )
    return candidates.firstOrNull { Files.exists(it) } ?: error("missing fixture $relative")
}

internal fun fixture(relative: String): String = Files.readString(fixturePath(relative))

internal fun defaultFileName(language: String): String = "Sample${Frontends.require(language).extensions.first()}"
