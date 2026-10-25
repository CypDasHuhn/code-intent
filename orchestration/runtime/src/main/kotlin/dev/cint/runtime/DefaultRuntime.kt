package dev.cint.runtime

import dev.cint.contracts.data.AnalysisResult
import dev.cint.contracts.data.AnalysisResultMerger
import dev.cint.contracts.data.LabelledAnalysisResult
import dev.cint.contracts.facts.IndexedSource
import dev.cint.contracts.facts.SourceInput
import dev.cint.contracts.knowledge.KnowledgeChange
import dev.cint.contracts.knowledge.PackDefault
import dev.cint.contracts.knowledge.SpanSelector
import dev.cint.contracts.ports.AnalysisRequest
import dev.cint.contracts.ports.Index
import dev.cint.contracts.ports.IntentPipeline
import dev.cint.contracts.ports.KnowledgeRepositoryLocation
import dev.cint.contracts.ports.KnowledgeStore
import dev.cint.contracts.ports.LanguageSupport
import dev.cint.contracts.ports.RepositoryFilesRequest
import dev.cint.contracts.ports.RepositoryFilesResult
import dev.cint.contracts.ports.RepositorySymbolInventory
import dev.cint.contracts.ports.RepositorySymbolsRequest
import dev.cint.contracts.ports.RepositorySymbolsResult
import dev.cint.contracts.ports.ResolvedSelectorTarget
import dev.cint.contracts.ports.RulePackSource
import dev.cint.contracts.ports.Runtime
import dev.cint.contracts.ports.RuntimeLanguage
import dev.cint.contracts.ports.RuntimePack
import dev.cint.contracts.ports.RuntimeStatus
import dev.cint.contracts.ports.SelectorResolution
import dev.cint.contracts.ports.SelectorResolutionRequest
import dev.cint.contracts.ports.SelectorUnsupportedReason
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory
import dev.cint.knowledge.InMemoryKnowledgeStore
import dev.cint.knowledge.persistence.PersistentKnowledgeStore
import dev.cint.v2.index.InMemoryIndex
import dev.cint.v2.languages.LanguageCatalog
import dev.cint.v2.languages.LanguageOptions
import dev.cint.v2.languages.LanguageSupportFactory
import dev.cint.v2.languages.TreeSitterLanguageRegistry
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

