package com.semanticpainter.treesitter

import com.semanticpainter.core.config.IntentConfig
import com.semanticpainter.core.config.IntentDefinition
import com.semanticpainter.core.engine.AnalysisContext
import com.semanticpainter.core.engine.Phase
import com.semanticpainter.core.engine.RuleBlock
import com.semanticpainter.core.model.RawAnnotation
import com.semanticpainter.core.model.Role
import com.semanticpainter.core.model.Span
import com.semanticpainter.core.model.UNTAGGED_INTENT
import com.semanticpainter.core.syntax.SyntaxNode

private val PRINT_CALLS = setOf("println", "print")
private val STD_STREAMS = setOf("System.out", "System.err")

private fun typeMatches(actual: String, configured: List<String>): Boolean {
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
): RawAnnotation = RawAnnotation(
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
): RawAnnotation = RawAnnotation(
    role = Role.SCAFFOLD,
    intents = intents,
    rule = rule,
    evidence = "${start.text} ${end.text}".take(160),
    span = Span(start.startLine, start.startCol, end.endLine, end.endCol),
    source = source,
    confidence = confidence,
    startByte = start.startByte,
    endByte = end.endByte,
)

class PrintBlock(private val intent: String) : RuleBlock {
    override val id: String = "print"

    override fun apply(ctx: AnalysisContext): List<RawAnnotation> =
        KotlinSyntax.calls(ctx.tree).mapNotNull { call ->
            val callee = KotlinSyntax.calleeName(call) ?: return@mapNotNull null
            if (callee !in PRINT_CALLS) return@mapNotNull null
            val receiver = KotlinSyntax.receiverNode(call)
            if (receiver != null && receiver.text !in STD_STREAMS) return@mapNotNull null
            call.raw(Role.SOURCE_INTENT, setOf(intent), "print", "treesitter", 1.0)
        }.toList()
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
        val loggerSymbols = KotlinSyntax.properties(ctx.tree)
            .filter { it.initCallPath != null && definition.has("factories", it.initCallPath) }
            .map { it.name }
            .toSet()
        val importsMatch = KotlinSyntax.imports(ctx.tree).any { import ->
            definition.list("imports").any { import == it || import.startsWith("$it.") }
        }

        return KotlinSyntax.calls(ctx.tree).mapNotNull { call ->
            val callee = KotlinSyntax.calleeName(call) ?: return@mapNotNull null
            if (methods.isNotEmpty() && callee !in methods) return@mapNotNull null
            val receiver = KotlinSyntax.receiverNode(call) ?: return@mapNotNull null
            val receiverName = receiver.text.substringAfterLast('.')

            val type = ctx.oracle.typeAt(ctx.file, KotlinSyntax.receiverOffset(receiver))
            val match = if (type != null) {
                if (!typeMatches(type.fqName, types)) return@mapNotNull null
                Match("typed-receiver", type.source, type.confidence)
            } else {
                when {
                    receiverName in loggerSymbols ->
                        Match("call-on-logger-symbol", "heuristic", 0.8)

                    receiverNames.any { it.equals(receiverName, ignoreCase = true) } ->
                        Match("receiver-name", "heuristic", 0.6)

                    definition.has("qualifiers", receiver.text) ->
                        Match("qualified-static", "heuristic", 0.8)

                    importsMatch ->
                        Match("name-import-gated", "heuristic", 0.5)

                    else -> null
                }
            } ?: return@mapNotNull null

            call.raw(Role.SOURCE_INTENT, setOf(intent), match.rule, match.source, match.confidence)
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
        val conditions = ctx.tree.root.descendants().mapNotNull { node ->
            when (node.kind) {
                "if_expression" -> KotlinSyntax.ifCondition(node)
                "while_expression" -> node.children.firstOrNull {
                    it.kind !in setOf("while", "(", ")", "control_structure_body")
                }
                else -> null
            }
        }
        return conditions.mapNotNull { condition ->
            if (guardNames.any { condition.text.contains(it, ignoreCase = true) }) {
                condition.raw(Role.SOURCE_INTENT, setOf(intent), "logging-state", "treesitter", 0.7)
            } else {
                null
            }
        }.toList()
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

        val callAnnotations = KotlinSyntax.calls(ctx.tree).mapNotNull { call ->
            val callee = KotlinSyntax.calleeName(call) ?: return@mapNotNull null
            if (callee !in calls) return@mapNotNull null
            if (KotlinSyntax.receiverNode(call) != null) return@mapNotNull null
            call.raw(Role.SOURCE_INTENT, setOf(intent), "error-handling-call", "treesitter", 1.0)
        }.toList()

        val throwAnnotations = if (detectThrows) {
            ctx.tree.root.descendants("jump_expression")
                .filter(KotlinSyntax::isThrow)
                .map { it.raw(Role.SOURCE_INTENT, setOf(intent), "error-handling-throw", "treesitter", 1.0) }
                .toList()
        } else {
            emptyList()
        }

        return callAnnotations + throwAnnotations
    }
}

