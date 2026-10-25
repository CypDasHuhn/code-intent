package dev.cint.contracts.data

import dev.cint.contracts.facts.CallFact
import dev.cint.contracts.facts.CommentFact
import dev.cint.contracts.facts.DeclarationFact
import dev.cint.contracts.facts.FactBatch
import dev.cint.contracts.facts.FunctionFact
import dev.cint.contracts.facts.GuardFact
import dev.cint.contracts.facts.IfFact
import dev.cint.contracts.facts.ImportFact
import dev.cint.contracts.facts.PropertyFact
import dev.cint.contracts.facts.ReferenceFact
import dev.cint.contracts.facts.ReturnFact
import dev.cint.contracts.facts.ScopeFact
import dev.cint.contracts.facts.StatementFact
import dev.cint.contracts.facts.SyntaxNode
import dev.cint.contracts.facts.SyntaxTree
import dev.cint.contracts.facts.TryFact
import dev.cint.contracts.facts.ValueFact
import dev.cint.contracts.facts.WriteFact
import dev.cint.contracts.util.ContentHash

data class LabelledAnalysisResult(
    val path: String,
    val result: AnalysisResult,
)

object AnalysisResultMerger {
    fun merge(analyses: List<LabelledAnalysisResult>): AnalysisResult {
        require(analyses.isNotEmpty()) { "At least one analysis result is required" }
        if (analyses.size == 1) return analyses.single().result
        return AnalysisResult(
            annotations =
                analyses
                    .flatMap { analyse -> analyse.result.annotations.map { qualifyAnnotation(analyse.path, it) } }
                    .sortedWith(
                        compareBy(
                            { it.span.startLine },
                            { it.startByte },
                            { it.source },
                            { it.intents.joinToString(",") },
                            { it.nodeAnchor?.id.orEmpty() },
                        ),
                    ),
            candidates =
                analyses
                    .flatMap { analyse ->
                        analyse.result.candidates.map { candidate -> candidate.copy(path = analyse.path) }
                    }.sortedWith(
                        compareBy({ it.path.orEmpty() }, { it.span.startLine }, { it.startByte }, { it.source }),
                    ),
            facts = mergeFacts(analyses.map { qualifyFacts(it.path, it.result.facts) }),
            sourceEpoch = analyses.maxOf { it.result.sourceEpoch },
            completeness = analyses.map { it.result.completeness }.reduce(::worstCompleteness),
            errors = analyses.flatMap { it.result.errors },
        )
    }

    private fun qualifyAnnotation(
        prefix: String,
        annotation: Annotation,
    ): Annotation =
        annotation.copy(
            nodeAnchor = NodeAnchor(annotationAnchor(prefix, annotation)),
        )

    private fun annotationAnchor(
        prefix: String,
        annotation: Annotation,
    ): String {
        val anchor = annotation.nodeAnchor?.id
        if (anchor != null) return qualify(prefix, anchor)
        val span = annotation.span
        return qualify(
            prefix,
            "span:${span.startLine}:${span.startCol}:${span.endLine}:${span.endCol}",
        )
    }

    private fun worstCompleteness(
        left: Completeness,
        right: Completeness,
    ): Completeness =
        when {
            left == Completeness.PARTIAL || right == Completeness.PARTIAL -> Completeness.PARTIAL
            left == Completeness.STALE || right == Completeness.STALE -> Completeness.STALE
            else -> Completeness.COMPLETE
        }

    private fun mergeTrees(trees: List<SyntaxTree>): SyntaxTree? {
        if (trees.isEmpty()) return null
        if (trees.size == 1) return trees.single()
        val nodes = linkedMapOf<String, SyntaxNode>()
        trees.forEach { tree ->
            tree.nodes.forEach { (id, node) ->
                nodes[id] = if (id == tree.rootId) node.copy(parentId = "merge-root") else node
            }
        }
        val rootIds = trees.map { it.rootId }
        val endByte = trees.maxOfOrNull { tree -> tree.nodes.values.maxOfOrNull { it.endByte } ?: 0 } ?: 0
        val endLine = trees.maxOfOrNull { tree -> tree.nodes.values.maxOfOrNull { it.endLine } ?: 0 } ?: 0
        val endCol =
            trees.maxOfOrNull { tree ->
                tree.nodes.values
                    .filter { it.endLine == endLine }
                    .maxOfOrNull { it.endCol } ?: 0
            } ?: 0
        nodes["merge-root"] =
            SyntaxNode(
                id = "merge-root",
                kind = "merged_source_file",
                startByte = 0,
                endByte = endByte,
                startLine = 0,
                startCol = 0,
                endLine = endLine,
                endCol = endCol,
                text = "",
                childIds = rootIds,
            )
        return SyntaxTree(
            rootId = "merge-root",
            nodes = nodes,
            language = trees.first().language,
            version = trees.joinToString("+") { it.version },
            contentHash = ContentHash.compute(trees.joinToString(":") { it.contentHash }).value,
        )
    }

