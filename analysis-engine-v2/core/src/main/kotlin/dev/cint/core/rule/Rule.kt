package dev.cint.core.rule

import dev.cint.contracts.data.Candidate
import dev.cint.contracts.data.Contribution
import dev.cint.contracts.data.Provenance
import dev.cint.contracts.data.Role
import dev.cint.contracts.ports.CandidateEmission
import dev.cint.contracts.ports.EstablishedEmission
import dev.cint.contracts.ports.RuleEmission
import dev.cint.contracts.ports.RuleEvaluator

typealias Rule = RuleEvaluator
typealias RuleEmit = RuleEmission
typealias Emit = EstablishedEmission
typealias CandidateEmit = CandidateEmission

fun Rule.toContribution(emit: Emit): Contribution =
    Contribution(
        rule = spec.id,
        role = Role.SOURCE_INTENT,
        intents = emit.intents,
        span = emit.span,
        source = emit.source,
        confidence = emit.confidence,
        startByte = emit.startByte,
        endByte = emit.endByte,
        provenance = Provenance.RULE,
        certifiesReturn = emit.certifiesReturn,
    )

fun CandidateEmit.toCandidate(): Candidate =
    Candidate(
        intents = intents,
        span = span,
        startByte = startByte,
        endByte = endByte,
        source = source,
        confidence = confidence,
        reason = reason,
        aiEligible = aiEligible,
        rationale = rationale,
        provider = provider,
        model = model,
        promptVersion = promptVersion,
    )