class DerivativeUseBlock(private val pureAnnotations: List<String>) : RuleBlock {
    override val id: String = "derivative-use"
    override val phase: Phase = Phase.DERIVATIVE

    override fun apply(ctx: AnalysisContext): List<RawAnnotation> {
        val result = mutableListOf<RawAnnotation>()
        for (declaration in ctx.tree.root.descendants("property_declaration")) {
            val name = KotlinSyntax.variableName(declaration) ?: continue
            val nameNode = declaration.child("variable_declaration")?.child("simple_identifier") ?: continue
            val initializer = KotlinSyntax.initializerOf(declaration) ?: continue
            if (!isPure(ctx, initializer)) continue

            val scope = KotlinSyntax.enclosingFunction(declaration) ?: ctx.tree.root
            val uses = scope.descendants("simple_identifier")
                .filter { it.text == name }
                .filter { it.startByte != nameNode.startByte }
                .filterNot { it.startByte >= initializer.startByte && it.endByte <= initializer.endByte }
                .toList()
            if (uses.isEmpty()) continue

            val intents = mutableSetOf<String>()
            for (use in uses) {
                intents += ctx.coverage(KotlinSyntax.enclosingStatement(use)).intents
            }
            if (intents.none { it != UNTAGGED_INTENT }) continue

            result += declaration.raw(Role.DERIVATIVE_USE, intents, "derivative-use", "treesitter", 0.7)
        }
        return result
    }

    private fun isPure(ctx: AnalysisContext, initializer: SyntaxNode): Boolean =
        KotlinSyntax.isConstant(initializer) ||
            initializer.kind == "simple_identifier" ||
            (
                initializer.kind == "call_expression" &&
                    KotlinPurity.isAnnotatedPureCall(ctx.tree, initializer, pureAnnotations)
            )
}

class IntentScaffoldBlock : RuleBlock {
    override val id: String = "intent-scaffold"
    override val phase: Phase = Phase.SCAFFOLD

    override fun apply(ctx: AnalysisContext): List<RawAnnotation> {
        val result = mutableListOf<RawAnnotation>()
        result += ifScaffolds(ctx)
        result += catchScaffolds(ctx)
        result += tryScaffolds(ctx)
        result += functionScaffolds(ctx)
        return result
    }

    private fun ifScaffolds(ctx: AnalysisContext): List<RawAnnotation> =
        ctx.tree.root.descendants("if_expression").mapNotNull { ifNode ->
            val condition = KotlinSyntax.ifCondition(ifNode) ?: return@mapNotNull null
            val intents = mutableSetOf<String>()
            intents += conditionIntents(ctx, condition)
            intents += bodyIntents(ctx, ifNode.child("control_structure_body"))
            val elseBody = KotlinSyntax.elseBodyOf(ifNode)
            if (elseBody != null) intents += bodyIntents(ctx, elseBody)
            if (intents.none { it != UNTAGGED_INTENT }) return@mapNotNull null
            ifNode.raw(Role.SCAFFOLD, intents, "intent-scaffold", "treesitter", 0.9)
        }.toList()

