package dev.cint.frontend

import dev.cint.api.Annotation
import dev.cint.api.Span
import dev.cint.core.config.IntentConfig
import dev.cint.core.facts.frontend.LanguageFrontend
import dev.cint.core.facts.frontend.OracleOptions
import dev.cint.core.facts.frontend.SplitHandler
import dev.cint.core.facts.frontend.SplitRegion
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.NullOracle
import dev.cint.core.facts.oracle.TypeOracle
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
    ) -> List<Annotation>

private typealias SplitRecursion = (SourceFile, SplitHandler) -> List<Annotation>

object SplitAnalyzer {
    fun analyze(
        host: SourceFile,
        handler: SplitHandler,
        root: Path,
        options: OracleOptions,
        planFor: (String) -> ExecutionPlan,
        configFor: (String) -> IntentConfig,
    ): List<Annotation> =
        analyzeRegions(
            host,
            handler,
            root,
            options,
            configFor,
            leaf = { file, frontend, tree, syntax, oracle, config ->
                RuleEngine().analyze(planFor(frontend.language), file, tree, syntax, oracle, config)
            },
            recurse = { file, frontend -> analyze(file, frontend, root, options, planFor, configFor) },
        )

    private fun analyzeRegions(
        host: SourceFile,
        handler: SplitHandler,
        root: Path,
        options: OracleOptions,
        configFor: (String) -> IntentConfig,
        leaf: RegionAnalysis,
        recurse: SplitRecursion,
    ): List<Annotation> {
        val result = mutableListOf<Annotation>()
        for (region in handler.regions(host)) {
            val frontend = Frontends.require(region.language)
            val config = configFor(region.language)
            val file = SourceFile(host.path, region.text)
            val annotations =
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
                        leaf(file, frontend, tree, syntax, oracle, config)
                    } finally {
                        (oracle as? AutoCloseable)?.close()
                    }
                }
            result += annotations.map { it.shift(region) }
        }
        return result.sortedWith(
            compareBy<Annotation>({ it.span.startLine }, { it.span.startCol }, { -it.endByte }),
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

    private fun Span.shift(region: SplitRegion): Span =
        Span(
            startLine = region.startLine + startLine,
            startCol = if (startLine == 0) region.startCol + startCol else startCol,
            endLine = region.startLine + endLine,
            endCol = if (endLine == 0) region.startCol + endCol else endCol,
        )
}
