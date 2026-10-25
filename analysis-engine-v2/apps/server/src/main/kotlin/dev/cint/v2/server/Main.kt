@file:JvmName("Main")

package dev.cint.v2.server

import dev.cint.contracts.ports.Runtime
import dev.cint.runtime.RuntimeConfig
import dev.cint.runtime.RuntimeFactory
import java.nio.file.Path
import kotlin.system.exitProcess

fun main(argv: Array<String>) {
    val parsed = parseArguments(argv)
    if (parsed == null) {
        System.err.println("usage: cint-server-v2 --socket <path> [--root <path>] [--lang <language>]")
        exitProcess(2)
    }
    val runtime = createRuntime(parsed.root)
    if (runtime == null) {
        exitProcess(1)
    }
    val server = LiveServer(runtime, parsed)
    server.run()
    server.runtimeShutdownFailure()?.let { failure ->
        System.err.println("cint-server-v2 runtime shutdown failed: $failure")
        exitProcess(1)
    }
}

internal fun parseArguments(argv: Array<String>): ServerOptions? {
    var socket: String? = null
    var root = Path.of(".").toAbsolutePath().normalize()
    var language: String? = null
    var index = 0
    while (index < argv.size) {
        when (val argument = argv[index]) {
            "--socket" -> {
                index += 1
                if (index >= argv.size) return null
                socket = argv[index]
            }

            "--root" -> {
                index += 1
                if (index >= argv.size) return null
                root = Path.of(argv[index]).toAbsolutePath().normalize()
            }

            "--lang", "--language" -> {
                index += 1
                if (index >= argv.size) return null
                language = argv[index]
            }

            else -> {
                System.err.println("unknown flag: $argument")
                return null
            }
        }
        index += 1
    }
    val socketPath = socket?.takeIf { it.isNotBlank() } ?: return null
    return ServerOptions(
        socketPath = Path.of(socketPath).toAbsolutePath().normalize(),
        root = root,
        defaultLanguage = language,
    )
}

internal fun createRuntime(root: Path): Runtime? {
    val created =
        RuntimeFactory.create(
            RuntimeConfig(
                rootPath = root.toString(),
                materializeDefaultsOnOpen = false,
                applyUnmaterializedDefaults = false,
            ),
        )
    if (created.isFailure) {
        System.err.println("unable to create runtime: ${created.errorOrNull}")
        return null
    }
    return created.getOrThrow()
}