class DefaultRuntime private constructor(
    private val config: RuntimeConfig,
    private val components: RuntimeComponents,
    private val index: Index,
    private val language: RuntimeLanguageComposition,
    private var packSource: RulePackSource,
    private var knowledgeStore: KnowledgeStore,
    private var pipeline: IntentPipeline,
    private var persistentStore: PersistentKnowledgeStore?,
    private val reloadable: Boolean,
) : Runtime {
    private val supports = linkedMapOf<SupportKey, LanguageSupport>()
    private val indexedPaths = linkedSetOf<String>()
    private var lastAnalysis: Long? = null
    private var closed = false
    private var shutdownError: ContractError? = null

    override fun analyze(
        request: AnalysisRequest,
        cancellation: CancellationToken,
    ): ContractResult<AnalysisResult> {
        checkOpen()?.let { return ResultFactory.failure(it) }
        cancellationError(cancellation)?.let { return ResultFactory.failure(it) }
        val paths = request.targetPaths.distinct()
        if (paths.isEmpty()) {
            return ResultFactory.failure(
                ContractError.InvalidInput("analysis request has no target paths", "targetPaths"),
            )
        }
        val inputs = paths.map { SourceInput.FilePath(it, request.language) }
        return analyzeInputs(inputs, request, cancellation)
    }

    override fun analyzeSource(
        source: SourceInput,
        request: AnalysisRequest,
        cancellation: CancellationToken,
    ): ContractResult<AnalysisResult> {
        checkOpen()?.let { return ResultFactory.failure(it) }
        cancellationError(cancellation)?.let { return ResultFactory.failure(it) }
        return analyzeInputs(listOf(source), request, cancellation)
    }

    override fun packs(cancellation: CancellationToken): ContractResult<List<RuntimePack>> {
        checkOpen()?.let { return ResultFactory.failure(it) }
        cancellationError(cancellation)?.let { return ResultFactory.failure(it) }
        val manifests = packSource.manifests(cancellation)
        if (manifests.isFailure) return ResultFactory.failure(manifests.errorOrNull!!)
        val packs = mutableListOf<RuntimePack>()
        for (manifest in manifests.getOrThrow()) {
            val playbook = packSource.playbook(manifest.id, cancellation)
            if (playbook.isFailure) return ResultFactory.failure(playbook.errorOrNull!!)
            packs += RuntimePack(manifest, playbook.getOrThrow())
        }
        return ResultFactory.success(packs)
    }

    override fun suggestDefaults(
        languages: Set<String>?,
        cancellation: CancellationToken,
    ): ContractResult<List<PackDefault>> {
        checkOpen()?.let { return ResultFactory.failure(it) }
        cancellationError(cancellation)?.let { return ResultFactory.failure(it) }
        val requested =
            if (languages != null) {
                ResultFactory.success(languages.filter { it.isNotBlank() }.map { it.trim() }.toSet())
            } else {
                initializationLanguages(config, language.catalog)
            }
        if (requested.isFailure) return ResultFactory.failure(requested.errorOrNull!!)
        return collectDefaultsFor(requested.getOrThrow(), packSource, cancellation)
    }

    override fun indexContentHash(
        path: String,
        cancellation: CancellationToken,
    ): ContractResult<String?> {
        checkOpen()?.let { return ResultFactory.failure(it) }
        cancellationError(cancellation)?.let { return ResultFactory.failure(it) }
        return index.contentHash(path, cancellation)
    }

    override fun repositoryFiles(
        request: RepositoryFilesRequest,
        cancellation: CancellationToken,
    ): ContractResult<RepositoryFilesResult> {
        checkOpen()?.let { return ResultFactory.failure(it) }
        cancellationError(cancellation)?.let { return ResultFactory.failure(it) }
        val root = configuredRoot()
        if (root.isFailure) return ResultFactory.failure(root.errorOrNull!!)
        val path =
            root.getOrThrow()
                ?: return ResultFactory.failure(
                    ContractError.PreconditionFailed(
                        "Repository root is not configured; start cint-mcp with --root PATH",
                    ),
                )
        return RepositoryDiscovery.files(path, language.catalog, request, cancellation)
    }

    override fun repositorySymbols(
        request: RepositorySymbolsRequest,
        cancellation: CancellationToken,
    ): ContractResult<RepositorySymbolsResult> {
        checkOpen()?.let { return ResultFactory.failure(it) }
        cancellationError(cancellation)?.let { return ResultFactory.failure(it) }
        val paths =
            request.targetPaths
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        if (paths.isEmpty()) {
            return ResultFactory.failure(
                ContractError.InvalidInput(
                    "repositorySymbols requires at least one target path",
                    "targetPaths",
                ),
            )
        }
        val sources = mutableListOf<RepositorySymbolInventory>()
        for (path in paths) {
            val indexed =
                ensureForDiscovery(path, request.language, request.options, cancellation)
            if (indexed.isFailure) return ResultFactory.failure(indexed.errorOrNull!!)
            val source = indexed.getOrThrow()
            sources +=
                RepositoryDiscovery.symbols(
                    path = path,
                    language = source.facts.syntaxTree?.language,
                    facts = source.facts,
                    semanticFacts = source.semanticFacts,
                )
        }
        return ResultFactory.success(RepositorySymbolsResult(sources = sources, count = sources.size))
    }

    override fun resolveSelector(
        request: SelectorResolutionRequest,
        cancellation: CancellationToken,
    ): ContractResult<SelectorResolution> {
        checkOpen()?.let { return ResultFactory.failure(it) }
        cancellationError(cancellation)?.let { return ResultFactory.failure(it) }
        val requestedRoot = request.root
        val rootResult: ContractResult<Path?> =
            if (requestedRoot != null && requestedRoot.isNotBlank()) {
                normalizeRoot(requestedRoot, "root").map(Path::of)
            } else {
                configuredRoot()
            }
        if (rootResult.isFailure) return ResultFactory.failure(rootResult.errorOrNull!!)
        val root = rootResult.getOrThrow()
        val requestedPaths =
            request.targetPaths
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        val selector = request.selector
        val targetPaths =
            when {
                requestedPaths.isNotEmpty() -> {
                    requestedPaths
                }

                selector is SpanSelector -> {
                    listOf(selector.path)
                }

                root != null -> {
                    val files =
                        RepositoryDiscovery.files(
                            root,
                            language.catalog,
                            RepositoryFilesRequest(
                                languages =
                                    request.language
                                        ?.takeIf { it.isNotBlank() }
                                        ?.let(::listOf)
                                        .orEmpty(),
                            ),
                            cancellation,
                        )
                    if (files.isFailure) return ResultFactory.failure(files.errorOrNull!!)
                    files.getOrThrow().files.map { it.absolutePath }
                }

                else -> {
                    return ResultFactory.failure(
                        ContractError.InvalidInput(
                            "resolveSelector requires targetPaths or a configured repository root",
                            "targetPaths",
                        ),
                    )
                }
            }
        val matched = mutableListOf<ResolvedSelectorTarget>()
        val unsupported = mutableListOf<SelectorUnsupportedReason>()
        val scanned = mutableListOf<String>()
        for (path in targetPaths) {
            val indexed =
                ensureForDiscovery(path, request.language, request.options, cancellation)
            if (indexed.isFailure) return ResultFactory.failure(indexed.errorOrNull!!)
            val resolution = pipeline.resolveSelector(selector, indexed.getOrThrow(), cancellation)
            if (resolution.isFailure) return ResultFactory.failure(resolution.errorOrNull!!)
            val resolved = resolution.getOrThrow()
            matched += resolved.matched
            unsupported += resolved.unsupported
            scanned += resolved.scannedPaths.ifEmpty { listOf(path) }
        }
        return ResultFactory.success(
            SelectorResolution(
                selector = selector.identityKey,
                matched =
                    matched
                        .distinct()
                        .sortedWith(
                            compareBy(
                                { it.path.orEmpty() },
                                { it.startByte },
                                { it.endByte },
                                { it.nodeId },
                                { it.kind },
                            ),
                        ),
                unsupported =
                    unsupported
                        .distinctBy { Triple(it.reason, it.path, it.nodeId) }
                        .sortedWith(
                            compareBy(
                                { it.path.orEmpty() },
                                { it.reason },
                                { it.nodeId.orEmpty() },
                            ),
                        ),
                scannedPaths = scanned.distinct().sorted(),
            ),
        )
    }

    private fun configuredRoot(): ContractResult<Path?> {
        val raw = config.rootPath?.takeIf { it.isNotBlank() } ?: return ResultFactory.success(null)
        return normalizeRoot(raw, "rootPath").map(Path::of)
    }

    private fun ensureForDiscovery(
        path: String,
        language: String?,
        options: Map<String, String>,
        cancellation: CancellationToken,
    ): ContractResult<IndexedSource> {
        cancellationError(cancellation)?.let { return ResultFactory.failure(it) }
        val input = SourceInput.FilePath(path, language)
        val languageOptions = languageOptions(AnalysisRequest(targetPaths = listOf(path), language = language, options = options))
        if (languageOptions.isFailure) return ResultFactory.failure(languageOptions.errorOrNull!!)
        val support = resolveSupport(input, languageOptions.getOrThrow(), cancellation)
        if (support.isFailure) return ResultFactory.failure(support.errorOrNull!!)
        return index.ensure(input, support.getOrThrow(), cancellation)
    }

    private fun analyzeInputs(
        inputs: List<SourceInput>,
        request: AnalysisRequest,
        cancellation: CancellationToken,
    ): ContractResult<AnalysisResult> {
        if (inputs.isEmpty()) {
            return ResultFactory.failure(
                ContractError.InvalidInput("analysis request has no sources", "source"),
            )
        }
        if (request.forceFullAnalysis) {
            val invalidated = index.invalidate(inputs.map(::inputPath).distinct(), cancellation)
            if (invalidated.isFailure) return ResultFactory.failure(invalidated.errorOrNull!!)
        }
        val snapshot = knowledgeStore.snapshot(cancellation)
        if (snapshot.isFailure) return ResultFactory.failure(snapshot.errorOrNull!!)

        val analyses = mutableListOf<LabelledAnalysisResult>()
        for (input in inputs) {
            cancellationError(cancellation)?.let { return ResultFactory.failure(it) }
            val label = inputPath(input)
            val options = languageOptions(request)
            if (options.isFailure) return ResultFactory.failure(options.errorOrNull!!)
            val support = resolveSupport(input, options.getOrThrow(), cancellation)
            if (support.isFailure) return ResultFactory.failure(support.errorOrNull!!)
            val indexed = index.ensure(input, support.getOrThrow(), cancellation)
            if (indexed.isFailure) return ResultFactory.failure(indexed.errorOrNull!!)
            indexedPaths += label
            val analysis =
                pipeline.analyze(request, indexed.getOrThrow(), snapshot.getOrThrow(), cancellation)
            if (analysis.isFailure) return ResultFactory.failure(analysis.errorOrNull!!)
            analyses += LabelledAnalysisResult(label, analysis.getOrThrow())
        }

        val merged = AnalysisResultMerger.merge(analyses)
        lastAnalysis = components.clock().toEpochMilliseconds()
        if (config.publishOnAnalyze) {
            val published = components.publisher.publish(merged, cancellation)
            if (published.isFailure) return ResultFactory.failure(published.errorOrNull!!)
        }
        return ResultFactory.success(merged)
    }

    override fun knowledge(): KnowledgeStore = knowledgeStore

    override fun materializeDefaults(
        changes: KnowledgeChange.MaterializeDefaults,
        cancellation: CancellationToken,
    ): ContractResult<Unit> {
        checkOpen()?.let { return ResultFactory.failure(it) }
        return knowledgeStore.materializeDefaults(changes.defaults, cancellation)
    }

    override fun reload(cancellation: CancellationToken): ContractResult<Unit> {
        checkOpen()?.let { return ResultFactory.failure(it) }
        cancellationError(cancellation)?.let { return ResultFactory.failure(it) }
        if (!reloadable) {
            return ResultFactory.failure(
                ContractError.PreconditionFailed(
                    "reload requires factory-owned pipeline and knowledge store",
                ),
            )
        }

        val supportShutdown = shutdownSupports()
        if (supportShutdown.isFailure) return ResultFactory.failure(supportShutdown.errorOrNull!!)

        val newPackSource = createPackSource(components)
        if (newPackSource.isFailure) return ResultFactory.failure(newPackSource.errorOrNull!!)
        val newKnowledge =
            createKnowledgeStore(config, components, language, newPackSource.getOrThrow(), cancellation)
        if (newKnowledge.isFailure) return ResultFactory.failure(newKnowledge.errorOrNull!!)
        val newPipelineResult =
            components.createPipeline(
                newPackSource.getOrThrow(),
                newKnowledge.getOrThrow(),
                config.applyUnmaterializedDefaults,
                config.enableCallChains,
            )
        if (newPipelineResult.isFailure) return ResultFactory.failure(newPipelineResult.errorOrNull!!)

        val invalidated = index.invalidate(indexedPaths.toList(), cancellation)
        if (invalidated.isFailure) return ResultFactory.failure(invalidated.errorOrNull!!)
        indexedPaths.clear()

        packSource = newPackSource.getOrThrow()
        knowledgeStore = newKnowledge.getOrThrow()
        persistentStore = newKnowledge.getOrThrow() as? PersistentKnowledgeStore
        pipeline = newPipelineResult.getOrThrow()
        lastAnalysis = null
        shutdownError = null
        return ResultFactory.success(Unit)
    }

    override fun publish(
        result: AnalysisResult,
        cancellation: CancellationToken,
    ): ContractResult<Unit> {
        checkOpen()?.let { return ResultFactory.failure(it) }
        return components.publisher.publish(result, cancellation)
    }

    override fun status(cancellation: CancellationToken): ContractResult<RuntimeStatus> {
        cancellationError(cancellation)?.let { return ResultFactory.failure(it) }
        val snapshot = knowledgeStore.snapshot(cancellation)
        if (snapshot.isFailure) return ResultFactory.failure(snapshot.errorOrNull!!)
        val knowledge = snapshot.getOrThrow()
        return ResultFactory.success(
            RuntimeStatus(
                isInitialized =
                    !closed &&
                        (
                            knowledge.materialized ||
                                knowledge.functionAnnotations.isNotEmpty() ||
                                knowledge.spanOverrides.isNotEmpty() ||
                                knowledge.patternHypotheses.isNotEmpty()
                        ),
                indexedFiles = indexedPaths.size,
                knowledgeEntries =
                    knowledge.functionAnnotations.size +
                        knowledge.spanOverrides.size +
                        knowledge.patternHypotheses.size,
                lastAnalysis = lastAnalysis,
                indexedPaths = indexedPaths.toList(),
                knowledgeMaterialized = knowledge.materialized,
                isRuntimeOpen = !closed,
            ),
        )
    }

    override fun languages(cancellation: CancellationToken): ContractResult<List<RuntimeLanguage>> {
        checkOpen()?.let { return ResultFactory.failure(it) }
        cancellationError(cancellation)?.let { return ResultFactory.failure(it) }
        return ResultFactory.success(
            language.catalog.descriptors.map { descriptor ->
                config.languageStatus(descriptor, config.languageOptions)
            },
        )
    }

    override fun shutdown(): ContractResult<Unit> {
        if (closed && shutdownError == null) return ResultFactory.success(Unit)
        val supportError = shutdownSupports().errorOrNull
        val saveError =
            if (knowledgeStore.isMaterialized) persistentStore?.save()?.errorOrNull else null
        closed = true
        val error = supportError ?: saveError
        shutdownError = error
        return error?.let { ResultFactory.failure(it) } ?: ResultFactory.success(Unit)
    }

    private fun resolveSupport(
        input: SourceInput,
        options: LanguageOptions,
        cancellation: CancellationToken,
    ): ContractResult<LanguageSupport> {
        cancellationError(cancellation)?.let { return ResultFactory.failure(it) }
        val resolved = language.resolve(input, options)
        if (resolved.isFailure) return ResultFactory.failure(resolved.errorOrNull!!)
        val support = resolved.getOrThrow()
        val descriptor = support.descriptor()
        if (descriptor.isFailure) {
            support.shutdown()
            return ResultFactory.failure(descriptor.errorOrNull!!)
        }
        val key = supportKey(descriptor.getOrThrow().id, options)
        val existing = supports[key]
        if (existing != null) {
            if (existing !== support) {
                val displaced = support.shutdown()
                if (displaced.isFailure) return ResultFactory.failure(displaced.errorOrNull!!)
            }
            return ResultFactory.success(existing)
        }
        supports[key] = support
        return ResultFactory.success(support)
    }

    private fun languageOptions(request: AnalysisRequest): ContractResult<LanguageOptions> {
        val requestedRoot =
            request.options["rootPath"]?.takeIf { it.isNotBlank() }
                ?: config.rootPath?.takeIf { it.isNotBlank() }
                ?: config.languageOptions.rootPath
        if (requestedRoot == null) return ResultFactory.success(config.languageOptions)
        val normalized = normalizeRoot(requestedRoot, "rootPath")
        if (normalized.isFailure) return ResultFactory.failure(normalized.errorOrNull!!)
        return ResultFactory.success(config.languageOptions.copy(rootPath = normalized.getOrThrow()))
    }

    private fun supportKey(
        languageId: String,
        options: LanguageOptions,
    ): SupportKey {
        val root = options.requestedRoot.orEmpty()
        val provider = options.providerConfig
        return SupportKey(
            language = languageId,
            root = root,
            mode = provider?.mode?.name.orEmpty(),
            command = provider?.command.orEmpty(),
            endpoint = provider?.endpoint.orEmpty(),
            timeoutMs = provider?.timeoutMs ?: 0,
            initTimeoutMs = provider?.initTimeoutMs ?: 0,
            hostChannel = options.hostChannel?.let { System.identityHashCode(it).toString() }.orEmpty(),
            hostChannelProvider =
                options.hostChannelProvider?.let { System.identityHashCode(it).toString() }.orEmpty(),
        )
    }

    private fun shutdownSupports(): ContractResult<Unit> {
        val failed = linkedMapOf<SupportKey, Pair<LanguageSupport, ContractError>>()
        val firstError = mutableListOf<ContractError>()
        for ((key, support) in supports) {
            val result = support.shutdown()
            if (result.isFailure) {
                val error = result.errorOrNull ?: ContractError.InternalError("Language support shutdown failed")
                failed[key] = support to error
                if (firstError.isEmpty()) firstError += error
            }
        }
        supports.clear()
        failed.forEach { (key, value) -> supports[key] = value.first }
        return firstError.firstOrNull()?.let { ResultFactory.failure(it) }
            ?: ResultFactory.success(Unit)
    }

    private fun checkOpen(): ContractError? = if (closed) ContractError.PreconditionFailed("runtime is shut down") else null

    companion object {
        fun create(
            config: RuntimeConfig = RuntimeConfig(),
            components: RuntimeComponents = RuntimeComponents(),
        ): ContractResult<DefaultRuntime> {
            val language = components.languageComposition ?: defaultLanguageComposition(config)
            val packSource = createPackSource(components)
            if (packSource.isFailure) return ResultFactory.failure(packSource.errorOrNull!!)
            val knowledge =
                createKnowledgeStore(
                    config,
                    components,
                    language,
                    packSource.getOrThrow(),
                    CancellationToken.NONE,
                )
            if (knowledge.isFailure) return ResultFactory.failure(knowledge.errorOrNull!!)
            val index =
                components.index
                    ?: InMemoryIndex(config.indexPlan, config.semanticTimeoutMs)
            val pipeline =
                components.createPipeline(
                    packSource.getOrThrow(),
                    knowledge.getOrThrow(),
                    config.applyUnmaterializedDefaults,
                    config.enableCallChains,
                )
            if (pipeline.isFailure) return ResultFactory.failure(pipeline.errorOrNull!!)
            return ResultFactory.success(
                DefaultRuntime(
                    config = config,
                    components = components,
                    index = index,
                    language = language,
                    packSource = packSource.getOrThrow(),
                    knowledgeStore = knowledge.getOrThrow(),
                    pipeline = pipeline.getOrThrow(),
                    persistentStore = knowledge.getOrThrow() as? PersistentKnowledgeStore,
                    reloadable = components.pipeline == null && components.knowledgeStore == null,
                ),
            )
        }
    }
}

