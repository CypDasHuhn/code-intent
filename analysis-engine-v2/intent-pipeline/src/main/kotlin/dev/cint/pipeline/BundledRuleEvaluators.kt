package dev.cint.pipeline

import dev.cint.contracts.data.CandidateReason
import dev.cint.contracts.data.Confidence
import dev.cint.contracts.data.Intents
import dev.cint.contracts.facts.CallFact
import dev.cint.contracts.facts.CommentFact
import dev.cint.contracts.facts.DeclarationFact
import dev.cint.contracts.facts.DeclarationKind
import dev.cint.contracts.facts.GuardKind
import dev.cint.contracts.facts.ScopeKind
import dev.cint.contracts.facts.SyntaxNode
import dev.cint.contracts.facts.WriteFact
import dev.cint.contracts.facts.WriteKind
import dev.cint.contracts.ports.CandidateEmission
import dev.cint.contracts.ports.EstablishedEmission
import dev.cint.contracts.ports.RuleEmission
import dev.cint.contracts.ports.RuleEvaluator
import dev.cint.contracts.ports.RuleEvaluatorRegistry
import dev.cint.contracts.ports.RuleEvaluationView
import dev.cint.contracts.ports.RuleSpec

object BundledRuleEvaluators {
    const val PROVISIONAL_SOURCE: String = "treesitter"

    fun registry(): RuleEvaluatorRegistry = RuleEvaluatorCatalog(evaluators())

    fun evaluators(): List<RuleEvaluator> = buildList {
        add(LoggingEvaluator())
        add(ErrorHandlingEvaluator())
        add(BookkeepingEvaluator())
        add(ValidationEvaluator())
        add(SearchEvaluator())
        add(LookupEvaluator())
        add(NormalizationEvaluator())
        add(DataTransformationEvaluator())
        add(DataModifyEvaluator())
        add(LocalValueModifyEvaluator())
        add(ServiceDelegationEvaluator())
        add(ServiceInitEvaluator())
        add(ReturnValueEvaluator())
        add(LocalCopyEvaluator())
    }.sortedBy { it.spec.id }
}

private abstract class V1Evaluator(override val spec: RuleSpec) : RuleEvaluator {
    protected fun RuleEvaluationView.emitNode(
        node: SyntaxNode?,
        confidence: Double,
        source: String = "treesitter",
    ): EstablishedEmission? =
        node?.let { emitRange(it, it, confidence, source) }

    protected fun RuleEvaluationView.emitRange(
        start: SyntaxNode,
        end: SyntaxNode,
        confidence: Double,
        source: String = "treesitter",
        certifiesReturn: Boolean = false,
    ): EstablishedEmission =
        EstablishedEmission(
            intents = setOf(spec.owns),
            span = dev.cint.contracts.data.Span(start.startLine, start.startCol, end.endLine, end.endCol),
            startByte = start.startByte,
            endByte = end.endByte,
            source = source,
            confidence = Confidence.fromDouble(confidence),
            certifiesReturn = certifiesReturn,
        )

    protected fun RuleEvaluationView.emitId(
        nodeId: String?,
        confidence: Double,
        source: String = "treesitter",
    ): EstablishedEmission? = node(nodeId)?.let { emitNode(it, confidence, source) }

    protected fun RuleEvaluationView.candidateNode(
        node: SyntaxNode?,
        confidence: Double = CANDIDATE_CONFIDENCE,
        reason: CandidateReason = CandidateReason.PACK_DOMAIN,
    ): CandidateEmission? =
        node?.let {
            CandidateEmission(
                intents = setOf(spec.owns),
                span = it.span,
                startByte = it.startByte,
                endByte = it.endByte,
                source = "rule:${spec.id}",
                confidence = Confidence.fromDouble(confidence),
                reason = reason,
                aiEligible = true,
            )
        }

    protected fun RuleEvaluationView.candidateId(
        nodeId: String?,
        confidence: Double = CANDIDATE_CONFIDENCE,
        reason: CandidateReason = CandidateReason.PACK_DOMAIN,
    ): CandidateEmission? = node(nodeId)?.let { candidateNode(it, confidence, reason) }
}

private class LoggingEvaluator : V1Evaluator(RuleSpec("logging:logging", Intents.LOGGING)) {
    override fun evaluate(view: RuleEvaluationView): List<RuleEmission> {
        val loggerSymbols = loggerSymbols(view)
        val hasLoggingImport =
            view.imports.any { import ->
                LOGGING_IMPORTS.any { import.path == it || import.path.startsWith("$it.") }
            }
        val result = mutableListOf<RuleEmission>()
        for (call in view.calls) {
            val callee = call.callee.orEmpty()
            val tokens = call.calleeSegments()
            val receiver = call.receiverText(view)
            val receiverName = receiver.substringAfterLast('.')
            when {
                PRINT_QUALIFIERS.any { receiver == it || receiver.endsWith(".$it") } &&
                    tokens.any { it in PRINT_CALLS || it in LOGGING_METHODS } ->
                    view.emitNode(view.node(call.nodeId), 1.0, "treesitter")?.let(result::add)
                tokens.any { it in PRINT_CALLS } && receiver.isEmpty() ->
                    view.emitNode(view.node(call.nodeId), 1.0, "treesitter")?.let(result::add)
                tokens.any { it in LOGGING_METHODS } && receiver.isNotEmpty() -> {
                    val evidence =
                        when {
                            receiverName in loggerSymbols -> 0.8 to "heuristic"
                            LOGGING_RECEIVER_NAMES.any { it.equals(receiverName, ignoreCase = true) } -> 0.6 to "heuristic"
                            PRINT_QUALIFIERS.any { receiver == it || receiver.endsWith(".$it") } -> 0.8 to "heuristic"
                            hasLoggingImport -> 0.5 to "heuristic"
                            else -> null
                        } ?: continue
                    view.emitNode(view.node(call.nodeId), evidence.first, evidence.second)?.let(result::add)
                }
            }
        }
        for (guard in view.guards) {
            val condition = view.node(guard.conditionId) ?: continue
            if (LOGGING_GUARD_NAMES.any { condition.text.contains(it, ignoreCase = true) }) {
                view.emitNode(condition, 0.7, "treesitter")?.let(result::add)
            }
        }
        return result.distinctBy { Triple(it.startByte, it.endByte, it.intents) }
    }

    private fun loggerSymbols(view: RuleEvaluationView): Set<String> {
        val callsBySpan = view.calls.associateBy { (view.node(it.nodeId)?.startByte ?: -1) to (view.node(it.nodeId)?.endByte ?: -2) }
        return view.properties
            .mapNotNull { property ->
                val name = property.name ?: return@mapNotNull null
                val initializer = view.node(property.initializerId) ?: return@mapNotNull null
                val path = callsBySpan[initializer.startByte to initializer.endByte]?.path.orEmpty()
                name.takeIf { pathContainsFactory(path) }
            }.toSet()
    }
}

private class ErrorHandlingEvaluator : V1Evaluator(RuleSpec("error-handling:error-handling", Intents.ERROR_HANDLING)) {
    override fun evaluate(view: RuleEvaluationView): List<RuleEmission> {
        val result = mutableListOf<RuleEmission>()
        for (call in view.calls) {
            val callee = call.callee.orEmpty()
            if (callee == "Error" || callee == "Exception") continue
            val receiver = call.receiverText(view)
            val receiverName = receiver.substringAfterLast('.')
            val loggingCall =
                callee.lowercase() in LOGGING_METHODS &&
                    (
                        LOGGING_RECEIVER_NAMES.any { it.equals(receiverName, ignoreCase = true) } ||
                            PRINT_QUALIFIERS.any { receiver == it || receiver.endsWith(".$it") }
                    )
            if (loggingCall) continue
            if (call.calleeSegments().any { it in ERROR_CALLS }) {
                view.emitNode(view.node(call.nodeId), 1.0, "treesitter")?.let(result::add)
            }
        }
        val root = view.rootNode()
        if (root != null) {
            for (node in view.descendants(root)) {
                val text = node.text.trim()
                if (node.kind.contains("throw") || text.startsWith("throw ") || text.startsWith("raise ")) {
                    if (node.kind in setOf("throw_expression", "throw_statement", "raise_statement") ||
                        text.startsWith("throw ") || text.startsWith("raise ")
                    ) {
                        view.emitNode(node, 1.0, "treesitter")?.let(result::add)
                    }
                }
            }
        }
        return result.distinctBy { Triple(it.startByte, it.endByte, it.intents) }
    }
}