    private fun mergeFacts(batches: List<FactBatch>): FactBatch =
        FactBatch(
            syntaxTree = mergeTrees(batches.mapNotNull { it.syntaxTree }),
            calls = batches.flatMap { it.calls },
            writes = batches.flatMap { it.writes },
            declarations = batches.flatMap { it.declarations },
            comments = batches.flatMap { it.comments },
            scopes = batches.flatMap { it.scopes },
            guards = batches.flatMap { it.guards },
            returns = batches.flatMap { it.returns },
            properties = batches.flatMap { it.properties },
            functions = batches.flatMap { it.functions },
            ifs = batches.flatMap { it.ifs },
            tries = batches.flatMap { it.tries },
            imports = batches.flatMap { it.imports },
            references = batches.flatMap { it.references },
            statements = batches.flatMap { it.statements },
            values = batches.flatMap { it.values },
        )

    private fun qualifyFacts(
        prefix: String,
        facts: FactBatch,
    ): FactBatch {
        fun id(value: String): String = qualify(prefix, value)

        fun ids(values: List<String>): List<String> = values.map(::id)

        fun idOrNull(value: String?): String? = value?.let(::id)

        fun tree(value: SyntaxTree): SyntaxTree {
            val nodes =
                value.nodes.values.associate { node ->
                    val newId = id(node.id)
                    newId to
                        node.copy(
                            id = newId,
                            parentId = idOrNull(node.parentId),
                            childIds = ids(node.childIds),
                        )
                }
            return value.copy(rootId = id(value.rootId), nodes = nodes)
        }

        return facts.copy(
            syntaxTree = facts.syntaxTree?.let(::tree),
            calls = facts.calls.map { qualifyCall(prefix, it) },
            writes = facts.writes.map { qualifyWrite(prefix, it) },
            declarations = facts.declarations.map { qualifyDeclaration(prefix, it) },
            comments = facts.comments.map { qualifyComment(prefix, it) },
            scopes = facts.scopes.map { qualifyScope(prefix, it) },
            guards = facts.guards.map { qualifyGuard(prefix, it) },
            returns = facts.returns.map { qualifyReturn(prefix, it) },
            properties = facts.properties.map { qualifyProperty(prefix, it) },
            functions = facts.functions.map { qualifyFunction(prefix, it) },
            ifs = facts.ifs.map { qualifyIf(prefix, it) },
            tries = facts.tries.map { qualifyTry(prefix, it) },
            imports = facts.imports.map { qualifyImport(prefix, it) },
            references = facts.references.map { qualifyReference(prefix, it) },
            statements = facts.statements.map { qualifyStatement(prefix, it) },
            values = facts.values.map { qualifyValue(prefix, it) },
        )
    }

    private fun qualify(
        prefix: String,
        id: String,
    ): String = "$prefix|$id"

    private fun qualifyCall(
        prefix: String,
        fact: CallFact,
    ): CallFact =
        fact.copy(
            nodeId = qualify(prefix, fact.nodeId),
            siteId = qualify(prefix, fact.siteId),
            receiverId = fact.receiverId?.let { qualify(prefix, it) },
            trailingLambdaId = fact.trailingLambdaId?.let { qualify(prefix, it) },
        )

    private fun qualifyWrite(
        prefix: String,
        fact: WriteFact,
    ): WriteFact =
        fact.copy(
            nodeId = qualify(prefix, fact.nodeId),
            referenceId = fact.referenceId?.let { qualify(prefix, it) },
            valueId = fact.valueId?.let { qualify(prefix, it) },
        )

    private fun qualifyDeclaration(
        prefix: String,
        fact: DeclarationFact,
    ): DeclarationFact =
        fact.copy(
            nodeId = qualify(prefix, fact.nodeId),
            nameNodeId = fact.nameNodeId?.let { qualify(prefix, it) },
            annotationNodeIds = fact.annotationNodeIds.map { qualify(prefix, it) },
            precedingCommentIds = fact.precedingCommentIds.map { qualify(prefix, it) },
            scopeId = fact.scopeId?.let { qualify(prefix, it) },
            writeIds = fact.writeIds.map { qualify(prefix, it) },
        )

