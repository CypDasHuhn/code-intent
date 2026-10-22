package dev.cint.rulepacks.intents

import dev.cint.api.SyntaxNode

private val TYPEABLE_RECEIVER_FRAGMENTS =
    setOf(
        "identifier",
        "call",
        "invocation",
        "index",
        "subscript",
        "element_access",
        "navigation",
        "member",
        "field",
        "qualified",
        "this_expression",
        "super_expression",
        "parenthesized",
        "parenthesised",
        "cast",
        "as_expression",
    )

private val NON_TYPEABLE_RECEIVER_FRAGMENTS =
    setOf(
        "literal",
        "string",
        "number",
        "integer",
        "float",
        "double",
        "decimal",
        "char",
        "regex",
        "lambda",
        "anonymous",
        "function_literal",
        "binary",
        "unary",
        "additive",
        "multiplicative",
        "comparison",
        "logical",
        "boolean",
        "ternary",
        "conditional",
        "assignment",
        "range",
        "object_literal",
        "array_literal",
        "collection_literal",
        "interpolated",
        "template_string",
    )

internal fun isTypeQueryableReceiver(receiver: SyntaxNode): Boolean {
    if (receiver.text.isBlank()) return false
    val kind = receiver.kind.lowercase()
    if (NON_TYPEABLE_RECEIVER_FRAGMENTS.any { it in kind }) return false
    return TYPEABLE_RECEIVER_FRAGMENTS.any { it in kind }
}
