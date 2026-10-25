package dev.cint.v2.mcp

import dev.cint.contracts.data.Provenance
import dev.cint.contracts.data.Status
import dev.cint.contracts.init.InitCheckpoint
import dev.cint.contracts.init.InitProposal
import dev.cint.contracts.init.InitStatus
import dev.cint.contracts.knowledge.AnnotationScope
import dev.cint.contracts.knowledge.FunctionAnnotation
import dev.cint.contracts.knowledge.KnowledgeChange
import dev.cint.contracts.knowledge.KnowledgeSnapshot
import dev.cint.contracts.knowledge.PatternHypothesis
import dev.cint.contracts.mcp.McpToolResult
import dev.cint.contracts.ports.AnalysisRequest
import dev.cint.contracts.ports.InitCheckpointStore
import dev.cint.contracts.ports.Runtime
import dev.cint.runtime.InitCheckpointStores
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContentHash
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.milliseconds

internal class McpRuntimeTools(
    private val runtime: Runtime,
    private val now: () -> Instant = { Clock.System.now() },
    private val checkpoints: InitCheckpointStore = InitCheckpointStores.inMemory(),
    private val defaultWorkflowId: String = "default",
) {
    fun call(
        name: String,
        args: JsonObject,
        cancellation: CancellationToken = CancellationToken.NONE,
    ): McpToolResult =
        try {
            when (name) {
                "init.begin" -> initBegin(args, cancellation)
                "init.status" -> initStatus(args, cancellation)
                "init.complete" -> initComplete(args, cancellation)
                "analysis.run" -> analysisRun(args, cancellation)
                "knowledge.snapshot" -> knowledgeSnapshot(cancellation)
                "knowledge.suggestDefaults" -> suggestDefaults(args, cancellation)
                "knowledge.materializeDefaults" -> materializeDefaults(args, cancellation)
                "function_annotations.list" -> functionAnnotationsList(args, cancellation)
                "function_annotations.set" -> functionAnnotationsSet(args, cancellation)
                "function_annotations.remove" -> functionAnnotationsRemove(args, cancellation)
                "span_overrides.list" -> spanOverridesList(args, cancellation)
                "span_overrides.set" -> spanOverridesSet(args, cancellation)
                "span_overrides.remove" -> spanOverridesRemove(args, cancellation)
                "patterns.list" -> patternsList(cancellation)
                "patterns.propose" -> patternsPropose(args, cancellation)
                "patterns.validate" -> patternsValidate(args, cancellation)
                "patterns.materialize" -> patternsMaterialize(args, cancellation)
                "changes.propose" -> changesPropose(args, cancellation)
                "changes.apply" -> changesApply(args, cancellation)
                "repo.status" -> repoStatus(args, cancellation)
                "repo.index" -> repoIndex(args, cancellation)
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

    private fun initBegin(args: JsonObject, cancellation: CancellationToken): McpToolResult {
        val workflowId = workflowId(args)
        var resumed = false
        val updated =
            checkpoints.update(workflowId, cancellation) { existing ->
                resumed = existing != null
                ResultFactory.success(existing ?: newCheckpoint(workflowId))
            }
        if (updated.isFailure) return toolFailure(updated.errorOrNull!!)
        val checkpoint = updated.getOrThrow()
            ?: return toolFailure(ContractError.NotFound("init-checkpoint", workflowId))
        return checkpointSuccess(checkpoint, resumed)
    }

    private fun initStatus(args: JsonObject, cancellation: CancellationToken): McpToolResult {
        val workflowId = workflowId(args)
        val loaded = checkpoints.load(workflowId, cancellation)
        if (loaded.isFailure) return toolFailure(loaded.errorOrNull!!)
        val checkpoint = loaded.getOrThrow()
        return toolSuccess(
            buildJsonObject {
                put("workflowId", workflowId)
                put("started", checkpoint != null)
                put("status", checkpoint?.status?.name ?: "NOT_STARTED")
                put("completedSteps", encodeMcp(checkpoint?.completedSteps.orEmpty()))
                put("analyzedPaths", encodeMcp(checkpoint?.analyzedPaths.orEmpty()))
                put("analyzedHashes", encodeMcp(checkpoint?.analyzedHashes.orEmpty()))
                put("pendingProposals", encodeMcp(checkpoint?.pendingProposals().orEmpty()))
                checkpoint?.let { put("checkpoint", encodeMcp(it)) }
            },
        )
    }

    private fun initComplete(args: JsonObject, cancellation: CancellationToken): McpToolResult {
        val workflowId = workflowId(args)
        val updated =
            checkpoints.update(workflowId, cancellation) { existing ->
                val checkpoint =
                    existing
                        ?: return@update ResultFactory.failure(
                            ContractError.NotFound("init-checkpoint", workflowId),
                        )
                if (checkpoint.status == InitStatus.COMPLETE) {
                    return@update ResultFactory.failure(
                        ContractError.PreconditionFailed("Init checkpoint is already complete"),
                    )
                }
                val pending = checkpoint.pendingProposals()
                if (pending.isNotEmpty()) {
                    return@update ResultFactory.failure(
                        ContractError.PreconditionFailed(
                            "Cannot complete init with ${pending.size} pending proposal(s)",
                        ),
                    )
                }
                val missing = requiredInitSteps(checkpoint)
                if (missing.isNotEmpty()) {
                    return@update ResultFactory.failure(
                        ContractError.PreconditionFailed(
                            "Cannot complete init; missing steps: ${missing.joinToString(", ")}",
                        ),
                    )
                }
                ResultFactory.success(
                    checkpoint.copy(
                        status = InitStatus.COMPLETE,
                        updatedAt = nextInstant(checkpoint.updatedAt),
                    ),
                )
            }
        if (updated.isFailure) return toolFailure(updated.errorOrNull!!)
        val checkpoint = updated.getOrThrow()
            ?: return toolFailure(ContractError.NotFound("init-checkpoint", workflowId))
        return toolSuccess(
            buildJsonObject {
                put("workflowId", workflowId)
                put("status", checkpoint.status.name)
                put("checkpoint", encodeMcp(checkpoint))
            },
        )
    }

    private fun checkpointSuccess(
        checkpoint: InitCheckpoint,
        resumed: Boolean,
    ): McpToolResult =
        toolSuccess(
            buildJsonObject {
                put("workflowId", checkpoint.workflowId)
                put("resumed", resumed)
                put("checkpoint", encodeMcp(checkpoint))
                put("status", checkpoint.status.name)
                put("completedSteps", encodeMcp(checkpoint.completedSteps))
                put("analyzedPaths", encodeMcp(checkpoint.analyzedPaths))
                put("analyzedHashes", encodeMcp(checkpoint.analyzedHashes))
                put("pendingProposals", encodeMcp(checkpoint.pendingProposals()))
            },
        )

    private fun analysisRun(args: JsonObject, cancellation: CancellationToken): McpToolResult {
        val requested = args.stringList("targetPaths").orEmpty()
        if (requested.isEmpty()) {
            return toolFailure(
                ContractError.InvalidInput("analysis.run requires at least one target path", "targetPaths"),
            )
        }
        val workflowId = workflowId(args)
        val checkpointResult = checkpoints.load(workflowId, cancellation)
        if (checkpointResult.isFailure) return toolFailure(checkpointResult.errorOrNull!!)
        val checkpoint = checkpointResult.getOrThrow()
        val force = args.bool("forceFullAnalysis") ?: false
        val language = args.string("language")
        val options = parseOptions(args["options"])
        val currentHashes = linkedMapOf<String, String>()
        val skipped = mutableListOf<String>()
        val targets = mutableListOf<String>()
        for (path in requested.distinct()) {
            val hashResult = runtime.indexContentHash(path, cancellation)
            if (hashResult.isFailure) return toolFailure(hashResult.errorOrNull!!)
            val hash = hashResult.getOrThrow()
            val fingerprint = hash?.let { resumeFingerprint(it, language, options) }
            if (fingerprint != null) currentHashes[path] = fingerprint
            val storedHash = checkpoint?.analyzedHashes?.get(path)
            if (!force &&
                checkpoint?.status == InitStatus.IN_PROGRESS &&
                fingerprint != null &&
                fingerprint == storedHash
            ) {
                skipped += path
            } else {
                targets += path
            }
        }
        if (targets.isEmpty()) {
            return toolSuccess(
                buildJsonObject {
                    put("result", JsonNull)
                    put("analyzedPaths", buildJsonArray {})
                    put("skippedPaths", encodeMcp(skipped))
                },
            )
        }
        val request =
            AnalysisRequest(
                targetPaths = targets,
                language = language,
                options = options,
                forceFullAnalysis = force,
            )
        val result = runtime.analyze(request, cancellation)
        if (result.isFailure) return toolFailure(result.errorOrNull!!)
        val progress =
            recordProgress(
                workflowId = workflowId,
                steps = targets.map { "analysis:$it" },
                analyzedPaths = targets,
                analyzedHashes = targets.mapNotNull { path -> currentHashes[path]?.let { path to it } }.toMap(),
                cancellation = cancellation,
            )
        if (progress.isFailure) return toolFailure(progress.errorOrNull!!)
        return toolSuccess(
            buildJsonObject {
                put("result", encodeMcp(result.getOrThrow()))
                put("analyzedPaths", encodeMcp(targets))
                put("skippedPaths", encodeMcp(skipped))
            },
        )
    }

    private fun suggestDefaults(args: JsonObject, cancellation: CancellationToken): McpToolResult {
        val languages = args.stringList("languages")?.toSet()
        val result = runtime.suggestDefaults(languages, cancellation)
        if (result.isFailure) return toolFailure(result.errorOrNull!!)
        val defaults = result.getOrThrow()
        val progress =
            recordProgress(
                workflowId = workflowId(args),
                steps = listOf("defaults.suggest"),
                cancellation = cancellation,
            )
        if (progress.isFailure) return toolFailure(progress.errorOrNull!!)
        return toolSuccess(
            buildJsonObject {
                put("defaults", encodeMcp(defaults))
                put("count", defaults.size)
                put("knowledgeMaterialized", runtime.knowledge().isMaterialized)
            },
        )
    }

    private fun materializeDefaults(args: JsonObject, cancellation: CancellationToken): McpToolResult {
        val languages = args.stringList("languages")?.toSet()
        val suggested = runtime.suggestDefaults(languages, cancellation)
        if (suggested.isFailure) return toolFailure(suggested.errorOrNull!!)
        val defaults = suggested.getOrThrow()
        val before = runtime.knowledge().snapshot(cancellation)
        if (before.isFailure) return toolFailure(before.errorOrNull!!)
        val wasMaterialized = before.getOrThrow().materialized
        val result =
            runtime.materializeDefaults(
                KnowledgeChange.MaterializeDefaults(defaults),
                cancellation,
            )
        if (result.isFailure) return toolFailure(result.errorOrNull!!)
        val after = runtime.knowledge().snapshot(cancellation)
        if (after.isFailure) return toolFailure(after.errorOrNull!!)
        val snapshot = after.getOrThrow()
        val newlyMaterialized = !wasMaterialized && snapshot.materialized
        val defaultsStep =
            when {
                newlyMaterialized -> "defaults.materialize"
                wasMaterialized -> "defaults.skipped"
                defaults.isEmpty() -> "defaults.none"
                else -> "defaults.unmet"
            }
        val progress =
            recordProgress(
                workflowId = workflowId(args),
                steps = listOf(defaultsStep),
                cancellation = cancellation,
            )
        if (progress.isFailure) return toolFailure(progress.errorOrNull!!)
        return toolSuccess(
            buildJsonObject {
                put("materialized", newlyMaterialized)
                put("alreadyMaterialized", wasMaterialized || snapshot.materialized)
                put("defaults", encodeMcp(defaults))
                put("count", defaults.size)
                put(
                    "knowledgeEntries",
                    snapshot.functionAnnotations.size +
                        snapshot.spanOverrides.size +
                        snapshot.patternHypotheses.size,
                )
                put("knowledgeMaterialized", snapshot.materialized)
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
        val proposed = KnowledgeInputs.patternHypothesis(body, now())
        val hypothesis = proposed.copy(status = Status.SUGGESTED)
        val result = runtime.knowledge().setPatternHypothesis(hypothesis, cancellation)
        if (result.isFailure) return toolFailure(result.errorOrNull!!)
        val stored = result.getOrThrow()
        val progress =
            recordProgress(
                workflowId = workflowId(args),
                steps = listOf("pattern.propose:${stored.selector.identityKey}"),
                cancellation = cancellation,
            )
        if (progress.isFailure) return toolFailure(progress.errorOrNull!!)
        return toolSuccess(
            buildJsonObject {
                put("hypothesis", encodeMcp(stored))
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
        val targetStatus = if (accept) Status.ACCEPTED else Status.REJECTED
        if (existing.status != Status.SUGGESTED && existing.status != targetStatus) {
            return toolFailure(
                ContractError.PreconditionFailed(
                    "Pattern hypothesis '${existing.selector.identityKey}' has already been ${existing.status.name.lowercase()}",
                ),
            )
        }
        val updated =
            if (existing.status == targetStatus) {
                existing
            } else {
                existing.withStatus(targetStatus, nextInstant(existing.updatedAt))
            }
        val result =
            if (updated === existing) {
                ResultFactory.success(existing)
            } else {
                runtime.knowledge().setPatternHypothesis(updated, cancellation)
            }
        if (result.isFailure) return toolFailure(result.errorOrNull!!)
        val stored = result.getOrThrow()
        val progress =
            recordProgress(
                workflowId = workflowId(args),
                steps = listOf("pattern.validate:${stored.selector.identityKey}:${stored.status.name}"),
                cancellation = cancellation,
            )
        if (progress.isFailure) return toolFailure(progress.errorOrNull!!)
        return toolSuccess(
            buildJsonObject {
                put("success", true)
                put("hypothesis", encodeMcp(stored))
            },
        )
    }

    private fun patternsMaterialize(args: JsonObject, cancellation: CancellationToken): McpToolResult {
        val id = args.string("id")?.takeIf { it.isNotBlank() }
            ?: return toolFailure(ContractError.InvalidInput("id is required", "id"))
        val hypotheses = runtime.knowledge().patternHypotheses(cancellation)
        if (hypotheses.isFailure) return toolFailure(hypotheses.errorOrNull!!)
        val hypothesis =
            resolvePattern(hypotheses.getOrThrow(), id)
                ?: return toolFailure(ContractError.NotFound("pattern-hypothesis", id))
        if (hypothesis.status != Status.ACCEPTED && hypothesis.status != Status.ESTABLISHED) {
            return toolFailure(
                ContractError.PreconditionFailed(
                    "Pattern hypothesis '${hypothesis.selector.identityKey}' must be accepted before materialization",
                ),
            )
        }
        val targets = args.arrayOrNull("targets")
        if (targets == null || targets.isEmpty()) {
            return toolFailure(
                ContractError.InvalidInput("targets must contain at least one function", "targets"),
            )
        }
        val desired =
            targets.map { element ->
                val body =
                    element as? JsonObject
                        ?: throw McpInputException(
                            ContractError.InvalidInput("each target must be an object", "targets"),
                        )
                KnowledgeInputs.patternTarget(body, hypothesis, now())
            }
        desired.forEach { annotation ->
            validatePatternTarget(annotation, hypothesis)?.let { return toolFailure(it) }
        }
        val snapshot = runtime.knowledge().snapshot(cancellation)
        if (snapshot.isFailure) return toolFailure(snapshot.errorOrNull!!)
        val existingByKey =
            snapshot.getOrThrow().functionAnnotations.associateBy { it.identityKey }
        val toCreate = mutableListOf<FunctionAnnotation>()
        val unchanged = mutableListOf<FunctionAnnotation>()
        for (annotation in desired) {
            val existing = existingByKey[annotation.identityKey]
            when {
                existing == null -> toCreate += annotation
                existing.patternId == hypothesis.selector.identityKey -> unchanged += existing
                else -> return toolFailure(
                    ContractError.PreconditionFailed(
                        "Function annotation '${annotation.identityKey}' already exists and is not owned by this pattern",
                    ),
                )
            }
        }
        if (toCreate.isNotEmpty()) {
            val batch =
                KnowledgeChange.Batch(
                    "materialize pattern ${hypothesis.selector.identityKey}",
                    toCreate.map { KnowledgeChange.SetFunctionAnnotation(it) },
                )
            val applied = runtime.knowledge().proposeChange(batch, cancellation)
            if (applied.isFailure) return toolFailure(applied.errorOrNull!!)
        }
        val progress =
            recordProgress(
                workflowId = workflowId(args),
                steps = listOf("pattern.materialize:${hypothesis.selector.identityKey}"),
                cancellation = cancellation,
            )
        if (progress.isFailure) return toolFailure(progress.errorOrNull!!)
        return toolSuccess(
            buildJsonObject {
                put("hypothesisId", hypothesis.selector.identityKey)
                put("annotations", encodeMcp(desired))
                put("count", desired.size)
                put("created", toCreate.size)
                put("unchanged", unchanged.size)
            },
        )
    }

    private fun changesPropose(args: JsonObject, cancellation: CancellationToken): McpToolResult {
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
        forbiddenChange(KnowledgeChange.Batch(description, changes))?.let { return toolFailure(it) }
        val workflowId = workflowId(args)
        val explicitId = args.string("proposalId")?.takeIf { it.isNotBlank() }
        val canonical = canonicalChanges(changes)
        val id = explicitId ?: stableProposalId(description, canonical)
        var idempotent = false
        var applied = false
        val timestamp = now()
        val updated =
            checkpoints.update(workflowId, cancellation) { existing ->
                val checkpoint = existing ?: newCheckpoint(workflowId)
                val reopened = reopen(checkpoint)
                val existingProposal = reopened.proposal(id)
                if (existingProposal != null) {
                    if (canonicalChanges(existingProposal.changes) != canonical) {
                        return@update ResultFactory.failure(
                            ContractError.AlreadyExists("knowledge-proposal", id),
                        )
                    }
                    idempotent = true
                    applied = existingProposal.isApplied
                    return@update ResultFactory.success(reopened)
                }
                val snapshot = runtime.knowledge().snapshot(cancellation)
                if (snapshot.isFailure) return@update ResultFactory.failure(snapshot.errorOrNull!!)
                val proposal =
                    InitProposal(
                        id = id,
                        description = description,
                        changes = KnowledgeChange.Batch(description, withServerTimestamps(changes, timestamp)),
                        createdAt = timestamp,
                        baseKnowledgeTimestamp = snapshot.getOrThrow().timestamp,
                    )
                ResultFactory.success(
                    reopened.copy(
                        proposals = reopened.proposals + proposal,
                        updatedAt = nextInstant(reopened.updatedAt),
                    ),
                )
            }
        if (updated.isFailure) return toolFailure(updated.errorOrNull!!)
        return toolSuccess(
            buildJsonObject {
                put("proposalId", id)
                put("count", changes.size)
                put("idempotent", idempotent)
                put("applied", applied)
            },
        )
    }

    private fun changesApply(args: JsonObject, cancellation: CancellationToken): McpToolResult {
        val id = args.string("proposalId")?.takeIf { it.isNotBlank() }
            ?: return toolFailure(ContractError.InvalidInput("proposalId is required", "proposalId"))
        val workflowId = workflowId(args)
        var alreadyApplied = false
        var reconciled = false
        val updated =
            checkpoints.update(workflowId, cancellation) { existing ->
                val checkpoint = existing
                    ?: return@update ResultFactory.failure(
                        ContractError.NotFound("knowledge-proposal", id),
                    )
                val proposal = checkpoint.proposal(id)
                    ?: return@update ResultFactory.failure(
                        ContractError.NotFound("knowledge-proposal", id),
                    )
                forbiddenChange(proposal.changes)?.let {
                    return@update ResultFactory.failure(it)
                }
                val before = runtime.knowledge().snapshot(cancellation)
                if (before.isFailure) return@update ResultFactory.failure(before.errorOrNull!!)
                val snapshot = before.getOrThrow()
                val reflected = proposalReflected(proposal.changes, snapshot)
                val knowledgeRegressed =
                    proposal.appliedKnowledgeTimestamp?.let { snapshot.timestamp < it } ?: false
                if (proposal.isApplied && reflected && !knowledgeRegressed) {
                    alreadyApplied = true
                    return@update ResultFactory.success(checkpoint)
                }
                if (!proposal.isApplied && reflected && !knowledgeRegressed) {
                    reconciled = true
                    return@update ResultFactory.success(
                        checkpoint.markApplied(
                            id = id,
                            proposal = proposal,
                            appliedAt = nextInstant(proposal.createdAt),
                            knowledgeTimestamp = snapshot.timestamp,
                        ),
                    )
                }
                staleRemoval(proposal.changes, snapshot, proposal.createdAt)?.let {
                    return@update ResultFactory.failure(it)
                }
                val result = runtime.knowledge().proposeChange(proposal.changes, cancellation)
                if (result.isFailure) return@update ResultFactory.failure(result.errorOrNull!!)
                val after = runtime.knowledge().snapshot(cancellation)
                if (after.isFailure) return@update ResultFactory.failure(after.errorOrNull!!)
                ResultFactory.success(
                    checkpoint.markApplied(
                        id = id,
                        proposal = proposal,
                        appliedAt = nextInstant(proposal.createdAt),
                        knowledgeTimestamp = after.getOrThrow().timestamp,
                    ),
                )
            }
        if (updated.isFailure) return toolFailure(updated.errorOrNull!!)
        val checkpoint = updated.getOrThrow()
            ?: return toolFailure(ContractError.NotFound("knowledge-proposal", id))
        val proposal = checkpoint.proposal(id)
            ?: return toolFailure(ContractError.NotFound("knowledge-proposal", id))
        return toolSuccess(
            buildJsonObject {
                put("success", true)
                put("proposalId", id)
                put("description", proposal.description)
                put("alreadyApplied", alreadyApplied)
                put("reconciled", reconciled)
            },
        )
    }

    private fun repoStatus(
        args: JsonObject,
        cancellation: CancellationToken,
    ): McpToolResult {
        val workflowId = workflowId(args)
        val result = runtime.status(cancellation)
        if (result.isFailure) return toolFailure(result.errorOrNull!!)
        val status = result.getOrThrow()
        val loaded = checkpoints.load(workflowId, cancellation)
        if (loaded.isFailure) return toolFailure(loaded.errorOrNull!!)
        val checkpoint = loaded.getOrThrow()
        val repositoryInitialized =
            checkpoint?.status == InitStatus.COMPLETE ||
                status.knowledgeMaterialized ||
                status.knowledgeEntries > 0
        return toolSuccess(
            buildJsonObject {
                put("workflowId", workflowId)
                put("repositoryInitialized", repositoryInitialized)
                put("checkpointStatus", checkpoint?.status?.name ?: "NOT_STARTED")
                put("pendingProposals", checkpoint?.pendingProposals()?.size ?: 0)
                put("status", encodeMcp(status))
            },
        )
    }

    private fun repoIndex(
        args: JsonObject,
        cancellation: CancellationToken,
    ): McpToolResult {
        val workflowId = workflowId(args)
        val result = runtime.status(cancellation)
        if (result.isFailure) return toolFailure(result.errorOrNull!!)
        val status = result.getOrThrow()
        val loaded = checkpoints.load(workflowId, cancellation)
        if (loaded.isFailure) return toolFailure(loaded.errorOrNull!!)
        val checkpointPaths = loaded.getOrThrow()?.analyzedPaths.orEmpty()
        val runtimePaths = status.indexedPaths
        val paths = (runtimePaths + checkpointPaths).distinct().sorted()
        val source =
            when {
                runtimePaths.isEmpty() && checkpointPaths.isNotEmpty() -> "checkpoint"
                runtimePaths.isNotEmpty() -> "runtime"
                else -> "none"
            }
        return toolSuccess(
            buildJsonObject {
                put("workflowId", workflowId)
                put("indexedFiles", paths.size)
                put("indexedPaths", encodeMcp(paths))
                put("runtimeIndexedFiles", status.indexedFiles)
                put("checkpointAnalyzedFiles", checkpointPaths.size)
                put("source", source)
                put("status", encodeMcp(status))
            },
        )
    }

    private fun workflowId(args: JsonObject): String =
        args.string("workflowId")?.takeIf { it.isNotBlank() } ?: defaultWorkflowId

    private fun newCheckpoint(workflowId: String): InitCheckpoint {
        val timestamp = now()
        return InitCheckpoint(
            workflowId = workflowId,
            status = InitStatus.IN_PROGRESS,
            createdAt = timestamp,
            updatedAt = timestamp,
        )
    }

    private fun reopen(checkpoint: InitCheckpoint): InitCheckpoint =
        if (checkpoint.status == InitStatus.COMPLETE) {
            checkpoint.copy(
                status = InitStatus.IN_PROGRESS,
                updatedAt = nextInstant(checkpoint.updatedAt),
            )
        } else {
            checkpoint
        }

    private fun requiredInitSteps(checkpoint: InitCheckpoint): List<String> =
        buildList {
            val defaultsComplete =
                checkpoint.completedSteps.any {
                    it == "defaults.materialize" ||
                        it == "defaults.skipped" ||
                        it == "defaults.none"
                }
            if (!defaultsComplete) {
                add("defaults")
            }
            if (checkpoint.completedSteps.none { it.startsWith("analysis:") }) {
                add("analysis")
            }
        }

    private fun recordProgress(
        workflowId: String,
        steps: List<String> = emptyList(),
        analyzedPaths: List<String> = emptyList(),
        analyzedHashes: Map<String, String> = emptyMap(),
        cancellation: CancellationToken,
    ): ContractResult<Unit> {
        val updated =
            checkpoints.update(workflowId, cancellation) { existing ->
                if (existing == null) return@update ResultFactory.success(null)
                val reopened = reopen(existing)
                val nextSteps = (reopened.completedSteps + steps).distinct().sorted()
                val nextPaths = (reopened.analyzedPaths + analyzedPaths).distinct().sorted()
                val nextHashes = reopened.analyzedHashes + analyzedHashes
                if (nextSteps == existing.completedSteps &&
                    nextPaths == existing.analyzedPaths &&
                    nextHashes == existing.analyzedHashes &&
                    reopened === existing
                ) {
                    return@update ResultFactory.success(existing)
                }
                ResultFactory.success(
                    reopened.copy(
                        completedSteps = nextSteps,
                        analyzedPaths = nextPaths,
                        analyzedHashes = nextHashes,
                        updatedAt = nextInstant(reopened.updatedAt),
                    ),
                )
            }
        if (updated.isFailure) return ResultFactory.failure(updated.errorOrNull!!)
        return ResultFactory.success(Unit)
    }

    private fun resumeFingerprint(
        contentHash: String,
        language: String?,
        options: Map<String, String>,
    ): String = buildString {
        fun appendField(value: String) {
            append(value.length)
            append(':')
            append(value)
        }
        appendField(contentHash)
        append('|')
        appendField(language.orEmpty())
        append('|')
        options.toSortedMap().forEach { (key, value) ->
            appendField(key)
            append('=')
            appendField(value)
            append(';')
        }
    }

    private fun stableProposalId(
        description: String,
        changes: List<KnowledgeChange>,
    ): String {
        val encoded = mcpJson.encodeToJsonElement(changes).toString()
        return "proposal-" + ContentHash.compute("$description\u0000$encoded").value.take(24)
    }

    private fun canonicalChanges(change: KnowledgeChange): List<KnowledgeChange> =
        canonicalChanges(
            when (change) {
                is KnowledgeChange.Batch -> change.changes
                else -> listOf(change)
            },
        )

    private fun canonicalChanges(changes: List<KnowledgeChange>): List<KnowledgeChange> =
        changes
            .map(::canonicalChange)
            .sortedBy { mcpJson.encodeToJsonElement(it).toString() }

    private fun canonicalChange(change: KnowledgeChange): KnowledgeChange =
        when (change) {
            is KnowledgeChange.SetFunctionAnnotation ->
                change.copy(
                    annotation =
                        change.annotation.copy(
                            intents = change.annotation.intents.sorted().toCollection(linkedSetOf()),
                            evidence = change.annotation.evidence.sorted(),
                            createdAt = CANONICAL_EPOCH,
                            updatedAt = CANONICAL_EPOCH,
                        ),
                )
            is KnowledgeChange.SetSpanOverride ->
                change.copy(
                    override =
                        change.override.copy(
                            intents = change.override.intents.sorted().toCollection(linkedSetOf()),
                            createdAt = CANONICAL_EPOCH,
                            updatedAt = CANONICAL_EPOCH,
                        ),
                )
            is KnowledgeChange.SetPatternHypothesis ->
                change.copy(
                    hypothesis =
                        change.hypothesis.copy(
                            evidence = change.hypothesis.evidence.sorted(),
                            examples = change.hypothesis.examples.sorted(),
                            counterexamples = change.hypothesis.counterexamples.sorted(),
                            proposedIntents =
                                change.hypothesis.proposedIntents.sorted().toCollection(linkedSetOf()),
                            createdAt = CANONICAL_EPOCH,
                            updatedAt = CANONICAL_EPOCH,
                        ),
                )
            is KnowledgeChange.Batch ->
                change.copy(changes = canonicalChanges(change.changes))
            is KnowledgeChange.MaterializeDefaults ->
                change.copy(
                    defaults =
                        change.defaults
                            .map {
                                it.copy(
                                    intents = it.intents.sorted().toCollection(linkedSetOf()),
                                    createdAt = CANONICAL_EPOCH,
                                )
                            }
                            .sortedBy { it.selector.identityKey },
                )
            is KnowledgeChange.RemoveFunctionAnnotation,
            is KnowledgeChange.RemoveSpanOverride,
            is KnowledgeChange.RemovePatternHypothesis,
            -> change
        }

    private fun forbiddenChange(change: KnowledgeChange): ContractError? =
        when (change) {
            is KnowledgeChange.Batch ->
                change.changes.firstNotNullOfOrNull { forbiddenChange(it) }
            is KnowledgeChange.SetPatternHypothesis ->
                if (change.hypothesis.status != Status.SUGGESTED) {
                    ContractError.PreconditionFailed(
                        "Pattern hypotheses must be accepted through patterns.validate",
                    )
                } else {
                    null
                }
            is KnowledgeChange.MaterializeDefaults ->
                ContractError.PreconditionFailed(
                    "Pack defaults must be materialized through knowledge.materializeDefaults",
                )
            else -> null
        }

    private fun proposalReflected(
        change: KnowledgeChange,
        snapshot: KnowledgeSnapshot,
    ): Boolean =
        when (change) {
            is KnowledgeChange.Batch -> change.changes.all { proposalReflected(it, snapshot) }
            is KnowledgeChange.SetFunctionAnnotation -> {
                val expected = change.annotation
                val existing =
                    snapshot.functionAnnotations.firstOrNull {
                        it.identityKey == expected.identityKey
                    }
                existing != null &&
                    existing.intents == expected.intents &&
                    existing.scope == expected.scope &&
                    existing.provenance == expected.provenance &&
                    existing.confidence == expected.confidence &&
                    existing.status == expected.status &&
                    existing.path == expected.path &&
                    existing.patternId == expected.patternId &&
                    existing.evidence == expected.evidence
            }
            is KnowledgeChange.RemoveFunctionAnnotation ->
                snapshot.functionAnnotations.none { it.selector == change.selector }
            is KnowledgeChange.SetSpanOverride -> {
                val expected = change.override
                val existing =
                    snapshot.spanOverrides.firstOrNull {
                        it.selector.identityKey == expected.selector.identityKey
                    }
                existing != null &&
                    existing.intents == expected.intents &&
                    existing.provenance == expected.provenance &&
                    existing.confidence == expected.confidence &&
                    existing.status == expected.status &&
                    existing.rationale == expected.rationale
            }
            is KnowledgeChange.RemoveSpanOverride ->
                snapshot.spanOverrides.none {
                    it.selector.identityKey == change.selector.identityKey
                }
            is KnowledgeChange.SetPatternHypothesis -> {
                val expected = change.hypothesis
                val existing =
                    snapshot.patternHypotheses.firstOrNull {
                        it.selector.identityKey == expected.selector.identityKey
                    }
                existing != null &&
                    existing.scope == expected.scope &&
                    existing.evidence == expected.evidence &&
                    existing.examples == expected.examples &&
                    existing.counterexamples == expected.counterexamples &&
                    existing.proposedIntents == expected.proposedIntents &&
                    existing.provenance == expected.provenance &&
                    existing.confidence == expected.confidence &&
                    existing.status == expected.status
            }
            is KnowledgeChange.RemovePatternHypothesis ->
                snapshot.patternHypotheses.none {
                    it.selector.identityKey == change.selector.identityKey
                }
            is KnowledgeChange.MaterializeDefaults -> snapshot.materialized
        }

    private fun staleRemoval(
        change: KnowledgeChange,
        snapshot: KnowledgeSnapshot,
        proposalCreatedAt: Instant,
    ): ContractError? =
        when (change) {
            is KnowledgeChange.Batch -> {
                change.changes.firstNotNullOfOrNull {
                    staleRemoval(it, snapshot, proposalCreatedAt)
                }
            }
            is KnowledgeChange.RemoveFunctionAnnotation -> {
                val stale =
                    snapshot.functionAnnotations.any {
                        it.selector == change.selector && it.updatedAt > proposalCreatedAt
                    }
                if (stale) {
                    ContractError.PreconditionFailed(
                        "Refusing stale removal of function annotation '${change.selector.identityKey}'",
                    )
                } else {
                    null
                }
            }
            is KnowledgeChange.RemoveSpanOverride -> {
                val stale =
                    snapshot.spanOverrides.any {
                        it.selector.identityKey == change.selector.identityKey &&
                            it.updatedAt > proposalCreatedAt
                    }
                if (stale) {
                    ContractError.PreconditionFailed(
                        "Refusing stale removal of span override '${change.selector.identityKey}'",
                    )
                } else {
                    null
                }
            }
            is KnowledgeChange.RemovePatternHypothesis -> {
                val stale =
                    snapshot.patternHypotheses.any {
                        it.selector.identityKey == change.selector.identityKey &&
                            it.updatedAt > proposalCreatedAt
                    }
                if (stale) {
                    ContractError.PreconditionFailed(
                        "Refusing stale removal of pattern hypothesis '${change.selector.identityKey}'",
                    )
                } else {
                    null
                }
            }
            else -> null
        }

    private fun InitCheckpoint.markApplied(
        id: String,
        proposal: InitProposal,
        appliedAt: Instant,
        knowledgeTimestamp: Long,
    ): InitCheckpoint =
        copy(
            proposals =
                proposals.map {
                    if (it.id == id) {
                        it.copy(
                            appliedAt = appliedAt,
                            appliedKnowledgeTimestamp = knowledgeTimestamp,
                        )
                    } else {
                        it
                    }
                },
            completedSteps =
                (completedSteps + "proposal.apply:$id").distinct().sorted(),
            status = if (status == InitStatus.COMPLETE) InitStatus.IN_PROGRESS else status,
            updatedAt = nextInstant(updatedAt),
        )

    private fun validatePatternTarget(
        annotation: FunctionAnnotation,
        hypothesis: PatternHypothesis,
    ): ContractError? {
        if (hypothesis.scope == AnnotationScope.FILE) {
            if (annotation.scope != AnnotationScope.FILE || annotation.path.isNullOrBlank()) {
                return ContractError.ValidationError(
                    "FILE-scoped pattern targets require FILE scope and a path",
                    "targets",
                )
            }
        }
        val expectedPath = hypothesis.selector.path
        if (expectedPath != null && annotation.path != null && annotation.path != expectedPath) {
            return ContractError.ValidationError(
                "Pattern target path '${annotation.path}' does not match '$expectedPath'",
                "targets",
            )
        }
        return null
    }

    private fun resolvePattern(
        hypotheses: List<PatternHypothesis>,
        id: String,
    ): PatternHypothesis? {
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

private val CANONICAL_EPOCH = Instant.parse("1970-01-01T00:00:00Z")