    private fun qualifyComment(
        prefix: String,
        fact: CommentFact,
    ): CommentFact =
        fact.copy(
            nodeId = qualify(prefix, fact.nodeId),
        )

    private fun qualifyScope(
        prefix: String,
        fact: ScopeFact,
    ): ScopeFact =
        fact.copy(
            nodeId = qualify(prefix, fact.nodeId),
            ownerId = fact.ownerId?.let { qualify(prefix, it) },
        )

    private fun qualifyGuard(
        prefix: String,
        fact: GuardFact,
    ): GuardFact =
        fact.copy(
            nodeId = qualify(prefix, fact.nodeId),
            conditionId = fact.conditionId?.let { qualify(prefix, it) },
            thenBodyId = fact.thenBodyId?.let { qualify(prefix, it) },
            elseBodyId = fact.elseBodyId?.let { qualify(prefix, it) },
            exitIds = fact.exitIds.map { qualify(prefix, it) },
        )

    private fun qualifyReturn(
        prefix: String,
        fact: ReturnFact,
    ): ReturnFact =
        fact.copy(
            nodeId = qualify(prefix, fact.nodeId),
            expressionId = fact.expressionId?.let { qualify(prefix, it) },
            guardIds = fact.guardIds.map { qualify(prefix, it) },
            loopId = fact.loopId?.let { qualify(prefix, it) },
            handlerId = fact.handlerId?.let { qualify(prefix, it) },
            scopeId = fact.scopeId?.let { qualify(prefix, it) },
        )

    private fun qualifyProperty(
        prefix: String,
        fact: PropertyFact,
    ): PropertyFact =
        fact.copy(
            nodeId = qualify(prefix, fact.nodeId),
            nameNodeId = fact.nameNodeId?.let { qualify(prefix, it) },
            initializerId = fact.initializerId?.let { qualify(prefix, it) },
        )

    private fun qualifyFunction(
        prefix: String,
        fact: FunctionFact,
    ): FunctionFact =
        fact.copy(
            nodeId = qualify(prefix, fact.nodeId),
            nameNodeId = fact.nameNodeId?.let { qualify(prefix, it) },
            bodyId = fact.bodyId?.let { qualify(prefix, it) },
        )

    private fun qualifyIf(
        prefix: String,
        fact: IfFact,
    ): IfFact =
        fact.copy(
            nodeId = qualify(prefix, fact.nodeId),
            conditionId = fact.conditionId?.let { qualify(prefix, it) },
            thenBodyId = fact.thenBodyId?.let { qualify(prefix, it) },
            elseBodyId = fact.elseBodyId?.let { qualify(prefix, it) },
            elseKeywordId = fact.elseKeywordId?.let { qualify(prefix, it) },
        )

    private fun qualifyTry(
        prefix: String,
        fact: TryFact,
    ): TryFact =
        fact.copy(
            nodeId = qualify(prefix, fact.nodeId),
            bodyId = fact.bodyId?.let { qualify(prefix, it) },
            catchIds = fact.catchIds.map { qualify(prefix, it) },
            finallyBlockId = fact.finallyBlockId?.let { qualify(prefix, it) },
            headerStartId = fact.headerStartId?.let { qualify(prefix, it) },
            headerEndId = fact.headerEndId?.let { qualify(prefix, it) },
        )

    private fun qualifyImport(
        prefix: String,
        fact: ImportFact,
    ): ImportFact =
        fact.copy(
            nodeId = qualify(prefix, fact.nodeId),
        )

    private fun qualifyReference(
        prefix: String,
        fact: ReferenceFact,
    ): ReferenceFact =
        fact.copy(
            nodeIds = fact.nodeIds.map { qualify(prefix, it) },
        )

    private fun qualifyStatement(
        prefix: String,
        fact: StatementFact,
    ): StatementFact =
        fact.copy(
            nodeId = qualify(prefix, fact.nodeId),
            containerId = qualify(prefix, fact.containerId),
            expressionId = fact.expressionId?.let { qualify(prefix, it) },
        )

    private fun qualifyValue(
        prefix: String,
        fact: ValueFact,
    ): ValueFact =
        fact.copy(
            nodeId = qualify(prefix, fact.nodeId),
        )
}