private class BookkeepingEvaluator : V1Evaluator(RuleSpec("bookkeeping:bookkeeping", Intents.BOOKKEEPING)) {
    override fun evaluate(view: RuleEvaluationView): List<RuleEmission> {
        val result = mutableListOf<RuleEmission>()
        for (declaration in view.declarations) {
            if (declaration.kind == DeclarationKind.FUNCTION) continue
            val node = view.node(declaration.nodeId) ?: continue
            val source =
                when {
                    declaration.annotations.any { annotationName(it) == "Bookkeeping" } ||
                        view.node(declaration.nodeId)?.text?.contains("@Bookkeeping") == true -> "annotation"
                    declaration.precedingCommentIds.any { id -> view.node(id)?.text?.contains("cint:bookkeeping") == true } -> "comment"
                    view.comments.any { comment -> isBookkeepingComment(view, comment, declaration, node) } -> "comment"
                    else -> continue
                }
            val start =
                if (source == "comment") {
                    precedingComment(view, declaration, node) ?: node
                } else {
                    node
                }
            result += view.emitRange(start, node.trimmedEnd(), 1.0, source)
            val writeIds =
                if (declaration.name != null) {
                    (declaration.writeIds + view.writes.filter { it.targetName == declaration.name }.map { it.nodeId }).distinct()
                } else {
                    declaration.writeIds
                }
            for (writeId in writeIds) {
                view.node(writeId)?.let { write -> view.emitNode(write, 1.0, source)?.let(result::add) }
            }
        }
        return result.distinctBy { Triple(it.startByte, it.endByte, it.intents) }
    }

    private fun precedingComment(
        view: RuleEvaluationView,
        declaration: DeclarationFact,
        node: SyntaxNode,
    ): SyntaxNode? =
        declaration.precedingCommentIds.mapNotNull { view.node(it) }.maxByOrNull { it.startByte }
            ?: view.comments
                .mapNotNull { view.node(it.nodeId) }
                .filter { it.endByte <= node.startByte && node.startLine - it.endLine <= 1 }
                .filter { it.text.contains("cint:bookkeeping") }
                .maxByOrNull { it.startByte }

    private fun isBookkeepingComment(
        view: RuleEvaluationView,
        comment: CommentFact,
        declaration: DeclarationFact,
        node: SyntaxNode,
    ): Boolean {
        val commentNode = view.node(comment.nodeId) ?: return false
        return commentNode.endByte <= node.startByte &&
            node.startLine - commentNode.endLine <= 1 &&
            commentNode.text.contains("cint:bookkeeping")
    }
}

private class ValidationEvaluator :
    V1Evaluator(
        RuleSpec(
            "validation:validation",
            Intents.VALIDATION,
            reads = listOf(Intents.BOOKKEEPING, Intents.DATA_MODIFY, Intents.LOCAL_VALUE_MODIFY),
        ),
    ) {
    override fun evaluate(view: RuleEvaluationView): List<RuleEmission> {
        val result = mutableListOf<RuleEmission>()
        for (guard in view.guards) {
            if (guard.kind != GuardKind.CONDITIONAL) continue
            val condition = view.node(guard.conditionId) ?: continue
            val body = view.node(guard.thenBodyId) ?: continue
            val explicit = hasValidationComment(view, guard.nodeId, condition)
            val bookkeeping = view.annotations(Intents.BOOKKEEPING).any { it.startByte >= body.startByte && it.endByte <= body.endByte }
            val predicate = callsWithin(view, condition).any { call -> isPredicate(call.callee.orEmpty()) }
            val rejection = hasRejectingExit(view, body) || view.node(guard.elseBodyId)?.let { hasRejectingExit(view, it) } == true
            val pure = rejection && callsWithin(view, condition).isEmpty() && !hasWriteWithin(view, condition)
            val readOnly = rejection && !hasWriteWithin(view, condition)
            val constant = isConstantCondition(condition)
            val established =
                (explicit || bookkeeping || (predicate && rejection) || pure || readOnly) &&
                    !(constant && !explicit && !bookkeeping)
            val emittedCondition = trimCondition(condition)
            if (established) {
                val confidence =
                    when {
                        explicit -> 1.0
                        bookkeeping -> 0.9
                        predicate -> 0.7
                        pure -> 0.6
                        else -> 0.5
                    }
                val source = if (explicit) "comment" else "treesitter"
                view.emitNode(emittedCondition, confidence, source)?.copy(certifiesReturn = true)?.let(result::add)
            } else if (hasValidationComment(view, guard.nodeId, condition, maxDistance = 3)) {
                view.candidateNode(emittedCondition, 0.4, CandidateReason.GAP)?.let { result += it }
            }
        }
        val root = view.rootNode()
        if (root != null) {
            for (node in view.descendants(root)) {
                if (!isElvis(view, node)) continue
                val right =
                    node.childIds
                        .mapNotNull { view.node(it) }
                        .lastOrNull { it.text.trim() !in COALESCE_TOKENS } ?: continue
                if (!hasRejectingExit(view, right)) continue
                if (hasKnownWrites(view, node)) continue
                view.emitNode(node, 0.6, "treesitter")?.let(result::add)
            }
        }
        return result.distinctBy { Triple(it.startByte, it.endByte, it.intents) }
    }

    private fun isElvis(
        view: RuleEvaluationView,
        node: SyntaxNode,
    ): Boolean =
        node.kind == "elvis_expression" ||
            (
                node.kind == "binary_expression" &&
                    node.childIds.mapNotNull { view.node(it) }.any { it.text.trim() in COALESCE_TOKENS }
            )

    private fun hasKnownWrites(view: RuleEvaluationView, node: SyntaxNode): Boolean =
        view.writes.any { write -> view.node(write.nodeId)?.let { within(it, node) } == true } ||
            setOf(Intents.BOOKKEEPING, Intents.DATA_MODIFY, Intents.LOCAL_VALUE_MODIFY).any { intent ->
                view.annotations(intent).any { it.startByte >= node.startByte && it.endByte <= node.endByte }
            }

    private fun hasValidationComment(
        view: RuleEvaluationView,
        guardId: String,
        condition: SyntaxNode,
        maxDistance: Int = 1,
    ): Boolean {
        val guard = view.node(guardId) ?: return false
        val comment =
            view.comments
                .mapNotNull { view.node(it.nodeId) }
                .filter { it.endByte <= guard.startByte }
                .filter { guard.startLine - it.endLine <= maxDistance }
                .maxByOrNull { it.startByte } ?: return false
        return Regex("""cint\s*:\s*validation""", RegexOption.IGNORE_CASE).containsMatchIn(comment.text)
    }

    private fun trimCondition(condition: SyntaxNode): SyntaxNode {
        var value = condition.text.trim()
        var start = condition.startByte
        var startCol = condition.startCol
        var end = condition.endByte
        var endCol = condition.endCol
        while (value.startsWith("(") && value.endsWith(")")) {
            value = value.substring(1, value.length - 1).trim()
            start++
            startCol++
            end--
            endCol--
        }
        return condition.copy(startByte = start, startCol = startCol, endByte = end, endCol = endCol)
    }

    private fun isPredicate(callee: String): Boolean =
        PREDICATE_CALLS.any { it.equals(callee, ignoreCase = true) } ||
            PREDICATE_PREFIXES.any { callee.startsWith(it) }

    private fun callsWithin(view: RuleEvaluationView, node: SyntaxNode): List<CallFact> =
        view.calls.filter { call -> view.node(call.nodeId)?.let { within(it, node) } == true }

    private fun hasWriteWithin(view: RuleEvaluationView, node: SyntaxNode): Boolean =
        view.writes.any { write -> view.node(write.nodeId)?.let { within(it, node) } == true }

    private fun hasRejectingExit(view: RuleEvaluationView, body: SyntaxNode): Boolean =
        view.descendants(body).any { node ->
            val text = node.text.trim().removeSuffix(";").trim()
            when {
                text.startsWith("continue") || text.startsWith("break") -> true
                text.startsWith("return") -> {
                    val expression = node.childIds.firstOrNull { child ->
                        view.node(child)?.text?.trim() != "return"
                    }?.let { view.node(it) }
                    if (expression == null) true else expression.text.trim() in REJECTION_VALUES
                }
                else -> false
            }
        }

    private fun isConstantCondition(condition: SyntaxNode): Boolean {
        val text = condition.text.trim()
        if (text in setOf("true", "false")) return true
        return CONSTANT_COMPARISON.matches(text)
    }
}

