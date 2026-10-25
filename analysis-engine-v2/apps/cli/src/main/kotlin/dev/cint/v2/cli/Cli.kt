package dev.cint.v2.cli

import dev.cint.contracts.ports.AnalysisRequest
import dev.cint.contracts.ports.ProviderMode
import dev.cint.contracts.ports.Runtime
import dev.cint.contracts.ports.SemanticProviderConfig
import dev.cint.contracts.util.ContractResult
import dev.cint.runtime.RuntimeConfig
import dev.cint.runtime.RuntimeFactory
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

fun interface CliRuntimeFactory {
    fun create(config: RuntimeConfig): ContractResult<Runtime>

    companion object {
        val DEFAULT: CliRuntimeFactory = CliRuntimeFactory { RuntimeFactory.create(it) }
    }
}

data class CliEnvironment(
    val runtimeFactory: CliRuntimeFactory = CliRuntimeFactory.DEFAULT,
)

object Cli {
    private val errorJson =
        Json {
            encodeDefaults = true
            explicitNulls = false
            prettyPrint = false
        }

    @OptIn(ExperimentalSerializationApi::class)
    private val json =
        Json {
            encodeDefaults = true
            explicitNulls = false
            prettyPrint = true
            prettyPrintIndent = "  "
        }

    private val ndjson =
        Json {
            encodeDefaults = true
            explicitNulls = false
            prettyPrint = false
        }

    fun run(
        argv: Array<String>,
        stdout: PrintStream = System.out,
        stderr: PrintStream = System.err,
        environment: CliEnvironment = CliEnvironment(),
    ): Int =
        try {
            when (val invocation = CliOptionsParser.parse(argv)) {
                is CliInvocation.Help -> {
                    stdout.println(invocation.text)
                    CliExitCode.OK
                }

                is CliInvocation.Version -> {
                    stdout.println(invocation.text)
                    CliExitCode.OK
                }

                is CliInvocation.Run -> execute(invocation.options, stdout, environment)
            }
        } catch (failure: CliFailure) {
            writeError(stderr, failure)
            failure.exitCode
        } catch (error: Throwable) {
            val failure = CliFailure.internal(error.message ?: error::class.simpleName ?: "Unexpected error")
            writeError(stderr, failure)
            failure.exitCode
        }

    private fun execute(
        options: CliOptions,
        stdout: PrintStream,
        environment: CliEnvironment,
    ): Int {
        val target = resolveRoot(options.root)
        val root = if (Files.isDirectory(target)) target else target.parent ?: target
        val files = discoverFiles(target, root, options.language)
        val knowledgePath = options.knowledgePath?.let { resolvePath(root, it, "knowledge").toString() }
        val config = runtimeConfig(options, root, knowledgePath)
        val runtime =
            when (val created = environment.runtimeFactory.create(config)) {
                is dev.cint.contracts.util.Success -> created.contents
                is dev.cint.contracts.util.Failure -> throw created.error.toCliFailure()
            }

        var document: CliDocument? = null
        var primary: CliFailure? = null
        try {
            document = analyze(options, root, files, runtime)
        } catch (failure: CliFailure) {
            primary = failure
        } catch (error: Throwable) {
            primary = CliFailure.internal(error.message ?: error::class.simpleName ?: "Analysis failed")
        }
        val shutdown = runtime.shutdown()
        if (primary == null && shutdown.isFailure) {
            primary = shutdown.errorOrNull!!.toCliFailure()
        }
        primary?.let { throw it }

        val text = render(options.format, document ?: throw CliFailure.internal("No analysis document"))
        writeOutput(options.outputPath, text, stdout)
        return CliExitCode.OK
    }

    private fun analyze(
        options: CliOptions,
        root: Path,
        files: List<Path>,
        runtime: Runtime,
    ): CliDocument {
        val results = mutableListOf<AnalysisResultWithPath>()
        for (file in files) {
            val relative = relativePath(root, file)
            val hash = readHash(file)
            val request =
                AnalysisRequest(
                    targetPaths = listOf(file.toString()),
                    language = options.language,
                    options = mapOf("rootPath" to root.toString()),
                    forceFullAnalysis = options.forceFullScan,
                )
            val analyzed =
                when (val result = runtime.analyze(request)) {
                    is dev.cint.contracts.util.Success -> result.contents
                    is dev.cint.contracts.util.Failure ->
                        throw result.error.toCliFailure().withPath(relative)
                }
            results += AnalysisResultWithPath(relative, hash, analyzed)
        }

        val emitted =
            if (options.includeUntaggedFiles) {
                results
            } else {
                results.filter {
                    it.result.annotations.isNotEmpty() ||
                        it.result.candidates.isNotEmpty() ||
                        it.result.errors.isNotEmpty()
                }
            }
        return CliDocument(
            language = options.language,
            files = emitted.map { CliOutputMapper.file(it.path, it.hash, it.result) },
            meta = CliOutputMapper.meta(emitted.map { it.result }),
        )
    }

    private fun render(
        format: CliFormat,
        document: CliDocument,
    ): String =
        when (format) {
            CliFormat.JSON -> json.encodeToString(document) + "\n"
            CliFormat.NDJSON ->
                buildString {
                    for (file in document.files) {
                        append(ndjson.encodeToString(CliNdjsonFile(language = document.language, file = file)))
                        append('\n')
                    }
                    append(ndjson.encodeToString(CliNdjsonMeta(meta = document.meta)))
                    append('\n')
                }
        }

