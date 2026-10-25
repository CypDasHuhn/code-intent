package dev.cint.runtime

import dev.cint.contracts.facts.FactBatch
import dev.cint.contracts.ports.RepositoryFile
import dev.cint.contracts.ports.RepositoryFilesRequest
import dev.cint.contracts.ports.RepositoryFilesResult
import dev.cint.contracts.ports.RepositorySymbolInventory
import dev.cint.contracts.ports.SymbolCall
import dev.cint.contracts.ports.SymbolDeclaration
import dev.cint.contracts.ports.SymbolImport
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory
import dev.cint.v2.languages.LanguageCatalog
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

internal object RepositoryDiscovery {
    const val MAX_SCANNED_FILES = 200_000

    val IGNORED_DIRECTORIES: List<String> =
        listOf(
            ".git",
            ".cint",
            ".gradle",
            ".idea",
            ".svn",
            ".hg",
            ".kotlin",
            ".venv",
            ".next",
            ".nuxt",
            ".cache",
            "build",
            "out",
            "target",
            "node_modules",
            "dist",
            "coverage",
            "bin",
            "obj",
        )

    private val ignoredDirectoryNames = IGNORED_DIRECTORIES.toSet()

    fun files(
        root: Path,
        catalog: LanguageCatalog,
        request: RepositoryFilesRequest,
        cancellation: CancellationToken,
    ): ContractResult<RepositoryFilesResult> {
        if (!Files.exists(root)) {
            return ResultFactory.failure(ContractError.NotFound("repository-root", root.toString()))
        }
        if (!Files.isDirectory(root)) {
            return ResultFactory.failure(
                ContractError.InvalidInput("repository root is not a directory", "rootPath"),
            )
        }
        val checked = cancellation.throwIfCancelled()
        if (checked.isFailure) return ResultFactory.failure(checked.errorOrNull!!)

        val languages =
            request.languages
                .map { it.trim().lowercase().removePrefix(".") }
                .filter { it.isNotEmpty() }
                .toSet()
        val extensions =
            request.extensions
                .map { it.trim().lowercase().removePrefix(".") }
                .filter { it.isNotEmpty() }
                .toSet()
        val limit = request.limit.coerceIn(1, RepositoryFilesRequest.MAX_FILE_LIMIT)
        val found = mutableListOf<RepositoryFile>()
        var scanned = 0
        var truncated = false
        var failure: ContractError? = null
        try {
            Files.walkFileTree(
                root,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(
                        dir: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        val cancelled = cancellation.throwIfCancelled()
                        if (cancelled.isFailure) {
                            failure = cancelled.errorOrNull
                            return FileVisitResult.TERMINATE
                        }
                        if (dir != root && dir.fileName.toString() in ignoredDirectoryNames) {
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                        val cancelled = cancellation.throwIfCancelled()
                        if (cancelled.isFailure) {
                            failure = cancelled.errorOrNull
                            return FileVisitResult.TERMINATE
                        }
                        scanned += 1
                        if (scanned > MAX_SCANNED_FILES) {
                            truncated = true
                            return FileVisitResult.TERMINATE
                        }
                        val descriptor = catalog.forPath(file.toString()) ?: return FileVisitResult.CONTINUE
                        val languageMatches =
                            languages.isEmpty() ||
                                descriptor.id.lowercase() in languages ||
                                descriptor.aliases.any { it.lowercase() in languages }
                        if (!languageMatches) return FileVisitResult.CONTINUE
                        val extension =
                            file.fileName
                                .toString()
                                .substringAfterLast('.', "")
                                .lowercase()
                        if (extensions.isNotEmpty() && extension !in extensions) {
                            return FileVisitResult.CONTINUE
                        }
                        found +=
                            RepositoryFile(
                                path = root.relativize(file).toString().replace('\\', '/'),
                                absolutePath = file.toAbsolutePath().normalize().toString(),
                                language = descriptor.id,
                                extension = extension,
                            )
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        } catch (error: Exception) {
            return ResultFactory.failure(
                ContractError.InternalError(
                    "Unable to scan repository files",
                    error.message ?: error::class.simpleName,
                ),
            )
        }
        failure?.let { return ResultFactory.failure(it) }

        val ordered = found.sortedWith(compareBy({ it.path }, { it.absolutePath }))
        if (ordered.size > limit) truncated = true
        val files = ordered.take(limit)
        return ResultFactory.success(
            RepositoryFilesResult(
                root = root.toString(),
                files = files,
                count = files.size,
                truncated = truncated,
                ignoredDirectories = IGNORED_DIRECTORIES,
            ),
        )
    }

    fun symbols(
        path: String,
        language: String?,
        facts: FactBatch,
    ): RepositorySymbolInventory {
        val tree = facts.syntaxTree
        val functionsById = facts.functions.associateBy { it.nodeId }
        val declarations =
            facts.declarations
                .mapNotNull { declaration ->
                    val node = tree?.getNode(declaration.nodeId) ?: return@mapNotNull null
                    val function = functionsById[node.id]
                    SymbolDeclaration(
                        nodeId = node.id,
                        name = declaration.name ?: function?.name,
                        kind = declaration.kind.name,
                        span = node.span,
                        startByte = node.startByte,
                        endByte = node.endByte,
                        declaringType = function?.declaringType,
                        signature = function?.signature,
                    )
                }.sortedWith(compareBy({ it.startByte }, { it.endByte }, { it.nodeId }))
        val calls =
            facts.calls
                .mapNotNull { call ->
                    val node = tree?.getNode(call.nodeId) ?: tree?.getNode(call.siteId) ?: return@mapNotNull null
                    SymbolCall(
                        nodeId = node.id,
                        callee = call.callee,
                        path = call.path,
                        span = node.span,
                        startByte = node.startByte,
                        endByte = node.endByte,
                        receiverType = call.receiverType?.takeIf { it.isNotBlank() },
                        declaringType = call.resolvedTarget?.takeIf { it.isNotBlank() },
                    )
                }.sortedWith(compareBy({ it.startByte }, { it.endByte }, { it.nodeId }))
        val imports =
            facts.imports
                .map { import ->
                    SymbolImport(
                        nodeId = import.nodeId,
                        path = import.path,
                        alias = import.alias,
                        span = tree?.getNode(import.nodeId)?.span,
                    )
                }.sortedWith(compareBy({ it.path }, { it.nodeId }))
        return RepositorySymbolInventory(
            path = path,
            language = language ?: tree?.language,
            declarations = declarations,
            calls = calls,
            imports = imports,
        )
    }
}
