package dev.cint.server.live

import dev.cint.api.Annotation
import dev.cint.api.CandidateEmit
import dev.cint.api.CandidateReason
import dev.cint.api.Contribution
import dev.cint.api.Role
import dev.cint.api.Span
import dev.cint.core.candidate.CandidateIdentity
import dev.cint.core.restriction.AI_CONFIRMED_CONTRIBUTOR_ID
import dev.cint.core.restriction.AI_CONFIRMED_SOURCE
import dev.cint.core.restriction.ResolvedRestriction
import dev.cint.core.restriction.ResolvedRestrictionOperation
import dev.cint.suggest.model.CandidateProposal
import dev.cint.suggest.model.ProposalOperation
import dev.cint.suggest.model.ProposalOperationKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveServerRestrictionValidationTest {
    private val text = "log.warn(message)"
    private val span = Span(0, 0, 0, text.length)
    private val doc = DocState("file:///Sample.kt", text, 1, "kotlin")

    @Test
    fun packRemovalAppliesBesideAiConfirmedAndUserDirectives() {
        val pack = contribution("logging:logging", "LOGGING", "treesitter")
        val replacement =
            contribution(
                "core:ai-confirmed",
                "SEARCH",
                AI_CONFIRMED_SOURCE,
                provider = "deepseek",
            )
        val user =
            contribution("core:user-annotations", "PURE", "user")
        val annotation = annotation(listOf(pack, replacement, user))

        assertTrue(removalsApply(listOf(annotation), listOf(removal("LOGGING"))))
        assertTrue(addsApply(listOf(annotation), listOf(removal("SEARCH"))))
        assertTrue(restrictionApplies(listOf(annotation), restriction(remove = true, add = true)))
    }

    @Test
    fun removeOnlyRecordWithoutAnApplicableRemovalIsDormant() {
        val replacement =
            contribution(
                "core:ai-confirmed",
                "SEARCH",
                AI_CONFIRMED_SOURCE,
                provider = "deepseek",
            )
        val annotation = annotation(listOf(replacement))

        assertFalse(removalsApply(listOf(annotation), listOf(removal("LOGGING"))))
        assertFalse(restrictionApplies(listOf(annotation), restriction(remove = true, add = false)))
    }

    @Test
    fun revalidationUsesPerContributionProtection() {
        val pack = contribution("logging:logging", "LOGGING", "treesitter")
        val replacement =
            contribution(
                "core:ai-confirmed",
                "SEARCH",
                AI_CONFIRMED_SOURCE,
                provider = "deepseek",
            )
        val mixed = annotation(listOf(pack, replacement))
        val proposal =
            CandidateProposal(
                proposalId = "proposal",
                anchorId = "anchor",
                path = "Sample.kt",
                contentHash = CandidateIdentity.contentHash(text),
                unitId = "u0",
                span = span,
                startByte = 0,
                endByte = text.length,
                operations =
                    listOf(
                        ProposalOperation(ProposalOperationKind.REMOVE, "LOGGING", 0, text.length, span, 0.7),
                        ProposalOperation(ProposalOperationKind.ADD, "LOOKUP", 0, text.length, span, 0.7),
                    ),
            )

        assertEquals(listOf(proposal), revalidatedProposals(doc, listOf(mixed), listOf(proposal)))
        assertEquals(
            emptyList(),
            revalidatedProposals(doc, listOf(annotation(listOf(replacement))), listOf(proposal)),
        )
    }

    @Test
    fun proposalWithAnEstablishedReplacementAddIsDropped() {
        val pack = contribution("logging:logging", "LOGGING", "treesitter")
        val establishedReplacement = contribution("data-access:search", "SEARCH", "treesitter")
        val annotation = annotation(listOf(pack, establishedReplacement))
        val proposal =
            CandidateProposal(
                proposalId = "proposal",
                anchorId = "anchor",
                path = "Sample.kt",
                contentHash = CandidateIdentity.contentHash(text),
                unitId = "u0",
                span = span,
                startByte = 0,
                endByte = text.length,
                operations =
                    listOf(
                        ProposalOperation(ProposalOperationKind.REMOVE, "LOGGING", 0, text.length, span, 0.7),
                        ProposalOperation(ProposalOperationKind.ADD, "SEARCH", 0, text.length, span, 0.7),
                    ),
            )

        assertTrue(revalidatedProposals(doc, listOf(annotation), listOf(proposal)).isEmpty())
        assertFalse(
            additionsAvailable(
                listOf(annotation),
                listOf(removal("SEARCH")),
            ),
        )
    }

    @Test
    fun carriedCandidatesAreDroppedWhenTheIntentIsAlreadyEstablished() {
        val existing = contribution("logging:logging", "SEARCH", "treesitter")
        val annotation = annotation(listOf(existing))
        val duplicate = candidate("SEARCH", 0, text.length)
        val unrelated = candidate("SEARCH", 0, 3)

        assertEquals(
            listOf(unrelated),
            revalidatedCandidates(doc, listOf(annotation), listOf(duplicate, unrelated)),
        )
    }

    private fun annotation(contributions: List<Contribution>): Annotation =
        Annotation(
            role = Role.SOURCE_INTENT,
            intents = contributions.flatMap { it.intents }.toSet(),
            span = span,
            source = contributions.map { it.source }.distinct().singleOrNull() ?: "mixed",
            confidence = 1.0,
            startByte = 0,
            endByte = text.length,
            provenance = contributions,
        )

    private fun contribution(
        rule: String,
        intent: String,
        source: String,
        provider: String? = null,
    ): Contribution =
        Contribution(
            rule = rule,
            role = Role.SOURCE_INTENT,
            intents = setOf(intent),
            span = span,
            source = source,
            confidence = 1.0,
            startByte = 0,
            endByte = text.length,
            provider = provider,
            model = provider?.let { "deepseek-chat" },
            promptVersion = provider?.let { "v3" },
        )

    private fun removal(intent: String): ResolvedRestrictionOperation =
        ResolvedRestrictionOperation(
            intent = intent,
            startByte = 0,
            endByte = text.length,
            span = span,
            confidence = 0.7,
            rationale = null,
        )

    private fun restriction(
        remove: Boolean,
        add: Boolean,
    ): ResolvedRestriction =
        ResolvedRestriction(
            id = "r",
            path = "Sample.kt",
            anchorStartByte = 0,
            anchorEndByte = text.length,
            anchorSpan = span,
            remove = if (remove) listOf(removal("LOGGING")) else emptyList(),
            add = if (add) listOf(removal("SEARCH")) else emptyList(),
            provider = "deepseek",
            model = "deepseek-chat",
            promptVersion = "v3",
        )

    private fun candidate(
        intent: String,
        start: Int,
        end: Int,
    ): CandidateEmit =
        CandidateEmit(
            intents = setOf(intent),
            span = Span(0, start, 0, end),
            startByte = start,
            endByte = end,
            source = "ai:deepseek",
            confidence = 0.6,
            reason = CandidateReason.GAP,
            aiEligible = false,
        )
}
