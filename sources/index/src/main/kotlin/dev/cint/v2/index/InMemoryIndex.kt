package dev.cint.v2.index

import dev.cint.contracts.data.Completeness
import dev.cint.contracts.facts.FactBatch
import dev.cint.contracts.facts.IndexedSource
import dev.cint.contracts.facts.SemanticFact
import dev.cint.contracts.facts.SemanticRequest
import dev.cint.contracts.facts.SemanticRequestKind
import dev.cint.contracts.facts.SemanticResult
import dev.cint.contracts.facts.SourceInput
import dev.cint.contracts.ports.IndexInput
import dev.cint.contracts.ports.LanguageSupport
import dev.cint.contracts.ports.RescanResult
import dev.cint.contracts.ports.SemanticProvider
import dev.cint.contracts.ports.SyntaxEngine
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContentHash
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.Deadline
import dev.cint.contracts.util.Failure
import dev.cint.contracts.util.ResultFactory
import dev.cint.contracts.util.Success
import dev.cint.contracts.util.Unavailable
import dev.cint.contracts.util.UnavailableReason
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit

class InMemoryIndex(
    private val plan: IndexPlan,
    private val semanticTimeoutMs: Long = 5_000,
) : IncrementalIndex {
    private data class FileMetadata(
        val modifiedNanos: Long,
        val size: Long,
    )

    private data class Entry(
        val input: SourceInput,
        val contentHash: String,
        val metadata: FileMetadata?,
        val facts: FactBatch,
        val semanticFacts: List<SemanticFact>,
        val merkle: MerkleState,
        val sourceEpoch: Long,
        val stale: Boolean,
        val dependencies: Set<String>,
        val syntax: SyntaxEngine,
        val provider: SemanticProvider,
        val syntaxLanguage: String,
        val providerLanguage: String,
    ) {
        fun toIndexedSource(): IndexedSource =
            IndexedSource(
                input = input,
                facts = facts,
                sourceEpoch = sourceEpoch,
                completeness = if (stale) Completeness.STALE else Completeness.COMPLETE,
                contentHash = contentHash,
                semanticFacts = semanticFacts,
            )
    }

    private val entries = linkedMapOf<String, Entry>()
    private val reverseDependencies = linkedMapOf<String, MutableSet<String>>()
    private val lastChanges = linkedMapOf<String, NodeChange>()
    private val providerEpochs = IdentityHashMap<SemanticProvider, Long>()
    private var providerEpochCounter = 0L
    private var epoch = 0L
    private var rescanning = false

    override fun ensure(
        input: SourceInput,
        support: LanguageSupport,
        cancellation: CancellationToken,
    ): ContractResult<IndexedSource> {
        cancelled(cancellation, "Index.ensure")?.let { return failure(it) }
        if (plan.semanticRequests.isEmpty()) {
            return failure(
                ContractError.ValidationError(
                    "Index plan must request at least one semantic fact",
                    field = "plan.semanticRequests",
                ),
            )
        }
        validateInput(input)?.let { return failure(it) }
        val syntax =
            when (val result = resolveSyntax(support)) {
                is Success -> result.contents
                is Failure -> return failure(result.error)
            }
        val semantic =
            when (val result = resolveSemanticProvider(support)) {
                is Success -> result.contents
                is Failure -> return failure(result.error)
            }
        return when (input) {
            is SourceInput.FilePath -> ensureFile(input, syntax, semantic, cancellation)
            is SourceInput.InMemory -> ensureInMemory(input, syntax, semantic, cancellation)
        }
    }

    private fun ensureInMemory(
        input: SourceInput.InMemory,
        syntax: SyntaxEngine,
        semantic: SemanticProvider,
        cancellation: CancellationToken,
    ): ContractResult<IndexedSource> {
        val bytes = input.text.encodeToByteArray()
        val contentHash = ContentHash.compute(bytes)
        val key = keyFor(input)
        val cached = entries[key]
        if (cached != null && cached.contentHash == contentHash.value) {
            return reuseOrRefresh(
                key,
                input,
                null,
                cached,
                bytes,
                contentHash,
                syntax,
                semantic,
                cancellation,
            )
        }
        val previous = previousTree(cached, syntax)
        return build(key, input, null, bytes, contentHash, previous, syntax, semantic, cancellation)
    }

    private fun ensureFile(
        input: SourceInput.FilePath,
        syntax: SyntaxEngine,
        semantic: SemanticProvider,
        cancellation: CancellationToken,
    ): ContractResult<IndexedSource> {
        if (input.path.isBlank()) {
            return failure(ContractError.ValidationError("Source path is blank", field = "path"))
        }
        val metadata =
            when (val result = readMetadata(input.path)) {
                is Success -> result.contents
                is Failure -> return failure(result.error)
            }
        val bytes =
            when (val result = readBytes(input.path)) {
                is Success -> result.contents
                is Failure -> return failure(result.error)
            }
        cancelled(cancellation, "Index.ensure")?.let { return failure(it) }
        val contentHash = ContentHash.compute(bytes)
        val key = keyFor(input)
        val cached = entries[key]
        if (cached != null && cached.contentHash == contentHash.value) {
            return reuseOrRefresh(
                key,
                input,
                metadata,
                cached,
                bytes,
                contentHash,
                syntax,
                semantic,
                cancellation,
            )
        }
        val previous = previousTree(cached, syntax)
        return build(
            key,
            input,
            metadata,
            bytes,
            contentHash,
            previous,
            syntax,
            semantic,
            cancellation,
        )
    }

    private fun previousTree(
        cached: Entry?,
        syntax: SyntaxEngine,
    ): Entry? =
        if (cached != null && cached.syntax === syntax && cached.syntaxLanguage == syntax.language) {
            cached
        } else {
            null
        }

    private fun reuseOrRefresh(
        key: String,
        input: SourceInput,
        metadata: FileMetadata?,
        cached: Entry,
        bytes: ByteArray,
        contentHash: ContentHash,
        syntax: SyntaxEngine,
        semantic: SemanticProvider,
        cancellation: CancellationToken,
    ): ContractResult<IndexedSource> {
        val sameSupport = sameSupport(cached, syntax, semantic)
        if (!sameSupport) {
            val previous = previousTree(cached, syntax)
            return build(
                key,
                input,
                metadata,
                bytes,
                contentHash,
                previous,
                syntax,
                semantic,
                cancellation,
            )
        }
        if (cached.stale || cached.input.version != input.version) {
            return refreshSemantic(key, input, bytes, metadata, cached, semantic, cancellation)
        }
        val entry = cached.copy(input = input, metadata = metadata ?: cached.metadata)
        entries[key] = entry
        return success(entry.toIndexedSource())
    }

    private fun refreshSemantic(
        key: String,
        input: SourceInput,
        bytes: ByteArray,
        metadata: FileMetadata?,
        cached: Entry,
        semantic: SemanticProvider,
        cancellation: CancellationToken,
    ): ContractResult<IndexedSource> {
        val semanticFacts =
            when (val result = requestSemantic(input, bytes, semantic, cancellation)) {
                is Success -> result.contents
                is Failure -> return failure(result.error)
            }
        cancelled(cancellation, "Index.ensure")?.let { return failure(it) }
        val entry =
            cached.copy(
                input = input,
                metadata = metadata ?: cached.metadata,
                semanticFacts = semanticFacts,
                sourceEpoch = nextEpoch(),
                stale = false,
            )
        entries[key] = entry
        return success(entry.toIndexedSource())
    }

    private fun build(
        key: String,
        input: SourceInput,
        metadata: FileMetadata?,
        bytes: ByteArray,
        contentHash: ContentHash,
        previous: Entry?,
        syntax: SyntaxEngine,
        semantic: SemanticProvider,
        cancellation: CancellationToken,
    ): ContractResult<IndexedSource> {
        cancelled(cancellation, "Index.ensure")?.let { return failure(it) }
        val tree =
            when (
                val result = syntax.parse(bytes, previous?.facts?.syntaxTree, cancellation)
            ) {
                is Success -> result.contents
                is Failure -> return failure(result.error)
            }
        cancelled(cancellation, "Index.ensure")?.let { return failure(it) }
        val merkle =
            when (
                val result =
                    buildMerkle(
                        tree = tree,
                        expectedContentHash = contentHash,
                        expectedLanguage = syntax.language,
                        syntax = syntax,
                        cancellation = cancellation,
                    )
            ) {
                is Success -> result.contents
                is Failure -> return failure(result.error)
            }
        cancelled(cancellation, "Index.ensure")?.let { return failure(it) }
        val facts =
            when (
                val result = syntax.facts(tree, plan.syntaxQueryIds, cancellation)
            ) {
                is Success -> result.contents
                is Failure -> return failure(result.error)
            }
        val factTree = facts.syntaxTree
        if (factTree != null && factTree != tree) {
            return failure(
                ContractError.ValidationError(
                    "Syntax facts refer to a different syntax tree",
                    field = "facts.syntaxTree",
                ),
            )
        }
        val normalizedFacts = if (factTree == null) facts.copy(syntaxTree = tree) else facts
        cancelled(cancellation, "Index.ensure")?.let { return failure(it) }
        val semanticFacts =
            when (val result = requestSemantic(input, bytes, semantic, cancellation)) {
                is Success -> result.contents
                is Failure -> return failure(result.error)
            }
        cancelled(cancellation, "Index.ensure")?.let { return failure(it) }

        val dependencies = dependencyKeys(normalizedFacts, input)
        val change =
            changeBetween(
                previous?.merkle?.nodeHashes ?: emptyMap(),
                merkle.nodeHashes,
            )
        val entry =
            Entry(
                input = input,
                contentHash = contentHash.value,
                metadata = metadata,
                facts = normalizedFacts,
                semanticFacts = semanticFacts,
                merkle = merkle,
                sourceEpoch = nextEpoch(),
                stale = false,
                dependencies = dependencies,
                syntax = syntax,
                provider = semantic,
                syntaxLanguage = syntax.language,
                providerLanguage = semantic.language,
            )
        val previousDependencies = previous?.dependencies ?: emptySet()
        entries[key] = entry
        lastChanges[key] = change
        replaceDependencies(key, previousDependencies, dependencies)
        if (!rescanning) {
            pathFor(input)?.let { markDependentsStale(it, key) }
        }
        return success(entry.toIndexedSource())
    }

    override fun invalidate(
        paths: List<String>,
        cancellation: CancellationToken,
    ): ContractResult<Unit> {
        cancelled(cancellation, "Index.invalidate")?.let { return failure(it) }
        for (path in paths) {
            cancelled(cancellation, "Index.invalidate")?.let { return failure(it) }
            val canonical = canonicalPath(path) ?: path
            val key = keyForPath(canonical)
            val entry = entries.remove(key)
            if (entry != null) {
                entry.dependencies.forEach { dependency ->
                    reverseDependencies[dependency]?.let { dependents ->
                        dependents.remove(key)
                        if (dependents.isEmpty()) reverseDependencies.remove(dependency)
                    }
                }
                lastChanges.remove(key)
            }
            markDependentsStale(canonical, key)
        }
        return success(Unit)
    }

    override fun snapshot(
        path: String,
        cancellation: CancellationToken,
    ): ContractResult<IndexedSource?> {
        cancelled(cancellation, "Index.snapshot")?.let { return failure(it) }
        val canonical = canonicalPath(path) ?: path
        return success(entries[keyForPath(canonical)]?.toIndexedSource())
    }

    override fun snapshotSource(
        input: SourceInput,
        cancellation: CancellationToken,
    ): ContractResult<IndexedSource?> {
        cancelled(cancellation, "Index.snapshotSource")?.let { return failure(it) }
        validateInput(input)?.let { return failure(it) }
        return success(entries[keyFor(input)]?.toIndexedSource())
    }

    override fun dependentsOf(
        path: String,
        cancellation: CancellationToken,
    ): ContractResult<List<String>> {
        cancelled(cancellation, "Index.dependentsOf")?.let { return failure(it) }
        val canonical = canonicalPath(path) ?: path
        val dependents = linkedSetOf<String>()
        val seen = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.addAll(reverseDependencies[canonical] ?: emptySet())
        while (queue.isNotEmpty()) {
            val key = queue.removeFirst()
            if (!seen.add(key)) continue
            val entry = entries[key] ?: continue
            val dependentPath = pathFor(entry.input)
            if (dependentPath != null) {
                dependents += dependentPath
                queue.addAll(reverseDependencies[dependentPath] ?: emptySet())
            } else {
                dependents += key
            }
        }
        return success(dependents.sorted())
    }

    override fun contentHash(
        path: String,
        cancellation: CancellationToken,
    ): ContractResult<String?> {
        cancelled(cancellation, "Index.contentHash")?.let { return failure(it) }
        val bytes =
            when (val result = readBytes(path)) {
                is Success -> result.contents
                is Failure -> return failure(result.error)
            }
        return success(ContentHash.compute(bytes).value)
    }

    override fun fullRescan(
        inputs: List<IndexInput>,
        cancellation: CancellationToken,
    ): ContractResult<RescanResult> {
        cancelled(cancellation, "Index.fullRescan")?.let { return failure(it) }
        if (plan.semanticRequests.isEmpty()) {
            return failure(
                ContractError.ValidationError(
                    "Index plan must request at least one semantic fact",
                    field = "plan.semanticRequests",
                ),
            )
        }
        val staged = InMemoryIndex(plan, semanticTimeoutMs)
        staged.epoch = epoch
        staged.providerEpochCounter = providerEpochCounter
        staged.providerEpochs.putAll(providerEpochs)
        staged.rescanning = true
        val sources = ArrayList<IndexedSource>(inputs.size)
        for (indexInput in inputs) {
            cancelled(cancellation, "Index.fullRescan")?.let { return failure(it) }
            val result = staged.ensure(indexInput.input, indexInput.support, cancellation)
            when (result) {
                is Success -> sources += result.contents
                is Failure -> return failure(result.error)
            }
        }
        entries.clear()
        entries.putAll(staged.entries)
        reverseDependencies.clear()
        reverseDependencies.putAll(staged.reverseDependencies)
        lastChanges.clear()
        lastChanges.putAll(staged.lastChanges)
        providerEpochs.clear()
        providerEpochs.putAll(staged.providerEpochs)
        providerEpochCounter = staged.providerEpochCounter
        epoch = staged.epoch
        return success(RescanResult(sources))
    }

    override fun nodeHashes(
        path: String,
        cancellation: CancellationToken,
    ): ContractResult<Map<String, String>?> {
        cancelled(cancellation, "Index.nodeHashes")?.let { return failure(it) }
        val canonical = canonicalPath(path) ?: path
        return success(nodeHashes(entries[keyForPath(canonical)]))
    }

    override fun nodeHashesOf(
        input: SourceInput,
        cancellation: CancellationToken,
    ): ContractResult<Map<String, String>?> {
        cancelled(cancellation, "Index.nodeHashesOf")?.let { return failure(it) }
        validateInput(input)?.let { return failure(it) }
        return success(nodeHashes(entries[keyFor(input)]))
    }

    override fun lastChange(
        path: String,
        cancellation: CancellationToken,
    ): ContractResult<NodeChange?> {
        cancelled(cancellation, "Index.lastChange")?.let { return failure(it) }
        val canonical = canonicalPath(path) ?: path
        return success(lastChanges[keyForPath(canonical)])
    }

    override fun lastChangeOf(
        input: SourceInput,
        cancellation: CancellationToken,
    ): ContractResult<NodeChange?> {
        cancelled(cancellation, "Index.lastChangeOf")?.let { return failure(it) }
        validateInput(input)?.let { return failure(it) }
        return success(lastChanges[keyFor(input)])
    }

    override fun keyOf(input: SourceInput): ContractResult<String> = validateInput(input)?.let { failure(it) } ?: success(keyFor(input))

    private fun nodeHashes(entry: Entry?): Map<String, String>? = entry?.merkle?.nodeHashes?.mapValues { (_, hash) -> hash.value }

    private fun requestSemantic(
        input: SourceInput,
        bytes: ByteArray,
        provider: SemanticProvider,
        cancellation: CancellationToken,
    ): ContractResult<List<SemanticFact>> {
        syncDocument(input, bytes, provider, cancellation)?.let { return failure(it) }
        val providerEpoch = providerEpoch(provider)
        val facts = ArrayList<SemanticFact>(plan.semanticRequests.size)
        for (spec in plan.semanticRequests) {
            cancelled(cancellation, "Index.semantic")?.let { return failure(it) }
            val request =
                SemanticRequest(
                    uri = uriFor(input),
                    version = input.version,
                    offset = spec.offset,
                    kind = spec.kind,
                    deadline = Deadline.fromNowMs(semanticTimeoutMs),
                )
            val response =
                try {
                    provider.request(request)
                } catch (error: Throwable) {
                    return failure(
                        ContractError.InternalError(
                            "Semantic provider request failed",
                            error.message,
                        ),
                    )
                }
            val result =
                when (response) {
                    is Success -> response.contents
                    is Failure -> return failure(response.error)
                }
            if (!result.matches(spec.kind)) {
                return failure(
                    ContractError.ValidationError(
                        "Semantic result does not match the requested kind",
                        field = "semanticFacts",
                    ),
                )
            }
            result.unavailableOrNull()?.let { unavailable ->
                return failure(unavailableError(unavailable))
            }
            facts +=
                SemanticFact(
                    request = request,
                    result = result,
                    providerEpoch = providerEpoch,
                )
        }
        cancelled(cancellation, "Index.semantic")?.let { return failure(it) }
        return success(facts)
    }

    private fun syncDocument(
        input: SourceInput,
        bytes: ByteArray,
        provider: SemanticProvider,
        cancellation: CancellationToken,
    ): ContractError? {
        cancelled(cancellation, "Index.documentSync")?.let { return it }
        val response =
            try {
                provider.openDocument(uriFor(input), bytes.toString(Charsets.UTF_8), input.version)
            } catch (error: Throwable) {
                return ContractError.InternalError(
                    "Semantic provider document sync failed",
                    error.message,
                )
            }
        return when (response) {
            is Success -> null
            is Failure ->
                if (response.error is ContractError.PreconditionFailed) {
                    null
                } else {
                    response.error
                }
        }
    }

    private fun resolveSyntax(support: LanguageSupport): ContractResult<SyntaxEngine> =
        try {
            support.syntax()
        } catch (error: Throwable) {
            failure(ContractError.InternalError("Syntax support resolution failed", error.message))
        }

    private fun resolveSemanticProvider(support: LanguageSupport): ContractResult<SemanticProvider> =
        try {
            support.semanticProvider()
        } catch (error: Throwable) {
            failure(
                ContractError.InternalError(
                    "Semantic provider resolution failed",
                    error.message,
                ),
            )
        }

    private fun readMetadata(path: String): ContractResult<FileMetadata> {
        val sourcePath =
            pathOf(path) ?: return failure(
                ContractError.ValidationError("Source path is invalid", field = "path"),
            )
        return try {
            success(
                FileMetadata(
                    modifiedNanos = Files.getLastModifiedTime(sourcePath).to(TimeUnit.NANOSECONDS),
                    size = Files.size(sourcePath),
                ),
            )
        } catch (error: NoSuchFileException) {
            failure(ContractError.NotFound("SourceFile", path))
        } catch (error: IOException) {
            failure(ContractError.InternalError("Unable to read source metadata", error.message))
        }
    }

    private fun readBytes(path: String): ContractResult<ByteArray> {
        val sourcePath =
            pathOf(path) ?: return failure(
                ContractError.ValidationError("Source path is invalid", field = "path"),
            )
        return try {
            success(Files.readAllBytes(sourcePath))
        } catch (error: NoSuchFileException) {
            failure(ContractError.NotFound("SourceFile", path))
        } catch (error: IOException) {
            failure(ContractError.InternalError("Unable to read source file", error.message))
        }
    }

    private fun pathOf(path: String): Path? =
        try {
            Path.of(path)
        } catch (error: InvalidPathException) {
            null
        }

    private fun replaceDependencies(
        key: String,
        previous: Set<String>,
        next: Set<String>,
    ) {
        for (dependency in previous - next) {
            reverseDependencies[dependency]?.let { dependents ->
                dependents.remove(key)
                if (dependents.isEmpty()) reverseDependencies.remove(dependency)
            }
        }
        for (dependency in next - previous) {
            reverseDependencies.getOrPut(dependency) { linkedSetOf() }.add(key)
        }
    }

    private fun markDependentsStale(
        path: String,
        excludeKey: String,
    ) {
        val seen = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.addAll(reverseDependencies[path] ?: emptySet())
        while (queue.isNotEmpty()) {
            val key = queue.removeFirst()
            if (!seen.add(key) || key == excludeKey) continue
            val entry = entries[key] ?: continue
            if (!entry.stale) entries[key] = entry.copy(stale = true)
            pathFor(entry.input)?.let { dependentPath ->
                queue.addAll(reverseDependencies[dependentPath] ?: emptySet())
            }
        }
    }

    private fun dependencyKeys(
        facts: FactBatch,
        input: SourceInput,
    ): Set<String> {
        val sourcePath = pathFor(input)
        return facts.imports.mapTo(linkedSetOf()) { import ->
            if (sourcePath == null) import.path else canonicalImport(import.path, sourcePath)
        }
    }

    private fun canonicalImport(
        importPath: String,
        sourcePath: String,
    ): String {
        val parsed =
            try {
                Path.of(importPath)
            } catch (error: InvalidPathException) {
                return importPath
            }
        if (parsed.isAbsolute) return parsed.normalize().toString()
        val base = Path.of(sourcePath).parent ?: Path.of("")
        return base.resolve(parsed).normalize().toString()
    }

    private fun sameSupport(
        entry: Entry,
        syntax: SyntaxEngine,
        semantic: SemanticProvider,
    ): Boolean =
        entry.syntax === syntax &&
            entry.provider === semantic &&
            entry.syntaxLanguage == syntax.language &&
            entry.providerLanguage == semantic.language

    private fun keyFor(input: SourceInput): String =
        when (input) {
            is SourceInput.FilePath -> {
                keyForPath(canonicalPath(input.path) ?: input.path)
            }

            is SourceInput.InMemory -> {
                input.path?.let {
                    keyForPath(canonicalPath(it) ?: it)
                } ?: "memory:${input.language}:${requireNotNull(input.documentId)}"
            }
        }

    private fun keyForPath(path: String): String = "file:$path"

    private fun pathFor(input: SourceInput): String? =
        when (input) {
            is SourceInput.FilePath -> canonicalPath(input.path) ?: input.path
            is SourceInput.InMemory -> input.path?.let { canonicalPath(it) ?: it }
        }

    private fun uriFor(input: SourceInput): String =
        when (input) {
            is SourceInput.FilePath -> {
                "file://${canonicalPath(input.path) ?: input.path}"
            }

            is SourceInput.InMemory -> {
                input.path?.let {
                    "file://${canonicalPath(it) ?: it}"
                } ?: "memory://${input.documentId}"
            }
        }

    private fun canonicalPath(path: String): String? = pathOf(path)?.toAbsolutePath()?.normalize()?.toString()

    private fun validateInput(input: SourceInput): ContractError? =
        when (input) {
            is SourceInput.FilePath -> {
                if (input.path.isBlank()) {
                    ContractError.InvalidInput("Source path is blank", input = "path")
                } else {
                    null
                }
            }

            is SourceInput.InMemory -> {
                if (input.path == null && input.documentId.isNullOrBlank()) {
                    ContractError.InvalidInput(
                        "Pathless in-memory source requires a non-blank documentId",
                        input = "documentId",
                    )
                } else {
                    null
                }
            }
        }

    private fun providerEpoch(provider: SemanticProvider): Long =
        providerEpochs.getOrPut(provider) {
            providerEpochCounter += 1
            providerEpochCounter
        }

    private fun cancelled(
        cancellation: CancellationToken,
        operation: String,
    ): ContractError? = if (cancellation.isCancelled) ContractError.Cancelled(operation) else null

    private fun nextEpoch(): Long {
        epoch += 1
        return epoch
    }

    private fun <T> success(value: T): ContractResult<T> = ResultFactory.success(value)

    private fun <T> failure(error: ContractError): ContractResult<T> = ResultFactory.failure(error)

    private fun SemanticResult.unavailableOrNull(): Unavailable? =
        when (this) {
            is SemanticResult.TypeAt -> unavailable
            is SemanticResult.SymbolAt -> unavailable
            is SemanticResult.DefinitionOf -> unavailable
            is SemanticResult.DiagnosticsFor -> unavailable
            is SemanticResult.HoverAt -> unavailable
            is SemanticResult.ReferencesAt -> unavailable
        }

    private fun SemanticResult.matches(kind: SemanticRequestKind): Boolean =
        when (kind) {
            SemanticRequestKind.TYPE_AT -> this is SemanticResult.TypeAt
            SemanticRequestKind.SYMBOL_AT -> this is SemanticResult.SymbolAt
            SemanticRequestKind.DEFINITION_OF -> this is SemanticResult.DefinitionOf
            SemanticRequestKind.DIAGNOSTICS_FOR -> this is SemanticResult.DiagnosticsFor
            SemanticRequestKind.HOVER_AT -> this is SemanticResult.HoverAt
            SemanticRequestKind.REFERENCES_AT -> this is SemanticResult.ReferencesAt
        }

    private fun unavailableError(unavailable: Unavailable): ContractError =
        when (unavailable.reason) {
            UnavailableReason.PROVIDER_UNAVAILABLE,
            UnavailableReason.LSP_NOT_RUNNING,
            UnavailableReason.LSP_NOT_INITIALIZED,
            -> {
                ContractError.DependencyUnavailable("semantic-provider", unavailable.message)
            }

            UnavailableReason.TIMEOUT -> {
                ContractError.Timeout("semantic-provider", semanticTimeoutMs)
            }

            UnavailableReason.CANCELLED -> {
                ContractError.Cancelled("Index.semantic")
            }

            UnavailableReason.NOT_SUPPORTED,
            UnavailableReason.INVALID_REQUEST,
            UnavailableReason.INTERNAL_ERROR,
            UnavailableReason.CAPABILITY_NOT_SUPPORTED,
            -> {
                ContractError.PreconditionFailed(
                    "Semantic provider returned unavailable: ${unavailable.message}",
                )
            }
        }
}
