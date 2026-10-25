package dev.cint.v2.testing.fakes

import dev.cint.contracts.data.Confidence
import dev.cint.contracts.data.Span
import dev.cint.contracts.facts.Diagnostic
import dev.cint.contracts.facts.Location
import dev.cint.contracts.facts.SemanticRequest
import dev.cint.contracts.facts.SemanticRequestKind
import dev.cint.contracts.facts.SemanticResult
import dev.cint.contracts.facts.SymbolInfo
import dev.cint.contracts.facts.SymbolKind
import dev.cint.contracts.facts.TypeRef
import dev.cint.contracts.ports.HostSemanticChannel
import dev.cint.contracts.ports.HostChannelConfig
import dev.cint.contracts.ports.HostSemanticChannelProvider
import dev.cint.contracts.ports.SemanticProvider
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory
import dev.cint.contracts.util.Unavailable

class FakeSemanticProvider(
    override val language: String = "fake",
    val recorder: FakeRecorder = FakeRecorder(),
    private val responseFactory: (SemanticRequest) -> ContractResult<SemanticResult> = { request ->
        ResultFactory.success(defaultSemanticResult(request))
    },
    private val clock: FakeClock = FakeClock(),
) : SemanticProvider {
    data class DocumentState(val text: String, val version: Long)

    val documents: MutableMap<String, DocumentState> = linkedMapOf()
    var closed: Boolean = false
        private set

    override fun request(request: SemanticRequest): ContractResult<SemanticResult> {
        recorder.record(
            "semantic.request",
            "${request.kind}:${request.uri}@${request.version}:${request.offset}",
        )
        if (closed) {
            return ResultFactory.failure(ContractError.PreconditionFailed("semantic provider is closed"))
        }
        if (clock.isExpired(request.deadline)) {
            recorder.record("semantic.deadlineExpired", request.kind.name)
            return ResultFactory.success(
                unavailableResult(
                    request.kind,
                    Unavailable.timeout("semantic request deadline expired for ${request.uri}"),
                ),
            )
        }
        return responseFactory(request)
    }

    override fun openDocument(
        uri: String,
        text: String,
        version: Long,
    ): ContractResult<Unit> {
        recorder.record("semantic.openDocument", "$uri@$version")
        return updateDocument("open", uri, text, version)
    }

    override fun changeDocument(
        uri: String,
        text: String,
        version: Long,
    ): ContractResult<Unit> {
        recorder.record("semantic.changeDocument", "$uri@$version")
        return updateDocument("change", uri, text, version)
    }

    override fun closeDocument(uri: String): ContractResult<Unit> {
        recorder.record("semantic.closeDocument", uri)
        if (closed) {
            return ResultFactory.failure(ContractError.PreconditionFailed("semantic provider is closed"))
        }
        documents.remove(uri)
        return ResultFactory.success(Unit)
    }

    private fun updateDocument(
        operation: String,
        uri: String,
        text: String,
        version: Long,
    ): ContractResult<Unit> {
        if (closed) {
            return ResultFactory.failure(ContractError.PreconditionFailed("semantic provider is closed"))
        }
        if (uri.isBlank()) {
            return ResultFactory.failure(ContractError.InvalidInput("$operation requires a document uri", "uri"))
        }
        documents[uri] = DocumentState(text, version)
        return ResultFactory.success(Unit)
    }

    override fun shutdown(): ContractResult<Unit> {
        recorder.record("semantic.shutdown")
        closed = true
        documents.clear()
        return ResultFactory.success(Unit)
    }
}

