package dev.cint.v2.mcp

import dev.cint.contracts.mcp.McpResourceDefinition
import dev.cint.contracts.mcp.McpToolDefinition
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object McpResourceUris {
    const val SNAPSHOT = "cint://knowledge/snapshot"
    const val FUNCTION_ANNOTATIONS = "cint://knowledge/function-annotations"
    const val SPAN_OVERRIDES = "cint://knowledge/span-overrides"
    const val PATTERN_HYPOTHESES = "cint://knowledge/pattern-hypotheses"
    const val REPO_STATUS = "cint://repo/status"
}

internal object McpCatalog {
    fun tools(): List<McpToolDefinition> =
        buildList {
            add(
                tool(
                    name = "init.begin",
                    description = "Start or resume a persistent repository init checkpoint.",
                    schema =
                        objectSchema(
                            properties =
                                mapOf(
                                    "workflowId" to stringProperty("Optional workflow identity. Defaults to the repo."),
                                ),
                        ),
                ),
            )
            add(
                tool(
                    name = "init.status",
                    description = "Report completed init steps, analyzed paths, and pending proposals.",
                    schema =
                        objectSchema(
                            properties =
                                mapOf(
                                    "workflowId" to stringProperty("Optional workflow identity. Defaults to the repo."),
                                ),
                        ),
                ),
            )
            add(
                tool(
                    name = "init.complete",
                    description = "Mark the persistent init checkpoint complete.",
                    schema =
                        objectSchema(
                            properties =
                                mapOf(
                                    "workflowId" to stringProperty("Optional workflow identity. Defaults to the repo."),
                                ),
                        ),
                ),
            )
            add(
                tool(
                    name = "analysis.run",
                    description = "Run deterministic V2 analysis over repository target paths.",
                    schema =
                        objectSchema(
                            required = listOf("targetPaths"),
                            properties =
                                mapOf(
                                    "targetPaths" to stringArrayProperty("Paths to analyze."),
                                    "language" to stringProperty("Optional language identifier."),
                                    "options" to stringMapProperty("Analysis options passed to runtime."),
                                    "forceFullAnalysis" to booleanProperty("Invalidate cached analysis first."),
                                ),
                        ),
                ),
            )
            add(
                tool(
                    name = "knowledge.snapshot",
                    description = "Read the complete materialized knowledge snapshot.",
                    schema = objectSchema(),
                ),
            )
            add(
                tool(
                    name = "knowledge.suggestDefaults",
                    description = "Read pack defaults for the repository without writing knowledge.",
                    schema =
                        objectSchema(
                            properties =
                                mapOf(
                                    "languages" to stringArrayProperty("Optional language identifiers to inspect."),
                                    "workflowId" to stringProperty("Optional workflow identity. Defaults to the repo."),
                                ),
                        ),
                ),
            )
            add(
                tool(
                    name = "knowledge.materializeDefaults",
                    description = "Materialize pack defaults only when no project knowledge exists.",
                    schema =
                        objectSchema(
                            properties =
                                mapOf(
                                    "languages" to stringArrayProperty("Optional languages used to suggest defaults."),
                                    "workflowId" to stringProperty("Optional workflow identity. Defaults to the repo."),
                                ),
                        ),
                ),
            )
            add(
                tool(
                    name = "function_annotations.list",
                    description = "List persisted function annotations with optional filters.",
                    schema =
                        filterSchema(
                            "provenance" to "Filter by provenance values.",
                            "status" to "Filter by status values.",
                            "intents" to "Filter by intents.",
                        ),
                ),
            )
            add(
                tool(
                    name = "function_annotations.set",
                    description = "Create or replace a function annotation through knowledge validation.",
                    schema =
                        objectSchema(
                            required = listOf("annotation"),
                            properties = mapOf("annotation" to functionAnnotationProperty()),
                        ),
                ),
            )
            add(
                tool(
                    name = "function_annotations.remove",
                    description = "Remove a function annotation by selector.",
                    schema =
                        objectSchema(
                            required = listOf("selector"),
                            properties =
                                mapOf(
                                    "selector" to functionSelectorProperty(),
                                    "path" to stringProperty("Optional file path for FILE-scoped annotations."),
                                ),
                        ),
                ),
            )
            add(
                tool(
                    name = "span_overrides.list",
                    description = "List span overrides with optional filters.",
                    schema =
                        objectSchema(
                            properties =
                                mapOf(
                                    "provenance" to stringArrayProperty("Filter by provenance values."),
                                    "status" to stringArrayProperty("Filter by status values."),
                                    "path" to stringProperty("Filter by file path."),
                                ),
                        ),
                ),
            )
            add(
                tool(
                    name = "span_overrides.set",
                    description = "Create or replace a span override through knowledge validation.",
                    schema =
                        objectSchema(
                            required = listOf("override"),
                            properties = mapOf("override" to spanOverrideProperty()),
                        ),
                ),
            )
            add(
                tool(
                    name = "span_overrides.remove",
                    description = "Remove a span override by selector.",
                    schema =
                        objectSchema(
                            required = listOf("selector"),
                            properties = mapOf("selector" to spanSelectorProperty()),
                        ),
                ),
            )
            add(
                tool(
                    name = "patterns.list",
                    description = "List pattern hypotheses.",
                    schema = objectSchema(),
                ),
            )
            add(
                tool(
                    name = "patterns.propose",
                    description = "Propose a pattern hypothesis through knowledge validation.",
                    schema =
                        objectSchema(
                            required = listOf("hypothesis"),
                            properties = mapOf("hypothesis" to patternHypothesisProperty()),
                        ),
                ),
            )
            add(
                tool(
                    name = "patterns.validate",
                    description = "Accept or reject a pattern hypothesis.",
                    schema =
                        objectSchema(
                            required = listOf("id", "accept"),
                            properties =
                                mapOf(
                                    "id" to stringProperty("Pattern identity key, pattern, or path."),
                                    "accept" to booleanProperty("True to accept, false to reject."),
                                ),
                        ),
                ),
            )
            add(
                tool(
                    name = "patterns.materialize",
                    description = "Convert an accepted pattern hypothesis into function annotations.",
                    schema =
                        objectSchema(
                            required = listOf("id", "targets"),
                            properties =
                                mapOf(
                                    "id" to stringProperty("Pattern identity key, pattern, or path."),
                                    "targets" to
                                        objectArrayProperty(
                                            "Function selectors to annotate, optionally overriding intents or metadata.",
                                        ),
                                    "workflowId" to stringProperty("Optional workflow identity. Defaults to the repo."),
                                ),
                        ),
                ),
            )
            add(
                tool(
                    name = "changes.propose",
                    description = "Stage a batch of knowledge changes and return a proposal id.",
                    schema =
                        objectSchema(
                            required = listOf("changes"),
                            properties =
                                mapOf(
                                    "description" to stringProperty("Human-readable proposal description."),
                                    "changes" to objectArrayProperty("KnowledgeChange objects."),
                                ),
                        ),
                ),
            )
            add(
                tool(
                    name = "changes.apply",
                    description = "Apply a staged knowledge proposal through knowledge validation.",
                    schema =
                        objectSchema(
                            required = listOf("proposalId"),
                            properties = mapOf("proposalId" to stringProperty("Proposal identifier.")),
                        ),
                ),
            )
            add(
                tool(
                    name = "repo.status",
                    description = "Report runtime and knowledge status.",
                    schema = objectSchema(),
                ),
            )
            add(
                tool(
                    name = "repo.index",
                    description = "Report index state. Indexing is performed during analysis.run.",
                    schema = objectSchema(),
                ),
            )
            add(
                tool(
                    name = "repo.languages",
                    description =
                        "List supported languages, resolved provider modes, and provider configuration status. " +
                            "Does not start an LSP server.",
                    schema = objectSchema(),
                ),
            )
            add(
                tool(
                    name = "repo.files",
                    description =
                        "List analyzable repository files with deterministic ordering. " +
                            "Skips generated directories such as .git, .cint, build, out, target, " +
                            "node_modules, and dist. Does not require git.",
                    schema =
                        objectSchema(
                            properties =
                                mapOf(
                                    "languages" to stringArrayProperty("Optional language identifiers to include."),
                                    "extensions" to stringArrayProperty("Optional file extensions to include."),
                                    "limit" to integerProperty("Maximum files to return. Defaults to 5000."),
                                ),
                        ),
                ),
            )
            add(
                tool(
                    name = "analysis.symbols",
                    description =
                        "Return a compact declaration, call, and import inventory for target paths " +
                            "without returning the full analysis result.",
                    schema =
                        objectSchema(
                            required = listOf("targetPaths"),
                            properties =
                                mapOf(
                                    "targetPaths" to stringArrayProperty("Paths to inspect."),
                                    "language" to stringProperty("Optional language identifier."),
                                    "options" to stringMapProperty("Analysis options passed to runtime."),
                                ),
                        ),
                ),
            )
            add(
                tool(
                    name = "knowledge.resolveSelector",
                    description =
                        "Dry-run a function or span selector against target paths or the repository root " +
                            "and report matches and unsupported reasons. Never mutates knowledge.",
                    schema =
                        objectSchema(
                            required = listOf("selector"),
                            properties =
                                mapOf(
                                    "selector" to selectorProperty(),
                                    "targetPaths" to stringArrayProperty("Paths to inspect. Defaults to the repository root."),
                                    "root" to stringProperty("Optional repository root override."),
                                    "language" to stringProperty("Optional language identifier."),
                                    "options" to stringMapProperty("Analysis options passed to runtime."),
                                ),
                        ),
                ),
            )
        }

