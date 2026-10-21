package dev.cint.integration.composition.support

import dev.cint.api.Annotation
import dev.cint.api.CINT_API_VERSION
import dev.cint.api.Emit
import dev.cint.api.Intent
import dev.cint.api.Role
import dev.cint.api.Rule
import dev.cint.api.RuleEmit
import dev.cint.api.RulePack
import dev.cint.api.Span
import dev.cint.api.SyntaxNode
import dev.cint.api.UNTAGGED_INTENT
import dev.cint.api.View
import dev.cint.core.config.IntentConfig
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.NullOracle
import dev.cint.core.runtime.scheduler.RuleEngine
import dev.cint.core.test.testConfig
import dev.cint.test.fixtures.testPacks
import dev.cint.test.fixtures.text
import dev.cint.treesitter.KotlinParser
import dev.cint.treesitter.KotlinSyntaxModel
import dev.cint.integration.fixture as sharedFixture

internal class ReplayRule(
    override val id: String,
    override val owns: Intent,
    override val reads: Set<Intent> = emptySet(),
    private val emissions: List<RuleEmit>,
) : Rule {
    override fun run(view: View): List<RuleEmit> = emissions
}

internal class ReplayPack(
    override val id: String,
    private val entries: List<Rule>,
) : RulePack {
    override val languages: Set<String> = setOf("kotlin")
    override val apiVersion: Int = CINT_API_VERSION

    override fun rules(): List<Rule> = entries
}

internal data class Snapshot(
    val role: Role,
    val intents: Set<String>,
    val span: Span,
    val source: String,
    val confidence: Double,
    val startByte: Int,
    val endByte: Int,
)

internal fun snapshot(annotation: Annotation): Snapshot =
    Snapshot(
        role = annotation.role,
        intents = annotation.intents,
        span = annotation.span,
        source = annotation.source,
        confidence = annotation.confidence,
        startByte = annotation.startByte,
        endByte = annotation.endByte,
    )

internal fun compositionSnapshots(annotations: List<Annotation>): List<Snapshot> =
    annotations
        .filter { annotation -> annotation.role == Role.SCAFFOLD || annotation.role == Role.DERIVATIVE_USE }
        .map(::snapshot)
        .sortedWith(compareBy({ it.startByte }, { it.endByte }, { it.role.ordinal }, { it.intents.joinToString() }))

internal fun analyzePipeline(
    text: String,
    packs: List<RulePack>,
    path: String = "Sample.kt",
    config: IntentConfig = testConfig("kotlin"),
): List<Annotation> {
    val source = SourceFile(path, text)
    val tree = KotlinParser().parse(source)
    return RuleEngine().analyze(source, tree, KotlinSyntaxModel, NullOracle, config, packs).annotations
}

internal fun emitFor(
    intent: Intent,
    node: SyntaxNode,
    certifiesReturn: Boolean = false,
): Emit =
    Emit(
        intents = setOf(intent),
        span = node.span(),
        source = "treesitter",
        confidence = 1.0,
        startByte = node.startByte,
        endByte = node.endByte,
        certifiesReturn = certifiesReturn,
    )

internal fun replaySource(
    text: String,
    idPrefix: String = "source",
): List<Rule> {
    val source = SourceFile("Sample.kt", text)
    val tree = KotlinParser().parse(source)
    val annotations =
        RuleEngine().analyze(source, tree, KotlinSyntaxModel, NullOracle, testConfig("kotlin"), testPacks()).annotations
    val emissions = linkedMapOf<String, MutableList<RuleEmit>>()
    for ((role, intents, span, source1, confidence, startByte, endByte) in annotations) {
        if (role != Role.SOURCE_INTENT) continue
        for (intent in intents) {
            if (intent == UNTAGGED_INTENT) continue
            emissions.getOrPut(intent) { mutableListOf() } +=
                Emit(
                    intents = setOf(intent),
                    span = span,
                    source = source1,
                    confidence = confidence,
                    startByte = startByte,
                    endByte = endByte,
                )
        }
    }
    return emissions
        .toSortedMap()
        .map { (intent, values) -> ReplayRule("$idPrefix:$intent", intent, emissions = values) }
}

internal fun fixture(
    name: String,
    language: String = "kotlin",
): String = sharedFixture("$language/$name")
