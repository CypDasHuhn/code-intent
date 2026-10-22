package dev.cint.core.runtime.context

import dev.cint.api.Annotation
import dev.cint.api.CallFact
import dev.cint.api.CommentFact
import dev.cint.api.Coverage
import dev.cint.api.DeclarationFact
import dev.cint.api.FunctionFact
import dev.cint.api.GuardFact
import dev.cint.api.IfFact
import dev.cint.api.PropertyFact
import dev.cint.api.ReturnFact
import dev.cint.api.ScopeFact
import dev.cint.api.SyntaxNode
import dev.cint.api.TryFact
import dev.cint.api.TypeRef
import dev.cint.api.WriteFact
import dev.cint.core.facts.flow.ControlFlow
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.TypeOracle
import dev.cint.core.syntax.SyntaxModel
import dev.cint.core.syntax.SyntaxTree
import dev.cint.core.user.UserAnnotationResolver

interface RuleContext {
    val file: SourceFile
    val language: String
    val tree: SyntaxTree
    val syntax: SyntaxModel
    val flow: ControlFlow
    val oracle: TypeOracle

    val calls: List<CallFact>
    val properties: List<PropertyFact>
    val functions: List<FunctionFact>
    val declarations: List<DeclarationFact>
    val writes: List<WriteFact>
    val comments: List<CommentFact>
    val scopes: List<ScopeFact>
    val guards: List<GuardFact>
    val returnSites: List<ReturnFact>
    val ifs: List<IfFact>
    val tries: List<TryFact>
    val catches: List<SyntaxNode>
    val guardConditions: List<SyntaxNode>
    val imports: List<String>
    val references: Map<String, List<SyntaxNode>>
    val throws: List<SyntaxNode>

    fun receiverType(call: CallFact): TypeRef?

    fun commentBefore(node: SyntaxNode): CommentFact?

    fun annotations(): List<Annotation>

    fun covering(node: SyntaxNode): List<Annotation>

    fun containingAnnotations(node: SyntaxNode): List<Annotation>

    fun intentsAt(node: SyntaxNode): Set<String>

    fun coverage(
        node: SyntaxNode,
        effect: Boolean = true,
    ): Coverage

    fun isUserPure(node: SyntaxNode): Boolean =
        covering(node).any { annotation ->
            annotation.provenance.any(UserAnnotationResolver::isUserPure)
        }

    fun isUserReadOnly(node: SyntaxNode): Boolean =
        covering(node).any { annotation ->
            annotation.provenance.any(UserAnnotationResolver::isUserReadOnly)
        }

    fun isUserPureCall(node: SyntaxNode): Boolean = isUserCall(node, ::isUserPure)

    fun isUserReadOnlyCall(node: SyntaxNode): Boolean = isUserCall(node, ::isUserReadOnly)

    private fun isUserCall(
        node: SyntaxNode,
        predicate: (SyntaxNode) -> Boolean,
    ): Boolean {
        if (syntax.receiverNode(node) != null) return false
        val callee = syntax.calleeName(node) ?: return false
        val function = syntax.findFunction(tree, callee) ?: return false
        return predicate(function)
    }
}
