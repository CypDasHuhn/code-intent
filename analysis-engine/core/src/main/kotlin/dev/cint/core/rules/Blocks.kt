package dev.cint.core.rules

import dev.cint.core.config.IntentConfig
import dev.cint.core.config.IntentDefinition
import dev.cint.core.engine.AnalysisContext
import dev.cint.core.engine.Phase
import dev.cint.core.engine.RuleBlock
import dev.cint.core.model.RawAnnotation
import dev.cint.core.model.Role
import dev.cint.core.model.Span
import dev.cint.core.model.UNTAGGED_INTENT
import dev.cint.core.syntax.SyntaxNode

private fun typeMatches(
    actual: String,
    configured: List<String>,
): Boolean {
    val actualSimple = actual.substringAfterLast('.')
    return configured.any { expected ->
        actual == expected ||
            actual.endsWith(".$expected") ||
            (!actual.contains('.') && expected.substringAfterLast('.') == actualSimple)
    }
}

private fun SyntaxNode.raw(
    role: Role,
    intents: Set<String>,
    rule: String,
    source: String,
    confidence: Double,
): RawAnnotation =
    RawAnnotation(
        role = role,
        intents = intents,
        rule = rule,
        evidence = text.replace("\n", " ").take(160),
        span = span(),
        source = source,
        confidence = confidence,
        startByte = startByte,
        endByte = endByte,
    )

private fun tagRange(
    start: SyntaxNode,
    end: SyntaxNode,
    intents: Set<String>,
    rule: String,
    source: String,
    confidence: Double,
    role: Role = Role.SCAFFOLD,
): RawAnnotation =
    RawAnnotation(
        role = role,
        intents = intents,
        rule = rule,
        evidence = "${start.text} ${end.text}".take(160),
        span = Span(start.startLine, start.startCol, end.endLine, end.endCol),
        source = source,
        confidence = confidence,
        startByte = start.startByte,
        endByte = end.endByte,
    )

class PrintBlock(
    private val intent: String,
    private val definition: IntentDefinition,
) : RuleBlock {
    override val id: String = "print"

    override fun apply(ctx: AnalysisContext): List<RawAnnotation> {
        val calls = definition.list("printCalls").toSet()
        val qualifiers = definition.list("printQualifiers").toSet()
        return ctx.calls
            .mapNotNull { call ->
                val callee = call.callee ?: return@mapNotNull null
                if (callee !in calls) return@mapNotNull null
                val receiver = call.receiver
                if (receiver != null && receiver.text !in qualifiers) return@mapNotNull null
                call.site.raw(Role.SOURCE_INTENT, setOf(intent), "print", "treesitter", 1.0)
            }.toList()
    }
}

class LoggerInteractionBlock(
    private val intent: String,
    private val definition: IntentDefinition,
) : RuleBlock {
    override val id: String = "logger-interaction"

    override fun apply(ctx: AnalysisContext): List<RawAnnotation> {
        val methods = definition.list("methods").toSet()
        val types = definition.list("types")
        val receiverNames = definition.list("receiverNames")
        val loggerSymbols =
            ctx.properties
                .filter { property ->
                    val path = property.initializer?.let { ctx.syntax.callPath(it) }
                    path != null && definition.has("factories", path)
                }.mapNotNull { it.name }
                .toSet()
        val importsMatch =
            ctx.imports.any { import ->
                definition.list("imports").any { import == it || import.startsWith("$it.") }
            }

        return ctx.calls
            .mapNotNull { call ->
                val callee = call.callee ?: return@mapNotNull null
                if (methods.isNotEmpty() && callee !in methods) return@mapNotNull null
                val receiver = call.receiver ?: return@mapNotNull null
                val receiverName = receiver.text.substringAfterLast('.')

                val heuristic =
                    when {
                        receiverName in loggerSymbols -> {
                            Match("call-on-logger-symbol", "heuristic", 0.8)
                        }

                        receiverNames.any { it.equals(receiverName, ignoreCase = true) } -> {
                            Match("receiver-name", "heuristic", 0.6)
                        }

                        definition.has("qualifiers", receiver.text) -> {
                            Match("qualified-static", "heuristic", 0.8)
                        }

                        importsMatch -> {
                            Match("name-import-gated", "heuristic", 0.5)
                        }

                        else -> {
                            null
                        }
                    }

                val match =
                    if (
                        heuristic == null ||
                        heuristic.rule == "receiver-name" ||
                        heuristic.rule == "name-import-gated"
                    ) {
                        val type = ctx.oracle.typeAt(ctx.file, ctx.syntax.receiverOffset(receiver))
                        when {
                            type == null -> {
                                heuristic
                            }

                            typeMatches(type.fqName, types) -> {
                                Match("typed-receiver", type.source, type.confidence)
                            }

                            else -> {
                                null
                            }
                        }
                    } else {
                        heuristic
                    } ?: return@mapNotNull null

                call.site.raw(Role.SOURCE_INTENT, setOf(intent), match.rule, match.source, match.confidence)
            }.toList()
    }

    private data class Match(
        val rule: String,
        val source: String,
        val confidence: Double,
    )
}

