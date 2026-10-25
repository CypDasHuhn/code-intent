@file:Suppress("ExperimentalTime")

package dev.cint.contracts.facts

import dev.cint.contracts.data.Confidence
import dev.cint.contracts.data.Span
import dev.cint.contracts.util.Deadline
import dev.cint.contracts.util.Unavailable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SemanticRequest(
    val uri: String,
    val version: Long,
    val offset: Int,
    val kind: SemanticRequestKind,
    val deadline: Deadline,
    val offsetUnit: OffsetUnit = OffsetUnit.UTF8_BYTE,
)

@Serializable
enum class OffsetUnit {
    UTF8_BYTE,
    UTF16_CODE_UNIT,
}

@Serializable
enum class SemanticRequestKind {
    TYPE_AT,
    SYMBOL_AT,
    DEFINITION_OF,
    DIAGNOSTICS_FOR,
    HOVER_AT,
    REFERENCES_AT,
}

@Serializable
sealed interface SemanticResult {
    @Serializable
    @SerialName("TypeAt")
    data class TypeAt(
        val typeRef: TypeRef?,
        val unavailable: Unavailable? = null,
    ) : SemanticResult

    @Serializable
    @SerialName("SymbolAt")
    data class SymbolAt(
        val symbol: SymbolInfo?,
        val unavailable: Unavailable? = null,
    ) : SemanticResult

    @Serializable
    @SerialName("DefinitionOf")
    data class DefinitionOf(
        val locations: List<Location>,
        val unavailable: Unavailable? = null,
    ) : SemanticResult

    @Serializable
    @SerialName("DiagnosticsFor")
    data class DiagnosticsFor(
        val diagnostics: List<Diagnostic>,
        val unavailable: Unavailable? = null,
    ) : SemanticResult

    @Serializable
    @SerialName("HoverAt")
    data class HoverAt(
        val content: String?,
        val unavailable: Unavailable? = null,
    ) : SemanticResult

    @Serializable
    @SerialName("ReferencesAt")
    data class ReferencesAt(
        val locations: List<Location>,
        val unavailable: Unavailable? = null,
    ) : SemanticResult
}

@Serializable
data class SymbolInfo(
    val name: String,
    val kind: SymbolKind,
    val containerName: String?,
    val fqName: String?,
)

@Serializable
enum class SymbolKind {
    FUNCTION,
    METHOD,
    PROPERTY,
    FIELD,
    CLASS,
    INTERFACE,
    ENUM,
    TYPE_PARAMETER,
    VARIABLE,
    PARAMETER,
    MODULE,
    NAMESPACE,
    PACKAGE,
}

@Serializable
data class Location(
    val uri: String,
    val span: Span,
)

@Serializable
data class Diagnostic(
    val span: Span,
    val message: String,
    val severity: DiagnosticSeverity,
    val code: String? = null,
    val source: String = "semantic",
)

@Serializable
enum class DiagnosticSeverity {
    ERROR,
    WARNING,
    INFO,
    HINT,
}
