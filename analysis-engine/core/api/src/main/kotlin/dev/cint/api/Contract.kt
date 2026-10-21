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

    fun run(view: View): List<RuleEmit>
}

fun Rule.toContribution(emit: Emit): Contribution =
    Contribution(
        rule = id,
        role = Role.SOURCE_INTENT,
        intents = emit.intents,
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

sealed interface RuleEmit {
    val intents: Set<Intent>
    val span: Span
    val startByte: Int
    val endByte: Int
    val source: String
    val confidence: Double
}

enum class CandidateReason {
    GAP,
    LOW_CONFIDENCE,
    PACK_DOMAIN,
}

data class Emit(
    override val intents: Set<Intent>,
    override val span: Span,
    override val source: String,
    override val confidence: Double,
    override val startByte: Int,
    override val endByte: Int,
    val certifiesReturn: Boolean = false,
) : RuleEmit {
    companion object {
        const val DEFAULT_SOURCE: String = "treesitter"

        fun of(
            intent: Intent,
            node: SyntaxNode,
            source: String = DEFAULT_SOURCE,
            confidence: Double = 1.0,
            certifiesReturn: Boolean = false,
        ): Emit = of(setOf(intent), node, source, confidence, certifiesReturn)

        fun of(
            intents: Set<Intent>,
            node: SyntaxNode,
            source: String = DEFAULT_SOURCE,
            confidence: Double = 1.0,
            certifiesReturn: Boolean = false,
        ): Emit =
            Emit(
                intents = intents,
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
        ): Emit =
            Emit(
                intents = setOf(intent),
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
        ): Emit {
            val start = declaration.commentFor(includePrecedingComment, magicComment)
            return if (start != null) {
                range(intent, start.node, declaration.node, source, confidence)
            } else {
                of(intent, declaration.node, source, confidence)
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

data class CandidateEmit(
    override val intents: Set<Intent>,
    override val span: Span,
    override val startByte: Int,
    override val endByte: Int,
    override val source: String,
    override val confidence: Double,
    val reason: CandidateReason,
    val aiEligible: Boolean = true,
    val rationale: String? = null,
    val promptVersion: String? = null,
    val provider: String? = null,
    val model: String? = null,
) : RuleEmit {
    companion object {
        const val DEFAULT_SOURCE: String = "candidate"

        fun of(
            intent: Intent,
            node: SyntaxNode,
            reason: CandidateReason,
            source: String = DEFAULT_SOURCE,
            confidence: Double = 0.5,
            aiEligible: Boolean = true,
        ): CandidateEmit = of(setOf(intent), node, reason, source, confidence, aiEligible)

        fun of(
            intents: Set<Intent>,
            node: SyntaxNode,
            reason: CandidateReason,
            source: String = DEFAULT_SOURCE,
            confidence: Double = 0.5,
            aiEligible: Boolean = true,
        ): CandidateEmit =
            CandidateEmit(
                intents = intents,
                span = node.span(),
                startByte = node.startByte,
                endByte = node.endByte,
                source = source,
                confidence = confidence,
                reason = reason,
                aiEligible = aiEligible,
            )

        fun range(
            intent: Intent,
            start: SyntaxNode,
            end: SyntaxNode,
            reason: CandidateReason,
            source: String = DEFAULT_SOURCE,
            confidence: Double = 0.5,
            aiEligible: Boolean = true,
        ): CandidateEmit =
            CandidateEmit(
                intents = setOf(intent),
                span = Span(start.startLine, start.startCol, end.endLine, end.endCol),
                startByte = start.startByte,
                endByte = end.endByte,
                source = source,
                confidence = confidence,
                reason = reason,
                aiEligible = aiEligible,
            )

        fun declaration(
            intent: Intent,
            declaration: DeclarationFact,
            reason: CandidateReason,
            source: String = DEFAULT_SOURCE,
            confidence: Double = 0.5,
            aiEligible: Boolean = true,
            includePrecedingComment: Boolean = false,
            magicComment: String? = null,
        ): CandidateEmit {
            val start = declaration.commentFor(includePrecedingComment, magicComment)
            return if (start != null) {
                range(intent, start.node, declaration.node, reason, source, confidence, aiEligible)
            } else {
                of(intent, declaration.node, reason, source, confidence, aiEligible)
            }
        }

        fun call(
            intent: Intent,
            call: CallFact,
            reason: CandidateReason,
            source: String = DEFAULT_SOURCE,
            confidence: Double = 0.5,
            aiEligible: Boolean = true,
        ): CandidateEmit = of(intent, call.site, reason, source, confidence, aiEligible)
    }
}

private fun DeclarationFact.commentFor(
    includePrecedingComment: Boolean,
    name: String?,
): CommentFact? =
    name?.let { magicComment(it) }
        ?: if (includePrecedingComment) precedingComment else null

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

fun interface Matcher {
    fun match(view: View): List<RuleEmit>

    infix fun union(other: Matcher): Matcher = Matcher { view -> match(view) + other.match(view) }
}

fun matcherUnion(vararg matchers: Matcher): Matcher = Matcher { view -> matchers.flatMap { it.match(view) } }

abstract class SingleIntentPack<C : Any>(
    final override val id: String,
    val intent: Intent,
    final override val languages: Set<String>,
    protected val config: C,
    private val schema: ConfigSchema<C>,
    protected val ruleId: String = "$id:$id",
) : ConfigurableRulePack {
    final override val apiVersion: Int = CINT_API_VERSION

    protected open val reads: Set<Intent> = emptySet()

    protected abstract val matchers: List<Matcher>

    protected abstract fun withConfig(config: C): SingleIntentPack<C>

    override fun configure(values: Map<String, Any?>): RulePack =
        if (values.isEmpty()) this else withConfig(schema.decode(config, values, id))

    override fun configureAdditive(values: Map<String, Any?>): RulePack =
        if (values.isEmpty()) this else withConfig(schema.decodeAdditive(config, values, id))

    override fun configSchema(): ConfigSchema<*> = schema

    override fun rules(): List<Rule> = listOf(SingleIntentRule(ruleId, intent, reads, matchers))
}

private class SingleIntentRule(
    override val id: String,
    override val owns: Intent,
    override val reads: Set<Intent>,
    private val matchers: List<Matcher>,
) : Rule {
    override fun run(view: View): List<RuleEmit> = matcherUnion(*matchers.toTypedArray()).match(view)
}