private class SearchEvaluator : V1Evaluator(RuleSpec("data-access:search", Intents.SEARCH)) {
    override fun evaluate(view: RuleEvaluationView): List<RuleEmission> {
        val result = mutableListOf<RuleEmission>()
        for (call in view.calls) {
            val callee = call.callee.orEmpty()
            if (isLookupOnly(callee)) continue
            val receiver = call.receiverText(view)
            val configured = SEARCH_CALLS.any { it.equals(callee, ignoreCase = true) } || SEARCH_PREFIXES.any { callee.startsWith(it) }
            val established = configured && receiver.isNotEmpty() && SEARCH_QUALIFIERS.any { qualifierMatches(receiver, it) }
            when {
                established -> view.emitNode(view.node(call.nodeId), 1.0, "treesitter")?.let(result::add)
                isAmbiguous(callee) -> view.candidateNode(view.node(call.nodeId))?.let { result += it }
                configured -> view.candidateNode(view.node(call.nodeId))?.let { result += it }
            }
        }
        return result.distinctBy { Triple(it.startByte, it.endByte, it.intents) }
    }
}

private class LookupEvaluator : V1Evaluator(RuleSpec("data-access:lookup", Intents.LOOKUP)) {
    override fun evaluate(view: RuleEvaluationView): List<RuleEmission> {
        val result = mutableListOf<RuleEmission>()
        for (call in view.calls) {
            val callee = call.callee.orEmpty()
            val receiver = call.receiverText(view)
            val configured = LOOKUP_CALLS.any { it.equals(callee, ignoreCase = true) } || isAmbiguous(callee)
            val knownReceiver = receiver.isNotEmpty() && LOOKUP_QUALIFIERS.any { qualifierMatches(receiver, it) }
            when {
                configured && knownReceiver ->
                    view.emitNode(view.node(call.nodeId), 1.0, "treesitter")?.let(result::add)
                isAmbiguous(callee) || configured -> view.candidateNode(view.node(call.nodeId))?.let { result += it }
            }
        }
        return result.distinctBy { Triple(it.startByte, it.endByte, it.intents) }
    }
}

private class NormalizationEvaluator : V1Evaluator(RuleSpec("normalization:normalization", Intents.NORMALIZATION)) {
    override fun evaluate(view: RuleEvaluationView): List<RuleEmission> {
        val result = mutableListOf<RuleEmission>()
        for (call in view.calls) {
            val callee = call.callee.orEmpty()
            val receiver = call.receiverText(view)
            val qualified = receiver.isNotEmpty() && NORMALIZATION_QUALIFIERS.any { receiver == it || receiver.endsWith(".$it") }
            when {
                NORMALIZATION_CALLS.any { it == callee } ->
                    if (qualified || receiver.isEmpty() || callee in NORMALIZATION_INSTANCE_CALLS) {
                        view.emitNode(view.node(call.nodeId), 0.9, "treesitter")?.let(result::add)
                    } else {
                        view.candidateNode(view.node(call.nodeId))?.let { result += it }
                    }
                NORMALIZATION_CANDIDATE_CALLS.any { it == callee } ||
                    NORMALIZATION_CANDIDATE_PREFIXES.any { callee.startsWith(it) } ->
                    view.candidateNode(view.node(call.nodeId))?.let { result += it }
            }
        }
        return result.distinctBy { Triple(it.startByte, it.endByte, it.intents) }
    }
}

private class DataTransformationEvaluator :
    V1Evaluator(
        RuleSpec(
            "data-transformation:data-transformation",
            Intents.DATA_TRANSFORMATION,
            reads = listOf(Intents.SEARCH),
        ),
    ) {
    override fun evaluate(view: RuleEvaluationView): List<RuleEmission> {
        val result = mutableListOf<RuleEmission>()
        for (call in view.calls) {
            val callee = call.callee.orEmpty()
            val receiver = call.receiverText(view)
            val receiverNode = view.node(call.receiverId)
            val receiverHasSearch =
                receiverNode != null &&
                    view.annotations(Intents.SEARCH).any { it.startByte >= receiverNode.startByte && it.endByte <= receiverNode.endByte }
            val established =
                TRANSFORMATION_CALLS.any { it == callee } &&
                    (
                        receiver.isNotEmpty() &&
                            (TRANSFORMATION_QUALIFIERS.any { qualifierMatches(receiver, it) } ||
                                receiverHasSearch ||
                                view.intentsAt(receiverNode ?: continue).any {
                                    it == Intents.SEARCH || it == Intents.DATA_TRANSFORMATION
                                })
                    )
            when {
                established -> view.emitNode(view.node(call.nodeId), 0.9, "treesitter")?.let(result::add)
                TRANSFORMATION_CANDIDATE_CALLS.any { it == callee } ||
                    TRANSFORMATION_CANDIDATE_PREFIXES.any { callee.startsWith(it) } ->
                    view.candidateNode(view.node(call.nodeId), reason = CandidateReason.LOW_CONFIDENCE)?.let { result += it }
            }
        }
        return result.distinctBy { Triple(it.startByte, it.endByte, it.intents) }
    }
}

private class DataModifyEvaluator : V1Evaluator(RuleSpec("mutation:data-modify", Intents.DATA_MODIFY)) {
    override fun evaluate(view: RuleEvaluationView): List<RuleEmission> {
        val result = mutableListOf<RuleEmission>()
        for (call in view.calls) {
            val callee = call.callee.orEmpty()
            val path = call.path.orEmpty()
            val receiver = call.receiverText(view)
            val receiverName = receiver.substringAfterLast('.').trim('_')
            val persistent = persistentReceiver(receiver, receiverName)
            val exactPath = PERSISTENCE_PATHS.any { path == it || path.endsWith(".$it") }
            val configured = configuredPersistence(callee, path)
            when {
                durableCallback(view, call) || exactPath || (configured && persistent) ->
                    view.emitNode(view.node(call.nodeId), 0.9, "treesitter")?.let(result::add)
                persistenceCandidate(view, call) ->
                    view.candidateNode(view.node(call.nodeId))?.let { result += it }
            }
        }
        return result.distinctBy { Triple(it.startByte, it.endByte, it.intents) }
    }
}

private class LocalValueModifyEvaluator :
    V1Evaluator(
        RuleSpec(
            "mutation:local-value-modify",
            Intents.LOCAL_VALUE_MODIFY,
            reads = listOf(Intents.BOOKKEEPING, Intents.LOCAL_COPY),
        ),
    ) {
    override fun evaluate(view: RuleEvaluationView): List<RuleEmission> {
        val result = mutableListOf<RuleEmission>()
        val localNames = view.declarations.filter { it.kind == DeclarationKind.VARIABLE }.mapNotNull { it.name }.toSet()
        val bookkeepingSpans = view.annotations(Intents.BOOKKEEPING).map { it.startByte to it.endByte }.toSet()
        for (write in view.writes) {
            val node = view.node(write.nodeId) ?: continue
            if (write.kind == WriteKind.INCREMENT && node.text.contains("++") && isLoopCounter(view, node)) continue
            if (bookkeepingSpans.any { node.startByte >= it.first && node.endByte <= it.second }) continue
            if (Intents.LOCAL_COPY in view.intentsAt(node)) continue
            if (write.targetName != null && write.targetName in localNames) {
                view.emitNode(node, 0.8, "treesitter")?.let(result::add)
            } else if (write.kind == WriteKind.ASSIGNMENT && write.targetName != null) {
                view.candidateNode(node)?.let { result += it }
            }
        }
        for (call in view.calls) {
            val callee = call.callee.orEmpty()
            val receiver = call.receiverText(view)
            val receiverName = receiver.substringAfterLast('.').trim()
            val mutableReceiver = MUTABLE_RECEIVER_NAMES.any { it.equals(receiverName, true) }
            if (persistenceEstablished(view, call) || persistentReceiver(receiver, receiverName.trim('_'))) continue
            if (persistenceCandidate(view, call)) continue
            when {
                MUTABLE_OPERATIONS.any { it == callee } && mutableReceiver ->
                    view.emitNode(view.node(call.nodeId), 0.8, "treesitter")?.let(result::add)
                MUTABLE_OPERATIONS.any { it == callee } || isLocalCandidate(callee) ->
                    view.candidateNode(view.node(call.nodeId))?.let { result += it }
            }
        }
        return result.distinctBy { Triple(it.startByte, it.endByte, it.intents) }
    }

    private fun isLoopCounter(view: RuleEvaluationView, node: SyntaxNode): Boolean {
        val parent = node.parentId?.let { view.node(it) } ?: return false
        return parent.kind in setOf("for_statement", "for_in_statement", "while_statement", "do_statement")
    }
}

