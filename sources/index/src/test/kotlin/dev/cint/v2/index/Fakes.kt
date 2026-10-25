package dev.cint.v2.index

import dev.cint.contracts.facts.ChangedRanges
import dev.cint.contracts.facts.FactBatch
import dev.cint.contracts.facts.LanguageDescriptor
import dev.cint.contracts.facts.SemanticRequest
import dev.cint.contracts.facts.SemanticRequestKind
import dev.cint.contracts.facts.SemanticResult
import dev.cint.contracts.facts.SyntaxNode
import dev.cint.contracts.facts.SyntaxTree
import dev.cint.contracts.ports.LanguageSupport
import dev.cint.contracts.ports.SemanticProvider
import dev.cint.contracts.ports.SyntaxEngine
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContentHash
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.Failure
import dev.cint.contracts.util.NodeHash
import dev.cint.contracts.util.ResultFactory
import dev.cint.contracts.util.Success

fun <T> success(value: T): ContractResult<T> = ResultFactory.success(value)

fun <T> failure(error: ContractError): ContractResult<T> = ResultFactory.failure(error)

fun <T> ContractResult<T>.requireSuccess(): T =
    when (this) {
        is Success -> contents
        is Failure -> error("expected success but was $error")
    }

fun <T> ContractResult<T>.requireFailure(): ContractError =
    when (this) {
        is Success -> error("expected failure but was success: $contents")
        is Failure -> error
    }

fun singleNodeTree(
    text: String,
    language: String = "test",
): SyntaxTree {
    val node =
        SyntaxNode(
            id = "root",
            kind = "file",
            startByte = 0,
            endByte = text.encodeToByteArray().size,
            startLine = 0,
            startCol = 0,
            endLine = 0,
            endCol = 0,
            text = text,
            parentId = null,
            childIds = emptyList(),
        )
    return SyntaxTree(
        rootId = "root",
        nodes = mapOf("root" to node),
        language = language,
        version = "test-1",
        contentHash = ContentHash.compute(text).value,
    )
}

fun twoChildTree(
    text: String,
    leftText: String,
    rightText: String,
    language: String = "test",
): SyntaxTree {
    val root =
        SyntaxNode(
            id = "root",
            kind = "file",
            startByte = 0,
            endByte = text.encodeToByteArray().size,
            startLine = 0,
            startCol = 0,
            endLine = 0,
            endCol = 0,
            text = text,
            parentId = null,
            childIds = listOf("left", "right"),
        )
    val left =
        SyntaxNode(
            id = "left",
            kind = "expression",
            startByte = 0,
            endByte = leftText.encodeToByteArray().size,
            startLine = 0,
            startCol = 0,
            endLine = 0,
            endCol = 0,
            text = leftText,
            parentId = "root",
            childIds = emptyList(),
        )
    val right =
        SyntaxNode(
            id = "right",
            kind = "expression",
            startByte = 0,
            endByte = rightText.encodeToByteArray().size,
            startLine = 0,
            startCol = 0,
            endLine = 0,
            endCol = 0,
            text = rightText,
            parentId = "root",
            childIds = emptyList(),
        )
    return SyntaxTree(
        rootId = "root",
        nodes = mapOf("root" to root, "left" to left, "right" to right),
        language = language,
        version = "test-1",
        contentHash = ContentHash.compute(text).value,
    )
}

fun testPlan(
    syntaxQueryIds: List<String> = listOf("imports"),
    semanticRequests: List<SemanticRequestSpec> =
        listOf(SemanticRequestSpec(SemanticRequestKind.DIAGNOSTICS_FOR)),
): IndexPlan = IndexPlan(syntaxQueryIds, semanticRequests)

