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
        hostFrontend: LanguageFrontend? = null,
        hostUserContributionsFor: (SourceFile, SyntaxTree, SyntaxModel) -> List<Contribution> =
            { _, _, _ -> emptyList() },
        hostRestrictionsFor: (SourceFile, SyntaxTree, SyntaxModel) -> List<ResolvedRestriction> =
            { _, _, _ -> emptyList() },
        configFor: (String) -> IntentConfig,
    ): AnalysisOutput {
        val leafRegions = SplitAnchorResolver.leafRegions(host, handler)
        val leafIdentityCounts = leafRegions.groupingBy { it.identityKey }.eachCount()

        fun analyzeWith(
            current: SourceFile,
            currentHandler: SplitHandler,
        ): AnalysisOutput =
            analyzeRegions(
                current,
                currentHandler,
                root,
                options,
                userContributionsFor,
                restrictionsFor,
                configFor,
                leafIdentityCounts,
                leaf = { file, frontend, tree, syntax, oracle, config, users, restrictions ->
                    RuleEngine().analyze(planFor(frontend.language), file, tree, syntax, oracle, config, users, restrictions)
                },
                recurse = { file, frontend -> analyzeWith(file, frontend) },
            )
        val regionOutput = analyzeWith(host, handler)
        if (hostFrontend == null) return regionOutput
        val tree = hostFrontend.parse(host)
        val syntax = hostFrontend.syntax(tree)
        val oracle: TypeOracle =
            if (options.enabled) {
                runCatching { hostFrontend.oracle(root, options) }.getOrElse { NullOracle }
            } else {
                NullOracle
            }
        val hostOutput =
            try {
                RuleEngine().analyze(
                    planFor(hostFrontend.language),
                    host,
                    tree,
                    syntax,
                    oracle,
                    configFor(hostFrontend.language),
                    hostUserContributionsFor(host, tree, syntax),
                    hostRestrictionsFor(host, tree, syntax),
                )
            } finally {
                (oracle as? AutoCloseable)?.close()
            }

        fun overlapsLeaf(
            startByte: Int,
            endByte: Int,
        ): Boolean =
            leafRegions.any { region ->
                val regionEnd = region.startByte + region.text.toByteArray(Charsets.UTF_8).size
                startByte < regionEnd && endByte > region.startByte
            }
        val hostAnnotations = hostOutput.annotations.filterNot { overlapsLeaf(it.startByte, it.endByte) }
        val hostCandidates = hostOutput.candidates.filterNot { overlapsLeaf(it.startByte, it.endByte) }
        return AnalysisOutput(
            (regionOutput.annotations + hostAnnotations).sortedWith(
                compareBy<Annotation>({ it.span.startLine }, { it.span.startCol }, { -it.endByte }),
            ),
            (regionOutput.candidates + hostCandidates).sortedWith(
                compareBy<CandidateEmit>(
                    { it.startByte },
                    { it.endByte },
                    { it.intents.sorted().joinToString(",") },
                    { it.reason.name },
                    { it.source },
                ),
            ),
            regionOutput.oracle + hostOutput.oracle,
        )
    }

    private fun analyzeRegions(
        host: SourceFile,
        handler: SplitHandler,
        root: Path,
        options: OracleOptions,
        userContributionsFor: (SourceFile, SourceFile, SyntaxTree, SyntaxModel, SplitRegion) -> List<Contribution>,
        restrictionsFor: (SourceFile, SourceFile, SyntaxTree, SyntaxModel, SplitRegion) -> List<ResolvedRestriction>,
        configFor: (String) -> IntentConfig,
        leafIdentityCounts: Map<String, Int>,
        leaf: RegionAnalysis,
        recurse: SplitRecursion,
    ): AnalysisOutput {
        val result = mutableListOf<Annotation>()
        val candidates = mutableListOf<CandidateEmit>()
        var oracleTotals = OracleStats.EMPTY
        val regions = handler.regions(host)
        for ((index, region) in regions.withIndex()) {
            val key = region.identityKey
            val scopedRegion =
                region.copy(
                    ordinal = index,
                    identityAmbiguous = (leafIdentityCounts[key] ?: 0) > 1,
                )
            val frontend = Frontends.require(scopedRegion.language)
            val config = configFor(scopedRegion.language)
            val file = SourceFile(host.path, scopedRegion.text)
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
                            userContributionsFor(host, file, tree, syntax, scopedRegion),
                            restrictionsFor(host, file, tree, syntax, scopedRegion),
                        )
                    } finally {
                        (oracle as? AutoCloseable)?.close()
                    }
                }
            result += output.annotations.map { it.shift(scopedRegion) }
            candidates += output.candidates.map { it.shift(scopedRegion) }
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