private class ServiceDelegationEvaluator :
    V1Evaluator(
        RuleSpec(
            "service:delegation",
            Intents.SERVICE_DELEGATION,
            optionalReads = listOf(Intents.DATA_MODIFY),
        ),
    ) {
    override fun evaluate(view: RuleEvaluationView): List<RuleEmission> {
        val result = mutableListOf<RuleEmission>()
        for (call in view.calls) {
            val callee = call.callee.orEmpty()
            val path = call.path.orEmpty()
            val receiver = call.receiverText(view)
            val receiverName = receiver.substringAfterLast('.').trim('_')
            if (initEvidence(view, call) != null) continue
            val repositoryLike = receiver.endsWith("repository", true) || receiver.endsWith("repo", true) || receiver.endsWith("dao", true) || receiver.endsWith("client", true) || receiver.endsWith("api", true)
            if (repositoryLike &&
                (SEARCH_CALLS.any { it.equals(callee, true) } ||
                    LOOKUP_CALLS.any { it.equals(callee, true) } ||
                    PERSISTENCE_CALLS.any { it == callee })
            ) {
                continue
            }
            if (PERSISTENCE_CALLS.any { it == callee } &&
                !SERVICE_NAMES.any { it.equals(receiverName, true) } &&
                !SERVICE_SUFFIXES.any { receiverName.endsWith(it, true) }
            ) {
                continue
            }
            val evidence =
                when {
                    DELEGATION_CALLS.any { it == callee } || path.endsWith(".${DELEGATION_CALLS.firstOrNull { path.endsWith(it) }.orEmpty()}") ->
                        0.9 to "treesitter"
                    DELEGATION_QUALIFIERS.any { path == it || path.startsWith("$it.") } -> 0.85 to "treesitter"
                    SERVICE_NAMES.any { it.equals(receiverName, true) } -> 0.75 to "heuristic"
                    SERVICE_SUFFIXES.any { receiverName.endsWith(it, true) } -> 0.75 to "heuristic"
                    else -> null
                }
            if (evidence != null) {
                view.emitNode(view.node(call.nodeId), evidence.first, evidence.second)?.let(result::add)
            } else if (callee in parameterNames(view, call)) {
                view.candidateNode(view.node(call.nodeId), reason = CandidateReason.LOW_CONFIDENCE)?.let { result += it }
            }
        }
        return result.distinctBy { Triple(it.startByte, it.endByte, it.intents) }
    }
}

private class ServiceInitEvaluator : V1Evaluator(RuleSpec("service:init", Intents.SERVICE_INIT)) {
    override fun evaluate(view: RuleEvaluationView): List<RuleEmission> {
        val result = mutableListOf<RuleEmission>()
        val creations = creationNodes(view)
        for (call in view.calls) {
            val evidence = initEvidence(view, call) ?: continue
            view.emitNode(view.node(call.nodeId), evidence.first, evidence.second)?.let(result::add)
        }
        for ((node, type) in creations) {
            if (type == null || !INIT_TYPES.any { type.endsWith(it) || type.endsWith(it + "Client") } && !type.endsWith("Factory") && !type.endsWith("Client")) continue
            view.emitNode(node, 0.85, "treesitter")?.let(result::add)
        }
        for (node in constructorInjectionNodes(view)) {
            view.emitNode(node, 0.8, "heuristic")?.let(result::add)
        }
        for (declaration in view.declarations) {
            if (declaration.kind != DeclarationKind.FUNCTION) continue
            val name = declaration.name ?: continue
            if (name !in INIT_METHODS) continue
            val node = view.node(declaration.nameNodeId ?: declaration.nodeId) ?: continue
            val function = view.functions.firstOrNull { it.nodeId == declaration.nodeId } ?: continue
            val hasEvidence =
                view.calls.any { call ->
                    val callNode = view.node(call.nodeId) ?: return@any false
                    within(callNode, view.node(function.nodeId) ?: continue) && initEvidence(view, call) != null
                } || creations.any { (creation, _) -> within(creation, view.node(function.nodeId) ?: continue) }
            if (hasEvidence) view.emitNode(node, 0.8, "heuristic")?.let(result::add)
        }
        for (declaration in view.declarations) {
            if (declaration.annotations.any { annotationName(it) in DI_ANNOTATIONS }) {
                val node = view.node(declaration.annotationNodeIds.firstOrNull() ?: declaration.nodeId) ?: continue
                view.emitNode(node, 0.95, "annotation")?.let(result::add)
            }
        }
        for (function in view.functions) {
            val name = function.name ?: continue
            if (name in INIT_METHODS) continue
            val node = view.node(function.nameNodeId ?: function.nodeId) ?: continue
            if (mostlyInit(view, function.nodeId, creations)) {
                view.candidateNode(node, reason = CandidateReason.LOW_CONFIDENCE)
                    ?.copy(rationale = "hidden initialization helper")
                    ?.let { result += it }
            }
        }
        return result.distinctBy { Triple(it.startByte, it.endByte, it.intents) }
    }

    private fun constructorInjectionNodes(view: RuleEvaluationView): List<SyntaxNode> {
        val result = mutableListOf<SyntaxNode>()
        val classNodes =
            view.scopes
                .filter { it.kind == ScopeKind.CLASS }
                .mapNotNull { view.node(it.nodeId) }
                .distinctBy { it.startByte to it.endByte }
        for (classNode in classNodes) {
            val params = PRIMARY_CONSTRUCTOR.find(classNode.text)?.groupValues?.get(1) ?: continue
            result += injectionParameters(view, classNode, params)
        }
        for (function in view.functions) {
            val functionNode = view.node(function.nodeId) ?: continue
            val classNode =
                classNodes
                    .filter { within(functionNode, it) }
                    .minByOrNull { it.endByte - it.startByte } ?: continue
            val className = classNameOf(classNode) ?: continue
            val name = function.name ?: continue
            val constructor = name == className || name == "constructor"
            if (!constructor) continue
            val params = FUNCTION_PARAMETERS.find(functionNode.text)?.groupValues?.get(1) ?: continue
            result += injectionParameters(view, functionNode, params)
        }
        return result.distinctBy { it.startByte to it.endByte }
    }

    private fun injectionParameters(
        view: RuleEvaluationView,
        container: SyntaxNode,
        params: String,
    ): List<SyntaxNode> =
        splitParameters(params).mapNotNull { parameter ->
            val type = injectionParameterType(parameter) ?: return@mapNotNull null
            if (!isWiringType(type)) return@mapNotNull null
            findTextNode(view, container, parameter) ?: container
        }

    private fun injectionParameterType(parameter: String): String? {
        val declaration = parameter.substringBefore('=').trim()
        PROPERTY_PARAMETER.find(declaration)?.let { return it.groupValues[2].trim() }
        TYPESCRIPT_PARAMETER_PROPERTY.find(declaration)?.let { return it.groupValues[2].trim() }
        val withoutAttribute = declaration.replace(Regex("""^\s*\[[^\]]*]\s*"""), "")
        return PARAMETER_TYPE.find(withoutAttribute)?.groupValues?.get(1)?.trim()
    }

    private fun isWiringType(type: String): Boolean =
        TYPE_TOKEN.findAll(type).any { match ->
            typeMatches(
                match.value,
                SERVICE_TYPES + INIT_TYPES,
                SERVICE_SUFFIXES + INIT_SUFFIXES,
            )
        }

    private fun findTextNode(
        view: RuleEvaluationView,
        container: SyntaxNode,
        text: String,
    ): SyntaxNode? {
        val target = text.trim()
        return view
            .descendants(container)
            .filter { it.text.trim() == target }
            .minByOrNull { it.endByte - it.startByte }
    }

    private fun classNameOf(node: SyntaxNode): String? =
        CLASS_NAME.find(node.text)?.groupValues?.get(1)

    private fun mostlyInit(
        view: RuleEvaluationView,
        functionId: String,
        creations: List<Pair<SyntaxNode, String?>>,
    ): Boolean {
        val function = view.node(functionId) ?: return false
        return creations.any { (creation, type) ->
            within(creation, function) && type != null && INIT_TYPES.any { type.endsWith(it) }
        } || view.calls.any { call ->
            val node = view.node(call.nodeId) ?: return@any false
            within(node, function) && initEvidence(view, call) != null
        }
    }
}

