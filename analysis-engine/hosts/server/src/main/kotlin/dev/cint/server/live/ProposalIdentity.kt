package dev.cint.server.live

import dev.cint.core.restriction.AiRestrictionRecord
import dev.cint.core.restriction.RestrictionOperation
import dev.cint.core.support.hashing.SourceNormalizer
import dev.cint.core.user.UserAnnotationResolver
import dev.cint.suggest.model.CandidateProposal
import dev.cint.suggest.model.ProposalOperationKind

internal fun proposalOperationShape(
    text: String,
    proposal: CandidateProposal,
    regionStartByte: Int = 0,
): List<String> =
    proposal.operations
        .map { operation ->
            listOf(
                operation.kind.name,
                operation.intent,
                UserAnnotationResolver
                    .selectionText(
                        text,
                        (operation.startByte - regionStartByte) to (operation.endByte - regionStartByte),
                    )?.let(SourceNormalizer::normalize)
                    .orEmpty(),
            ).joinToString(":")
        }.sorted()

internal fun restrictionOperationShape(
    text: String,
    proposal: CandidateProposal,
    record: AiRestrictionRecord,
    regionStartByte: Int = 0,
): List<String> {
    val tokens = SourceNormalizer.tokenSpans(text, stripComments = true)
    val anchorStart = proposal.startByte - regionStartByte
    val anchorIndex =
        tokens.indexOfFirst { it.endByte > anchorStart }.let { if (it < 0) tokens.size else it }

    fun range(operation: RestrictionOperation): Pair<Int, Int>? {
        val startToken = operation.startToken
        val endToken = operation.endToken
        if (startToken != null && endToken != null) {
            val start = anchorIndex + startToken
            val end = anchorIndex + endToken
            if (start >= 0 && end <= tokens.size && end > start) {
                return tokens[start].startByte to tokens[end - 1].endByte
            }
        }
        return (anchorStart + operation.startOffset) to (anchorStart + operation.endOffset)
    }
    return (
        record.remove.map { operation ->
            listOf(
                ProposalOperationKind.REMOVE.name,
                operation.intent,
                range(operation)
                    ?.let { UserAnnotationResolver.selectionText(text, it) }
                    ?.let(SourceNormalizer::normalize)
                    .orEmpty(),
            ).joinToString(":")
        } +
            record.add.map { operation ->
                listOf(
                    ProposalOperationKind.ADD.name,
                    operation.intent,
                    range(operation)
                        ?.let { UserAnnotationResolver.selectionText(text, it) }
                        ?.let(SourceNormalizer::normalize)
                        .orEmpty(),
                ).joinToString(":")
            }
    ).sorted()
}