private data class SupportKey(
    val language: String,
    val root: String,
    val mode: String,
    val command: String,
    val endpoint: String,
    val timeoutMs: Long,
    val initTimeoutMs: Long,
    val hostChannel: String,
    val hostChannelProvider: String,
)

private fun defaultLanguageComposition(config: RuntimeConfig): RuntimeLanguageComposition =
    DefaultRuntimeLanguageComposition(
        catalog = TreeSitterLanguageRegistry,
        factory =
            LanguageSupportFactory(
                catalog = TreeSitterLanguageRegistry,
                rootLocator = MarkerRootLocator,
                providerDefaults = config.providerDefaults(),
            ),
    )

private fun createPackSource(components: RuntimeComponents): ContractResult<RulePackSource> =
    try {
        ResultFactory.success(components.packSourceFactory())
    } catch (error: Throwable) {
        ResultFactory.failure(
            ContractError.InternalError(
                "Unable to create the rule pack source",
                error.message ?: error::class.simpleName,
            ),
        )
    }

private fun createKnowledgeStore(
    config: RuntimeConfig,
    components: RuntimeComponents,
    language: RuntimeLanguageComposition,
    packSource: RulePackSource,
    cancellation: CancellationToken,
): ContractResult<KnowledgeStore> {
    components.knowledgeStore?.let { return ResultFactory.success(it) }
    cancellationError(cancellation)?.let { return ResultFactory.failure(it) }

    val repositoryResult = openRepository(config, components)
    if (repositoryResult.isFailure) return ResultFactory.failure(repositoryResult.errorOrNull!!)
    val repository = repositoryResult.getOrThrow()
    val loaded =
        if (repository != null) {
            repository.load(cancellation)
        } else {
            ResultFactory.success(null)
        }
    if (loaded.isFailure) return ResultFactory.failure(loaded.errorOrNull!!)

    val snapshot = loaded.getOrThrow()
    val memory =
        if (snapshot != null) {
            InMemoryKnowledgeStore.fromSnapshot(
                snapshot,
                exists = snapshot.materialized,
                now = components.clock,
            )
        } else {
            ResultFactory.success(InMemoryKnowledgeStore.empty(components.clock))
        }
    if (memory.isFailure) return ResultFactory.failure(memory.errorOrNull!!)

    val store: KnowledgeStore =
        if (repository != null) {
            components.knowledgeStoreFactory.persistent(repository, memory.getOrThrow())
        } else {
            memory.getOrThrow()
        }

    if (config.materializeDefaultsOnOpen && (snapshot == null || !snapshot.materialized)) {
        val defaults =
            collectDefaults(config, language.catalog, packSource, cancellation)
        if (defaults.isFailure) return ResultFactory.failure(defaults.errorOrNull!!)
        val materialized = store.materializeDefaults(defaults.getOrThrow(), cancellation)
        if (materialized.isFailure) return ResultFactory.failure(materialized.errorOrNull!!)
    }
    return ResultFactory.success(store)
}