private class ReturnValueEvaluator : V1Evaluator(RuleSpec("return-value:return-value", Intents.RETURN_VALUE)) {
    override fun evaluate(view: RuleEvaluationView): List<RuleEmission> {
        val result = mutableListOf<RuleEmission>()
        for (ret in view.returns) {
            val statement = view.node(ret.nodeId) ?: continue
            val statementText = statement.text.trim()
            if (BARE_LABELED_RETURN.matches(statementText) ||
                statementText.startsWith("throw") ||
                statementText.startsWith("continue") ||
                statementText.startsWith("break")
            ) {
                continue
            }
            val expression = view.node(ret.expressionId) ?: continue
            if (isLiteral(expression.text)) continue
            result += view.emitRange(statement, expressionEnd(statement, expression), 0.9, "treesitter")
        }
        return result.distinctBy { Triple(it.startByte, it.endByte, it.intents) }
    }
}

private class LocalCopyEvaluator : V1Evaluator(RuleSpec("local-copy:local-copy", Intents.LOCAL_COPY)) {
    override fun evaluate(view: RuleEvaluationView): List<RuleEmission> {
        val result = mutableListOf<RuleEmission>()
        val declared = view.properties.mapNotNull { it.name }.toSet()
        val localNames = view.declarations.filter { it.kind == DeclarationKind.VARIABLE }.mapNotNull { it.name }.toSet()
        for (write in view.writes) {
            if (write.kind != WriteKind.ASSIGNMENT) continue
            val node = view.node(write.nodeId) ?: continue
            val target = write.targetName ?: continue
            val value = view.node(write.valueId)?.text?.trim() ?: continue
            if (!SIMPLE_IDENTIFIER.matches(value)) continue
            if (isFieldLike(target) && !isFieldLike(value) && value !in declared) {
                view.emitNode(node, 0.8, "heuristic")?.let(result::add)
            } else if (target in localNames || isFieldLike(target)) {
                view.candidateNode(node, reason = CandidateReason.LOW_CONFIDENCE)?.let { result += it }
            }
        }
        for (property in view.properties) {
            val name = property.name ?: continue
            val initializer = view.node(property.initializerId) ?: continue
            val value = initializer.text.trim()
            if (!SIMPLE_IDENTIFIER.matches(value) || name == value) continue
            if (isFieldLike(name) && !isFieldLike(value) && value !in declared) {
                view.emitNode(view.node(property.nodeId), 0.8, "heuristic")?.let(result::add)
            }
        }
        for (call in view.calls) {
            val callee = call.callee.orEmpty()
            if (callee in LOCAL_COPY_CALLS) {
                view.emitNode(view.node(call.nodeId), 0.55, "heuristic")?.let(result::add)
            }
        }
        return result.distinctBy { Triple(it.startByte, it.endByte, it.intents) }
    }
}

private fun RuleEvaluationView.rootNode(): SyntaxNode? {
    val seed =
        calls.firstOrNull()?.nodeId?.let(::node)
            ?: functions.firstOrNull()?.nodeId?.let(::node)
            ?: declarations.firstOrNull()?.nodeId?.let(::node)
            ?: properties.firstOrNull()?.nodeId?.let(::node)
            ?: comments.firstOrNull()?.nodeId?.let(::node)
            ?: return null
    var current = seed
    while (current.parentId != null) {
        current = node(current.parentId!!) ?: return current
    }
    return current
}

private fun CallFact.receiverText(view: RuleEvaluationView): String {
    view.node(receiverId)?.let { return it.text.trim() }
    val path = path.orEmpty()
    return if (path.contains('.')) path.substringBeforeLast('.').trim() else ""
}

private fun CallFact.calleeSegments(): List<String> = tokenize(callee.orEmpty())

private fun tokenize(value: String): List<String> {
    val tokens = mutableListOf<String>()
    val current = StringBuilder()
    fun flush() {
        if (current.isNotEmpty()) {
            tokens += current.toString().lowercase()
            current.clear()
        }
    }
    for (index in value.indices) {
        val character = value[index]
        if (!character.isLetterOrDigit()) {
            flush()
            continue
        }
        if (current.isNotEmpty()) {
            val previous = value[index - 1]
            val next = value.getOrNull(index + 1)
            if ((previous.isLowerCase() || previous.isDigit()) && character.isUpperCase()) flush()
            if (previous.isUpperCase() && character.isUpperCase() && next != null && next.isLowerCase()) flush()
        }
        current.append(character)
    }
    flush()
    return tokens
}

private fun annotationName(text: String): String =
    text.trim()
        .trim('[', ']')
        .removePrefix("@")
        .substringBefore('(')
        .trim()
        .substringAfterLast('.')
        .substringAfterLast(':')

private fun expressionEnd(statement: SyntaxNode, expression: SyntaxNode): SyntaxNode {
    val statementText = statement.text
    val expressionText = expression.text
    val index = statementText.indexOf(expressionText)
    if (index < 0) return expression
    val consumed = statementText.substring(0, index + expressionText.length)
    val lines = consumed.split('\n')
    return expression.copy(
        startByte = statement.startByte + index,
        startLine = statement.startLine + lines.size - 1,
        startCol = if (lines.size == 1) statement.startCol + index else lines.last().length,
        endByte = statement.startByte + consumed.length,
        endLine = statement.startLine + lines.size - 1,
        endCol = if (lines.size == 1) statement.startCol + consumed.length else lines.last().length,
    )
}

private fun parameterNames(view: RuleEvaluationView, call: CallFact): Set<String> =
    parameterDeclarations(view, call).mapNotNull(::parameterName).toSet()

private fun parameterDeclarations(view: RuleEvaluationView, call: CallFact): List<String> {
    val callNode = view.node(call.nodeId) ?: return emptyList()
    val function = view.enclosingFunction(callNode) ?: return emptyList()
    val text = view.node(function.nodeId)?.text ?: return emptyList()
    val open = text.indexOf('(')
    if (open < 0) return emptyList()
    var depth = 0
    var close = -1
    for (index in open until text.length) {
        if (text[index] == '(') depth++ else if (text[index] == ')') {
            depth--
            if (depth == 0) { close = index; break }
        }
    }
    if (close <= open) return emptyList()
    return splitParameters(text.substring(open + 1, close))
}

private fun parameterName(parameter: String): String? =
    parameter.substringBefore(':').substringBefore('=').trim().split(Regex("\\s+")).lastOrNull()?.takeIf { it.isNotBlank() }

private fun parameterDefault(parameter: String): String? {
    val assignment = assignmentIndex(parameter)
    if (assignment < 0) return null
    return parameter.substring(assignment + 1).trim().ifEmpty { null }
}

private fun parameterType(parameter: String): String? {
    val assignment = assignmentIndex(parameter)
    val signature = if (assignment >= 0) parameter.substring(0, assignment) else parameter
    val colon = signature.indexOf(':')
    if (colon < 0) return null
    return signature.substring(colon + 1).trim().ifEmpty { null }
}

private fun durableCallback(view: RuleEvaluationView, call: CallFact): Boolean {
    val callee = call.callee.orEmpty()
    if (callee.isEmpty()) return false
    for (parameter in parameterDeclarations(view, call)) {
        if (parameterName(parameter) != callee) continue
        val default = parameterDefault(parameter) ?: continue
        if (!isFunctionTyped(parameterType(parameter), default)) continue
        if ((PERSISTENCE_PATHS + PERSISTENCE_CALLS).any { target -> referencesTarget(default, target) }) return true
    }
    return false
}

private fun isFunctionTyped(type: String?, default: String): Boolean {
    val declared = type.orEmpty()
    if (FUNCTION_TYPE_MARKERS.any { declared.contains(it) }) return true
    return FUNCTION_DEFAULT_MARKERS.any { default.contains(it) }
}

private fun referencesTarget(default: String, target: String): Boolean {
    if (!target.contains('.')) return false
    val normalized = default.replace("::", ".")
    return Regex("""(?<![A-Za-z0-9_$])${Regex.escape(target)}(?![A-Za-z0-9_$])""").containsMatchIn(normalized)
}