class LoggingStateBlock(
    private val intent: String,
    private val guardNames: List<String>,
) : RuleBlock {
    override val id: String = "logging-state"

    override fun apply(ctx: AnalysisContext): List<RawAnnotation> {
        if (guardNames.isEmpty()) return emptyList()
        return ctx.guardConditions
            .mapNotNull { condition ->
                if (guardNames.any { condition.text.contains(it, ignoreCase = true) }) {
                    condition.raw(Role.SOURCE_INTENT, setOf(intent), "logging-state", "treesitter", 0.7)
                } else {
                    null
                }
            }.toList()
    }
}

class BookkeepingBlock : RuleBlock {
    override val id: String = "bookkeeping"

    override fun apply(ctx: AnalysisContext): List<RawAnnotation> {
        val result = mutableListOf<RawAnnotation>()
        val references = ctx.references

        for (declaration in ctx.properties) {
            val (source, startNode) = bookkeepingSource(ctx, declaration.node) ?: continue
            val name = declaration.name ?: continue
            val nameNode = declaration.nameNode ?: continue

            result +=
                if (startNode == null) {
                    declaration.node.raw(
                        Role.SOURCE_INTENT,
                        setOf("BOOKKEEPING"),
                        "bookkeeping-declaration",
                        source,
                        1.0,
                    )
                } else {
                    tagRange(
                        startNode,
                        declaration.node,
                        setOf("BOOKKEEPING"),
                        "bookkeeping-declaration",
                        source,
                        1.0,
                        Role.SOURCE_INTENT,
                    )
                }

            val scope = ctx.syntax.declarationHolder(declaration.node) ?: continue
            val seen = mutableSetOf<String>()
            for (reference in references[name].orEmpty()) {
                if (reference.startByte < scope.startByte || reference.endByte > scope.endByte) continue
                if (reference.startByte == nameNode.startByte && reference.endByte == nameNode.endByte) continue
                val write = ctx.syntax.writes(reference) ?: continue
                val key = "${write.startByte}:${write.endByte}"
                if (!seen.add(key)) continue
                result +=
                    write.raw(
                        Role.SOURCE_INTENT,
                        setOf("BOOKKEEPING"),
                        "bookkeeping-write",
                        source,
                        1.0,
                    )
            }
        }
        return result
    }

    private fun bookkeepingSource(
        ctx: AnalysisContext,
        declaration: SyntaxNode,
    ): Pair<String, SyntaxNode?>? {
        val annotation =
            ctx.syntax.annotationTexts(declaration).firstOrNull {
                it == "Bookkeeping" || it.endsWith(".Bookkeeping")
            }
        if (annotation != null) return "annotation" to null

        for (comment in ctx.syntax.precedingComments(declaration)) {
            if (Regex("""cint\s*:\s*bookkeeping""").containsMatchIn(comment.text.lowercase())) {
                return "comment" to comment
            }
        }
        return null
    }
}

