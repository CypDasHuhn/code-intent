package dev.cint.v2.mcp

import dev.cint.contracts.mcp.McpToolResult
import dev.cint.contracts.ports.Runtime
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractError
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun repoLanguagesReport(
    runtime: Runtime,
    cancellation: CancellationToken,
): McpToolResult {
    val result = runtime.languages(cancellation)
    if (result.isFailure) return toolFailure(result.errorOrNull!!)
    val languages = result.getOrThrow()
    return toolSuccess(
        buildJsonObject {
            put("languages", encodeMcp(languages))
            put("count", languages.size)
            put("availabilityProbe", "configuration-only")
            put(
                "availabilityCaveat",
                "Provider availability is inferred from configuration; repo.languages does not start an LSP process.",
            )
        },
    )
}

internal fun providerConfigurationHint(error: ContractError): JsonObject? =
    when (error) {
        is ContractError.PreconditionFailed, is ContractError.DependencyUnavailable -> {
            buildJsonObject {
                put(
                    "hint",
                    "Configure semantic providers with --provider-config PATH, " +
                        "--provider-command LANGUAGE=COMMAND, --provider-mode LANGUAGE=MODE, or " +
                        "--provider-endpoint LANGUAGE=ENDPOINT; call repo.languages to inspect resolved providers.",
                )
            }
        }

        else -> {
            null
        }
    }
