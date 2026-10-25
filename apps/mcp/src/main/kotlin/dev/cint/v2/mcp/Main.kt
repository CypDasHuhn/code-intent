package dev.cint.v2.mcp

import dev.cint.contracts.ports.ProviderMode
import dev.cint.contracts.ports.SemanticProviderConfig
import dev.cint.runtime.InitCheckpointStores
import dev.cint.runtime.RuntimeConfig
import dev.cint.runtime.RuntimeFactory
import kotlin.system.exitProcess

object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        val options =
            try {
                parseOptions(args)
            } catch (error: IllegalArgumentException) {
                System.err.println("cint-mcp: ${error.message}")
                System.err.println(USAGE)
                exitProcess(2)
            }

        if (options.help) {
            println(USAGE)
            return
        }
        if (options.version) {
            println("code-intent-mcp 0.1.0")
            return
        }

        if (options.root == null) {
            System.err.println(
                "cint-mcp: no --root; init checkpoints are in-memory and will not survive restarts",
            )
        }
        val runtime = RuntimeFactory.create(options.toRuntimeConfig())
        if (runtime.isFailure) {
            System.err.println("cint-mcp: unable to start runtime: ${runtime.errorOrNull}")
            exitProcess(2)
        }
        val checkpoints =
            InitCheckpointStores.open(
                rootPath = options.root,
                checkpointPath = options.checkpoint,
            )
        if (checkpoints.isFailure) {
            System.err.println("cint-mcp: unable to open init checkpoints: ${checkpoints.errorOrNull}")
            exitProcess(2)
        }
        McpServer(
            runtime = runtime.getOrThrow(),
            checkpointStore = checkpoints.getOrThrow(),
            workflowId = DEFAULT_WORKFLOW_ID,
        ).run()
    }
}

internal data class Options(
    val root: String? = null,
    val knowledge: String? = null,
    val checkpoint: String? = null,
    val help: Boolean = false,
    val version: Boolean = false,
    val providerCommands: Map<String, String> = emptyMap(),
    val providerConfigs: Map<String, SemanticProviderConfig> = emptyMap(),
) {
    fun toRuntimeConfig(): RuntimeConfig =
        RuntimeConfig(
            rootPath = root,
            knowledgePath = knowledge,
            detectLanguages = true,
            materializeDefaultsOnOpen = false,
            providerCommands = RuntimeConfig.DEFAULT_PROVIDER_COMMANDS + providerCommands,
            providerConfigs = providerConfigs,
        )
}

