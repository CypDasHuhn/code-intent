package dev.cint.v2.mcp

import dev.cint.contracts.data.Confidence
import dev.cint.contracts.data.Intent
import dev.cint.contracts.data.Provenance
import dev.cint.contracts.data.Span
import dev.cint.contracts.data.Status
import dev.cint.contracts.knowledge.AnnotationScope
import dev.cint.contracts.knowledge.FunctionAnnotation
import dev.cint.contracts.knowledge.FunctionSelector
import dev.cint.contracts.knowledge.PatternHypothesis
import dev.cint.contracts.knowledge.PatternSelector
import dev.cint.contracts.knowledge.SpanOverride
import dev.cint.contracts.knowledge.SpanSelector
import dev.cint.contracts.util.ContractError
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object KnowledgeInputs {
    fun unwrap(
        args: JsonObject,
        field: String,
    ): JsonObject {
        val nested = args[field] ?: return args
        return nested as? JsonObject
            ?: throw McpInputException(ContractError.InvalidInput("$field must be an object", field))
    }

    fun functionAnnotation(
        element: JsonObject,
        now: Instant,
    ): FunctionAnnotation {
        val selector = parseFunctionSelector(element["selector"] ?: element)
        return construct {
            FunctionAnnotation(
                selector = selector,
                intents = parseIntents(element["intents"], "intents", emptySet()),
                scope = parseEnum(element["scope"], AnnotationScope.GLOBAL, "scope"),
                provenance = parseEnum(element["provenance"], Provenance.MCP_AGENT, "provenance"),
                confidence = parseConfidence(element["confidence"], Confidence.HIGH),
                status = parseEnum(element["status"], Status.SUGGESTED, "status"),
                createdAt = now,
                updatedAt = now,
                path = element.string("path"),
            )
        }
    }

    fun functionSelector(element: JsonElement): FunctionSelector =
        when (element) {
            is JsonPrimitive -> {
                val name = element.asStringOrNull()?.takeIf { it.isNotBlank() }
                    ?: throw McpInputException(
                        ContractError.InvalidInput("selector must not be blank", "selector"),
                    )
                construct { FunctionSelector(qualifiedName = name) }
            }
            is JsonObject -> {
                val nested = element["selector"]
                if (nested != null && nested !is JsonObject) {
                    throw McpInputException(
                        ContractError.InvalidInput("selector must be an object", "selector"),
                    )
                }
                val source = nested ?: element
                construct {
                    FunctionSelector(
                        qualifiedName = source.string("qualifiedName") ?: source.string("name") ?: "",
                        importPath = source.string("importPath"),
                        receiverType = source.string("receiverType"),
                        declaringType = source.string("declaringType"),
                        signature = source.string("signature"),
                        structuralPattern = source.string("structuralPattern"),
                    )
                }
            }
            else -> throw McpInputException(
                ContractError.InvalidInput("selector must be an object or string", "selector"),
            )
        }

    fun spanOverride(
        element: JsonObject,
        now: Instant,
    ): SpanOverride {
        val selector = spanSelector(element["selector"] ?: element)
        return construct {
            SpanOverride(
                selector = selector,
                intents = parseIntents(element["intents"], "intents", emptySet()),
                provenance = parseEnum(element["provenance"], Provenance.MCP_AGENT, "provenance"),
                confidence = parseConfidence(element["confidence"], Confidence.HIGH),
                status = parseEnum(element["status"], Status.SUGGESTED, "status"),
                rationale = element.string("rationale").orEmpty(),
                createdAt = now,
                updatedAt = now,
            )
        }
    }

    fun spanSelector(element: JsonElement): SpanSelector =
        when (element) {
            is JsonObject -> {
                val nested = element["selector"]
                if (nested != null && nested !is JsonObject) {
                    throw McpInputException(
                        ContractError.InvalidInput("selector must be an object", "selector"),
                    )
                }
                val source = nested ?: element
                val path = source.string("path")?.takeIf { it.isNotBlank() }
                    ?: throw McpInputException(
                        ContractError.InvalidInput("selector.path is required", "path"),
                    )
                val contentHash = source.string("contentHash")?.takeIf { it.isNotBlank() }
                    ?: throw McpInputException(
                        ContractError.InvalidInput("selector.contentHash is required", "contentHash"),
                    )
                val span =
                    source["span"]?.let { parseSpan(it) }
                        ?: parseSpan(source)
                construct {
                    SpanSelector(path = path, span = span, contentHash = contentHash)
                }
            }
            else -> throw McpInputException(
                ContractError.InvalidInput("selector must be an object", "selector"),
            )
        }

    fun patternHypothesis(
        element: JsonObject,
        now: Instant,
    ): PatternHypothesis {
        val selector =
            when (val value = element["selector"] ?: element["pattern"]) {
                null -> throw McpInputException(
                    ContractError.InvalidInput("selector is required", "selector"),
                )
                is JsonPrimitive -> {
                    val pattern = value.asStringOrNull()?.takeIf { it.isNotBlank() }
                        ?: throw McpInputException(
                            ContractError.InvalidInput("selector.pattern is required", "pattern"),
                        )
                    construct { PatternSelector(pattern = pattern) }
                }
                is JsonObject -> construct {
                    PatternSelector(
                        pattern = value.string("pattern")?.takeIf { it.isNotBlank() }
                            ?: throw McpInputException(
                                ContractError.InvalidInput("selector.pattern is required", "pattern"),
                            ),
                        path = value.string("path"),
                    )
                }
                else -> throw McpInputException(
                    ContractError.InvalidInput("selector must be an object or string", "selector"),
                )
            }
        return construct {
            PatternHypothesis(
                selector = selector,
                scope = parseEnum(element["scope"], AnnotationScope.GLOBAL, "scope"),
                evidence = stringList(element["evidence"], "evidence"),
                examples = stringList(element["examples"], "examples"),
                counterexamples = stringList(element["counterexamples"], "counterexamples"),
                proposedIntents = parseIntents(element["proposedIntents"], "proposedIntents", emptySet()),
                provenance = parseEnum(element["provenance"], Provenance.MCP_AGENT, "provenance"),
                confidence = parseConfidence(element["confidence"], Confidence.LOW),
                status = parseEnum(element["status"], Status.SUGGESTED, "status"),
                createdAt = now,
                updatedAt = now,
            )
        }
    }

    private fun parseFunctionSelector(element: JsonElement): FunctionSelector =
        functionSelector(element)

    private fun parseSpan(element: JsonElement): Span {
        if (element !is JsonObject) {
            throw McpInputException(ContractError.InvalidInput("span must be an object", "span"))
        }
        val startLine = sourceLong(element, "startLine")
        val startCol = sourceLong(element, "startCol")
        val endLine = sourceLong(element, "endLine")
        val endCol = sourceLong(element, "endCol")
        return construct {
            Span(
                startLine = startLine,
                startCol = startCol,
                endLine = endLine,
                endCol = endCol,
            )
        }
    }

    private fun sourceLong(
        element: JsonObject,
        field: String,
    ): Int {
        val value = element.long(field)
            ?: throw McpInputException(
                ContractError.InvalidInput("$field is required", field),
            )
        if (value < 0 || value > Int.MAX_VALUE) {
            throw McpInputException(
                ContractError.InvalidInput("$field is out of range", field),
            )
        }
        return value.toInt()
    }

    private fun parseIntents(
        element: JsonElement?,
        field: String,
        default: Set<Intent>,
    ): Set<Intent> {
        if (element == null) return default
        return stringList(element, field).toSet()
    }

    private fun stringList(
        element: JsonElement?,
        field: String,
    ): List<String> =
        when (element) {
            null -> emptyList()
            is JsonArray -> element.map {
                it.asStringOrNull()
                    ?: throw McpInputException(
                        ContractError.InvalidInput("$field must contain only strings", field),
                    )
            }
            is JsonPrimitive -> listOf(
                element.asStringOrNull()
                    ?: throw McpInputException(
                        ContractError.InvalidInput("$field must be a string or array of strings", field),
                    ),
            )
            else -> throw McpInputException(
                ContractError.InvalidInput("$field must be a string or array of strings", field),
            )
        }

    private inline fun <reified T : Enum<T>> parseEnum(
        element: JsonElement?,
        default: T,
        field: String,
    ): T {
        if (element == null) return default
        val raw = element.asStringOrNull()
            ?: throw McpInputException(ContractError.InvalidInput("$field must be a string", field))
        return enumValues<T>().firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: throw McpInputException(
                ContractError.ValidationError("Unknown $field value '$raw'", field),
            )
    }

    private fun parseConfidence(
        element: JsonElement?,
        default: Confidence,
    ): Confidence {
        if (element == null) return default
        val value =
            when (element) {
                is JsonPrimitive -> element.content.toDoubleOrNull()
                is JsonObject -> element["value"]?.let { (it as? JsonPrimitive)?.content?.toDoubleOrNull() }
                else -> null
            } ?: throw McpInputException(
                ContractError.InvalidInput("confidence must be a number or {value}", "confidence"),
            )
        return construct { Confidence(value) }
    }

    private fun <T> construct(block: () -> T): T =
        try {
            block()
        } catch (error: McpInputException) {
            throw error
        } catch (error: IllegalArgumentException) {
            throw McpInputException(
                ContractError.ValidationError(error.message ?: "Invalid knowledge input", "input"),
            )
        }
}