    fun resources(): List<McpResourceDefinition> =
        buildList {
            add(resource(McpResourceUris.SNAPSHOT, "Knowledge snapshot", "Complete materialized knowledge document."))
            add(
                resource(
                    McpResourceUris.FUNCTION_ANNOTATIONS,
                    "Function annotations",
                    "Persisted function annotations.",
                ),
            )
            add(resource(McpResourceUris.SPAN_OVERRIDES, "Span overrides", "Persisted span overrides."))
            add(
                resource(
                    McpResourceUris.PATTERN_HYPOTHESES,
                    "Pattern hypotheses",
                    "Persisted pattern hypotheses.",
                ),
            )
            add(resource(McpResourceUris.REPO_STATUS, "Repository status", "Runtime status document."))
        }

    private fun tool(
        name: String,
        description: String,
        schema: JsonObject,
    ): McpToolDefinition =
        McpToolDefinition(
            name = name,
            description = description,
            inputSchema = schema,
        )

    private fun resource(
        uri: String,
        name: String,
        description: String,
    ): McpResourceDefinition =
        McpResourceDefinition(
            uri = uri,
            name = name,
            description = description,
        )

    private fun filterSchema(vararg fields: Pair<String, String>): JsonObject =
        objectSchema(
            properties =
                fields.associate { (name, description) ->
                    name to stringArrayProperty(description)
                },
        )

