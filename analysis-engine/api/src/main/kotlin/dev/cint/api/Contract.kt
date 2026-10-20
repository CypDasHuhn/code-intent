package dev.cint.api

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class RulePackApi(
    val version: Int,
)

interface RulePack {
    val id: String
    val languages: Set<String>
    val apiVersion: Int

    fun rules(): List<Rule>
}

interface Rule {
    val id: String
    val owns: Intent
    val reads: Set<Intent>

    fun run(view: View): List<Emit>
}

fun Rule.toContribution(emit: Emit): Contribution =
    Contribution(
        rule = id,
        role = Role.SOURCE_INTENT,
        intents = emit.intents,
        evidence = emit.evidence,
        span = emit.span,
        source = emit.source,
        confidence = emit.confidence,
        startByte = emit.startByte,
        endByte = emit.endByte,
        certifiesReturn = emit.certifiesReturn,
    )

interface View {
    fun language(): String

    fun typeAt(node: SyntaxNode): TypeRef?

    fun receiverType(call: CallFact): TypeRef?

    fun calls(): List<CallFact>

    fun properties(): List<PropertyFact>

    fun functions(): List<FunctionFact>

    fun declarations(): List<DeclarationFact>

    fun writes(): List<WriteFact>

    fun comments(): List<CommentFact>

    fun commentBefore(node: SyntaxNode): CommentFact?

    fun scopes(): List<ScopeFact>

    fun enclosingScope(node: SyntaxNode): ScopeFact?

    fun enclosingBlock(node: SyntaxNode): SyntaxNode?

    fun guards(): List<GuardFact>

    fun returnSites(): List<ReturnFact>

    fun returns(): List<ReturnFact> = returnSites()

    fun ifs(): List<IfFact>

    fun guardConditions(): List<SyntaxNode>

    fun tries(): List<TryFact>

    fun catches(): List<SyntaxNode>

    fun imports(): List<String>

    fun references(): Map<String, List<SyntaxNode>>

    fun throws(): List<SyntaxNode>

    fun coverage(node: SyntaxNode): Coverage

    fun enclosingFunction(node: SyntaxNode): SyntaxNode?

    fun enclosingDeclaration(node: SyntaxNode): SyntaxNode?

    fun writes(reference: SyntaxNode): SyntaxNode?

    fun isAnnotatedPure(node: SyntaxNode): Boolean

    fun isConstant(node: SyntaxNode): Boolean

    fun isSideEffectFree(node: SyntaxNode): Boolean

    fun statements(container: SyntaxNode): List<SyntaxNode>?

    fun isExit(node: SyntaxNode): Boolean

    fun annotations(intent: Intent): List<Annotation>

    fun intentsAt(node: SyntaxNode): Set<Intent>
}

data class Emit(
    val intents: Set<Intent>,
    val evidence: String,
    val span: Span,
    val source: String,
    val confidence: Double,
    val startByte: Int,
    val endByte: Int,
    val nodeAnchor: NodeAnchor? = null,
    val certifiesReturn: Boolean = false,
) {
    companion object {
        const val DEFAULT_SOURCE: String = "treesitter"

        fun of(
            intent: Intent,
            node: SyntaxNode,
            source: String = DEFAULT_SOURCE,
            confidence: Double = 1.0,
            evidence: String = node.evidence(),
            certifiesReturn: Boolean = false,
        ): Emit = of(setOf(intent), node, source, confidence, evidence, certifiesReturn)

        fun of(
            intents: Set<Intent>,
            node: SyntaxNode,
            source: String = DEFAULT_SOURCE,
            confidence: Double = 1.0,
            evidence: String = node.evidence(),
            certifiesReturn: Boolean = false,
        ): Emit =
            Emit(
                intents = intents,
                evidence = evidence,
                span = node.span(),
                source = source,
                confidence = confidence,
                startByte = node.startByte,
                endByte = node.endByte,
                certifiesReturn = certifiesReturn,
            )

        fun range(
            intent: Intent,
            start: SyntaxNode,
            end: SyntaxNode,
            source: String = DEFAULT_SOURCE,
            confidence: Double = 1.0,
            evidence: String = "${start.evidence()} ${end.evidence()}".take(160),
        ): Emit =
            Emit(
                intents = setOf(intent),
                evidence = evidence,
                span = Span(start.startLine, start.startCol, end.endLine, end.endCol),
                source = source,
                confidence = confidence,
                startByte = start.startByte,
                endByte = end.endByte,
            )

        fun declaration(
            intent: Intent,
            declaration: DeclarationFact,
            source: String = DEFAULT_SOURCE,
            confidence: Double = 1.0,
            includePrecedingComment: Boolean = false,
            magicComment: String? = null,
            evidence: String = declaration.evidence(includePrecedingComment, magicComment),
        ): Emit {
            val start = declaration.commentFor(includePrecedingComment, magicComment)
            return if (start != null) {
                range(intent, start.node, declaration.node, source, confidence, evidence)
            } else {
                of(intent, declaration.node, source, confidence, evidence)
            }
        }

        fun write(
            intent: Intent,
            write: WriteFact,
            source: String = DEFAULT_SOURCE,
            confidence: Double = 1.0,
        ): Emit = of(intent, write.node, source, confidence)

        fun call(
            intent: Intent,
            call: CallFact,
            source: String = DEFAULT_SOURCE,
            confidence: Double = 1.0,
        ): Emit = of(intent, call.site, source, confidence)
    }
}

private fun SyntaxNode.evidence(): String = text.replace("\n", " ").take(160)

private fun DeclarationFact.commentFor(
    includePrecedingComment: Boolean,
    name: String?,
): CommentFact? =
    name?.let { magicComment(it) }
        ?: if (includePrecedingComment) precedingComment else null

private fun DeclarationFact.evidence(
    includePrecedingComment: Boolean,
    magicComment: String?,
): String {
    val comment = commentFor(includePrecedingComment, magicComment)
    return if (comment != null) "${comment.text} ${node.text}".replace("\n", " ").take(160) else node.evidence()
}

fun DeclarationFact.emit(
    intent: Intent,
    source: String = Emit.DEFAULT_SOURCE,
    confidence: Double = 1.0,
    includePrecedingComment: Boolean = false,
    magicComment: String? = null,
): Emit = Emit.declaration(intent, this, source, confidence, includePrecedingComment, magicComment)

fun WriteFact.emit(
    intent: Intent,
    source: String = Emit.DEFAULT_SOURCE,
    confidence: Double = 1.0,
): Emit = Emit.write(intent, this, source, confidence)

fun CallFact.emit(
    intent: Intent,
    source: String = Emit.DEFAULT_SOURCE,
    confidence: Double = 1.0,
): Emit = Emit.call(intent, this, source, confidence)

data class Relation(
    val reader: Rule,
    val intent: Intent,
    val owner: Rule,
)

data class Schedule(
    val strata: List<List<Rule>>,
    val relations: List<Relation>,
)

interface Scheduler {
    fun schedule(packs: List<RulePack>): Schedule
}