class ValidationBlock(
    private val intent: String,
    private val definition: IntentDefinition,
) : RuleBlock {
    override val id: String = "validation"
    override val readsAnnotations: Boolean = true

    override fun apply(ctx: AnalysisContext): List<RawAnnotation> {
        return ctx.ifs
            .flatMap { guard ->
                val condition = guard.condition ?: return@flatMap emptyList()
                val body = guard.thenBody ?: return@flatMap emptyList()
                if (!hasDirectExit(ctx, body)) return@flatMap emptyList()

                val explicit = hasValidationComment(ctx, guard.node)
                val bookkeeping = hasBookkeeping(ctx, body)
                val predicate = isValidationPredicate(ctx, condition, definition)
                val pure = ctx.syntax.isSideEffectFree(condition)
                if (!explicit && !bookkeeping && !predicate && !pure) return@flatMap emptyList()
                if (ctx.syntax.isConstant(condition) && !explicit && !bookkeeping) {
                    return@flatMap emptyList()
                }

                val confidence =
                    when {
                        explicit -> 1.0
                        bookkeeping -> 0.9
                        predicate -> 0.7
                        else -> 0.5
                    }
                val source = if (explicit) "comment" else "treesitter"
                listOf(
                    condition.raw(
                        Role.SOURCE_INTENT,
                        setOf(intent),
                        "validation-guard",
                        source,
                        confidence,
                    ),
                    guard.node.raw(
                        Role.SCAFFOLD,
                        setOf(intent),
                        "validation-guard-block",
                        source,
                        confidence,
                    ),
                )
            }.toList()
    }

    private fun hasDirectExit(
        ctx: AnalysisContext,
        body: SyntaxNode,
    ): Boolean {
        val statements = ctx.syntax.statements(body) ?: listOf(body)
        return statements.any { ctx.syntax.isExit(it) }
    }

    private fun hasBookkeeping(
        ctx: AnalysisContext,
        body: SyntaxNode,
    ): Boolean =
        ctx.annotations().any { annotation ->
            "BOOKKEEPING" in annotation.intents &&
                annotation.startByte >= body.startByte &&
                annotation.endByte <= body.endByte
        }
}

private fun isValidationPredicate(
    ctx: AnalysisContext,
    condition: SyntaxNode,
    definition: IntentDefinition,
): Boolean {
    val predicates = definition.list("predicates").toSet()
    val prefixes = definition.list("predicatePrefixes")
    return ctx.syntax.callsIn(condition).any { call ->
        val name = call.callee ?: return@any false
        name in predicates || prefixes.any { name.startsWith(it) }
    }
}

private fun hasValidationComment(
    ctx: AnalysisContext,
    ifNode: SyntaxNode,
): Boolean {
    val scope =
        ctx.syntax
            .enclosingFunction(ifNode)
            ?.let { ctx.syntax.bodyOf(it) }
            ?: ctx.tree.root
    val comment = ctx.syntax.closestCommentBefore(scope, ifNode) ?: return false
    if (ifNode.startLine - comment.endLine > 1) return false
    return Regex("""cint\s*:\s*validation""").containsMatchIn(comment.text.lowercase())
}

private val REJECTION_SENTINELS =
    setOf("null", "undefined", "false", "0", "-1", "\"\"", "''", "[]", "{}")

class ReturnBlock(
    private val intent: String,
    private val definition: IntentDefinition,
) : RuleBlock {
    override val id: String = "return-category"

    override fun apply(ctx: AnalysisContext): List<RawAnnotation> =
        ctx.flow.returns
            .map { site ->
                val validation = isValidationReturn(ctx, site)
                site.node.raw(
                    Role.SOURCE_INTENT,
                    setOf(if (validation) intent else UNTAGGED_INTENT),
                    if (validation) "return-validation" else "return-untagged",
                    "cfg",
                    if (validation) 0.8 else 1.0,
                )
            }.toList()

    private fun isValidationReturn(
        ctx: AnalysisContext,
        site: dev.cint.core.flow.ReturnSite,
    ): Boolean {
        val guard = site.guards.lastOrNull { isValidationGuard(ctx, it) } ?: return false
        if (guard.onTrue) return true
        return isRejection(site)
    }

    private fun isValidationGuard(
        ctx: AnalysisContext,
        guard: dev.cint.core.flow.Guard,
    ): Boolean {
        val explicit = hasValidationComment(ctx, guard.node)
        val predicate = isValidationPredicate(ctx, guard.condition, definition)
        val pure = ctx.syntax.isSideEffectFree(guard.condition)
        if (ctx.syntax.isConstant(guard.condition) && !explicit) return false
        return explicit || predicate || pure
    }

    private fun isRejection(site: dev.cint.core.flow.ReturnSite): Boolean {
        val expression = site.expression ?: return true
        return expression.text.trim() in REJECTION_SENTINELS
    }
}