    private fun objectSchema(
        required: List<String> = emptyList(),
        properties: Map<String, JsonElement> = emptyMap(),
    ): JsonObject =
        buildJsonObject {
            put("type", "object")
            put("properties", JsonObject(properties))
            if (required.isNotEmpty()) {
                put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
            }
            put("additionalProperties", true)
        }

    private fun stringProperty(description: String): JsonObject =
        buildJsonObject {
            put("type", "string")
            put("description", description)
        }

    private fun booleanProperty(description: String): JsonObject =
        buildJsonObject {
            put("type", "boolean")
            put("description", description)
        }

    private fun stringArrayProperty(description: String): JsonObject =
        buildJsonObject {
            put("type", "array")
            put("description", description)
            put("items", buildJsonObject { put("type", "string") })
        }

    private fun objectArrayProperty(description: String): JsonObject =
        buildJsonObject {
            put("type", "array")
            put("description", description)
            put("items", buildJsonObject { put("type", "object") })
        }

    private fun stringMapProperty(description: String): JsonObject =
        buildJsonObject {
            put("type", "object")
            put("description", description)
            put("additionalProperties", buildJsonObject { put("type", "string") })
        }

    private fun integerProperty(description: String): JsonObject =
        buildJsonObject {
            put("type", "integer")
            put("description", description)
        }