internal fun parseOptions(args: Array<String>): Options {
    var root: String? = null
    var knowledge: String? = null
    var checkpoint: String? = null
    var providerConfig: String? = null
    var help = false
    var version = false
    val providerCommands = linkedMapOf<String, String>()
    val providerModes = linkedMapOf<String, ProviderMode>()
    val providerEndpoints = linkedMapOf<String, String>()
    var index = 0
    while (index < args.size) {
        val argument = args[index]
        when {
            argument == "--help" || argument == "-h" -> {
                help = true
            }

            argument == "--version" -> {
                version = true
            }

            argument == "--root" -> {
                root = args.getOrNull(++index) ?: throw IllegalArgumentException("--root requires a path")
            }

            argument.startsWith("--root=") -> {
                root = argument.substringAfter("=")
            }

            argument == "--knowledge" -> {
                knowledge =
                    args.getOrNull(++index) ?: throw IllegalArgumentException("--knowledge requires a path")
            }

            argument.startsWith("--knowledge=") -> {
                knowledge = argument.substringAfter("=")
            }

            argument == "--checkpoint" -> {
                checkpoint =
                    args.getOrNull(++index) ?: throw IllegalArgumentException("--checkpoint requires a path")
            }

            argument.startsWith("--checkpoint=") -> {
                checkpoint = argument.substringAfter("=")
            }

            argument == "--provider-config" -> {
                providerConfig =
                    args.getOrNull(++index)
                        ?: throw IllegalArgumentException("--provider-config requires a path")
            }

            argument.startsWith("--provider-config=") -> {
                providerConfig = argument.substringAfter("=")
            }

            argument == "--provider-command" -> {
                val (language, command) =
                    McpProviderConfigLoader.parseAssignment(
                        args.getOrNull(++index)
                            ?: throw IllegalArgumentException("--provider-command requires LANGUAGE=COMMAND"),
                        "--provider-command",
                    )
                providerCommands[language] = command
            }

            argument.startsWith("--provider-command=") -> {
                val (language, command) =
                    McpProviderConfigLoader.parseAssignment(
                        argument.substringAfter("="),
                        "--provider-command",
                    )
                providerCommands[language] = command
            }

            argument == "--provider-mode" -> {
                val (language, mode) =
                    McpProviderConfigLoader.parseAssignment(
                        args.getOrNull(++index)
                            ?: throw IllegalArgumentException("--provider-mode requires LANGUAGE=MODE"),
                        "--provider-mode",
                    )
                providerModes[language] = McpProviderConfigLoader.parseMode(mode, language)
            }

            argument.startsWith("--provider-mode=") -> {
                val (language, mode) =
                    McpProviderConfigLoader.parseAssignment(
                        argument.substringAfter("="),
                        "--provider-mode",
                    )
                providerModes[language] = McpProviderConfigLoader.parseMode(mode, language)
            }

            argument == "--provider-endpoint" -> {
                val (language, endpoint) =
                    McpProviderConfigLoader.parseAssignment(
                        args.getOrNull(++index)
                            ?: throw IllegalArgumentException("--provider-endpoint requires LANGUAGE=ENDPOINT"),
                        "--provider-endpoint",
                    )
                providerEndpoints[language] = endpoint
            }

            argument.startsWith("--provider-endpoint=") -> {
                val (language, endpoint) =
                    McpProviderConfigLoader.parseAssignment(
                        argument.substringAfter("="),
                        "--provider-endpoint",
                    )
                providerEndpoints[language] = endpoint
            }

            argument.startsWith("-") -> {
                throw IllegalArgumentException("unknown argument '$argument'")
            }

            root == null -> {
                root = argument
            }

            else -> {
                throw IllegalArgumentException("unexpected argument '$argument'")
            }
        }
        index += 1
    }
    if (root != null && root.isBlank()) {
        throw IllegalArgumentException("--root must not be blank")
    }
    if (knowledge != null && knowledge.isBlank()) {
        throw IllegalArgumentException("--knowledge must not be blank")
    }
    if (checkpoint != null && checkpoint.isBlank()) {
        throw IllegalArgumentException("--checkpoint must not be blank")
    }
    if (providerConfig != null && providerConfig.isBlank()) {
        throw IllegalArgumentException("--provider-config must not be blank")
    }
    val file = providerConfig?.let(McpProviderConfigLoader::load) ?: McpProviderConfiguration()
    val provider = McpProviderConfigLoader.resolve(file, providerCommands, providerModes, providerEndpoints)
    return Options(
        root = root,
        knowledge = knowledge,
        checkpoint = checkpoint,
        help = help,
        version = version,
        providerCommands = provider.providerCommands,
        providerConfigs = provider.providerConfigs,
    )
}

private const val DEFAULT_WORKFLOW_ID = "repository"

private const val USAGE =
    "Usage: cint-mcp [--root PATH] [--knowledge PATH] [--checkpoint PATH]\n" +
        "                [--provider-config PATH]\n" +
        "                [--provider-command LANGUAGE=COMMAND]...\n" +
        "                [--provider-mode LANGUAGE=managed|attached|editor]...\n" +
        "                [--provider-endpoint LANGUAGE=ENDPOINT]...\n" +
        "  --root PATH        Repository root used for knowledge and analysis\n" +
        "  --knowledge PATH   Explicit knowledge store path\n" +
        "  --checkpoint PATH  Explicit init checkpoint path\n" +
        "                     Without --root or --checkpoint, init state is in-memory only\n" +
        "  --provider-config PATH  JSON provider configuration for one or more languages\n" +
        "  --provider-command LANGUAGE=COMMAND  Managed provider command override\n" +
        "  --provider-mode LANGUAGE=MODE        Managed, attached, or editor provider mode\n" +
        "  --provider-endpoint LANGUAGE=ENDPOINT  Attached provider endpoint override\n" +
        "  --version          Print the server version\n" +
        "  --help             Print this help\n" +
        "Kotlin keeps its managed kotlin-lsp default unless overridden.\n" +
        "Speaks MCP JSON-RPC over stdin/stdout."