class FakeSyntaxEngine(
    private val treeFactory: (String) -> SyntaxTree = { singleNodeTree(it) },
    private val factsFactory: (SyntaxTree) -> FactBatch = { FactBatch(syntaxTree = it) },
) : SyntaxEngine {
    var engineLanguage: String = "test"
    override val language: String
        get() = engineLanguage

    var parseCalls: Int = 0
        private set
    var factsCalls: Int = 0
        private set
    var lastPrevious: SyntaxTree? = null
        private set
    var lastQueryIds: List<String>? = null
        private set

    var parseError: ContractError? = null
    var factsError: ContractError? = null
    var treeOverride: SyntaxTree? = null
    var factsOverride: FactBatch? = null
    var nodeHashOverride: ContractResult<String>? = null

    override fun parse(
        content: ByteArray,
        previous: SyntaxTree?,
        cancellation: CancellationToken,
    ): ContractResult<SyntaxTree> {
        parseCalls += 1
        lastPrevious = previous
        if (cancellation.isCancelled) {
            return failure(ContractError.Cancelled("SyntaxEngine.parse"))
        }
        parseError?.let { return failure(it) }
        treeOverride?.let { return success(it) }
        return success(treeFactory(content.decodeToString()))
    }

    override fun diff(
        previous: SyntaxTree,
        next: SyntaxTree,
    ): ChangedRanges = ChangedRanges.EMPTY

    override fun nodeHash(
        tree: SyntaxTree,
        nodeId: String,
    ): ContractResult<String> {
        nodeHashOverride?.let { return it }
        if (tree.getNode(nodeId) == null) {
            return failure(ContractError.NotFound("SyntaxNode", nodeId))
        }
        return try {
            success(subtreeHash(tree, nodeId, mutableMapOf(), mutableSetOf()).value)
        } catch (error: Throwable) {
            failure(ContractError.InternalError("node hash failed", error.message))
        }
    }

    private fun subtreeHash(
        tree: SyntaxTree,
        nodeId: String,
        memo: MutableMap<String, NodeHash>,
        visiting: MutableSet<String>,
    ): NodeHash {
        memo[nodeId]?.let { return it }
        check(visiting.add(nodeId)) { "cycle at $nodeId" }
        val node = checkNotNull(tree.getNode(nodeId)) { "missing node $nodeId" }
        val children = node.childIds.map { subtreeHash(tree, it, memo, visiting) }
        val hash = NodeHash.compute(node.kind, children, ContentHash.compute(node.text))
        visiting.remove(nodeId)
        memo[nodeId] = hash
        return hash
    }

    override fun facts(
        tree: SyntaxTree,
        queryIds: List<String>,
        cancellation: CancellationToken,
    ): ContractResult<FactBatch> {
        factsCalls += 1
        lastQueryIds = queryIds
        if (cancellation.isCancelled) {
            return failure(ContractError.Cancelled("SyntaxEngine.facts"))
        }
        factsError?.let { return failure(it) }
        factsOverride?.let { return success(it) }
        return success(factsFactory(tree))
    }
}

class FakeSemanticProvider : SemanticProvider {
    var providerLanguage: String = "test"
    override val language: String
        get() = providerLanguage

    var requestCalls: Int = 0
        private set
    val requests: MutableList<SemanticRequest> = mutableListOf()

    var executor: (SemanticRequest) -> ContractResult<SemanticResult> =
        { success(SemanticResult.DiagnosticsFor(emptyList())) }
    var throwOnRequest: Throwable? = null

    override fun request(request: SemanticRequest): ContractResult<SemanticResult> {
        requestCalls += 1
        requests += request
        throwOnRequest?.let { throw it }
        return executor(request)
    }

    override fun shutdown(): ContractResult<Unit> = success(Unit)
}

class FakeLanguageSupport(
    private val syntaxResult: ContractResult<SyntaxEngine>,
    private val semanticResult: ContractResult<SemanticProvider>,
) : LanguageSupport {
    constructor(
        syntax: SyntaxEngine,
        semantic: SemanticProvider,
    ) : this(success(syntax), success(semantic))

    var descriptorCalls: Int = 0
        private set

    override fun descriptor(): ContractResult<LanguageDescriptor> {
        descriptorCalls += 1
        return success(
            LanguageDescriptor(
                id = "test",
                name = "Test",
                extensions = listOf("kt"),
                grammarName = "test",
            ),
        )
    }

    override fun syntax(): ContractResult<SyntaxEngine> = syntaxResult

    override fun semanticProvider(): ContractResult<SemanticProvider> = semanticResult
}