private fun splitParameters(parameters: String): List<String> {
    val result = mutableListOf<String>()
    var angle = 0
    var parens = 0
    var brackets = 0
    var braces = 0
    var start = 0
    for (index in parameters.indices) {
        when (parameters[index]) {
            '<' -> angle++
            '>' -> if (angle > 0) angle--
            '(' -> parens++
            ')' -> if (parens > 0) parens--
            '[' -> brackets++
            ']' -> if (brackets > 0) brackets--
            '{' -> braces++
            '}' -> if (braces > 0) braces--
            ',' -> if (angle == 0 && parens == 0 && brackets == 0 && braces == 0) {
                result += parameters.substring(start, index).trim()
                start = index + 1
            }
        }
    }
    result += parameters.substring(start).trim()
    return result.filter { it.isNotEmpty() }
}

private fun assignmentIndex(text: String): Int {
    var angle = 0
    var parens = 0
    var brackets = 0
    var braces = 0
    for (index in text.indices) {
        when (text[index]) {
            '<' -> angle++
            '>' -> if (angle > 0) angle--
            '(' -> parens++
            ')' -> if (parens > 0) parens--
            '[' -> brackets++
            ']' -> if (brackets > 0) brackets--
            '{' -> braces++
            '}' -> if (braces > 0) braces--
            '=' -> {
                if (angle == 0 && parens == 0 && brackets == 0 && braces == 0) {
                    val previous = text.getOrNull(index - 1)
                    val next = text.getOrNull(index + 1)
                    if (next != '>' && next != '=' && previous != '=' && previous != '!' && previous != '<' && previous != '>') {
                        return index
                    }
                }
            }
        }
    }
    return -1
}

private fun SyntaxNode.trimmedEnd(): SyntaxNode {
    if (!text.trimEnd().endsWith(";")) return this
    return copy(endByte = endByte - 1, endCol = (endCol - 1).coerceAtLeast(0))
}

private fun within(node: SyntaxNode, container: SyntaxNode): Boolean =
    node.startByte >= container.startByte && node.endByte <= container.endByte

private fun typeMatches(
    actual: String,
    configured: Set<String>,
    suffixes: Set<String>,
): Boolean {
    val simple = actual.substringAfterLast('.')
    return configured.any { expected ->
        actual == expected ||
            actual.endsWith(".$expected") ||
            (!actual.contains('.') && expected.substringAfterLast('.') == actual)
    } || suffixes.any { simple.endsWith(it) }
}

private fun qualifierMatches(receiver: String, qualifier: String): Boolean {
    val segments = receiver.split('.').map { it.substringBefore('(').substringBefore('[').trim() }
    return segments.any { it.equals(qualifier, ignoreCase = true) } ||
        receiver.equals(qualifier, ignoreCase = true) ||
        receiver.endsWith(".$qualifier", ignoreCase = true)
}

private fun pathContainsFactory(path: String): Boolean =
    LOGGING_FACTORIES.any { path == it || path.endsWith(".$it") }

private fun isLookupOnly(callee: String): Boolean =
    LOOKUP_CALLS.any { it.equals(callee, true) } && !SEARCH_CALLS.any { it.equals(callee, true) }

private fun isAmbiguous(callee: String): Boolean {
    if (COMPOUND_MUTATION_CALLS.any { it.equals(callee, true) }) return false
    if (LOOKUP_CALLS.any { it.equals(callee, true) }) return false
    if (SEARCH_CALLS.any { it.equals(callee, true) }) return false
    if (SEARCH_PREFIXES.any { matchesTokenBoundaryPrefix(callee, it) }) return false
    return AMBIGUOUS_CALLS.any { it.equals(callee, true) } || AMBIGUOUS_PREFIXES.any { callee.startsWith(it, true) }
}

private fun matchesTokenBoundaryPrefix(
    callee: String,
    prefix: String,
): Boolean {
    if (prefix.isEmpty()) return false
    if (callee.equals(prefix, ignoreCase = true)) return true
    if (callee.startsWith(prefix, ignoreCase = true)) {
        val suffix = callee.substring(prefix.length)
        if (suffix.isNotEmpty() && (suffix.first().isUpperCase() || suffix.first().isDigit() || !suffix.first().isLetterOrDigit())) {
            return true
        }
    }
    return false
}

private fun isLiteral(text: String): Boolean {
    val value = text.trim()
    if (value == "true" || value in REJECTION_VALUES) return true
    if (value.startsWith("\"") || value.startsWith("'")) return true
    if (value.startsWith("`")) return !value.contains("\${")
    return value.toLongOrNull() != null || value.toDoubleOrNull() != null
}

private fun isFieldLike(name: String): Boolean {
    val simple = name.substringAfterLast('.')
    return name.startsWith("this.") || simple.startsWith("_") || FIELD_PREFIXES.any { simple.startsWith(it) }
}

private fun persistentReceiver(receiver: String, receiverName: String): Boolean =
    receiver.isNotEmpty() &&
        (
            PERSISTENCE_QUALIFIERS.any { qualifierMatches(receiver, it) } ||
                PERSISTENCE_SUFFIXES.any { receiverName.endsWith(it, true) }
        )

private fun configuredPersistence(callee: String, path: String): Boolean =
    PERSISTENCE_CALLS.any { it == callee } ||
        PERSISTENCE_PREFIXES.any { callee.startsWith(it, ignoreCase = true) } ||
        PERSISTENCE_PATHS.any { path == it || path.endsWith(".$it") }

private fun persistenceEstablished(view: RuleEvaluationView, call: CallFact): Boolean {
    val callee = call.callee.orEmpty()
    val path = call.path.orEmpty()
    val receiver = call.receiverText(view)
    val receiverName = receiver.substringAfterLast('.').trim('_')
    if (durableCallback(view, call)) return true
    if (PERSISTENCE_PATHS.any { path == it || path.endsWith(".$it") }) return true
    return configuredPersistence(callee, path) && persistentReceiver(receiver, receiverName)
}

private fun persistenceCandidate(view: RuleEvaluationView, call: CallFact): Boolean {
    val callee = call.callee.orEmpty()
    if (callee.isEmpty()) return false
    if (persistenceEstablished(view, call)) return false
    val path = call.path.orEmpty()
    val receiver = call.receiverText(view)
    val receiverName = receiver.substringAfterLast('.').trim('_')
    val configured = configuredPersistence(callee, path)
    if (receiver.isEmpty()) return configured
    if (persistentReceiver(receiver, receiverName)) {
        return configured || MUTABLE_OPERATIONS.any { it == callee } || isLocalCandidate(callee)
    }
    val mutableReceiver = MUTABLE_RECEIVER_NAMES.any { it.equals(receiverName, true) }
    if (mutableReceiver && !(configured && isReadCall(callee))) return false
    if (MUTABLE_OPERATIONS.any { it == callee }) return configured
    if (!configured) return false
    if (isLocalCandidate(callee) && PERSISTENCE_CALLS.none { it == callee }) return false
    return true
}

private fun isReadCall(callee: String): Boolean =
    READ_CALL_PREFIXES.any { callee.startsWith(it, ignoreCase = true) }

private fun isLocalCandidate(callee: String): Boolean {
    val lower = callee.lowercase()
    if (lower in LOCAL_CANDIDATE_EXACT) return true
    return LOCAL_CANDIDATE_PREFIXES.any { prefix ->
        val normalized = prefix.lowercase()
        callee.length > normalized.length && lower.startsWith(normalized) && callee[normalized.length].isUpperCase()
    }
}

private fun initEvidence(view: RuleEvaluationView, call: CallFact): Pair<Double, String>? {
    val callee = call.callee.orEmpty()
    val path = call.path.orEmpty()
    val receiver = call.receiverText(view)
    val receiverName = receiver.substringAfterLast('.').trim('_')
    if (callee in INIT_CALLS || INIT_CALLS.any { path.endsWith(".$it") }) return 0.9 to "treesitter"
    if (INIT_QUALIFIERS.any { path == it || path.startsWith("$it.") }) return 0.85 to "treesitter"
    if (receiver.isEmpty() && INIT_TYPES.any { callee.endsWith(it) }) return 0.85 to "treesitter"
    if (callee in CONNECTION_CALLS) {
        return if (CONNECTION_QUALIFIERS.any { receiverName.equals(it, true) || receiver.startsWith("$it.") }) {
            0.85 to "treesitter"
        } else {
            null
        }
    }
    if (callee in DI_CALLS) {
        return if (DI_QUALIFIERS.any { receiverName.equals(it, true) || receiver.startsWith("$it.") }) {
            0.85 to "treesitter"
        } else {
            null
        }
    }
    return null
}