class ErrorHandlingBlock(
    private val intent: String,
    private val definition: IntentDefinition,
) : RuleBlock {
    override val id: String = "error-handling"

    override fun apply(ctx: AnalysisContext): List<RawAnnotation> {
        val calls = definition.list("calls").toSet()
        val detectThrows = definition.list("throws").any { it.equals("true", ignoreCase = true) }

        val callAnnotations =
            ctx.syntax
                .calls(ctx.tree)
                .mapNotNull { call ->
                    val callee = call.callee ?: return@mapNotNull null
                    if (callee !in calls) return@mapNotNull null
                    val receiver = call.receiver
                    if (receiver != null && !definition.has("qualifiers", receiver.text)) return@mapNotNull null
                    call.site.raw(Role.SOURCE_INTENT, setOf(intent), "error-handling-call", "treesitter", 1.0)
                }.toList()

        val throwAnnotations =
            if (detectThrows) {
                ctx.throws.map {
                    it.raw(Role.SOURCE_INTENT, setOf(intent), "error-handling-throw", "treesitter", 1.0)
                }
            } else {
                emptyList()
            }

        return callAnnotations + throwAnnotations
    }
}

class DerivativeUseBlock(
    private val pureAnnotations: List<String>,
) : RuleBlock {
    override val id: String = "derivative-use"
    override val phase: Phase = Phase.DERIVATIVE
    override val readsAnnotations: Boolean = true

    override fun apply(ctx: AnalysisContext): List<RawAnnotation> {
        val result = mutableListOf<RawAnnotation>()
        val references = ctx.references

        for (declaration in ctx.properties) {
            val name = declaration.name ?: continue
            val nameNode = declaration.nameNode ?: continue
            val initializer = declaration.initializer ?: continue

            val scope = ctx.syntax.enclosingFunction(declaration.node) ?: ctx.tree.root
            val uses =
                references[name]
                    .orEmpty()
                    .filter { it.startByte in scope.startByte until scope.endByte }
                    .filter { it.startByte != nameNode.startByte }
                    .filterNot { it.startByte >= initializer.startByte && it.endByte <= initializer.endByte }
            if (uses.isEmpty()) continue

            val useIntents = uses.map { ctx.intentsAt(it) }
            val allClassified =
                useIntents.all { intents ->
                    UNTAGGED_INTENT !in intents && intents.any { it != UNTAGGED_INTENT }
                }
            if (!allClassified) continue
            if (!isPure(ctx, initializer)) continue

            val intents = useIntents.flatten().toSet()
            if (intents.none { it != UNTAGGED_INTENT }) continue

            result += declaration.node.raw(Role.DERIVATIVE_USE, intents, "derivative-use", "treesitter", 0.7)
        }

        for (declaration in ctx.functions) {
            val nameNode = declaration.nameNode ?: continue
            val name = nameNode.text
            if (name.isEmpty()) continue
            if (!ctx.syntax.isAnnotatedPure(declaration.node, pureAnnotations)) continue

            val functionReferences =
                references[name].orEmpty().filter { reference ->
                    (reference.startByte != nameNode.startByte || reference.endByte != nameNode.endByte) &&
                        reference.startByte !in declaration.node.startByte until declaration.node.endByte &&
                        !ctx.syntax.isDeclarationName(reference)
                }
            if (functionReferences.isEmpty()) continue

            val places = functionReferences.mapNotNull { derivativePlaceIntents(ctx, it) }
            if (places.size != functionReferences.size) continue

            val intents = places.flatten().toSet()
            if (intents.none { it != UNTAGGED_INTENT }) continue

            result += declaration.node.raw(Role.DERIVATIVE_USE, intents, "derivative-use", "treesitter", 0.7)
        }

        return result
    }

    private fun derivativePlaceIntents(
        ctx: AnalysisContext,
        reference: SyntaxNode,
    ): Set<String>? {
        val place = ctx.syntax.enclosingDeclaration(reference) ?: return null
        val annotations = ctx.covering(place).filter { it.role == Role.DERIVATIVE_USE }
        if (annotations.isEmpty()) return null
        return annotations.flatMap { it.intents }.toSet()
    }

    private fun isPure(
        ctx: AnalysisContext,
        initializer: SyntaxNode,
    ): Boolean =
        ctx.syntax.isConstant(initializer) ||
            ctx.syntax.isIdentifier(initializer) ||
            ctx.syntax.isAnnotatedPureCall(ctx.tree, initializer, pureAnnotations)
}

class IntentScaffoldBlock : RuleBlock {
    override val id: String = "intent-scaffold"
    override val phase: Phase = Phase.SCAFFOLD
    override val readsAnnotations: Boolean = true

