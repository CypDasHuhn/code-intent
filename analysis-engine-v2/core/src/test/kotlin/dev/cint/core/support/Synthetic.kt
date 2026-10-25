package dev.cint.core.support

import dev.cint.contracts.data.CandidateReason
import dev.cint.contracts.data.Confidence
import dev.cint.contracts.data.Contribution
import dev.cint.contracts.data.Intent
import dev.cint.contracts.data.Provenance
import dev.cint.contracts.data.Role
import dev.cint.contracts.data.Span
import dev.cint.contracts.facts.FactBatch
import dev.cint.contracts.facts.SyntaxNode
import dev.cint.contracts.facts.SyntaxTree
import dev.cint.contracts.ports.RuleEvaluationView
import dev.cint.contracts.ports.RuleEvaluator
import dev.cint.contracts.ports.RuleEmission
import dev.cint.contracts.ports.RuleSpec
import dev.cint.core.rule.CandidateEmit
import dev.cint.core.rule.Emit

internal class TestRule(
    id: String,
    owns: Intent,
    reads: Set<Intent> = emptySet(),
    optionalReads: Set<Intent> = emptySet(),
    private val body: (RuleEvaluationView) -> List<RuleEmission>,
) : RuleEvaluator {
    override val spec: RuleSpec = RuleSpec(id, owns, reads.toList(), optionalReads.toList())

    var runs: Int = 0
        private set

    override fun evaluate(view: RuleEvaluationView): List<RuleEmission> {
        runs++
        return body(view)
    }
}

internal fun node(
    id: String,
    kind: String,
    startByte: Int,
    endByte: Int,
    text: String = "x".repeat(endByte - startByte),
    parentId: String? = null,
    childIds: List<String> = emptyList(),
): SyntaxNode =
    SyntaxNode(
        id = id,
        kind = kind,
        startByte = startByte,
        endByte = endByte,
        startLine = 0,
        startCol = startByte,
        endLine = 0,
        endCol = endByte,
        text = text,
        parentId = parentId,
        childIds = childIds,
    )

internal fun tree(
    rootId: String,
    nodes: List<SyntaxNode>,
): SyntaxTree =
    SyntaxTree(
        rootId = rootId,
        nodes = nodes.associateBy { it.id },
        language = "synthetic",
        version = "1",
        contentHash = "synthetic-hash",
    )

internal fun facts(
    rootId: String,
    nodes: List<SyntaxNode>,
    builder: FactBatch.() -> FactBatch = { this },
): FactBatch =
    FactBatch(syntaxTree = tree(rootId, nodes)).builder()

internal fun emit(
    intent: Intent,
    node: SyntaxNode,
    source: String = "rule",
    confidence: Double = 1.0,
    certifiesReturn: Boolean = false,
): Emit =
    Emit(
        intents = setOf(intent),
        span = node.span,
        startByte = node.startByte,
        endByte = node.endByte,
        source = source,
        confidence = Confidence.fromDouble(confidence),
        certifiesReturn = certifiesReturn,
    )

internal fun emitAt(
    intent: Intent,
    byte: Int,
    source: String = "rule",
): Emit =
    Emit(
        intents = setOf(intent),
        span = Span(0, byte, 0, byte),
        startByte = byte,
        endByte = byte,
        source = source,
        confidence = Confidence.MAX,
    )

internal fun emitRange(
    intent: Intent,
    index: Int,
): Emit =
    Emit(
        intents = setOf(intent),
        span = Span(index, 0, index, 1),
        startByte = index * 10,
        endByte = index * 10 + 1,
        source = "rule",
        confidence = Confidence.MAX,
    )

internal fun candidate(
    intent: Intent,
    node: SyntaxNode,
    reason: CandidateReason = CandidateReason.GAP,
    source: String = "candidate",
    confidence: Double = 0.3,
): CandidateEmit =
    CandidateEmit(
        intents = setOf(intent),
        span = node.span,
        startByte = node.startByte,
        endByte = node.endByte,
        source = source,
        confidence = Confidence.fromDouble(confidence),
        reason = reason,
    )

internal fun contribution(
    rule: String,
    intent: Intent,
    node: SyntaxNode,
    role: Role = Role.SOURCE_INTENT,
    source: String = "rule",
    confidence: Double = 1.0,
    certifiesReturn: Boolean = false,
): Contribution =
    Contribution(
        rule = rule,
        role = role,
        intents = setOf(intent),
        span = node.span,
        source = source,
        confidence = Confidence.fromDouble(confidence),
        startByte = node.startByte,
        endByte = node.endByte,
        provenance = Provenance.RULE,
        certifiesReturn = certifiesReturn,
    )