private fun creationNodes(view: RuleEvaluationView): List<Pair<SyntaxNode, String?>> {
    val root = view.rootNode() ?: return emptyList()
    val result = mutableListOf<Pair<SyntaxNode, String?>>()
    for (node in view.descendants(root)) {
        val text = node.text.trim()
        if (!text.startsWith("new ")) continue
        val type = text.removePrefix("new ").substringBefore('(').substringBefore('<').trim()
        if (type.isNotEmpty()) result += node to type
    }
    return result
}

private const val CANDIDATE_CONFIDENCE = 0.4

private val PRINT_CALLS = setOf("println", "print", "writeline", "write", "writeerror")
private val PRINT_QUALIFIERS = setOf("System.out", "System.err", "console", "Console", "Console.Error", "Debug", "Trace")
private val LOGGING_METHODS = setOf("info", "debug", "warn", "error", "trace", "log", "verbose", "fatal", "loginformation", "logwarning", "logerror", "logdebug", "logtrace")
private val LOGGING_RECEIVER_NAMES = setOf("log", "logger", "LOG", "LOGGER", "Logger", "_log", "_logger")
private val LOGGING_GUARD_NAMES = setOf("loggingEnabled", "logEnabled", "debugEnabled", "logging", "IsEnabled")
private val LOGGING_FACTORIES = setOf("LoggerFactory.getLogger", "KotlinLogging.logger", "LogManager.getLogger", "Logger.getLogger", "CreateLogger", "GetLogger", "createLogger", "pino")
private val LOGGING_IMPORTS = setOf("org.slf4j", "mu.KotlinLogging", "java.util.logging", "Microsoft.Extensions.Logging", "Serilog", "NLog", "log4net")

private val ERROR_CALLS = setOf("require", "requirenotnull", "check", "checknotnull", "error", "todo", "assert", "invariant")

private val PREDICATE_CALLS =
    setOf(
        "none",
        "any",
        "all",
        "isempty",
        "isnotempty",
        "isblank",
        "isnotblank",
        "isnullorblank",
        "isnullorempty",
        "contains",
        "containskey",
        "containsvalue",
        "startswith",
        "endswith",
        "matches",
    )
private val PREDICATE_PREFIXES = listOf("is", "has", "Is", "Has")
private val REJECTION_VALUES =
    setOf("null", "undefined", "false", "0", "-1", "\"\"", "''", "[]", "{}", "emptyList()", "emptyMap()", "emptySet()", "listOf()", "mapOf()", "setOf()", "arrayOf()", "String.Empty")
private val COALESCE_TOKENS = setOf("??", "?:")
private val FUNCTION_TYPE_MARKERS =
    listOf("->", "=>", "function", "Func<", "Action<", "Consumer<", "Supplier<", "Predicate<", "Callable<", "Runnable", "BiFunction", "BiConsumer", "UnaryOperator", "Function<", "BiPredicate<")
private val FUNCTION_DEFAULT_MARKERS = setOf("::", "=>", "->", "{")
private val CONSTANT_COMPARISON = Regex("""^(?:true|false|-?\d+)\s*(?:==|!=)\s*(?:true|false|-?\d+)$""")

private val SEARCH_CALLS =
    setOf("findAll", "findOne", "findFirst", "findAny", "search", "query", "filter", "where", "select", "from", "join", "orderBy", "groupBy", "createQuery", "createNativeQuery", "executeQuery", "findMany", "findUnique", "findUniqueOrThrow", "findFirstOrThrow", "getResultList", "getSingleResult", "firstOrDefault", "singleOrDefault", "toListAsync", "findAsync", "scan")
private val SEARCH_PREFIXES = setOf("findBy", "getBy", "loadBy", "search", "query", "findAllBy")
private val SEARCH_QUALIFIERS = setOf("repository", "repo", "dao", "query", "builder", "client", "api", "db", "database", "store", "mapper", "session", "entityManager", "jdbc", "collection", "service", "prisma", "context")
private val LOOKUP_CALLS = setOf("getValue", "getOrDefault", "getOrElse", "lookup", "forKey", "getByKey")
private val LOOKUP_QUALIFIERS = setOf("map", "dictionary", "dict", "cache", "config", "configuration", "enum", "enumMap", "table", "registry", "index")
private val AMBIGUOUS_CALLS = setOf("get", "find", "fetch", "load", "read", "resolve")
private val AMBIGUOUS_PREFIXES = setOf("get", "find", "fetch", "load", "read", "resolve")

private val NORMALIZATION_CALLS =
    setOf("trim", "trimStart", "trimEnd", "lowercase", "uppercase", "toLowerCase", "toUpperCase", "toInt", "toLong", "toShort", "toByte", "toDouble", "toFloat", "toBigDecimal", "toBigInteger", "toPath", "toUri", "parseInt", "parseLong", "parseDouble", "parseFloat", "parseBoolean", "fromString", "normalize", "canonicalize", "decodeFromString", "String", "Number")
private val NORMALIZATION_INSTANCE_CALLS = setOf("trim", "trimStart", "trimEnd", "lowercase", "uppercase", "toLocaleLowerCase", "toLocaleUpperCase", "toLowerCase", "toUpperCase", "toInt", "toLong", "toShort", "toByte", "toDouble", "toFloat", "toBigDecimal", "toBigInteger", "toPath", "toUri", "normalize")
private val NORMALIZATION_CANDIDATE_CALLS = setOf("format", "stringify")
private val NORMALIZATION_CANDIDATE_PREFIXES = listOf("format", "pretty", "display")
private val NORMALIZATION_QUALIFIERS = setOf("String", "Json", "Integer", "Long", "Short", "Byte", "Double", "Float", "Boolean", "BigDecimal", "BigInteger", "LocalDate", "LocalDateTime", "LocalTime", "Instant", "OffsetDateTime", "ZonedDateTime", "Duration", "Period", "UUID", "Path", "Paths", "Files", "DateTimeFormatter", "Charsets")

private val TRANSFORMATION_CALLS =
    setOf("map", "mapNotNull", "mapValues", "mapKeys", "flatMap", "filter", "filterNot", "filterIsInstance", "filterNotNull", "fold", "foldIndexed", "reduce", "reduceIndexed", "collect", "collectLatest", "distinct", "distinctBy", "sorted", "sortedBy", "sortedWith", "sortedDescending", "sortedByDescending", "groupBy", "groupingBy", "associate", "associateBy", "associateWith", "partition", "take", "takeWhile", "takeLast", "drop", "dropWhile", "dropLast", "chunked", "windowed", "zip", "zipWithNext", "flatten", "all", "any", "none", "Select", "SelectMany", "Where", "Aggregate", "GroupBy", "Distinct", "OrderBy", "ToList", "ToArray")
private val TRANSFORMATION_CANDIDATE_CALLS = setOf("transform", "process", "reshape", "convert", "apply", "adjust", "Transform", "Process", "Reshape", "Convert", "Apply", "Adjust")
private val TRANSFORMATION_CANDIDATE_PREFIXES = listOf("transform", "process", "reshape", "convert")
private val TRANSFORMATION_QUALIFIERS = setOf("list", "lists", "items", "values", "uses", "collection", "collections", "stream", "streams", "sequence", "sequences", "array", "arrays", "set", "sets", "map", "maps", "entries", "elements", "nodes", "results", "rows", "records", "children")

private val COMPOUND_MUTATION_CALLS =
    setOf(
        "findOneAndUpdate",
        "findOneAndDelete",
        "findOneAndReplace",
        "findOneAndIncrement",
        "findOneAndDecrement",
        "findOneAndModify",
        "findAndModify",
        "getAndUpdate",
        "getAndIncrement",
        "getAndDecrement",
    )

private val READ_CALL_PREFIXES =
    setOf(
        "find",
        "get",
        "query",
        "search",
        "load",
        "read",
        "count",
        "exists",
        "has",
        "fetch",
        "list",
        "select",
        "scan",
        "head",
        "contains",
    )

