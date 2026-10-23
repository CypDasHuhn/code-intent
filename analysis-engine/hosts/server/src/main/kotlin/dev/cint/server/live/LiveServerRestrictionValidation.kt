package dev.cint.server.live

import dev.cint.api.Annotation
import dev.cint.api.CandidateEmit
import dev.cint.api.Contribution
import dev.cint.api.Role
import dev.cint.api.Span
import dev.cint.core.candidate.CandidateIdentity
import dev.cint.core.facts.frontend.SplitRegion
import dev.cint.core.restriction.AI_CONFIRMED_CONTRIBUTOR_ID
import dev.cint.core.restriction.AI_CONFIRMED_SOURCE
import dev.cint.core.restriction.ResolvedRestriction
import dev.cint.core.restriction.ResolvedRestrictionOperation
import dev.cint.core.user.USER_CONTRIBUTOR_ID
import dev.cint.core.user.USER_PURE_INTENT
import dev.cint.core.user.USER_READONLY_INTENT
import dev.cint.core.user.USER_SOURCE
import dev.cint.core.user.UserAnnotationResolver
import dev.cint.suggest.model.CandidateProposal

internal fun revalidatedProposals(
    doc: DocState,
    annotations: List<Annotation>,
    proposals: List<CandidateProposal>,
): List<CandidateProposal> =
    proposals.filter { proposal ->
        val currentHash =
            UserAnnotationResolver
                .selectionText(doc.text, proposal.span)
                ?.let(CandidateIdentity::contentHash)
        currentHash == proposal.contentHash &&
            proposal.removes.isNotEmpty() &&
            proposal.removes.all { removal ->
                establishedRemovable(annotations, removal.intent, removal.startByte, removal.endByte)
            } &&
            additionsAvailable(
                annotations,
                proposal.adds.map { addition ->
                    ResolvedRestrictionOperation(
                        intent = addition.intent,
                        startByte = addition.startByte,
                        endByte = addition.endByte,
                        span = addition.span,
                        confidence = addition.confidence,
                        rationale = addition.rationale,
                    )
                },
            )
    }

internal fun revalidatedCandidates(
    doc: DocState,
    annotations: List<Annotation>,
    candidates: List<CandidateEmit>,
): List<CandidateEmit> =
    candidates.filter { candidate ->
        UserAnnotationResolver.byteRange(doc.text, candidate.span) != null &&
            candidate.intents.none { intent ->
                annotations.any { annotation ->
                    annotation.provenance.any { contribution ->
                        contribution.role == Role.SOURCE_INTENT &&
                            intent in contribution.intents &&
                            contribution.startByte == candidate.startByte &&
                            contribution.endByte == candidate.endByte
                    }
                }
            }
    }

internal fun removalsApply(
    annotations: List<Annotation>,
    removals: List<ResolvedRestrictionOperation>,
): Boolean =
    removals.isNotEmpty() &&
        removals.all { removal ->
            establishedRemovable(annotations, removal.intent, removal.startByte, removal.endByte)
        }

internal fun addsApply(
    annotations: List<Annotation>,
    additions: List<ResolvedRestrictionOperation>,
): Boolean =
    additions.isNotEmpty() &&
        additions.all { addition ->
            annotations.any { annotation ->
                annotation.provenance.any { contribution ->
                    contribution.rule == AI_CONFIRMED_CONTRIBUTOR_ID &&
                        contribution.source == AI_CONFIRMED_SOURCE &&
                        addition.intent in contribution.intents &&
                        contribution.startByte == addition.startByte &&
                        contribution.endByte == addition.endByte
                }
            }
        }

internal fun additionsAvailable(
    annotations: List<Annotation>,
    additions: List<ResolvedRestrictionOperation>,
): Boolean =
    additions.all { addition ->
        annotations.none { annotation ->
            annotation.provenance.any { contribution ->
                addition.intent in contribution.intents &&
                    contribution.startByte == addition.startByte &&
                    contribution.endByte == addition.endByte
            }
        }
    }

internal fun restrictionApplies(
    annotations: List<Annotation>,
    restriction: dev.cint.core.restriction.ResolvedRestriction,
): Boolean = removalsApply(annotations, restriction.remove) || addsApply(annotations, restriction.add)

internal fun ResolvedRestriction.shift(region: SplitRegion): ResolvedRestriction =
    copy(
        anchorSpan = anchorSpan.shift(region),
        anchorStartByte = region.startByte + anchorStartByte,
        anchorEndByte = region.startByte + anchorEndByte,
        remove = remove.map { it.shift(region) },
        add = add.map { it.shift(region) },
    )

internal fun ResolvedRestrictionOperation.shift(region: SplitRegion): ResolvedRestrictionOperation =
    copy(
        span = span.shift(region),
        startByte = region.startByte + startByte,
        endByte = region.startByte + endByte,
    )

internal fun Span.shift(region: SplitRegion): Span =
    Span(
        startLine = region.startLine + startLine,
        startCol = if (startLine == 0) region.startCol + startCol else startCol,
        endLine = region.startLine + endLine,
        endCol = if (endLine == 0) region.startCol + endCol else endCol,
    )

private fun establishedRemovable(
    annotations: List<Annotation>,
    intent: String,
    startByte: Int,
    endByte: Int,
): Boolean =
    annotations.any { annotation ->
        annotation.provenance.any { contribution ->
            contribution.role == Role.SOURCE_INTENT &&
                contribution.removable() &&
                intent in contribution.intents &&
                contribution.startByte == startByte &&
                contribution.endByte == endByte
        }
    }

private fun Contribution.removable(): Boolean =
    rule != USER_CONTRIBUTOR_ID &&
        source != USER_SOURCE &&
        rule != AI_CONFIRMED_CONTRIBUTOR_ID &&
        !rule.startsWith("$AI_CONFIRMED_CONTRIBUTOR_ID:") &&
        source != AI_CONFIRMED_SOURCE &&
        intents.none { it == USER_PURE_INTENT || it == USER_READONLY_INTENT }
