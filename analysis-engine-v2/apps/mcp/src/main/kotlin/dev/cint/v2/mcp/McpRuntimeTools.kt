package dev.cint.v2.mcp

import dev.cint.contracts.data.Provenance
import dev.cint.contracts.data.Status
import dev.cint.contracts.knowledge.KnowledgeChange
import dev.cint.contracts.mcp.McpToolResult
import dev.cint.contracts.ports.AnalysisRequest
import dev.cint.contracts.ports.Runtime
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContractResult
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

internal class McpRuntimeTools(
    private val runtime: Runtime,
    private val now: () -> Instant = { Clock.System.now() },
) {
    private data class Proposal(
        val description: String,
        val change: KnowledgeChange.Batch,
    )

    private val proposals = ConcurrentHashMap<String, Proposal>()
    private val proposalCounter = AtomicLong()

    fun call(
        name: String,
        args: JsonObject,
        cancellation: CancellationToken = CancellationToken.NONE,
    ): McpToolResult =
        try {
            when (name) {
                "analysis.run" -> analysisRun(args, cancellation)
                "knowledge.snapshot" -> knowledgeSnapshot(cancellation)
                "function_annotations.list" -> functionAnnotationsList(args, cancellation)
                "function_annotations.set" -> functionAnnotationsSet(args, cancellation)
                "function_annotations.remove" -> functionAnnotationsRemove(args, cancellation)
                "span_overrides.list" -> spanOverridesList(args, cancellation)
                "span_overrides.set" -> spanOverridesSet(args, cancellation)
                "span_overrides.remove" -> spanOverridesRemove(args, cancellation)
                "patterns.list" -> patternsList(cancellation)
                "patterns.propose" -> patternsPropose(args, cancellation)
                "patterns.validate" -> patternsValidate(args, cancellation)
                "changes.propose" -> changesPropose(args)
                "changes.apply" -> changesApply(args, cancellation)
                "repo.status" -> repoStatus(cancellation)
                "repo.index" -> repoIndex(cancellation)
                else -> throw McpProtocolException(
                    -32602,
                    "Unknown tool: $name",
                    buildJsonObject {
                        put("code", "UNKNOWN_TOOL")
                        put("tool", name)
                    },
                )
            }
        } catch (error: McpInputException) {
            toolFailure(error.error)
        } catch (error: McpProtocolException) {
            throw error
        } catch (error: Exception) {
            toolFailure(
                ContractError.InternalError(
                    "Tool '$name' failed",
                    error.message ?: error::class.simpleName,
                ),
            )
        }

    fun readResource(
        uri: String,
        cancellation: CancellationToken = CancellationToken.NONE,
    ): String =
        when (uri) {
            McpResourceUris.SNAPSHOT -> {
                val snapshot = runtime.knowledge().snapshot(cancellation)
                    .orResourceFailure("knowledge snapshot")
                encodeMcp(snapshot).toString()
            }
            McpResourceUris.FUNCTION_ANNOTATIONS -> {
                val annotations = runtime.knowledge().functionAnnotations(cancellation)
                    .orResourceFailure("function annotations")
                buildJsonObject {
                    put("annotations", encodeMcp(annotations))
                }.toString()
            }
            McpResourceUris.SPAN_OVERRIDES -> {
                val overrides = runtime.knowledge().spanOverrides(cancellation)
                    .orResourceFailure("span overrides")
                buildJsonObject {
                    put("overrides", encodeMcp(overrides))
                }.toString()
            }
            McpResourceUris.PATTERN_HYPOTHESES -> {
                val hypotheses = runtime.knowledge().patternHypotheses(cancellation)
                    .orResourceFailure("pattern hypotheses")
                buildJsonObject {
                    put("hypotheses", encodeMcp(hypotheses))
                }.toString()
            }
            McpResourceUris.REPO_STATUS -> {
                val status = runtime.status(cancellation).orResourceFailure("repository status")
                encodeMcp(status).toString()
            }
            else -> throw McpProtocolException(
                -32002,
                "Unknown resource: $uri",
                buildJsonObject {
                    put("code", "UNKNOWN_RESOURCE")
                    put("uri", uri)
                },
            )
        }

    private fun analysisRun(args: JsonObject, cancellation: CancellationToken): McpToolResult {
        val request =
            AnalysisRequest(
                targetPaths = args.stringList("targetPaths").orEmpty(),
                language = args.string("language"),
                options = parseOptions(args["options"]),
                forceFullAnalysis = args.bool("forceFullAnalysis") ?: false,
            )
        val result = runtime.analyze(request, cancellation)
        if (result.isFailure) return toolFailure(result.errorOrNull!!)
        return toolSuccess(
            buildJsonObject {
                put("result", encodeMcp(result.getOrThrow()))
            },
        )
    }

    private fun knowledgeSnapshot(cancellation: CancellationToken): McpToolResult {
        val result = runtime.knowledge().snapshot(cancellation)
        if (result.isFailure) return toolFailure(result.errorOrNull!!)
        return toolSuccess(encodeMcp(result.getOrThrow()))
    }

    private fun functionAnnotationsList(args: JsonObject, cancellation: CancellationToken): McpToolResult {
        val result = runtime.knowledge().functionAnnotations(cancellation)
        if (result.isFailure) return toolFailure(result.errorOrNull!!)
        val filter = args.objectOrNull("filter") ?: args
        val provenance = enumList<Provenance>(filter["provenance"], "provenance")
        val status = enumList<Status>(filter["status"], "status")
        val intents = filter.stringList("intents")?.toSet()
        val annotations = result.getOrThrow().filter { annotation ->
            (provenance == null || annotation.provenance in provenance) &&
                (status == null || annotation.status in status) &&
                (intents == null || annotation.intents.any { it in intents })
        }
        return toolSuccess(
            buildJsonObject {
                put("annotations", encodeMcp(annotations))
                put("count", annotations.size)
            },
        )
    }

    private fun functionAnnotationsSet(args: JsonObject, cancellation: CancellationToken): McpToolResult {
        val body = KnowledgeInputs.unwrap(args, "annotation")
        val annotation = KnowledgeInputs.functionAnnotation(body, now())
        val result = runtime.knowledge().setFunctionAnnotation(annotation, cancellation)
        if (result.isFailure) return toolFailure(result.errorOrNull!!)
        return toolSuccess(
            buildJsonObject {
                put("annotation", encodeMcp(result.getOrThrow()))
            },
        )
    }

    private fun functionAnnotationsRemove(args: JsonObject, cancellation: CancellationToken): McpToolResult {
        val selector = KnowledgeInputs.functionSelector(args["selector"] ?: args)
        val result =
            runtime.knowledge().removeFunctionAnnotation(
                selector,
                cancellation,
                path = args.string("path"),
            )
        if (result.isFailure) return toolFailure(result.errorOrNull!!)
        return toolSuccess(
            buildJsonObject {
                put("success", true)
                put("selector", selector.identityKey)
            },
        )
    }

    private fun spanOverridesList(args: JsonObject, cancellation: CancellationToken): McpToolResult {
        val result = runtime.knowledge().spanOverrides(cancellation)
        if (result.isFailure) return toolFailure(result.errorOrNull!!)
        val filter = args.objectOrNull("filter") ?: args
        val provenance = enumList<Provenance>(filter["provenance"], "provenance")
        val status = enumList<Status>(filter["status"], "status")
        val path = filter.string("path")
        val overrides = result.getOrThrow().filter { override ->
            (provenance == null || override.provenance in provenance) &&
                (status == null || override.status in status) &&
                (path == null || override.selector.path == path)
        }
        return toolSuccess(
            buildJsonObject {
                put("overrides", encodeMcp(overrides))
                put("count", overrides.size)
            },
        )
    }

    private fun spanOverridesSet(args: JsonObject, cancellation: CancellationToken): McpToolResult {
        val body = KnowledgeInputs.unwrap(args, "override")
        val override = KnowledgeInputs.spanOverride(body, now())
        val result = runtime.knowledge().setSpanOverride(override, cancellation)
        if (result.isFailure) return toolFailure(result.errorOrNull!!)
        return toolSuccess(
            buildJsonObject {
                put("override", encodeMcp(result.getOrThrow()))
            },
        )
    }

    private fun spanOverridesRemove(args: JsonObject, cancellation: CancellationToken): McpToolResult {
        val selector = KnowledgeInputs.spanSelector(args["selector"] ?: args)
        val result = runtime.knowledge().removeSpanOverride(selector, cancellation)
        if (result.isFailure) return toolFailure(result.errorOrNull!!)
        return toolSuccess(
            buildJsonObject {
                put("success", true)
                put("selector", selector.identityKey)
            },
        )
    }

    private fun patternsList(cancellation: CancellationToken): McpToolResult {
        val result = runtime.knowledge().patternHypotheses(cancellation)
        if (result.isFailure) return toolFailure(result.errorOrNull!!)
        val hypotheses = result.getOrThrow()
        return toolSuccess(
            buildJsonObject {
                put("hypotheses", encodeMcp(hypotheses))
                put("count", hypotheses.size)
            },
        )
    }

    private fun patternsPropose(args: JsonObject, cancellation: CancellationToken): McpToolResult {
        val body = KnowledgeInputs.unwrap(args, "hypothesis")
        val hypothesis = KnowledgeInputs.patternHypothesis(body, now())
        val result = runtime.knowledge().setPatternHypothesis(hypothesis, cancellation)
        if (result.isFailure) return toolFailure(result.errorOrNull!!)
        return toolSuccess(
            buildJsonObject {
                put("hypothesis", encodeMcp(result.getOrThrow()))
            },
        )
    }

    private fun patternsValidate(args: JsonObject, cancellation: CancellationToken): McpToolResult {
        val id = args.string("id")?.takeIf { it.isNotBlank() }
            ?: return toolFailure(ContractError.InvalidInput("id is required", "id"))
        val accept = args.bool("accept")
            ?: return toolFailure(ContractError.InvalidInput("accept is required", "accept"))
        val hypotheses = runtime.knowledge().patternHypotheses(cancellation)
        if (hypotheses.isFailure) return toolFailure(hypotheses.errorOrNull!!)
        val existing =
            resolvePattern(hypotheses.getOrThrow(), id)
                ?: return toolFailure(ContractError.NotFound("pattern-hypothesis", id))
        val updated = existing.withStatus(
            if (accept) Status.ACCEPTED else Status.REJECTED,
            nextInstant(existing.updatedAt),
        )
        val result = runtime.knowledge().setPatternHypothesis(updated, cancellation)
        if (result.isFailure) return toolFailure(result.errorOrNull!!)
        return toolSuccess(
            buildJsonObject {
                put("success", true)
                put("hypothesis", encodeMcp(result.getOrThrow()))
            },
        )
    }

    private fun changesPropose(args: JsonObject): McpToolResult {
        val description = args.string("description").orEmpty()
        val changesElement = args["changes"]
            ?: return toolFailure(ContractError.InvalidInput("changes is required", "changes"))
        val changes = try {
            decodeMcp<List<KnowledgeChange>>(changesElement)
        } catch (error: Exception) {
            return toolFailure(
                ContractError.InvalidInput(
                    "Invalid knowledge change",
                    error.message ?: error::class.simpleName,
                ),
            )
        }
        if (changes.isEmpty()) {
            return toolFailure(ContractError.ValidationError("At least one change is required", "changes"))
        }
        val stamped = withServerTimestamps(changes, now())
        val id = "proposal-${proposalCounter.incrementAndGet()}-${now().toEpochMilliseconds()}"
        proposals[id] = Proposal(description, KnowledgeChange.Batch(description, stamped))
        return toolSuccess(
            buildJsonObject {
                put("proposalId", id)
                put("count", changes.size)
            },
        )
    }

    private fun changesApply(args: JsonObject, cancellation: CancellationToken): McpToolResult {
        val id = args.string("proposalId")?.takeIf { it.isNotBlank() }
            ?: return toolFailure(ContractError.InvalidInput("proposalId is required", "proposalId"))
        val proposal = proposals.remove(id)
            ?: return toolFailure(ContractError.NotFound("knowledge-proposal", id))
        val result = runtime.knowledge().proposeChange(proposal.change, cancellation)
        if (result.isFailure) {
            proposals[id] = proposal
            return toolFailure(result.errorOrNull!!)
        }
        return toolSuccess(
            buildJsonObject {
                put("success", true)
                put("proposalId", id)
                put("description", proposal.description)
            },
        )
    }

    private fun repoStatus(cancellation: CancellationToken): McpToolResult {
        val result = runtime.status(cancellation)
        if (result.isFailure) return toolFailure(result.errorOrNull!!)
        return toolSuccess(
            buildJsonObject {
                put("status", encodeMcp(result.getOrThrow()))
            },
        )
    }

    private fun repoIndex(cancellation: CancellationToken): McpToolResult {
        val result = runtime.status(cancellation)
        if (result.isFailure) return toolFailure(result.errorOrNull!!)
        val status = result.getOrThrow()
        return toolSuccess(
            buildJsonObject {
                put("indexedFiles", status.indexedFiles)
                put("status", encodeMcp(status))
            },
        )
    }

    private fun resolvePattern(
        hypotheses: List<dev.cint.contracts.knowledge.PatternHypothesis>,
        id: String,
    ): dev.cint.contracts.knowledge.PatternHypothesis? {
        hypotheses.firstOrNull { it.selector.identityKey == id }?.let { return it }
        val matches =
            hypotheses.filter { it.selector.pattern == id || it.selector.path == id }
        return when (matches.size) {
            0 -> null
            1 -> matches.single()
            else -> throw McpInputException(
                ContractError.ValidationError(
                    "Pattern id '$id' is ambiguous; use the identity key",
                    "id",
                ),
            )
        }
    }

    private fun withServerTimestamps(
        changes: List<KnowledgeChange>,
        timestamp: Instant,
    ): List<KnowledgeChange> =
        changes.map { change ->
            when (change) {
                is KnowledgeChange.SetFunctionAnnotation ->
                    change.copy(
                        annotation = change.annotation.copy(
                            createdAt = timestamp,
                            updatedAt = timestamp,
                        ),
                    )
                is KnowledgeChange.SetSpanOverride ->
                    change.copy(
                        override = change.override.copy(
                            createdAt = timestamp,
                            updatedAt = timestamp,
                        ),
                    )
                is KnowledgeChange.SetPatternHypothesis ->
                    change.copy(
                        hypothesis = change.hypothesis.copy(
                            createdAt = timestamp,
                            updatedAt = timestamp,
                        ),
                    )
                is KnowledgeChange.Batch -> change.copy(
                    changes = withServerTimestamps(change.changes, timestamp),
                )
                is KnowledgeChange.RemoveFunctionAnnotation,
                is KnowledgeChange.RemoveSpanOverride,
                is KnowledgeChange.RemovePatternHypothesis,
                is KnowledgeChange.MaterializeDefaults,
                -> change
            }
        }

    private fun parseOptions(element: JsonElement?): Map<String, String> {
        if (element == null || element is JsonNull) return emptyMap()
        val objectValue = element as? JsonObject
            ?: throw McpInputException(
                ContractError.InvalidInput("options must be an object", "options"),
            )
        return objectValue.mapValues { (key, value) ->
            value.asStringOrNull()
                ?: throw McpInputException(
                    ContractError.InvalidInput("options.$key must be a string", "options"),
                )
        }
    }

    private fun nextInstant(previous: Instant): Instant {
        val current = now()
        return if (current > previous) current else previous.milliseconds()
    }

    private fun Instant.milliseconds(): Instant = plus(1.milliseconds)

    private inline fun <reified T : Enum<T>> enumList(
        element: JsonElement?,
        field: String,
    ): List<T>? {
        if (element == null || element is JsonNull) return null
        val rawValues =
            when (element) {
                is JsonArray -> element.map {
                    it.asStringOrNull()
                        ?: throw McpInputException(
                            ContractError.InvalidInput("$field must contain only strings", field),
                        )
                }
                is JsonPrimitive -> listOf(
                    element.asStringOrNull()
                        ?: throw McpInputException(
                            ContractError.InvalidInput("$field must be a string or array", field),
                        ),
                )
                else -> throw McpInputException(
                    ContractError.InvalidInput("$field must be a string or array", field),
                )
            }
        return rawValues.map { raw ->
            enumValues<T>().firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: throw McpInputException(
                    ContractError.ValidationError("Unknown $field value '$raw'", field),
                )
        }
    }

    private fun <T> ContractResult<T>.orResourceFailure(what: String): T =
        if (isSuccess) {
            getOrThrow()
        } else {
            throw McpProtocolException(
                -32603,
                "Unable to read $what",
                contractErrorJson(errorOrNull!!),
            )
        }
}
