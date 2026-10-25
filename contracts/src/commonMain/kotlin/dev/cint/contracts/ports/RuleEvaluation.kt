package dev.cint.contracts.ports

import dev.cint.contracts.data.Annotation
import dev.cint.contracts.data.CandidateReason
import dev.cint.contracts.data.Confidence
import dev.cint.contracts.data.Intent
import dev.cint.contracts.data.Span
import dev.cint.contracts.facts.CallFact
import dev.cint.contracts.facts.CommentFact
import dev.cint.contracts.facts.DeclarationFact
import dev.cint.contracts.facts.FunctionFact
import dev.cint.contracts.facts.GuardFact
import dev.cint.contracts.facts.IfFact
import dev.cint.contracts.facts.ImportFact
import dev.cint.contracts.facts.PropertyFact
import dev.cint.contracts.facts.ReferenceFact
import dev.cint.contracts.facts.ReturnFact
import dev.cint.contracts.facts.ScopeFact
import dev.cint.contracts.facts.SyntaxNode
import dev.cint.contracts.facts.TryFact
import dev.cint.contracts.facts.WriteFact
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory

interface RuleEvaluationView {
    val owns: Intent
    val reads: Set<Intent>
    val optionalReads: Set<Intent>

    val calls: List<CallFact>
    val writes: List<WriteFact>
    val declarations: List<DeclarationFact>
    val comments: List<CommentFact>
    val scopes: List<ScopeFact>
    val guards: List<GuardFact>
    val returns: List<ReturnFact>
    val properties: List<PropertyFact>
    val functions: List<FunctionFact>
    val ifs: List<IfFact>
    val tries: List<TryFact>
    val imports: List<ImportFact>
    val references: List<ReferenceFact>

    fun node(id: String?): SyntaxNode?

    fun children(node: SyntaxNode): List<SyntaxNode>

    fun descendants(node: SyntaxNode): Sequence<SyntaxNode>

    fun enclosingFunction(node: SyntaxNode): FunctionFact?

    fun enclosingDeclaration(node: SyntaxNode): DeclarationFact?

    fun annotations(intent: Intent): List<Annotation>

    fun intentsAt(node: SyntaxNode): Set<Intent>

    fun coverage(
        node: SyntaxNode,
        effect: Boolean = true,
    ): Set<Intent>
}

interface RuleEvaluator {
    val spec: RuleSpec

    fun evaluate(view: RuleEvaluationView): List<RuleEmission>
}

data class RulePlan(
    val rules: List<RuleEvaluator>,
    val composition: CompositionSpec? = null,
)

/**
 * Evaluator ownership is deferred to ticket 075: intent-pipeline constructs this
 * registry, selects evaluator implementations, and binds ticket 069 playbooks
 * through [bindRulePlan]. Ticket 084 asserts corpus parity of the bound plans.
 */
interface RuleEvaluatorRegistry {
    fun resolve(spec: RuleSpec): RuleEvaluator?
}

fun bindRulePlan(
    playbook: Playbook,
    registry: RuleEvaluatorRegistry,
): ContractResult<RulePlan> {
    val rules = mutableListOf<RuleEvaluator>()
    for (spec in playbook.rules) {
        val evaluator =
            registry.resolve(spec)
                ?: return ResultFactory.failure(
                    ContractError.NotFound("rule-evaluator", spec.id),
                )
        if (evaluator.spec != spec) {
            return ResultFactory.failure(
                ContractError.ValidationError(
                    "evaluator '${spec.id}' does not match its playbook spec",
                    "RuleSpec",
                ),
            )
        }
        rules += evaluator
    }
    return ResultFactory.success(RulePlan(rules, playbook.composition))
}

sealed interface RuleEmission {
    val intents: Set<Intent>
    val span: Span
    val startByte: Int
    val endByte: Int
    val source: String
    val confidence: Confidence
}

data class EstablishedEmission(
    override val intents: Set<Intent>,
    override val span: Span,
    override val startByte: Int,
    override val endByte: Int,
    override val source: String = DEFAULT_SOURCE,
    override val confidence: Confidence = Confidence.MAX,
    val certifiesReturn: Boolean = false,
) : RuleEmission {
    companion object {
        const val DEFAULT_SOURCE: String = "rule"
    }
}

data class CandidateEmission(
    override val intents: Set<Intent>,
    override val span: Span,
    override val startByte: Int,
    override val endByte: Int,
    override val source: String = DEFAULT_SOURCE,
    override val confidence: Confidence = Confidence.LOW,
    val reason: CandidateReason = CandidateReason.GAP,
    val aiEligible: Boolean = true,
    val rationale: String? = null,
    val provider: String? = null,
    val model: String? = null,
    val promptVersion: String? = null,
) : RuleEmission {
    companion object {
        const val DEFAULT_SOURCE: String = "candidate"
    }
}
