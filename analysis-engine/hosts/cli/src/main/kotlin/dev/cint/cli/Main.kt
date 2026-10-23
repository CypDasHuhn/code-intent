package dev.cint.cli

import dev.cint.core.artifact.Artifact
import dev.cint.core.artifact.FileEntry
import dev.cint.core.facts.frontend.SplitHandler
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.NullOracle
import dev.cint.core.facts.oracle.TypeOracle
import dev.cint.core.support.hashing.contentHash
import dev.cint.frontend.Frontends
import dev.cint.frontend.SplitAnalyzer
import dev.cint.host.HostConfig
import dev.cint.host.HostRuntime
import dev.cint.rulepacks.BundledRulePackConfigResolver
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
    val settings = HostConfig.load(args.config)
    val config = settings.intentConfig(args.language)
    val lspOptions = settings.oracleOptions(!args.noLsp)
    val frontend = Frontends.require(config.language)

    val root = File(args.root)
    require(root.exists()) { "no such path: ${args.root}" }
    val packs = HostRuntime.pipeline(settings, BundledRulePackConfigResolver, root.toPath())

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

    val entries = mutableListOf<FileEntry>()
    val usedLanguages = linkedSetOf<String>()
    if (frontend !is SplitHandler) usedLanguages += config.language
    val oracle: TypeOracle =
        if (args.noLsp || frontend is SplitHandler) {
            NullOracle
        } else {
            runCatching { frontend.oracle(root.toPath(), lspOptions) }
                .onFailure { System.err.println("lsp unavailable, using heuristic facts: ${it.message}") }
                .getOrElse { NullOracle }
        }

    try {
        for (file in files) {
            val text = runCatching { file.readText() }.getOrNull() ?: continue
            val relative = root.toURI().relativize(file.toURI()).path
            val source = SourceFile(relative, text)
            val output =
                if (frontend is SplitHandler) {
                    runCatching {
                        val languages = frontend.regions(source).map { it.language }
                        val result =
                            SplitAnalyzer.analyze(
                                source,
                                frontend,
                                root.toPath(),
                                lspOptions,
                                packs::plan,
                                packs::userContributionsFor,
                                packs::restrictionsFor,
                            ) { language -> settings.intentConfig(language) }
                        usedLanguages += languages
                        result
                    }.onFailure { System.err.println("parse failed: ${file.path}: ${it.message}") }
                        .getOrNull() ?: continue
                } else {
                    val tree =
                        runCatching { frontend.parse(source) }
                            .onFailure { System.err.println("parse failed: ${file.path}: ${it.message}") }
                            .getOrNull() ?: continue
                    runCatching { packs.analyze(source, tree, frontend.syntax(tree), oracle, config) }
                        .onFailure { System.err.println("analysis failed: ${file.path}: ${it.message}") }
                        .getOrNull() ?: continue
                }
            if (output.annotations.isNotEmpty() || output.candidates.isNotEmpty() || args.all) {
                entries.add(FileEntry(relative, contentHash(text), output.annotations, output.candidates))
            }
        }
    } finally {
        (oracle as? AutoCloseable)?.close()
    }

    if (usedLanguages.isEmpty()) usedLanguages += config.language
    val result = Artifact.render(config.language, packs.intents(usedLanguages), entries)
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
                "usage: cint <path> [--config file] [--lang kotlin] " +
                    "[--out file] [--all] [--no-lsp]",
            ),
        config = config,
        language = language,
        out = out,
        all = all,
        noLsp = noLsp,
    )
}