    private fun selectorProperty(): JsonObject =
        buildJsonObject {
            put("type", "object")
            put("description", "Function selector or span selector.")
            put(
                "properties",
                JsonObject(
                    mapOf(
                        "qualifiedName" to stringProperty("Qualified function name."),
                        "importPath" to stringProperty("Import path."),
                        "receiverType" to stringProperty("Receiver type."),
                        "declaringType" to stringProperty("Declaring type."),
                        "signature" to stringProperty("Function signature."),
                        "structuralPattern" to stringProperty("Structural pattern."),
                        "path" to stringProperty("File path for span selectors."),
                        "span" to buildJsonObject { put("type", "object") },
                        "contentHash" to stringProperty("Content hash for span selectors."),
                    ),
                ),
            )
        }

    private fun persistenceProperties(): Map<String, JsonElement> =
        mapOf(
            "provenance" to stringProperty("Provenance value."),
            "confidence" to
                buildJsonObject {
                    put("description", "Confidence value or {value} object.")
                    put("type", "number")
                },
            "status" to stringProperty("Status value."),
        )

    private fun functionSelectorProperty(): JsonObject =
        buildJsonObject {
            put("type", "object")
            put("description", "Function selector.")
            put(
                "properties",
                JsonObject(
                    mapOf(
                        "qualifiedName" to stringProperty("Qualified function name."),
                        "importPath" to stringProperty("Import path."),
                        "receiverType" to stringProperty("Receiver type."),
                        "declaringType" to stringProperty("Declaring type."),
                        "signature" to stringProperty("Function signature."),
                        "structuralPattern" to stringProperty("Structural pattern."),
                    ),
                ),
            )
        }

    private fun spanSelectorProperty(): JsonObject =
        buildJsonObject {
            put("type", "object")
            put("description", "Span selector.")
            put(
                "properties",
                JsonObject(
                    mapOf(
                        "path" to stringProperty("File path."),
                        "span" to buildJsonObject { put("type", "object") },
                        "contentHash" to stringProperty("Content hash."),
                    ),
                ),
            )
            put(
                "required",
                buildJsonArray {
                    add(JsonPrimitive("path"))
                    add(JsonPrimitive("span"))
                    add(JsonPrimitive("contentHash"))
                },
            )
        }

    private fun functionAnnotationProperty(): JsonObject =
        buildJsonObject {
            put("type", "object")
            put("description", "FunctionAnnotation object.")
            put(
                "properties",
                JsonObject(
                    mapOf(
                        "selector" to functionSelectorProperty(),
                        "intents" to stringArrayProperty("Intent names."),
                        "scope" to stringProperty("Annotation scope."),
                        "path" to stringProperty("File path for FILE scope."),
                    ) + persistenceProperties(),
                ),
            )
            put(
                "required",
                buildJsonArray {
                    add(JsonPrimitive("selector"))
                    add(JsonPrimitive("intents"))
                },
            )
        }

    private fun spanOverrideProperty(): JsonObject =
        buildJsonObject {
            put("type", "object")
            put("description", "SpanOverride object.")
            put(
                "properties",
                JsonObject(
                    mapOf(
                        "selector" to spanSelectorProperty(),
                        "intents" to stringArrayProperty("Intent names."),
                        "rationale" to stringProperty("Why this span differs."),
                    ) + persistenceProperties(),
                ),
            )
            put(
                "required",
                buildJsonArray {
                    add(JsonPrimitive("selector"))
                    add(JsonPrimitive("intents"))
                },
            )
        }

    private fun patternHypothesisProperty(): JsonObject =
        buildJsonObject {
            put("type", "object")
            put("description", "PatternHypothesis object.")
            put(
                "properties",
                JsonObject(
                    mapOf(
                        "selector" to
                            buildJsonObject {
                                put("type", "object")
                                put("properties", JsonObject(mapOf("pattern" to stringProperty("Pattern text."))))
                            },
                        "scope" to stringProperty("Annotation scope."),
                        "evidence" to stringArrayProperty("Evidence lines."),
                        "examples" to stringArrayProperty("Example locations."),
                        "counterexamples" to stringArrayProperty("Counterexample locations."),
                        "proposedIntents" to stringArrayProperty("Proposed intents."),
                    ) + persistenceProperties(),
                ),
            )
            put("required", buildJsonArray { add(JsonPrimitive("selector")) })
        }
}