    override fun apply(ctx: AnalysisContext): List<RawAnnotation> {
        val result = mutableListOf<RawAnnotation>()
        result += ifScaffolds(ctx)
        result += catchScaffolds(ctx)
        result += tryScaffolds(ctx)
        result += functionScaffolds(ctx)
        return result
    }

    private fun ifScaffolds(ctx: AnalysisContext): List<RawAnnotation> =
        ctx.ifs
            .flatMap { guard ->
                val condition = guard.condition ?: return@flatMap emptyList()
                val thenBody = guard.thenBody ?: return@flatMap emptyList()
                val thenIntents = mutableSetOf<String>()
                thenIntents += conditionIntents(ctx, condition)
                thenIntents += bodyIntents(ctx, thenBody)

                val elseBody = guard.elseBody
                if (elseBody == null) {
                    if (thenIntents.none { it != UNTAGGED_INTENT }) return@flatMap emptyList()
                    return@flatMap listOf(
                        guard.node.raw(Role.SCAFFOLD, thenIntents, "intent-scaffold", "treesitter", 0.9),
                    )
                }

                val elseIntents = bodyIntents(ctx, elseBody)
                val elseStart = (guard.elseKeyword ?: elseBody).startByte
                if (thenIntents == elseIntents) {
                    if (thenIntents.none { it != UNTAGGED_INTENT }) return@flatMap emptyList()
                    ctx.remove(Role.SCAFFOLD, guard.node.startByte, thenBody.endByte)
                    ctx.remove(Role.SCAFFOLD, elseStart, elseBody.endByte)
                    return@flatMap listOf(
                        guard.node.raw(Role.SCAFFOLD, thenIntents, "intent-scaffold", "treesitter", 0.9),
                    )
                }

                if (
                    thenIntents.none { it != UNTAGGED_INTENT } &&
                    elseIntents.none { it != UNTAGGED_INTENT }
                ) {
                    return@flatMap emptyList()
                }

                ctx.remove(Role.SCAFFOLD, guard.node.startByte, guard.node.endByte)
                val result = mutableListOf<RawAnnotation>()
                result +=
                    tagRange(
                        guard.node,
                        thenBody,
                        thenIntents,
                        "intent-scaffold-if-then",
                        "treesitter",
                        0.9,
                    )
                result +=
                    tagRange(
                        guard.elseKeyword ?: elseBody,
                        elseBody,
                        elseIntents,
                        "intent-scaffold-if-else",
                        "treesitter",
                        0.9,
                    )
                result
            }.toList()

    private fun catchScaffolds(ctx: AnalysisContext): List<RawAnnotation> =
        ctx.catches
            .mapNotNull { catchNode ->
                val intents = bodyIntents(ctx, catchNode)
                if (intents.none { it != UNTAGGED_INTENT }) return@mapNotNull null

                val previous = ctx.syntax.previousBlockClose(catchNode)
                if (previous != null) {
                    tagRange(previous, catchNode, intents, "intent-scaffold-catch", "treesitter", 0.9)
                } else {
                    catchNode.raw(Role.SCAFFOLD, intents, "intent-scaffold-catch", "treesitter", 0.9)
                }
            }.toList()

    private fun tryScaffolds(ctx: AnalysisContext): List<RawAnnotation> {
        val result = mutableListOf<RawAnnotation>()
        for (tryFact in ctx.tries) {
            val catches = tryFact.catches
            if (catches.isEmpty()) continue

            val tryBodyIntents = bodyIntents(ctx, tryFact.node)
            val catchIntents = catches.map { bodyIntents(ctx, it) }
            val finallyIntents = tryFact.finallyBlock?.let { bodyIntents(ctx, it) } ?: emptySet()

            val catchesCovered = catchIntents.all { it.isNotEmpty() && UNTAGGED_INTENT !in it }
            val catchesHaveRealIntent =
                catchIntents.any { intents ->
                    intents.any { it != UNTAGGED_INTENT }
                }

            val wholeCovered =
                tryBodyIntents.isNotEmpty() &&
                    UNTAGGED_INTENT !in tryBodyIntents &&
                    catchesCovered &&
                    (tryFact.finallyBlock == null || (finallyIntents.isNotEmpty() && UNTAGGED_INTENT !in finallyIntents))

            val start = tryFact.headerStart
            val end = tryFact.headerEnd

            if (wholeCovered) {
                if (start != null && end != null) {
                    ctx.remove(Role.SCAFFOLD, start.startByte, end.endByte)
                }
                val intents = tryBodyIntents + catchIntents.flatten().toSet() + finallyIntents
                result += tryFact.node.raw(Role.SCAFFOLD, intents, "intent-scaffold-try", "treesitter", 0.9)
            } else if (catchesCovered && catchesHaveRealIntent && start != null && end != null) {
                val intents = catchIntents.flatten().toSet()
                result +=
                    tagRange(
                        start,
                        end,
                        intents,
                        "intent-scaffold-try-header",
                        "treesitter",
                        0.8,
                    )
            }
        }
        return result
    }

