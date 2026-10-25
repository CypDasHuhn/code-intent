package dev.cint.v2.testing.fakes

import dev.cint.contracts.data.Completeness
import dev.cint.contracts.facts.IndexedSource
import dev.cint.contracts.facts.SemanticFact
import dev.cint.contracts.facts.SemanticRequest
import dev.cint.contracts.facts.SemanticRequestKind
import dev.cint.contracts.facts.SourceInput
import dev.cint.contracts.ports.Index
import dev.cint.contracts.ports.IndexInput
import dev.cint.contracts.ports.LanguageSupport
import dev.cint.contracts.ports.RescanResult
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContentHash
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory

class FakeIndex(
    val contents: MutableMap<String, String> = mutableMapOf(),
    private val clock: FakeClock = FakeClock(),
    private val queryIds: List<String> = listOf("fake-facts"),
    val recorder: FakeRecorder = FakeRecorder(),
    private val dependents: Map<String, List<String>> = emptyMap(),
) : Index {
    private data class CacheEntry(
        val source: IndexedSource,
        val key: String,
    )

    private val cache = linkedMapOf<String, CacheEntry>()
    private val latestByDocument = linkedMapOf<String, String>()
    private var nextEpoch = 0L
    private var nextProviderEpoch = 0L

    override fun ensure(
        input: SourceInput,
        support: LanguageSupport,
        cancellation: CancellationToken,
    ): ContractResult<IndexedSource> {
        recorder.record("index.ensure", input.describe())
        val checked = cancellation.throwIfCancelled()
        if (checked.isFailure) return ResultFactory.failure(checked.errorOrNull!!)
        if (input is SourceInput.InMemory && input.path == null && input.documentId.isNullOrBlank()) {
            return ResultFactory.failure(
                ContractError.InvalidInput("pathless in-memory source requires documentId", input = "documentId"),
            )
        }

        val text =
            when (input) {
                is SourceInput.InMemory -> {
                    input.text
                }

                is SourceInput.FilePath -> {
                    contents[input.path]
                        ?: return ResultFactory.failure(
                            ContractError.InvalidInput("missing in-memory content for ${input.path}", input.path),
                        )
                }
            }
        val descriptor = support.descriptor()
        if (descriptor.isFailure) return ResultFactory.failure(descriptor.errorOrNull!!)

        val documentKey = input.documentKey()
        val contentHash = ContentHash.compute(text).value
        val cacheKey =
            buildString {
                append(documentKey)
                append('|')
                append(descriptor.getOrThrow().id)
                append('|')
                append(input.language())
                append('|')
                append(input.version)
                append('|')
                append(contentHash)
            }
        cache[cacheKey]?.let {
            recorder.record("index.cacheHit", cacheKey)
            return ResultFactory.success(it.source)
        }

        val syntax = support.syntax()
        if (syntax.isFailure) return ResultFactory.failure(syntax.errorOrNull!!)
        val provider = support.semanticProvider()
        if (provider.isFailure) return ResultFactory.failure(provider.errorOrNull!!)
        if (nextProviderEpoch == 0L) nextProviderEpoch = 1L

        val tree = syntax.getOrThrow().parse(text.encodeToByteArray(), null, cancellation)
        if (tree.isFailure) return ResultFactory.failure(tree.errorOrNull!!)
        val facts = syntax.getOrThrow().facts(tree.getOrThrow(), queryIds, cancellation)
        if (facts.isFailure) return ResultFactory.failure(facts.errorOrNull!!)

        val request =
            SemanticRequest(
                uri = input.uri(),
                version = input.version,
                offset = 0,
                kind = SemanticRequestKind.TYPE_AT,
                deadline = clock.deadlineIn(5000),
            )
        val semantic = provider.getOrThrow().request(request)
        if (semantic.isFailure) return ResultFactory.failure(semantic.errorOrNull!!)

        nextEpoch += 1
        val indexed =
            IndexedSource(
                input = input,
                facts = facts.getOrThrow(),
                sourceEpoch = nextEpoch,
                completeness = Completeness.COMPLETE,
                contentHash = contentHash,
                semanticFacts =
                    listOf(
                        SemanticFact(
                            request = request,
                            result = semantic.getOrThrow(),
                            providerEpoch = nextProviderEpoch,
                        ),
                    ),
            )
        cache[cacheKey] = CacheEntry(indexed, documentKey)
        latestByDocument[documentKey] = cacheKey
        recorder.record("index.indexed", "epoch=$nextEpoch,hash=$contentHash,version=${input.version}")
        return ResultFactory.success(indexed)
    }

    override fun fullRescan(
        inputs: List<IndexInput>,
        cancellation: CancellationToken,
    ): ContractResult<RescanResult> {
        recorder.record("index.fullRescan", "inputs=${inputs.size}")
        val sources = mutableListOf<IndexedSource>()
        for (indexInput in inputs) {
            val result = ensure(indexInput.input, indexInput.support, cancellation)
            if (result.isFailure) return ResultFactory.failure(result.errorOrNull!!)
            sources.add(result.getOrThrow())
        }
        return ResultFactory.success(RescanResult(sources))
    }

    override fun invalidate(
        paths: List<String>,
        cancellation: CancellationToken,
    ): ContractResult<Unit> {
        recorder.record("index.invalidate", paths.joinToString(","))
        val checked = cancellation.throwIfCancelled()
        if (checked.isFailure) return checked
        val requested = paths.toSet()
        cache.entries.removeAll { it.value.key in requested }
        latestByDocument.entries.removeAll { it.value !in cache }
        return ResultFactory.success(Unit)
    }

    override fun snapshot(
        path: String,
        cancellation: CancellationToken,
    ): ContractResult<IndexedSource?> {
        recorder.record("index.snapshot", path)
        val checked = cancellation.throwIfCancelled()
        if (checked.isFailure) return ResultFactory.failure(checked.errorOrNull!!)
        return ResultFactory.success(latestByDocument[path]?.let { cache[it]?.source })
    }

    override fun dependentsOf(
        path: String,
        cancellation: CancellationToken,
    ): ContractResult<List<String>> {
        recorder.record("index.dependentsOf", path)
        val checked = cancellation.throwIfCancelled()
        if (checked.isFailure) return ResultFactory.failure(checked.errorOrNull!!)
        return ResultFactory.success(dependents[path].orEmpty().sorted())
    }

    override fun contentHash(
        path: String,
        cancellation: CancellationToken,
    ): ContractResult<String?> {
        val checked = cancellation.throwIfCancelled()
        if (checked.isFailure) return ResultFactory.failure(checked.errorOrNull!!)
        val text = contents[path] ?: return ResultFactory.success(null)
        return ResultFactory.success(ContentHash.compute(text).value)
    }

    fun indexedCount(): Int = latestByDocument.size

    fun setContent(
        path: String,
        text: String,
    ) {
        contents[path] = text
    }
}

private fun SourceInput.describe(): String =
    when (this) {
        is SourceInput.InMemory -> "memory:$language:$version:${path ?: documentId ?: "-"}"
        is SourceInput.FilePath -> "file:$path:${languageHint ?: "-"}:$version"
    }

private fun SourceInput.documentKey(): String =
    when (this) {
        is SourceInput.InMemory -> path ?: "memory:$language:${requireNotNull(documentId)}"
        is SourceInput.FilePath -> path
    }

private fun SourceInput.language(): String =
    when (this) {
        is SourceInput.InMemory -> language
        is SourceInput.FilePath -> languageHint ?: ""
    }

private fun SourceInput.uri(): String =
    when (this) {
        is SourceInput.InMemory -> path?.let { "file://$it" } ?: "memory://${requireNotNull(documentId)}"
        is SourceInput.FilePath -> "file://$path"
    }
