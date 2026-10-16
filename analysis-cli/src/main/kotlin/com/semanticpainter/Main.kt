package com.semanticpainter

import java.io.File

private data class Args(
    val root: String,
    val config: String,
    val language: String,
    val feature: String,
    val out: String?,
    val all: Boolean,
)

fun main(argv: Array<String>) {
    val args = parseArgs(argv)
    val cfg = loadFeatureConfig(args.config, args.feature, args.language)
    val rootFile = File(args.root)
    require(rootFile.exists()) { "no such path: ${args.root}" }

    val extensions = extensionsFor(args.language)
    val files = rootFile.walkTopDown()
        .filter { it.isFile && extensions.any { ext -> it.name.endsWith(ext) } }
        .filterNot { it.path.contains("/build/") || it.path.contains("/.git/") }
        .sortedBy { it.path }
        .toList()

    val entries = mutableListOf<FileEntry>()
    for (f in files) {
        val text = runCatching { f.readText() }.getOrNull() ?: continue
        val facts = runCatching { KotlinFrontend.facts(f.path, text) }
            .onFailure { System.err.println("parse failed: ${f.path}: ${it.message}") }
            .getOrNull() ?: continue
        val spans = IntentQuery.run(facts, cfg)
        if (spans.isNotEmpty() || args.all) {
            val rel = rootFile.toURI().relativize(f.toURI()).path
            entries.add(FileEntry(rel, contentHash(text), spans))
        }
    }

    val out = StringBuilder()
    out.append("{\n")
    out.append("  \"feature\": \"${Json.escape(args.feature)}\",\n")
    out.append("  \"tag\": \"${Json.escape(cfg.tag)}\",\n")
    out.append("  \"language\": \"${Json.escape(args.language)}\",\n")
    out.append("  \"files\": [\n")
    entries.forEachIndexed { i, entry ->
        out.append("    {\n")
        out.append("      \"path\": \"${Json.escape(entry.path)}\",\n")
        out.append("      \"hash\": \"${Json.escape(entry.hash)}\",\n")
        out.append("      \"spans\": [\n")
        entry.spans.forEachIndexed { j, s ->
            out.append("        {\"tag\":\"${Json.escape(s.tag)}\",")
            out.append("\"rule\":\"${Json.escape(s.rule)}\",")
            out.append("\"evidence\":\"${Json.escape(s.evidence)}\",")
            out.append("\"startLine\":${s.startLine},\"startCol\":${s.startCol},")
            out.append("\"endLine\":${s.endLine},\"endCol\":${s.endCol}}")
            if (j < entry.spans.size - 1) out.append(",")
            out.append("\n")
        }
        out.append("      ]\n    }")
        if (i < entries.size - 1) out.append(",")
        out.append("\n")
    }
    out.append("  ]\n}\n")

    val result = out.toString()
    if (args.out != null) File(args.out).writeText(result) else print(result)
    System.err.println("scanned ${files.size} file(s), tagged ${entries.size} file(s)")
}

private fun extensionsFor(language: String): List<String> = when (language) {
    "kotlin" -> listOf(".kt", ".kts")
    else -> listOf(".$language")
}

private fun parseArgs(argv: Array<String>): Args {
    var root: String? = null
    var config = "config/logging.yaml"
    var language = "kotlin"
    var feature = "logging"
    var out: String? = null
    var all = false
    var i = 0
    while (i < argv.size) {
        when (val a = argv[i]) {
            "--config" -> config = argv[++i]
            "--lang" -> language = argv[++i]
            "--feature" -> feature = argv[++i]
            "--out" -> out = argv[++i]
            "--all" -> all = true
            else -> if (a.startsWith("--")) error("unknown flag: $a") else root = a
        }
        i++
    }
    return Args(
        root ?: error(
            "usage: semantic-code-painter <path> [--config file] [--lang kotlin] " +
                "[--feature logging] [--out file] [--all]",
        ),
        config, language, feature, out, all,
    )
}