    private fun writeOutput(
        outputPath: String?,
        text: String,
        stdout: PrintStream,
    ) {
        if (outputPath == null || outputPath == "-") {
            stdout.print(text)
            return
        }
        val target =
            try {
                val path = Path.of(outputPath)
                if (path.isAbsolute) {
                    path.normalize()
                } else {
                    Path.of("").toAbsolutePath().resolve(path).normalize()
                }
            } catch (_: InvalidPathException) {
                throw CliFailure.invalidArguments("Invalid output path '$outputPath'", "out")
            }
        try {
            target.parent?.let { Files.createDirectories(it) }
            Files.writeString(target, text, StandardCharsets.UTF_8)
        } catch (error: Exception) {
            throw CliFailure.output("Unable to write output '$target': ${error.message ?: error::class.simpleName}")
        }
    }

    private fun runtimeConfig(
        options: CliOptions,
        root: Path,
        knowledgePath: String?,
    ): RuntimeConfig {
        val provider = providerConfig(options, root)
        return RuntimeConfig(
            rootPath = if (options.initializeKnowledge) root.toString() else null,
            knowledgePath = knowledgePath,
            initializeLanguages =
                if (options.initializeKnowledge && options.languageExplicit) {
                    setOf(options.language)
                } else {
                    emptySet()
                },
            detectLanguages = options.initializeKnowledge && !options.languageExplicit,
            materializeDefaultsOnOpen = options.initializeKnowledge,
            applyUnmaterializedDefaults = false,
            enableCallChains = false,
            semanticTimeoutMs = options.providerTimeoutMs ?: 5_000,
            providerConfigs = provider?.let { mapOf(options.language to it) } ?: emptyMap(),
        )
    }

    private fun providerConfig(
        options: CliOptions,
        root: Path,
    ): SemanticProviderConfig? {
        val mode =
            options.providerMode
                ?: when {
                    options.providerEndpoint != null -> ProviderMode.ATTACHED
                    options.providerCommand != null -> ProviderMode.MANAGED
                    else -> return null
                }
        val command =
            when (mode) {
                ProviderMode.MANAGED ->
                    options.providerCommand
                        ?: RuntimeConfig.DEFAULT_PROVIDER_COMMANDS[options.language.trim().lowercase()]
                else -> options.providerCommand
            }
        return SemanticProviderConfig(
            mode = mode,
            command = command,
            endpoint = options.providerEndpoint,
            rootPath = root.toString(),
            timeoutMs = options.providerTimeoutMs ?: 5_000,
        )
    }

    private fun resolveRoot(raw: String): Path {
        val path =
            try {
                Path.of(raw).toAbsolutePath().normalize()
            } catch (_: InvalidPathException) {
                throw CliFailure.rootNotFound("Invalid root path '$raw'")
            }
        if (!Files.exists(path)) throw CliFailure.rootNotFound("No such analysis root: $path")
        if (!Files.isDirectory(path) && !Files.isRegularFile(path)) {
            throw CliFailure.rootNotFound("Analysis root is not a file or directory: $path")
        }
        return path
    }

    private fun resolvePath(
        root: Path,
        raw: String,
        field: String,
    ): Path =
        try {
            val path = Path.of(raw)
            if (path.isAbsolute) path.normalize() else root.resolve(path).normalize()
        } catch (_: InvalidPathException) {
            throw CliFailure.invalidArguments("Invalid path '$raw'", field)
        }

    private fun discoverFiles(
        target: Path,
        root: Path,
        language: String,
    ): List<Path> {
        if (Files.isRegularFile(target)) return listOf(target)
        val extensions = CliLanguages.extensions(language)
        val files = mutableListOf<Path>()
        try {
            Files.walk(target).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) }
                    .filter { file -> containsSkippedDirectory(root, file) == false }
                    .filter { file -> extensionOf(file) in extensions }
                    .forEach { files.add(it) }
            }
        } catch (error: Exception) {
            throw CliFailure.analysis("Unable to scan root '$target': ${error.message ?: error::class.simpleName}")
        }
        return files.sortedBy { relativePath(root, it) }
    }

    private fun containsSkippedDirectory(
        root: Path,
        file: Path,
    ): Boolean {
        val relative =
            try {
                root.relativize(file.toAbsolutePath().normalize())
            } catch (_: IllegalArgumentException) {
                return false
            }
        for (index in 0 until (relative.nameCount - 1).coerceAtLeast(0)) {
            if (relative.getName(index).toString() in SKIPPED_DIRECTORIES) return true
        }
        return false
    }

    private fun readHash(file: Path): String =
        try {
            val canonical =
                Files.readString(file, StandardCharsets.UTF_8)
                    .replace("\r\n", "\n")
                    .removeSuffix("\n")
            dev.cint.contracts.util.ContentHash.compute(canonical).value
        } catch (error: Exception) {
            throw CliFailure.analysis("Unable to read '$file': ${error.message ?: error::class.simpleName}")
        }

    private fun relativePath(
        root: Path,
        file: Path,
    ): String {
        val base = if (Files.isDirectory(root)) root else root.parent ?: root
        return try {
            base.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/')
        } catch (_: IllegalArgumentException) {
            (file.fileName ?: file).toString()
        }
    }

    private fun extensionOf(file: Path): String =
        file.fileName?.toString()?.substringAfterLast('.', "")?.lowercase().orEmpty()

    private fun CliFailure.withPath(path: String): CliFailure =
        copy(details = details + ("path" to path))

    private fun writeError(
        stderr: PrintStream,
        failure: CliFailure,
    ) {
        stderr.println(errorJson.encodeToString(failure.asOutput()))
    }

    private data class AnalysisResultWithPath(
        val path: String,
        val hash: String,
        val result: dev.cint.contracts.data.AnalysisResult,
    )

    private val SKIPPED_DIRECTORIES =
        setOf(".git", ".gradle", ".idea", "build", "node_modules", "out", "target", ".cint")
}
