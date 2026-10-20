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

    fun calls(): List<CallFact>

    fun properties(): List<PropertyFact>

    fun functions(): List<FunctionFact>

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
)

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
