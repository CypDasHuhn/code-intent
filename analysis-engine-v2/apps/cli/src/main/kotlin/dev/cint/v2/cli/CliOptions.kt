package dev.cint.v2.cli

import dev.cint.contracts.ports.ProviderMode

enum class CliFormat {
    JSON,
    NDJSON,
    ;

    companion object {
        fun parse(raw: String): CliFormat? =
            entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
    }
}

data class CliOptions(
    val root: String,
    val language: String,
    val languageExplicit: Boolean,
    val providerMode: ProviderMode?,
    val providerCommand: String?,
    val providerEndpoint: String?,
    val providerTimeoutMs: Long?,
    val knowledgePath: String?,
    val initializeKnowledge: Boolean,
    val outputPath: String?,
    val forceFullScan: Boolean,
    val includeUntaggedFiles: Boolean,
    val format: CliFormat,
)

sealed interface CliInvocation {
    data class Run(val options: CliOptions) : CliInvocation

    data class Help(val text: String) : CliInvocation

    data class Version(val text: String) : CliInvocation
}

object CliOptionsParser {
    const val VERSION = "0.1.0"
    const val EXECUTABLE = "cint-v2"

    val USAGE: String =
        """
        usage: $EXECUTABLE <root> [options]

        Analyze a source root through the V2 runtime and emit JSON.

        Options:
          --root <path>                 Analysis root or file (also positional)
          --lang, --language <name>     Language id (default: kotlin)
          --provider, --oracle <mode>   Semantic provider mode: managed|attached
          --provider-command <command>  Command for managed provider mode
          --provider-endpoint <value>   Endpoint for attached provider mode
          --provider-timeout-ms <ms>    Semantic provider request timeout
          --knowledge <path>            Knowledge store file (default: <root>/.cint/knowledge.json)
          --init, --init-knowledge      Initialize/materialize project knowledge defaults
          --out, -o <path>              Write the analysis document to a file
          --format <json|ndjson>        Output format (default: json)
          --full-rescan, --force        Invalidate indexed targets before analysis
          --all                         Include files with no annotations or candidates
          --no-lsp                      Rejected: V2 always requires a semantic provider
          --help, -h                    Show this help
          --version                     Show the CLI version
        """.trimIndent()