fun defaultSemanticResult(request: SemanticRequest): SemanticResult = when (request.kind) {
    SemanticRequestKind.TYPE_AT -> SemanticResult.TypeAt(
        TypeRef("fake.Type", "fake-provider", Confidence.HIGH),
    )

    SemanticRequestKind.SYMBOL_AT -> SemanticResult.SymbolAt(
        SymbolInfo(
            name = "fakeSymbol",
            kind = SymbolKind.FUNCTION,
            containerName = "FakeContainer",
            fqName = "fake.FakeContainer.fakeSymbol",
        ),
    )

    SemanticRequestKind.DEFINITION_OF -> SemanticResult.DefinitionOf(
        listOf(Location(request.uri, Span.ZERO)),
    )

    SemanticRequestKind.DIAGNOSTICS_FOR -> SemanticResult.DiagnosticsFor(
        listOf(Diagnostic(Span.ZERO, "fake diagnostic", dev.cint.contracts.facts.DiagnosticSeverity.INFO)),
    )

    SemanticRequestKind.HOVER_AT -> SemanticResult.HoverAt("fake hover for ${request.uri}")

    SemanticRequestKind.REFERENCES_AT -> SemanticResult.ReferencesAt(
        listOf(Location(request.uri, Span.ZERO)),
    )
}

class FakeHostSemanticChannel(
    private val provider: SemanticProvider = FakeSemanticProvider(),
    val recorder: FakeRecorder = FakeRecorder(),
) : HostSemanticChannel {
    var closed: Boolean = false
        private set

    override fun send(request: SemanticRequest): ContractResult<SemanticResult> {
        recorder.record("host.send", "${request.kind}:${request.uri}")
        if (closed) {
            return ResultFactory.failure(ContractError.PreconditionFailed("host semantic channel is closed"))
        }
        val result = provider.request(request)
        recorder.record("host.response", if (result.isSuccess) "success" else "failure")
        return result
    }

    override fun openDocument(
        uri: String,
        text: String,
        version: Long,
    ): ContractResult<Unit> {
        recorder.record("host.openDocument", "$uri@$version")
        if (closed) return ResultFactory.failure(ContractError.PreconditionFailed("host semantic channel is closed"))
        return provider.openDocument(uri, text, version)
    }

    override fun changeDocument(
        uri: String,
        text: String,
        version: Long,
    ): ContractResult<Unit> {
        recorder.record("host.changeDocument", "$uri@$version")
        if (closed) return ResultFactory.failure(ContractError.PreconditionFailed("host semantic channel is closed"))
        return provider.changeDocument(uri, text, version)
    }

    override fun closeDocument(uri: String): ContractResult<Unit> {
        recorder.record("host.closeDocument", uri)
        if (closed) return ResultFactory.failure(ContractError.PreconditionFailed("host semantic channel is closed"))
        return provider.closeDocument(uri)
    }

    override fun close(): ContractResult<Unit> {
        recorder.record("host.close")
        closed = true
        return ResultFactory.success(Unit)
    }
}

class FakeHostSemanticChannelProvider(
    private val channelFactory: (HostChannelConfig) -> HostSemanticChannel = { FakeHostSemanticChannel() },
    val recorder: FakeRecorder = FakeRecorder(),
) : HostSemanticChannelProvider {
    val openedConfigs: MutableList<HostChannelConfig> = mutableListOf()

    override fun open(config: HostChannelConfig): ContractResult<HostSemanticChannel> {
        recorder.record("hostProvider.open", config.type)
        openedConfigs.add(config)
        return ResultFactory.success(channelFactory(config))
    }
}

fun unavailableResult(kind: SemanticRequestKind, unavailable: Unavailable): SemanticResult = when (kind) {
    SemanticRequestKind.TYPE_AT -> SemanticResult.TypeAt(typeRef = null, unavailable = unavailable)
    SemanticRequestKind.SYMBOL_AT -> SemanticResult.SymbolAt(symbol = null, unavailable = unavailable)
    SemanticRequestKind.DEFINITION_OF -> SemanticResult.DefinitionOf(emptyList(), unavailable)
    SemanticRequestKind.DIAGNOSTICS_FOR -> SemanticResult.DiagnosticsFor(emptyList(), unavailable)
    SemanticRequestKind.HOVER_AT -> SemanticResult.HoverAt(content = null, unavailable = unavailable)
    SemanticRequestKind.REFERENCES_AT -> SemanticResult.ReferencesAt(emptyList(), unavailable)
}