    private fun catchScaffolds(ctx: AnalysisContext): List<RawAnnotation> =
        ctx.tree.root.descendants("catch_block").mapNotNull { catchNode ->
            val intents = bodyIntents(ctx, catchNode)
            if (intents.none { it != UNTAGGED_INTENT }) return@mapNotNull null
            catchNode.raw(Role.SCAFFOLD, intents, "intent-scaffold-catch", "treesitter", 0.9)
        }.toList()

    private fun tryScaffolds(ctx: AnalysisContext): List<RawAnnotation> {
        val result = mutableListOf<RawAnnotation>()
        for (tryNode in ctx.tree.root.descendants("try_expression")) {
            val catches = tryNode.childrenOfKind("catch_block")
            if (catches.isEmpty()) continue

            val tryBodyIntents = bodyIntents(ctx, tryNode)
            val catchIntents = catches.map { bodyIntents(ctx, it) }
            val finallyBlock = tryNode.children.firstOrNull { it.kind == "finally_block" }
            val finallyIntents = finallyBlock?.let { bodyIntents(ctx, it) } ?: emptySet()

            val catchesCovered = catchIntents.all { it.isNotEmpty() && UNTAGGED_INTENT !in it }
            val catchesHaveRealIntent = catchIntents.any { intents ->
                intents.any { it != UNTAGGED_INTENT }
            }

            val wholeCovered = tryBodyIntents.isNotEmpty() &&
                UNTAGGED_INTENT !in tryBodyIntents &&
                catchesCovered &&
                (finallyBlock == null || (finallyIntents.isNotEmpty() && UNTAGGED_INTENT !in finallyIntents))

            val start = tryNode.children.firstOrNull { it.kind == "try" }
            val end = tryNode.children.firstOrNull { it.kind == "{" }

            if (wholeCovered) {
                if (start != null && end != null) {
                    ctx.remove(Role.SCAFFOLD, start.startByte, end.endByte)
                }
                val intents = tryBodyIntents + catchIntents.flatten().toSet() + finallyIntents
                result += tryNode.raw(Role.SCAFFOLD, intents, "intent-scaffold-try", "treesitter", 0.9)
            } else if (catchesCovered && catchesHaveRealIntent && start != null && end != null) {
                val intents = catchIntents.flatten().toSet()
                result += tagRange(
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
        ctx.tree.root.descendants("function_declaration").mapNotNull { function ->
            val body = function.child("function_body") ?: return@mapNotNull null
            val intents = bodyIntents(ctx, body)
            if (intents.isEmpty()) return@mapNotNull null
            if (UNTAGGED_INTENT in intents) return@mapNotNull null
            if (intents.none { it != UNTAGGED_INTENT }) return@mapNotNull null
            function.raw(Role.SCAFFOLD, intents, "intent-scaffold-function", "treesitter", 0.9)
        }.toList()

    private fun conditionIntents(ctx: AnalysisContext, condition: SyntaxNode): Set<String> =
        if (KotlinSyntax.isConstant(condition)) emptySet() else ctx.coverage(condition).intents

    private fun bodyIntents(ctx: AnalysisContext, body: SyntaxNode?): Set<String> {
        if (body == null) return emptySet()
        val statements = body.child("statements")
        if (statements != null) {
            val effects = statements.children.filterNot { it.kind in COMMENT_KINDS }
            return effects.flatMap { ctx.coverage(it).intents }.toSet()
        }
        return ctx.coverage(body).intents
    }
}

private val COMMENT_KINDS = setOf("comment", "line_comment", "multiline_comment")

object IntentBlocks {
    fun build(config: IntentConfig): List<RuleBlock> {
        val blocks = mutableListOf<RuleBlock>()
        for (definition in config.intents.values) {
            for (blockId in definition.blocks) {
                blocks += when (blockId) {
                    "print" -> PrintBlock(definition.name)
                    "logger-interaction" -> LoggerInteractionBlock(definition.name, definition)
                    "logging-state" -> LoggingStateBlock(definition.name, definition.list("guardNames"))
                    "error-handling" -> ErrorHandlingBlock(definition.name, definition)
                    else -> error("unknown block '$blockId' for intent '${definition.name}'")
                }
            }
        }
        blocks += DerivativeUseBlock(config.pureAnnotations)
        blocks += IntentScaffoldBlock()
        return blocks
    }
}
