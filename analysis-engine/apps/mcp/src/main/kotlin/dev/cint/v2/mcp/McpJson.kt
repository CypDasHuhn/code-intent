package dev.cint.v2.mcp

import dev.cint.contracts.util.ContractError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

internal val mcpJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "type"
        allowSpecialFloatingPointValues = false
    }

internal inline fun <reified T> encodeMcp(value: T): JsonElement = mcpJson.encodeToJsonElement(value)

internal inline fun <reified T> decodeMcp(element: JsonElement): T = mcpJson.decodeFromJsonElement(element)

internal fun JsonElement?.asStringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.string(name: String): String? = this[name].asStringOrNull()

internal fun JsonObject.bool(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()

internal fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()

internal fun JsonObject.objectOrNull(name: String): JsonObject? = this[name] as? JsonObject

internal fun JsonObject.arrayOrNull(name: String): JsonArray? = this[name] as? JsonArray

internal fun JsonObject.stringList(name: String): List<String>? {
    val element = this[name] ?: return null
    return when (element) {
        is JsonArray -> element.mapNotNull { it.asStringOrNull() }
        is JsonPrimitive -> element.asStringOrNull()?.let(::listOf)
        else -> null
    }
}

internal fun contractErrorJson(error: ContractError): JsonObject =
    buildJsonObject {
        put("type", error::class.simpleName)
        put("code", error::class.simpleName)
        when (error) {
            is ContractError.SerializationError -> {
                put("message", error.message)
                error.cause?.let { put("cause", it) }
            }

            is ContractError.ValidationError -> {
                put("message", error.message)
                error.field?.let { put("field", it) }
            }

            is ContractError.NotFound -> {
                put("message", "not found: ${error.resource} '${error.identifier}'")
                put("resource", error.resource)
                put("identifier", error.identifier)
            }

            is ContractError.AlreadyExists -> {
                put("message", "already exists: ${error.resource} '${error.identifier}'")
                put("resource", error.resource)
                put("identifier", error.identifier)
            }

            is ContractError.InvalidInput -> {
                put("message", error.message)
                error.input?.let { put("input", it) }
            }

            is ContractError.PreconditionFailed -> {
                put("message", error.message)
            }

            is ContractError.InternalError -> {
                put("message", error.message)
                error.cause?.let { put("cause", it) }
            }

            is ContractError.DependencyUnavailable -> {
                put("message", "dependency unavailable: ${error.dependency}")
                put("dependency", error.dependency)
                put("reason", error.reason)
            }

            is ContractError.Timeout -> {
                put("message", "timed out: ${error.operation}")
                put("operation", error.operation)
                put("timeoutMs", error.timeoutMs)
            }

            is ContractError.Cancelled -> {
                put("message", "cancelled: ${error.operation}")
                put("operation", error.operation)
            }
        }
    }