private fun openRepository(
    config: RuntimeConfig,
    components: RuntimeComponents,
): ContractResult<dev.cint.contracts.ports.KnowledgeRepository?> {
    val rootPath = config.rootPath?.takeIf { it.isNotBlank() }
    val knowledgePath = config.knowledgePath?.takeIf { it.isNotBlank() }
    if (rootPath == null && knowledgePath == null) {
        return ResultFactory.success(null)
    }
    val opened =
        components.knowledgeRepositoryFactory.open(
            KnowledgeRepositoryLocation(rootPath = rootPath, path = knowledgePath),
        )
    if (opened.isFailure) return ResultFactory.failure(opened.errorOrNull!!)
    return ResultFactory.success(opened.getOrThrow())
}

private fun collectDefaults(
    config: RuntimeConfig,
    catalog: LanguageCatalog,
    packSource: RulePackSource,
    cancellation: CancellationToken,
): ContractResult<List<PackDefault>> {
    val languages = initializationLanguages(config, catalog)
    if (languages.isFailure) return ResultFactory.failure(languages.errorOrNull!!)
    return collectDefaultsFor(languages.getOrThrow(), packSource, cancellation)
}

private fun collectDefaultsFor(
    requested: Set<String>,
    packSource: RulePackSource,
    cancellation: CancellationToken,
): ContractResult<List<PackDefault>> {
    if (requested.isEmpty()) return ResultFactory.success(emptyList())

    val manifests = packSource.manifests(cancellation)
    if (manifests.isFailure) return ResultFactory.failure(manifests.errorOrNull!!)
    val supported = manifests.getOrThrow().flatMap { it.languages }.toSet()
    val targets = if (supported.isEmpty()) requested else requested.filter { it in supported }
    val defaults = mutableListOf<PackDefault>()
    for (language in targets.sorted()) {
        cancellationError(cancellation)?.let { return ResultFactory.failure(it) }
        val result = packSource.defaults(language, cancellation)
        if (result.isFailure) return ResultFactory.failure(result.errorOrNull!!)
        defaults += result.getOrThrow()
    }
    return ResultFactory.success(
        defaults.distinctBy {
            Triple(it.packId, it.selector.identityKey, it.intents.sorted().joinToString(","))
        },
    )
}

