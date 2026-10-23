package dev.cint.frontend

import dev.cint.api.Annotation
import dev.cint.api.CandidateEmit
import dev.cint.api.Contribution
import dev.cint.api.Span
import dev.cint.core.config.IntentConfig
import dev.cint.core.facts.frontend.LanguageFrontend
import dev.cint.core.facts.frontend.OracleOptions
import dev.cint.core.facts.frontend.SplitHandler
import dev.cint.core.facts.frontend.SplitRegion
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.NullOracle
import dev.cint.core.facts.oracle.OracleStats
import dev.cint.core.facts.oracle.TypeOracle
import dev.cint.core.restriction.ResolvedRestriction
import dev.cint.core.runtime.scheduler.AnalysisOutput
import dev.cint.core.runtime.scheduler.ExecutionPlan
import dev.cint.core.runtime.scheduler.RuleEngine
import dev.cint.core.syntax.SyntaxModel
import dev.cint.core.syntax.SyntaxTree
import java.nio.file.Path

private typealias RegionAnalysis =
    (
        SourceFile,
        LanguageFrontend,
        SyntaxTree,
        SyntaxModel,
        TypeOracle,
        IntentConfig,
        List<Contribution>,
        List<ResolvedRestriction>,
    ) -> AnalysisOutput

private typealias SplitRecursion = (SourceFile, SplitHandler) -> AnalysisOutput

object SplitAnalyzer {
    fun analyze(
        host: SourceFile,
        handler: SplitHandler,
        root: Path,
        options: OracleOptions,
        planFor: (String) -> ExecutionPlan,
        userContributionsFor: (SourceFile, SourceFile, SyntaxTree, SyntaxModel, SplitRegion) -> List<Contribution> =
            { _, _, _, _, _ -> emptyList() },
        restrictionsFor: (SourceFile, SourceFile, SyntaxTree, SyntaxModel, SplitRegion) -> List<ResolvedRestriction> =
            { _, _, _, _, _ -> emptyList() },
        configFor: (String) -> IntentConfig,
    ): AnalysisOutput =
        analyzeRegions(
            host,
            handler,
            root,
            options,
            userContributionsFor,
            restrictionsFor,
            configFor,
            leaf = { file, frontend, tree, syntax, oracle, config, users, restrictions ->
                RuleEngine().analyze(planFor(frontend.language), file, tree, syntax, oracle, config, users, restrictions)
            },
            recurse = { file, frontend ->
                analyze(file, frontend, root, options, planFor, userContributionsFor, restrictionsFor, configFor)
            },
        )

    private fun analyzeRegions(
        host: SourceFile,
        handler: SplitHandler,
        root: Path,
        options: OracleOptions,
        userContributionsFor: (SourceFile, SourceFile, SyntaxTree, SyntaxModel, SplitRegion) -> List<Contribution>,
        restrictionsFor: (SourceFile, SourceFile, SyntaxTree, SyntaxModel, SplitRegion) -> List<ResolvedRestriction>,
        configFor: (String) -> IntentConfig,
        leaf: RegionAnalysis,
        recurse: SplitRecursion,
    ): AnalysisOutput {
        val result = mutableListOf<Annotation>()
        val candidates = mutableListOf<CandidateEmit>()
        var oracleTotals = OracleStats.EMPTY
        for (region in handler.regions(host)) {
            val frontend = Frontends.require(region.language)
            val config = configFor(region.language)
            val file = SourceFile(host.path, region.text)
            val output =
                if (frontend is SplitHandler) {
                    recurse(file, frontend)
                } else {
                    val tree = frontend.parse(file)
                    val syntax = frontend.syntax(tree)
                    val oracle: TypeOracle =
                        if (options.enabled) {
                            runCatching { frontend.oracle(root, options) }.getOrElse { NullOracle }
                        } else {
                            NullOracle
                        }
                    try {
                        leaf(
                            file,
                            frontend,
                            tree,
                            syntax,
                            oracle,
                            config,
                            userContributionsFor(host, file, tree, syntax, region),
                            restrictionsFor(host, file, tree, syntax, region),
                        )
                    } finally {
                        (oracle as? AutoCloseable)?.close()
                    }
                }
            result += output.annotations.map { it.shift(region) }
            candidates += output.candidates.map { it.shift(region) }
            oracleTotals += output.oracle
        }
        return AnalysisOutput(
            result.sortedWith(
                compareBy<Annotation>({ it.span.startLine }, { it.span.startCol }, { -it.endByte }),
            ),
            candidates.sortedWith(
                compareBy<CandidateEmit>(
                    { it.startByte },
                    { it.endByte },
                    { it.intents.sorted().joinToString(",") },
                    { it.reason.name },
                    { it.source },
                ),
            ),
            oracleTotals,
        )
    }

    private fun Annotation.shift(region: SplitRegion): Annotation =
        copy(
            span = span.shift(region),
            startByte = region.startByte + startByte,
            endByte = region.startByte + endByte,
            provenance =
                provenance.map {
                    it.copy(
                        span = it.span.shift(region),
                        startByte = region.startByte + it.startByte,
                        endByte = region.startByte + it.endByte,
                    )
                },
        )

    private fun CandidateEmit.shift(region: SplitRegion): CandidateEmit =
        copy(
            span = span.shift(region),
            startByte = region.startByte + startByte,
            endByte = region.startByte + endByte,
        )

    private fun Span.shift(region: SplitRegion): Span =
        Span(
            startLine = region.startLine + startLine,
            startCol = if (startLine == 0) region.startCol + startCol else startCol,
            endLine = region.startLine + endLine,
            endCol = if (endLine == 0) region.startCol + endCol else endCol,
        )
}
