package com.semanticpainter.frontend

import com.semanticpainter.core.config.loadIntentConfig
import com.semanticpainter.core.engine.AnalysisEngine
import com.semanticpainter.core.frontend.OracleOptions
import com.semanticpainter.core.frontend.SplitHandler
import com.semanticpainter.core.frontend.SplitRegion
import com.semanticpainter.core.model.Annotation
import com.semanticpainter.core.model.SourceFile
import com.semanticpainter.core.model.Span
import com.semanticpainter.core.oracle.NullOracle
import com.semanticpainter.core.oracle.TypeOracle
import com.semanticpainter.core.rules.IntentBlocks
import java.nio.file.Path

object SplitAnalyzer {
    fun analyze(
        host: SourceFile,
        handler: SplitHandler,
        root: Path,
        configPath: String?,
        options: OracleOptions,
    ): List<Annotation> {
        val result = mutableListOf<Annotation>()
        for (region in handler.regions(host)) {
            val frontend = Frontends.require(region.language)
            val config = loadIntentConfig(configPath, region.language)
            val file = SourceFile(host.path, region.text)
            val annotations =
                if (frontend is SplitHandler) {
                    analyze(file, frontend, root, configPath, options)
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
                        AnalysisEngine(IntentBlocks.build(config)).analyze(file, tree, syntax, oracle, config)
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
            span =
                Span(
                    startLine = region.startLine + span.startLine,
                    startCol = if (span.startLine == 0) region.startCol + span.startCol else span.startCol,
                    endLine = region.startLine + span.endLine,
                    endCol = if (span.endLine == 0) region.startCol + span.endCol else span.endCol,
                ),
            startByte = region.startByte + startByte,
            endByte = region.startByte + endByte,
        )
}
