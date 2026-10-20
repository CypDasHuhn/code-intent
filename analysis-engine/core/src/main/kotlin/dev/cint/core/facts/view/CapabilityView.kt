package dev.cint.core.facts.view

import dev.cint.api.Annotation
import dev.cint.api.CallFact
import dev.cint.api.CommentFact
import dev.cint.api.Contribution
import dev.cint.api.Coverage
import dev.cint.api.DeclarationFact
import dev.cint.api.FunctionFact
import dev.cint.api.GuardFact
import dev.cint.api.IfFact
import dev.cint.api.Intent
import dev.cint.api.PropertyFact
import dev.cint.api.ReturnFact
import dev.cint.api.Role
import dev.cint.api.ScopeFact
import dev.cint.api.SyntaxNode
import dev.cint.api.TryFact
import dev.cint.api.TypeRef
import dev.cint.api.View
import dev.cint.api.WriteFact
import dev.cint.core.runtime.context.RuleContext

class CapabilityView(
    private val context: RuleContext,
    private val owns: Intent,
    private val reads: Set<Intent>,
    private val pureAnnotations: List<String>,
    private val failOnUndeclaredRead: Boolean = false,
) : View {
    private val visible: Set<Intent> = reads + owns

    override fun language(): String = context.language

    override fun typeAt(node: SyntaxNode): TypeRef? =
        context.oracle
            .typeAt(context.file, context.syntax.receiverOffset(node))
            ?.let { TypeRef(it.fqName, it.source, it.confidence) }

    override fun receiverType(call: CallFact): TypeRef? = context.receiverType(call)

    override fun calls(): List<CallFact> = context.calls

    override fun properties(): List<PropertyFact> = context.properties

    override fun functions(): List<FunctionFact> = context.functions

    override fun declarations(): List<DeclarationFact> = context.declarations

    override fun writes(): List<WriteFact> = context.writes

    override fun comments(): List<CommentFact> = context.comments

    override fun commentBefore(node: SyntaxNode): CommentFact? = context.commentBefore(node)

    override fun scopes(): List<ScopeFact> = context.scopes

    override fun enclosingScope(node: SyntaxNode): ScopeFact? = context.syntax.enclosingScope(node)

    override fun enclosingBlock(node: SyntaxNode): SyntaxNode? = context.syntax.enclosingBlock(node)

    override fun guards(): List<GuardFact> = context.guards

    override fun returnSites(): List<ReturnFact> = context.returnSites

    override fun ifs(): List<IfFact> = context.ifs

    override fun guardConditions(): List<SyntaxNode> = context.guardConditions

    override fun tries(): List<TryFact> = context.tries

    override fun catches(): List<SyntaxNode> = context.catches

    override fun imports(): List<String> = context.imports

    override fun references(): Map<String, List<SyntaxNode>> = context.references

    override fun throws(): List<SyntaxNode> = context.throws

    override fun coverage(node: SyntaxNode): Coverage = context.coverage(node)

    override fun enclosingFunction(node: SyntaxNode): SyntaxNode? = context.syntax.enclosingFunction(node)

    override fun enclosingDeclaration(node: SyntaxNode): SyntaxNode? = context.syntax.enclosingDeclaration(node)

    override fun writes(reference: SyntaxNode): SyntaxNode? = context.syntax.writes(reference)

    override fun isAnnotatedPure(node: SyntaxNode): Boolean = context.syntax.isAnnotatedPure(node, pureAnnotations)

    override fun isConstant(node: SyntaxNode): Boolean = context.syntax.isConstant(node)

    override fun isSideEffectFree(node: SyntaxNode): Boolean = context.syntax.isSideEffectFree(node)

    override fun statements(container: SyntaxNode): List<SyntaxNode>? = context.syntax.statements(container)

    override fun isExit(node: SyntaxNode): Boolean = context.syntax.isExit(node)

    override fun annotations(intent: Intent): List<Annotation> {
        if (intent !in visible) {
            if (failOnUndeclaredRead) {
                error("intent '$intent' is not declared by the rule owning '$owns' (reads: ${reads.sorted()})")
            }
            return emptyList()
        }
        return context
            .annotations()
            .asSequence()
            .filter { it.role == Role.SOURCE_INTENT }
            .mapNotNull { it.restrictedTo(visible) }
            .filter { intent in it.intents }
            .toList()
    }

    override fun intentsAt(node: SyntaxNode): Set<Intent> {
        val sourceCovering = context.containingAnnotations(node).filter { it.role == Role.SOURCE_INTENT }
        val covered =
            sourceCovering.mapNotNull { annotation ->
                val intents = annotation.visibleIntents(visible)
                if (intents.isEmpty()) null else annotation to intents
            }
        if (covered.isEmpty()) {
            if (failOnUndeclaredRead && sourceCovering.isNotEmpty()) {
                error("node is covered only by intents not declared by the rule owning '$owns' (reads: ${reads.sorted()})")
            }
            return emptySet()
        }
        val smallest = covered.minOf { (annotation, _) -> annotation.endByte - annotation.startByte }
        return covered
            .filter { (annotation, _) -> annotation.endByte - annotation.startByte == smallest }
            .flatMapTo(linkedSetOf()) { (_, intents) -> intents }
    }
}

private fun Annotation.visibleProvenance(visible: Set<Intent>): List<Contribution> =
    provenance.mapNotNull { contribution ->
        val intents = contribution.intents.filterTo(linkedSetOf()) { it in visible }
        if (intents.isEmpty()) null else contribution.copy(intents = intents)
    }

private fun Annotation.visibleIntents(visible: Set<Intent>): Set<Intent> =
    visibleProvenance(visible).flatMapTo(linkedSetOf()) { it.intents }

private fun Annotation.restrictedTo(visible: Set<Intent>): Annotation? {
    val provenance = visibleProvenance(visible)
    if (provenance.isEmpty()) return null
    return copy(
        intents = provenance.flatMap { it.intents }.sorted().toSet(),
        evidence = provenance.maxByOrNull { it.evidence.length }?.evidence.orEmpty(),
        source = provenance.map { it.source }.distinct().singleOrNull() ?: "mixed",
        confidence = provenance.maxOf { it.confidence },
        provenance = provenance,
    )
}
