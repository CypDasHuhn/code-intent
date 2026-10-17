package com.semanticpainter.cli

import com.semanticpainter.core.artifact.Artifact
import com.semanticpainter.core.artifact.FileEntry
import com.semanticpainter.core.config.loadIntentConfig
import com.semanticpainter.core.engine.AnalysisEngine
import com.semanticpainter.core.hashing.contentHash
import com.semanticpainter.core.model.SourceFile
import com.semanticpainter.core.model.UNTAGGED_INTENT
import com.semanticpainter.core.oracle.NullOracle
import com.semanticpainter.core.oracle.TypeOracle
import com.semanticpainter.lsp.KotlinLspOracle
import com.semanticpainter.treesitter.IntentBlocks
import com.semanticpainter.treesitter.KotlinParser
import java.io.File

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
    require(config.language == "kotlin") { "only kotlin is supported by the tree-sitter frontend" }

    val root = File(args.root)
    require(root.exists()) { "no such path: ${args.root}" }

    val extensions = extensionsFor(config.language)
    val files = root.walkTopDown()
        .filter { it.isFile && extensions.any { ext -> it.name.endsWith(ext) } }
        .filterNot {
            it.path.contains("/build/") ||
                it.path.contains("/.git/") ||
                it.path.contains("/.gradle/")
        }
        .sortedBy { it.path }
        .toList()

    val parser = KotlinParser()
    val blocks = IntentBlocks.build(config)
    val entries = mutableListOf<FileEntry>()
    val oracle: TypeOracle = if (args.noLsp) {
        NullOracle
    } else {
        runCatching { KotlinLspOracle.connect(root.toPath()) }
            .onFailure { System.err.println("lsp unavailable, using heuristic facts: ${it.message}") }
            .getOrElse { NullOracle }
    }

    try {
        for (file in files) {
            val text = runCatching { file.readText() }.getOrNull() ?: continue
            val relative = root.toURI().relativize(file.toURI()).path
            val source = SourceFile(relative, text)
            val tree = runCatching { parser.parse(source) }
                .onFailure { System.err.println("parse failed: ${file.path}: ${it.message}") }
                .getOrNull() ?: continue
            val annotations = AnalysisEngine(blocks).analyze(source, tree, oracle, config)
            if (annotations.isNotEmpty() || args.all) {
                entries.add(FileEntry(relative, contentHash(text), annotations))
            }
        }
    } finally {
        (oracle as? AutoCloseable)?.close()
    }

    val result = Artifact.render(
        config.language,
        config.intents.keys.toList() + UNTAGGED_INTENT,
        entries,
    )
    if (args.out != null) File(args.out).writeText(result) else print(result)
    System.err.println("scanned ${files.size} file(s), tagged ${entries.size} file(s)")
}

private fun extensionsFor(language: String): List<String> = when (language) {
    "kotlin" -> listOf(".kt", ".kts")
    else -> listOf(".$language")
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
        root = root ?: error(
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
