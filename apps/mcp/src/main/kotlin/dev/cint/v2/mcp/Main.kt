package dev.cint.v2.mcp

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
        val runtime =
            RuntimeFactory.create(
                RuntimeConfig(
                    rootPath = options.root,
                    knowledgePath = options.knowledge,
                    detectLanguages = true,
                    materializeDefaultsOnOpen = false,
                ),
            )
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

private data class Options(
    val root: String? = null,
    val knowledge: String? = null,
    val checkpoint: String? = null,
    val help: Boolean = false,
    val version: Boolean = false,
)

private fun parseOptions(args: Array<String>): Options {
    var root: String? = null
    var knowledge: String? = null
    var checkpoint: String? = null
    var help = false
    var version = false
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
    return Options(
        root = root,
        knowledge = knowledge,
        checkpoint = checkpoint,
        help = help,
        version = version,
    )
}

private const val DEFAULT_WORKFLOW_ID = "repository"

private const val USAGE =
    "Usage: cint-mcp [--root PATH] [--knowledge PATH] [--checkpoint PATH]\n" +
        "  --root PATH        Repository root used for knowledge and analysis\n" +
        "  --knowledge PATH   Explicit knowledge store path\n" +
        "  --checkpoint PATH  Explicit init checkpoint path\n" +
        "                     Without --root or --checkpoint, init state is in-memory only\n" +
        "  --version          Print the server version\n" +
        "  --help             Print this help\n" +
        "Speaks MCP JSON-RPC over stdin/stdout."
