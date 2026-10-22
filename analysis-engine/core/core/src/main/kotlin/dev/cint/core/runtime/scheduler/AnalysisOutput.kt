package dev.cint.core.runtime.scheduler

import dev.cint.api.Annotation
import dev.cint.api.CandidateEmit
import dev.cint.api.Emit
import dev.cint.core.facts.oracle.OracleStats

data class RuleOutcome(
    val established: List<Emit>,
    val candidates: List<CandidateEmit>,
)

data class AnalysisOutput(
    val annotations: List<Annotation>,
    val candidates: List<CandidateEmit>,
    val oracle: OracleStats = OracleStats.EMPTY,
)
