package dev.cint.contracts.facts

import dev.cint.contracts.data.Confidence
import dev.cint.contracts.data.Span
import kotlinx.serialization.Serializable

@Serializable
data class TypeRef(
    val fqName: String,
    val source: String,
    val confidence: Confidence,
)

@Serializable
data class CallFact(
    val nodeId: String,
    val siteId: String,
    val callee: String?,
    val receiverId: String?,
    val path: String?,
    val trailingLambdaId: String? = null,
    val arity: Int? = null,
    val calleeTokens: List<String> = emptyList(),
    val receiverPath: String? = null,
    val receiverSegments: List<String> = emptyList(),
    val receiverName: String? = null,
    val receiverLeaf: String? = null,
    val pathSegments: List<String> = emptyList(),
    val resolvedTarget: String? = null,
    val receiverType: String? = null,
)

@Serializable
enum class WriteKind {
    ASSIGNMENT,
    INCREMENT,
    UNKNOWN,
}

@Serializable
data class WriteFact(
    val nodeId: String,
    val kind: WriteKind,
    val referenceId: String? = null,
    val valueId: String? = null,
    val targetName: String? = null,
    val valueIdentifier: String? = null,
    val inLoopHeader: Boolean = false,
    val loopIncrement: Boolean = false,
)

@Serializable
enum class ValueKind {
    LITERAL,
    IDENTIFIER,
    CALL,
    OTHER,
}

@Serializable
data class ValueFact(
    val nodeId: String,
    val kind: ValueKind = ValueKind.OTHER,
    val constant: Boolean = false,
    val sideEffectFree: Boolean = false,
    val rejection: Boolean = false,
    val booleanValue: Boolean? = null,
    val literalText: String? = null,
    val identifier: String? = null,
    val returnLiteral: Boolean = false,
) {
    init {
        require(booleanValue == null || kind == ValueKind.LITERAL) {
            "booleanValue requires ValueKind.LITERAL"
        }
        require(!rejection || kind == ValueKind.LITERAL) {
            "rejection requires ValueKind.LITERAL"
        }
    }
}

@Serializable
enum class StatementKind {
    STATEMENT,
    RETURN,
    CONTINUE,
    BREAK,
}

@Serializable
data class StatementFact(
    val nodeId: String,
    val containerId: String,
    val expressionId: String? = null,
    val kind: StatementKind = StatementKind.STATEMENT,
)

@Serializable
enum class DeclarationKind {
    PROPERTY,
    VARIABLE,
    FUNCTION,
}

@Serializable
enum class CommentKind {
    LINE,
    BLOCK,
    DOC,
    OTHER,
}

@Serializable
data class CommentFact(
    val nodeId: String,
    val magicComment: String? = null,
    val kind: CommentKind = CommentKind.OTHER,
    val directives: List<String> = emptyList(),
)

@Serializable
data class DeclarationFact(
    val nodeId: String,
    val kind: DeclarationKind,
    val name: String?,
    val nameNodeId: String?,
    val annotations: List<String> = emptyList(),
    val annotationNodeIds: List<String> = emptyList(),
    val annotationNames: List<String> = emptyList(),
    val precedingCommentIds: List<String> = emptyList(),
    val scopeId: String? = null,
    val writeIds: List<String> = emptyList(),
    val trimmedEndSpan: Span? = null,
    val trimmedEndByte: Int? = null,
)

@Serializable
enum class ScopeKind {
    FILE,
    FUNCTION,
    BLOCK,
    LAMBDA,
    CLASS,
}

@Serializable
data class ScopeFact(
    val nodeId: String,
    val kind: ScopeKind,
    val ownerId: String? = null,
)

@Serializable
enum class GuardKind {
    CONDITIONAL,
    LOOP,
    OTHER,
}

@Serializable
data class GuardFact(
    val nodeId: String,
    val kind: GuardKind,
    val conditionId: String?,
    val thenBodyId: String?,
    val elseBodyId: String?,
    val exitIds: List<String> = emptyList(),
    val conditionSpan: Span? = null,
    val conditionStartByte: Int? = null,
    val conditionEndByte: Int? = null,
    val conditionConstant: Boolean = false,
    val conditionBoolean: Boolean? = null,
    val conditionNames: List<String> = emptyList(),
)

@Serializable
enum class JumpKind {
    RETURN,
    THROW,
    CONTINUE,
    BREAK,
}

@Serializable
data class ReturnFact(
    val nodeId: String,
    val expressionId: String?,
    val guardIds: List<String> = emptyList(),
    val loopId: String? = null,
    val handlerId: String? = null,
    val scopeId: String? = null,
    val jumpKind: JumpKind = JumpKind.RETURN,
    val rejection: Boolean = false,
    val labeled: Boolean = false,
    val expressionSpan: Span? = null,
)

@Serializable
data class ExitFact(
    val nodeId: String,
    val kind: JumpKind,
    val expressionId: String? = null,
    val rejection: Boolean = false,
    val guardIds: List<String> = emptyList(),
    val loopId: String? = null,
    val handlerId: String? = null,
)

@Serializable
data class CoalesceFact(
    val nodeId: String,
    val leftId: String? = null,
    val rightId: String? = null,
)

@Serializable
data class CreationFact(
    val nodeId: String,
    val type: String?,
    val typeNames: List<String> = emptyList(),
)

@Serializable
data class ParameterFact(
    val nodeId: String? = null,
    val name: String? = null,
    val type: String? = null,
    val typeNames: List<String> = emptyList(),
    val defaultValue: String? = null,
    val functionTyped: Boolean = false,
    val referencedPaths: List<String> = emptyList(),
    val annotations: List<String> = emptyList(),
    val annotationNames: List<String> = emptyList(),
)

@Serializable
data class ClassFact(
    val nodeId: String,
    val name: String?,
    val constructorParameters: List<ParameterFact> = emptyList(),
)

@Serializable
data class PropertyFact(
    val nodeId: String,
    val name: String?,
    val nameNodeId: String?,
    val initializerId: String?,
    val initializerIdentifier: String? = null,
)

@Serializable
data class FunctionFact(
    val nodeId: String,
    val name: String?,
    val nameNodeId: String?,
    val bodyId: String?,
    val declaringType: String? = null,
    val signature: String? = null,
    val arity: Int? = null,
    val parameters: List<ParameterFact> = emptyList(),
    val constructor: Boolean = false,
)

@Serializable
data class IfFact(
    val nodeId: String,
    val conditionId: String?,
    val thenBodyId: String?,
    val elseBodyId: String?,
    val elseKeywordId: String?,
)

@Serializable
data class TryFact(
    val nodeId: String,
    val bodyId: String?,
    val catchIds: List<String> = emptyList(),
    val finallyBlockId: String? = null,
    val headerStartId: String? = null,
    val headerEndId: String? = null,
)

@Serializable
data class ImportFact(
    val nodeId: String,
    val path: String,
    val alias: String? = null,
    val pathSegments: List<String> = emptyList(),
)

@Serializable
data class ReferenceFact(
    val name: String,
    val nodeIds: List<String>,
)
