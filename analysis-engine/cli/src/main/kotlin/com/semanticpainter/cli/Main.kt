package com.semanticpainter.cli

import com.semanticpainter.core.artifact.Artifact
import com.semanticpainter.core.artifact.FileEntry
import com.semanticpainter.core.config.loadIntentConfig
import com.semanticpainter.core.engine.AnalysisEngine
import com.semanticpainter.core.frontend.OracleOptions
import com.semanticpainter.core.hashing.contentHash
import com.semanticpainter.core.model.SourceFile
import com.semanticpainter.core.model.UNTAGGED_INTENT
import com.semanticpainter.core.oracle.NullOracle
import com.semanticpainter.core.oracle.TypeOracle
import com.semanticpainter.core.rules.IntentBlocks
import com.semanticpainter.frontend.Frontends
import java.io.File

private const val DEFAULT_LSP_TIMEOUT_MS = 5_000L

private data class Args(
    val root: String,
    val config: String?,
    val language: String,
    val out: String?,
    val all: Boolean,
    val noLsp: Boolean,
)

fun main(argv: Array<String>) {
    val args = parseArgs(argv)
    val config = loadIntentConfig(args.config, args.language)
    val frontend = Frontends.require(config.language)

    val root = File(args.root)
    require(root.exists()) { "no such path: ${args.root}" }

    val files =
        root
            .walkTopDown()
            .filter { it.isFile && frontend.extensions.any { ext -> it.name.endsWith(ext) } }
            .filterNot {
                it.path.contains("/build/") ||
                    it.path.contains("/.git/") ||
                    it.path.contains("/.gradle/")
            }.sortedBy { it.path }
            .toList()

    val blocks = IntentBlocks.build(config)
    val entries = mutableListOf<FileEntry>()
    val oracle: TypeOracle =
        if (args.noLsp) {
            NullOracle
        } else {
            runCatching { frontend.oracle(root.toPath(), OracleOptions(true, DEFAULT_LSP_TIMEOUT_MS)) }
                .onFailure { System.err.println("lsp unavailable, using heuristic facts: ${it.message}") }
                .getOrElse { NullOracle }
        }

    try {
        for (file in files) {
            val text = runCatching { file.readText() }.getOrNull() ?: continue
            val relative = root.toURI().relativize(file.toURI()).path
            val source = SourceFile(relative, text)
            val tree =
                runCatching { frontend.parse(source) }
                    .onFailure { System.err.println("parse failed: ${file.path}: ${it.message}") }
                    .getOrNull() ?: continue
            val syntax = frontend.syntax(tree)
            val annotations = AnalysisEngine(blocks).analyze(source, tree, syntax, oracle, config)
            if (annotations.isNotEmpty() || args.all) {
                entries.add(FileEntry(relative, contentHash(text), annotations))
            }
        }
    } finally {
        (oracle as? AutoCloseable)?.close()
    }

    val result =
        Artifact.render(
            config.language,
            config.intents.keys.toList() + UNTAGGED_INTENT,
            entries,
        )
    if (args.out != null) File(args.out).writeText(result) else print(result)
    System.err.println("scanned ${files.size} file(s), tagged ${entries.size} file(s)")
}

private fun parseArgs(argv: Array<String>): Args {
    var root: String? = null
    var config: String? = null
    var language = "kotlin"
    var out: String? = null
    var all = false
    var noLsp = false
    var i = 0
    while (i < argv.size) {
        when (val arg = argv[i]) {
            "--config" -> config = argv[++i]
            "--lang" -> language = argv[++i]
            "--out" -> out = argv[++i]
            "--all" -> all = true
            "--no-lsp" -> noLsp = true
            else -> if (arg.startsWith("--")) error("unknown flag: $arg") else root = arg
        }
        i++
    }
    return Args(
        root =
            root ?: error(
                "usage: semantic-code-painter <path> [--config file] [--lang kotlin] " +
                    "[--out file] [--all] [--no-lsp]",
            ),
        config = config,
        language = language,
        out = out,
        all = all,
        noLsp = noLsp,
    )
}