    private fun functionScaffolds(ctx: AnalysisContext): List<RawAnnotation> =
        ctx.syntax
            .functions(ctx.tree)
            .mapNotNull { function ->
                val body = function.body ?: return@mapNotNull null
                val intents = bodyIntents(ctx, body)
                if (intents.isEmpty()) return@mapNotNull null
                if (UNTAGGED_INTENT in intents) return@mapNotNull null
                if (intents.none { it != UNTAGGED_INTENT }) return@mapNotNull null
                function.node.raw(Role.SCAFFOLD, intents, "intent-scaffold-function", "treesitter", 0.9)
            }.toList()

    private fun conditionIntents(
        ctx: AnalysisContext,
        condition: SyntaxNode,
    ): Set<String> {
        val covered = ctx.coverage(condition).intents
        if (covered.any { it != UNTAGGED_INTENT }) return covered
        if (ctx.syntax.isConstant(condition) || ctx.syntax.isSideEffectFree(condition)) {
            return emptySet()
        }
        return covered
    }

    private fun bodyIntents(
        ctx: AnalysisContext,
        body: SyntaxNode?,
    ): Set<String> {
        if (body == null) return emptySet()
        val statements = ctx.syntax.statements(body)
        if (statements != null) {
            return statements.flatMap { statementIntents(ctx, it) }.toSet()
        }
        val exit = exitIntents(ctx, body)
        if (exit != null) return exit
        val direct = ctx.coverage(body).intents
        if (direct.any { it != UNTAGGED_INTENT }) return direct
        val expression = ctx.syntax.statementExpression(body) ?: return direct
        val inner = ctx.coverage(expression).intents
        return if (inner.any { it != UNTAGGED_INTENT }) inner else direct
    }

    private fun statementIntents(
        ctx: AnalysisContext,
        statement: SyntaxNode,
    ): Set<String> {
        exitIntents(ctx, statement)?.let { return it }
        val direct = ctx.coverage(statement).intents
        if (direct.any { it != UNTAGGED_INTENT }) return direct
        val expression = ctx.syntax.statementExpression(statement) ?: return direct
        val inner = ctx.coverage(expression).intents
        return if (inner.any { it != UNTAGGED_INTENT }) inner else direct
    }

    private fun exitIntents(
        ctx: AnalysisContext,
        node: SyntaxNode,
    ): Set<String>? {
        if (!ctx.syntax.isExit(node)) return null
        if (ctx.syntax.isReturn(node)) {
            val covered = ctx.coverage(node)
            if (covered.hasRealIntent || UNTAGGED_INTENT in covered.intents) {
                return covered.intents
            }
        }
        val expression = ctx.syntax.exitExpression(node) ?: return emptySet()
        if (ctx.syntax.isConstant(expression) || ctx.syntax.isSideEffectFree(expression)) {
            return emptySet()
        }
        return ctx.intentsAt(expression)
    }
}

object IntentBlocks {
    fun build(config: IntentConfig): List<RuleBlock> {
        val blocks = mutableListOf<RuleBlock>()
        for (definition in config.intents.values) {
            for (blockId in definition.blocks) {
                blocks +=
                    when (blockId) {
                        "print" -> PrintBlock(definition.name, definition)
                        "logger-interaction" -> LoggerInteractionBlock(definition.name, definition)
                        "logging-state" -> LoggingStateBlock(definition.name, definition.list("guardNames"))
                        "bookkeeping" -> BookkeepingBlock()
                        "error-handling" -> ErrorHandlingBlock(definition.name, definition)
                        "validation" -> ValidationBlock(definition.name, definition)
                        else -> error("unknown block '$blockId' for intent '${definition.name}'")
                    }
            }
        }
        val validation = config.intents["VALIDATION"]
        if (validation != null) {
            blocks += ReturnBlock(validation.name, validation)
        }
        blocks += DerivativeUseBlock(config.pureAnnotations)
        blocks += IntentScaffoldBlock()
        return blocks
    }
}