    fun parse(argv: Array<String>): CliInvocation {
        if (argv.any { it == "--help" || it == "-h" }) return CliInvocation.Help(USAGE)
        if (argv.any { it == "--version" }) return CliInvocation.Version("$EXECUTABLE $VERSION")

        var positional: String? = null
        var rootOption: String? = null
        var language = "kotlin"
        var languageExplicit = false
        var providerMode: ProviderMode? = null
        var providerCommand: String? = null
        var providerEndpoint: String? = null
        var providerTimeoutMs: Long? = null
        var knowledgePath: String? = null
        var initializeKnowledge = false
        var outputPath: String? = null
        var forceFullScan = false
        var includeUntaggedFiles = false
        var format = CliFormat.JSON
        var noLsp = false

        var index = 0
        while (index < argv.size) {
            val raw = argv[index]
            val (name, inlineValue) = splitOption(raw)
            val nextValue = {
                inlineValue ?: argv.getOrNull(index + 1)?.also { index += 1 }
                    ?: throw CliFailure.invalidArguments("Missing value for $name", name)
            }
            when (name) {
                "--root" -> {
                    if (rootOption != null) throw CliFailure.invalidArguments("--root may be specified only once", name)
                    rootOption = nextValue()
                }

                "--lang", "--language" -> {
                    language = nextValue()
                    languageExplicit = true
                }

                "--provider", "--oracle" -> providerMode = parseProviderMode(nextValue())
                "--provider-command", "--oracle-command" -> providerCommand = nextValue()
                "--provider-endpoint", "--oracle-endpoint" -> providerEndpoint = nextValue()
                "--provider-timeout-ms" -> {
                    val rawValue = nextValue()
                    providerTimeoutMs =
                        rawValue.toLongOrNull()?.takeIf { it > 0 }
                            ?: throw CliFailure.invalidArguments(
                                "Provider timeout must be a positive integer",
                                name,
                            )
                }

                "--knowledge", "--knowledge-path" -> knowledgePath = nextValue()
                "--init", "--init-knowledge", "--materialize-defaults" -> initializeKnowledge = true
                "--out", "-o" -> outputPath = nextValue()
                "--format" -> {
                    format =
                        CliFormat.parse(nextValue())
                            ?: throw CliFailure.invalidArguments(
                                "Unsupported format; expected json or ndjson",
                                name,
                            )
                }

                "--full-rescan", "--force", "--rescan" -> forceFullScan = true
                "--all" -> includeUntaggedFiles = true
                "--no-lsp" -> noLsp = true
                else -> {
                    if (raw.startsWith("-")) {
                        throw CliFailure.invalidArguments("Unknown flag: $raw", raw)
                    }
                    if (positional != null) {
                        throw CliFailure.invalidArguments("Only one root may be specified", "root")
                    }
                    positional = raw
                }
            }
            index += 1
        }

        if (noLsp) {
            throw CliFailure.missingProvider(
                "--no-lsp is not supported: V2 requires a semantic provider; use --provider managed|attached",
            )
        }
        val root = rootOption ?: positional ?: throw CliFailure.invalidArguments("Missing analysis root", "root")
        val canonicalLanguage =
            CliLanguages.canonical(language)
                ?: throw CliFailure.invalidArguments(
                    "Unsupported language '$language'; expected one of kotlin, javascript, typescript, vue, csharp, c",
                    "language",
                )
        if (rootOption != null && positional != null) {
            throw CliFailure.invalidArguments("Root specified both positionally and with --root", "root")
        }
        if (root.isBlank()) throw CliFailure.invalidArguments("Analysis root must not be blank", "root")
        if (language.isBlank()) throw CliFailure.invalidArguments("Language must not be blank", "language")
        if (knowledgePath?.isBlank() == true) {
            throw CliFailure.invalidArguments("Knowledge path must not be blank", "knowledge")
        }
        if (knowledgePath != null && !initializeKnowledge) {
            throw CliFailure.invalidArguments("--knowledge requires --init", "knowledge")
        }
        if (outputPath?.isBlank() == true) {
            throw CliFailure.invalidArguments("Output path must not be blank", "out")
        }
        if (providerCommand?.isBlank() == true) {
            throw CliFailure.invalidArguments("Provider command must not be blank", "provider-command")
        }
        if (providerEndpoint?.isBlank() == true) {
            throw CliFailure.invalidArguments("Provider endpoint must not be blank", "provider-endpoint")
        }

        return CliInvocation.Run(
            CliOptions(
                root = root,
                language = canonicalLanguage,
                languageExplicit = languageExplicit,
                providerMode = providerMode,
                providerCommand = providerCommand,
                providerEndpoint = providerEndpoint,
                providerTimeoutMs = providerTimeoutMs,
                knowledgePath = knowledgePath,
                initializeKnowledge = initializeKnowledge,
                outputPath = outputPath,
                forceFullScan = forceFullScan,
                includeUntaggedFiles = includeUntaggedFiles,
                format = format,
            ),
        )
    }

    private fun splitOption(raw: String): Pair<String, String?> {
        if (!raw.startsWith("--")) return raw to null
        val separator = raw.indexOf('=')
        if (separator <= 2) return raw to null
        return raw.substring(0, separator) to raw.substring(separator + 1)
    }

    private fun parseProviderMode(raw: String): ProviderMode =
        when {
            raw.trim().equals("managed", ignoreCase = true) -> ProviderMode.MANAGED
            raw.trim().equals("attached", ignoreCase = true) -> ProviderMode.ATTACHED
            else -> throw CliFailure.invalidArguments(
                "Unsupported provider mode '$raw'; expected managed or attached",
                "provider",
            )
        }
}