private val PERSISTENCE_CALLS =
    setOf("save", "saveAll", "saveAndFlush", "persist", "merge", "flush", "commit", "update", "updateAll", "updateOne", "updateMany", "updateById", "upsert", "put", "putObject", "putItem", "upload", "uploadObject", "copyObject", "writeObject", "insert", "insertOne", "insertMany", "insertAll", "delete", "deleteAll", "deleteById", "deleteOne", "deleteMany", "deleteAllById", "remove", "removeById", "publish", "publishEvent", "publishAll", "emit", "send", "sendAsync", "sendMessage", "sendMessageAsync", "convertAndSend", "sendAndReceive", "write", "writeText", "writeBytes", "writeString", "writeFile", "writeFileSync", "appendFile", "appendFileSync", "appendText", "appendBytes", "createNewFile", "deleteIfExists", "deleteRecursively", "unlink", "unlinkSync", "SaveChanges", "SaveChangesAsync", "WriteAllText", "WriteAllTextAsync", "WriteAllBytes", "WriteAllBytesAsync", "WriteAllLines", "WriteAllLinesAsync", "AppendAllText", "AppendAllTextAsync", "AppendAllLines", "AppendAllLinesAsync", "Delete", "Move", "Copy", "CreateDirectory")
private val PERSISTENCE_PATHS = setOf("Files.write", "Files.writeString", "Files.delete", "Files.deleteIfExists", "Files.copy", "Files.move", "File.WriteAllText", "File.WriteAllBytes", "File.WriteAllLines", "File.AppendAllText", "File.Delete", "File.Move", "File.Copy", "File.Create")
private val PERSISTENCE_PREFIXES = setOf("save", "persist", "store", "publish", "emit", "send", "write", "delete", "remove", "update", "insert", "upsert", "flush", "commit", "dispatch", "enqueue", "produce", "append", "put", "upload", "copyObject", "writeObject", "sync")
private val PERSISTENCE_QUALIFIERS = setOf("repository", "repo", "dao", "entityManager", "entityManagerFactory", "sessionFactory", "database", "dataSource", "jdbcTemplate", "mongoTemplate", "mongo", "firestore", "dynamoDb", "bucket", "s3", "publisher", "producer", "eventBus", "kafka", "rabbit", "sqs", "sns", "file", "path", "paths", "files", "fs", "directory", "dbContext", "connection", "transaction")
private val PERSISTENCE_SUFFIXES = setOf("Template", "Client", "Producer", "Repository", "Repo", "Dao", "File", "Path", "Files", "Bucket")
private val MUTABLE_OPERATIONS = setOf("add", "addAll", "addFirst", "addLast", "put", "putAll", "putIfAbsent", "remove", "removeAll", "removeIf", "removeAt", "clear", "insert", "insertAt", "set", "append", "push", "pop", "shift", "unshift", "splice", "sort", "reverse", "shuffle", "retainAll", "compute", "computeIfAbsent", "computeIfPresent", "merge", "replace", "replaceAll", "fill", "swap", "update", "delete", "Add", "AddRange", "Remove", "RemoveAt", "RemoveRange", "RemoveAll", "Clear", "Insert", "InsertRange", "Push", "Pop", "Enqueue", "Dequeue", "TryAdd", "TryRemove", "Sort", "Reverse")
private val MUTABLE_RECEIVER_NAMES = setOf("list", "lists", "items", "values", "map", "maps", "dict", "dictionary", "set", "sets", "stack", "deque", "collection", "collections", "array", "arrays", "buffer", "cache", "results", "rows", "nodes", "children", "elements", "records", "entries", "data", "state")
private val LOCAL_CANDIDATE_EXACT = setOf("set", "clear", "add", "remove", "put", "merge", "reset", "replace", "update", "delete", "modify", "mutate")
private val LOCAL_CANDIDATE_PREFIXES = setOf("mutate", "modify", "update", "reset", "clear", "merge", "replace", "increment", "decrement")

private val DELEGATION_CALLS = setOf("delegate", "forward", "dispatch", "callThrough", "Delegate", "Forward", "Dispatch", "CallThrough")
private val DELEGATION_QUALIFIERS = setOf("services", "Services")
private val SERVICE_NAMES = setOf("service", "svc", "repository", "repo", "facade", "adapter", "gateway", "client", "delegate", "proxy", "controller", "services", "apiClient", "httpClient", "remote", "_service", "_repository", "_facade", "_adapter", "_gateway", "_client")
private val SERVICE_TYPES = setOf("ApiClient", "ServiceClient", "ServiceProxy", "ServiceFacade", "ServiceAdapter", "ServiceGateway")
private val SERVICE_SUFFIXES = setOf("Service", "Repository", "Repo", "Facade", "Adapter", "Gateway", "Client", "Provider", "Controller")
private val INIT_SUFFIXES = setOf("Factory")
private val INIT_CALLS = setOf("createClient", "newClient", "buildClient", "createService", "newService", "createConnection", "CreateClient", "BuildClient", "CreateService", "CreateConnection", "Initialize", "Configure", "AddHttpClient", "AddDbContext", "AddSingleton", "AddScoped", "AddTransient", "BuildServiceProvider")
private val INIT_QUALIFIERS = setOf("ClientBuilder", "ServiceFactory", "DriverManager", "DataSource", "HttpClient", "OkHttpClient", "WebClient", "RestClient", "RestTemplate", "Retrofit", "Feign", "ServiceLoader", "ServiceProvider", "ConnectionFactory", "Pool")
private val INIT_TYPES = setOf("HttpClient", "OkHttpClient", "WebClient", "RestClient", "RestTemplate", "Retrofit", "FeignClient", "KafkaProducer", "KafkaConsumer", "RedisClient", "Jedis", "MongoClient", "DataSource", "Connection", "Socket", "Server", "Factory")
private val INIT_METHODS = setOf("init", "initialize", "initialise", "setup", "setUp", "bootstrap", "wire", "configure", "createClient", "createConnection", "openConnection", "buildClient", "connect", "Initialize", "Initialise", "Setup", "Bootstrap", "Configure", "Wire", "Register", "CreateClient", "CreateConnection", "Connect")
private val CONNECTION_CALLS = setOf("connect", "openConnection", "getConnection", "createConnection", "newConnection", "acquireConnection", "openSession", "Connect", "OpenConnection", "GetConnection", "CreateConnection", "OpenSession")
private val CONNECTION_QUALIFIERS = setOf("DriverManager", "DataSource", "ConnectionFactory", "Connection", "connection", "conn", "_connection", "Pool", "HttpClient", "Client", "client")
private val DI_CALLS = setOf("getBean", "getService", "getInstance", "inject", "resolve", "GetService", "GetRequiredService", "Resolve", "Inject", "GetInstance")
private val DI_QUALIFIERS = setOf("context", "ctx", "container", "provider", "serviceProvider", "services", "injector", "locator", "beanFactory", "applicationContext", "resolver", "registry", "_services")
private val DI_ANNOTATIONS = setOf("Inject", "Autowired", "Provides", "Bean", "Injectable", "FromServices", "Injected")

private val FIELD_PREFIXES = listOf("m_", "s_", "f_")
private val SIMPLE_IDENTIFIER = Regex("""^[A-Za-z_$][A-Za-z0-9_$]*$""")
private val BARE_LABELED_RETURN = Regex("""^return@[A-Za-z_][A-Za-z0-9_]*$""")
private val LOCAL_COPY_CALLS = setOf("copy", "clone", "toList", "toSet", "toMutableList", "toMutableMap", "toMutableSet", "toArray", "toQueue", "toStack")

private val PRIMARY_CONSTRUCTOR =
    Regex(
        """\b(?:class|record)\s+\w+(?:<[^>]*>)?(?:\s*@[A-Za-z_][\w.]*(?:\s*\([^)]*\))?)*\s*(?:(?:public|private|protected|internal)\s+)?(?:constructor\s*)?\(([^)]*)\)""",
        RegexOption.DOT_MATCHES_ALL,
    )
private val CLASS_NAME = Regex("""\b(?:class|object|interface|record|struct)\s+([A-Za-z_][A-Za-z0-9_]*)""")
private val PROPERTY_PARAMETER =
    Regex(
        """(?:^|\s)(?:private|public|protected|internal|open|override|lateinit)?\s*(?:val|var)\s+(\w+)\s*:\s*(.+?)\s*$""",
    )
private val TYPESCRIPT_PARAMETER_PROPERTY =
    Regex(
        """^(?:(?:public|private|protected|readonly|override)\s+)+([A-Za-z_$][A-Za-z0-9_$]*\??)\s*:\s*(.+?)\s*$""",
    )
private val FUNCTION_PARAMETERS = Regex("""^[^(]*\(([^)]*)\)""")
private val PARAMETER_TYPE = Regex("""^(.+?)\s+[A-Za-z_][A-Za-z0-9_]*$""")
private val TYPE_TOKEN = Regex("""[A-Za-z_][A-Za-z0-9_.]*""")
