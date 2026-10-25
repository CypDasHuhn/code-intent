package dev.cint.v2.cli

import dev.cint.contracts.util.ContractError
import kotlinx.serialization.Serializable

object CliExitCode {
    const val OK = 0
    const val INTERNAL_ERROR = 1
    const val INVALID_ARGUMENTS = 2
    const val ROOT_NOT_FOUND = 3
    const val MISSING_PROVIDER = 4
    const val MISSING_GRAMMAR = 5
    const val KNOWLEDGE_ERROR = 6
    const val ANALYSIS_FAILED = 7
    const val OUTPUT_ERROR = 8
}

@Serializable
data class CliErrorOutput(
    val error: CliError,
)

@Serializable
data class CliError(
    val code: String,
    val kind: String,
    val message: String,
    val details: Map<String, String> = emptyMap(),
)

class CliFailure(
    val exitCode: Int,
    val code: String,
    val kind: String,
    override val message: String,
    val details: Map<String, String> = emptyMap(),
) : RuntimeException(message) {
    fun asOutput(): CliErrorOutput = CliErrorOutput(CliError(code, kind, message, details))

    fun copy(details: Map<String, String> = this.details): CliFailure =
        CliFailure(exitCode, code, kind, message, details)

    companion object {
        fun invalidArguments(
            message: String,
            field: String? = null,
        ): CliFailure =
            CliFailure(
                CliExitCode.INVALID_ARGUMENTS,
                "INVALID_ARGUMENTS",
                "InvalidInput",
                message,
                field?.let { mapOf("field" to it) } ?: emptyMap(),
            )

        fun rootNotFound(message: String): CliFailure =
            CliFailure(CliExitCode.ROOT_NOT_FOUND, "ROOT_NOT_FOUND", "NotFound", message)

        fun missingProvider(message: String): CliFailure =
            CliFailure(CliExitCode.MISSING_PROVIDER, "MISSING_PROVIDER", "DependencyUnavailable", message)

        fun missingGrammar(message: String): CliFailure =
            CliFailure(CliExitCode.MISSING_GRAMMAR, "MISSING_GRAMMAR", "DependencyUnavailable", message)

        fun knowledge(message: String): CliFailure =
            CliFailure(CliExitCode.KNOWLEDGE_ERROR, "KNOWLEDGE_ERROR", "KnowledgeError", message)

        fun analysis(message: String): CliFailure =
            CliFailure(CliExitCode.ANALYSIS_FAILED, "ANALYSIS_FAILED", "AnalysisFailed", message)

        fun output(message: String): CliFailure =
            CliFailure(CliExitCode.OUTPUT_ERROR, "OUTPUT_ERROR", "OutputFailed", message)

        fun internal(message: String): CliFailure =
            CliFailure(CliExitCode.INTERNAL_ERROR, "INTERNAL_ERROR", "InternalError", message)
    }
}

fun ContractError.toCliFailure(): CliFailure =
    when (this) {
        is ContractError.InvalidInput ->
            if (input.orEmpty().contains("knowledge", ignoreCase = true) ||
                message.contains("knowledge", ignoreCase = true)
            ) {
                CliFailure.knowledge(message)
            } else {
                CliFailure.invalidArguments(message, input)
            }
        is ContractError.NotFound ->
            when (resource) {
                "knowledge store" -> CliFailure.knowledge("Knowledge store not found: $identifier")
                else -> CliFailure.analysis("$resource not found: $identifier")
            }

        is ContractError.SerializationError ->
            if (message.contains("knowledge", ignoreCase = true)) {
                CliFailure.knowledge(message)
            } else {
                CliFailure.analysis(message)
            }

        is ContractError.ValidationError ->
            if (field.orEmpty().contains("knowledge", ignoreCase = true) ||
                message.contains("knowledge", ignoreCase = true)
            ) {
                CliFailure.knowledge(message)
            } else {
                CliFailure.analysis(message)
            }

        is ContractError.DependencyUnavailable ->
            when {
                dependency.startsWith("tree-sitter") -> CliFailure.missingGrammar("$dependency: $reason")
                dependency.startsWith("semantic-provider") ||
                    dependency.startsWith("language-root") ||
                    dependency == "lsp" ->
                    CliFailure.missingProvider("$dependency: $reason")

                else -> CliFailure.analysis("$dependency unavailable: $reason")
            }

        is ContractError.PreconditionFailed ->
            if (message.contains("provider", ignoreCase = true) ||
                message.contains("lsp", ignoreCase = true) ||
                message.contains("semantic", ignoreCase = true) ||
                message.contains("hostChannel", ignoreCase = true)
            ) {
                CliFailure.missingProvider(message)
            } else {
                CliFailure.analysis(message)
            }

        is ContractError.AlreadyExists -> CliFailure.analysis("$resource already exists: $identifier")
        is ContractError.Timeout -> CliFailure.analysis("$operation timed out after ${timeoutMs}ms")
        is ContractError.Cancelled -> CliFailure.analysis("$operation was cancelled")
        is ContractError.InternalError ->
            if (message.contains("knowledge", ignoreCase = true)) {
                CliFailure.knowledge(message)
            } else {
                CliFailure.analysis(message)
            }
    }