private fun initializationLanguages(
    config: RuntimeConfig,
    catalog: LanguageCatalog,
): ContractResult<Set<String>> {
    val configured =
        config.initializeLanguages
            .filter { it.isNotBlank() }
            .map { it.trim() }
            .toSet()
    if (configured.isNotEmpty()) return ResultFactory.success(configured)
    if (!config.detectLanguages) return ResultFactory.success(emptySet())
    val rawRoot =
        config.rootPath?.takeIf { it.isNotBlank() }
            ?: return ResultFactory.success(emptySet())
    val normalized = normalizeRoot(rawRoot, "rootPath")
    if (normalized.isFailure) return ResultFactory.failure(normalized.errorOrNull!!)
    val root = Path.of(normalized.getOrThrow())
    if (!Files.isDirectory(root)) return ResultFactory.success(emptySet())
    val found = linkedSetOf<String>()
    try {
        Files.walk(root).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .filter { file -> file.none { it.toString() in SKIPPED_DIRECTORIES } }
                .limit(20_000)
                .forEach { file ->
                    catalog.forPath(file.toString())?.let { found += it.id }
                }
        }
    } catch (_: Exception) {
        return ResultFactory.success(emptySet())
    }
    return ResultFactory.success(found)
}

private fun normalizeRoot(
    raw: String,
    field: String,
): ContractResult<String> =
    try {
        ResultFactory.success(
            Path
                .of(raw)
                .toAbsolutePath()
                .normalize()
                .toString(),
        )
    } catch (_: InvalidPathException) {
        ResultFactory.failure(ContractError.InvalidInput("Invalid path '$raw'", field))
    }

private fun cancellationError(cancellation: CancellationToken): ContractError? = cancellation.throwIfCancelled().errorOrNull

private fun inputPath(input: SourceInput): String =
    when (input) {
        is SourceInput.FilePath -> input.path
        is SourceInput.InMemory -> input.path ?: input.documentId ?: "${input.language}:memory"
    }

private val SKIPPED_DIRECTORIES =
    setOf(".git", ".gradle", ".idea", "build", "node_modules", "out", "target", ".cint")
