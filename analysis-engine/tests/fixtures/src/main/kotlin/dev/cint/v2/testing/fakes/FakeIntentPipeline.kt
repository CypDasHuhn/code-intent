package dev.cint.v2.testing.fakes

import dev.cint.contracts.data.AnalysisResult
import dev.cint.contracts.data.Annotation
import dev.cint.contracts.data.Confidence
import dev.cint.contracts.data.Contribution
import dev.cint.contracts.data.Intent
import dev.cint.contracts.data.Role
import dev.cint.contracts.facts.IndexedSource
import dev.cint.contracts.facts.SemanticResult
import dev.cint.contracts.facts.SyntaxTree
import dev.cint.contracts.knowledge.FunctionAnnotation
import dev.cint.contracts.knowledge.KnowledgeSnapshot
import dev.cint.contracts.ports.AnalysisRequest
import dev.cint.contracts.ports.IntentPipeline
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory

class FakeIntentPipeline(
    val recorder: FakeRecorder = FakeRecorder(),
    private val analyzer: (AnalysisRequest, IndexedSource, KnowledgeSnapshot) -> ContractResult<AnalysisResult> =
        { request, source, knowledge -> ResultFactory.success(defaultAnalysis(request, source, knowledge)) },
) : IntentPipeline {
    override fun analyze(
        request: AnalysisRequest,
        source: IndexedSource,
        knowledge: KnowledgeSnapshot,
        cancellation: CancellationToken,
    ): ContractResult<AnalysisResult> {
        recorder.record(
            "pipeline.analyze",
            "targets=${request.targetPaths.joinToString(",")},epoch=${source.sourceEpoch}",
        )
        val checked = cancellation.throwIfCancelled()
        if (checked.isFailure) return checked.map { defaultAnalysis(request, source, knowledge) }
        return analyzer(request, source, knowledge)
    }
}

fun defaultAnalysis(
    request: AnalysisRequest,
    source: IndexedSource,
    knowledge: KnowledgeSnapshot,
): AnalysisResult {
    val tree = source.facts.syntaxTree
    val semanticConfidence =
        source.semanticFacts
            .mapNotNull { it.result as? SemanticResult.TypeAt }
            .mapNotNull { it.typeRef?.confidence }
            .minByOrNull { it.value }

    val annotations =
        if (tree == null) {
            emptyList()
        } else {
            source.facts.functions
                .sortedWith(compareBy({ it.nodeId }, { it.name.orEmpty() }))
                .mapNotNull { function ->
                    val name = function.name ?: return@mapNotNull null
                    val matching =
                        knowledge.functionAnnotations
                            .filter { annotation -> selectorMatches(annotation, name) }
                            .sortedBy { it.selector.identityKey }
                            .firstOrNull() ?: return@mapNotNull null
                    val confidence = semanticConfidence?.let { matching.confidence.min(it) } ?: matching.confidence
                    annotationFor(function.nodeId, tree, matching, confidence)
                }
        }

    return AnalysisResult(
        annotations = annotations,
        candidates = emptyList(),
        facts = source.facts,
        sourceEpoch = source.sourceEpoch,
        completeness = source.completeness,
        errors = emptyList(),
    )
}

private fun selectorMatches(
    annotation: FunctionAnnotation,
    name: String,
): Boolean {
    val qualified = annotation.selector.qualifiedName
    return qualified == name || qualified.endsWith(".$name")
}

private fun annotationFor(
    nodeId: String,
    tree: SyntaxTree,
    annotation: FunctionAnnotation,
    confidence: Confidence,
): Annotation? {
    val node = tree.getNode(nodeId) ?: return null
    val contribution =
        Contribution(
            rule = "fake-pipeline",
            role = Role.SOURCE_INTENT,
            intents = annotation.intents,
            span = node.span,
            source = "fake",
            confidence = confidence,
            startByte = node.startByte,
            endByte = node.endByte,
            provenance = annotation.provenance,
        )
    return Annotation(
        role = Role.SOURCE_INTENT,
        intents = annotation.intents.toSet(),
        span = node.span,
        source = "fake",
        confidence = confidence,
        startByte = node.startByte,
        endByte = node.endByte,
        provenance = listOf(contribution),
    )
}

fun funIntents(annotation: FunctionAnnotation): Set<Intent> = annotation.intents
