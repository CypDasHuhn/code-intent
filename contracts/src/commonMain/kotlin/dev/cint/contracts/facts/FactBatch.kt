package dev.cint.contracts.facts

import dev.cint.contracts.data.Span
import kotlinx.serialization.Serializable

@Serializable
data class FactBatch(
    val syntaxTree: SyntaxTree?,
    val calls: List<CallFact> = emptyList(),
    val writes: List<WriteFact> = emptyList(),
    val declarations: List<DeclarationFact> = emptyList(),
    val comments: List<CommentFact> = emptyList(),
    val scopes: List<ScopeFact> = emptyList(),
    val guards: List<GuardFact> = emptyList(),
    val returns: List<ReturnFact> = emptyList(),
    val properties: List<PropertyFact> = emptyList(),
    val functions: List<FunctionFact> = emptyList(),
    val ifs: List<IfFact> = emptyList(),
    val tries: List<TryFact> = emptyList(),
    val imports: List<ImportFact> = emptyList(),
    val references: List<ReferenceFact> = emptyList(),
    val statements: List<StatementFact> = emptyList(),
    val values: List<ValueFact> = emptyList(),
) {
    fun isEmpty(): Boolean =
        syntaxTree == null &&
            calls.isEmpty() && writes.isEmpty() && declarations.isEmpty() &&
            comments.isEmpty() && scopes.isEmpty() && guards.isEmpty() &&
            returns.isEmpty() && properties.isEmpty() && functions.isEmpty() &&
            ifs.isEmpty() && tries.isEmpty() && imports.isEmpty() && references.isEmpty() &&
            statements.isEmpty() && values.isEmpty()
}

@Serializable
data class ChangedRanges(
    val ranges: List<Span>,
    val isWholeFile: Boolean = false,
) {
    companion object {
        val EMPTY = ChangedRanges(emptyList())
        val WHOLE_FILE = ChangedRanges(emptyList(), true)
    }
}
