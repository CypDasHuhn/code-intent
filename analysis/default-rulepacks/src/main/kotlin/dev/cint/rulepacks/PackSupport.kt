package dev.cint.rulepacks

import dev.cint.contracts.data.CandidateReason
import dev.cint.contracts.data.Confidence
import dev.cint.contracts.data.Span
import dev.cint.contracts.facts.SyntaxNode
import dev.cint.contracts.ports.CandidateEmission
import dev.cint.contracts.ports.EstablishedEmission
import dev.cint.contracts.ports.RuleEvaluationView
import dev.cint.contracts.ports.RuleEvaluator
import dev.cint.contracts.ports.RuleSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

internal const val SYNTAX_SOURCE = "treesitter"
internal const val HEURISTIC_SOURCE = "heuristic"
internal const val ANNOTATION_SOURCE = "annotation"
internal const val COMMENT_SOURCE = "comment"
internal const val CANDIDATE_CONFIDENCE = 0.4

internal val ruleJson = Json { ignoreUnknownKeys = true }

internal inline fun <reified T> JsonObject.decodeRule(): T = ruleJson.decodeFromJsonElement(this)

internal abstract class PackEvaluator(
    override val spec: RuleSpec,
) : RuleEvaluator {
    protected fun RuleEvaluationView.emitNode(
        node: SyntaxNode?,
        confidence: Double,
        source: String,
    ): EstablishedEmission? = node?.let { emitSpan(it.span, it.startByte, it.endByte, confidence, source) }

    protected fun RuleEvaluationView.emitRange(
        start: SyntaxNode,
        end: SyntaxNode,
        confidence: Double,
        source: String,
        certifiesReturn: Boolean = false,
    ): EstablishedEmission =
        emitSpan(
            Span(start.startLine, start.startCol, end.endLine, end.endCol),
            start.startByte,
            end.endByte,
            confidence,
            source,
            certifiesReturn,
        )

    protected fun RuleEvaluationView.emitSpan(
        span: Span,
        startByte: Int,
        endByte: Int,
        confidence: Double,
        source: String,
        certifiesReturn: Boolean = false,
    ): EstablishedEmission =
        EstablishedEmission(
            intents = setOf(spec.owns),
            span = span,
            startByte = startByte,
            endByte = endByte,
            source = source,
            confidence = Confidence.fromDouble(confidence),
            certifiesReturn = certifiesReturn,
        )

    protected fun RuleEvaluationView.candidateNode(
        node: SyntaxNode?,
        reason: CandidateReason = CandidateReason.PACK_DOMAIN,
    ): CandidateEmission? = node?.let { candidateSpan(it.span, it.startByte, it.endByte, reason) }

    protected fun RuleEvaluationView.candidateSpan(
        span: Span,
        startByte: Int,
        endByte: Int,
        reason: CandidateReason,
    ): CandidateEmission =
        CandidateEmission(
            intents = setOf(spec.owns),
            span = span,
            startByte = startByte,
            endByte = endByte,
            source = "rule:${spec.id}",
            confidence = Confidence.fromDouble(CANDIDATE_CONFIDENCE),
            reason = reason,
            aiEligible = true,
        )
}

internal fun within(
    node: SyntaxNode,
    container: SyntaxNode,
): Boolean = node.startByte >= container.startByte && node.endByte <= container.endByte

internal fun RuleEvaluationView.withinNode(
    nodeId: String?,
    container: SyntaxNode,
): Boolean = node(nodeId)?.let { within(it, container) } == true
