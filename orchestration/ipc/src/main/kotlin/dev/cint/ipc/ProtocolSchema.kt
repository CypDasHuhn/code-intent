@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.cint.ipc

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

object ProtocolSchema {
    const val FILE_NAME: String = "protocol.schema.json"
    const val ID: String = "urn:code-intent:ipc:protocol"

    private val defNames: Set<String> =
        ProtocolFingerprint.wireSerializers.map { it.descriptor.serialName }.toSet()

    fun document(): JsonObject =
        buildJsonObject {
            put("\$schema", "https://json-schema.org/draft/2020-12/schema")
            put("\$id", ID)
            put("title", "CodeIntent renderer IPC protocol")
            put(
                "description",
                "Published SDK schema for the CodeIntent renderer wire protocol. " +
                    "Clients pin x-protocol-version and send x-schema-fingerprint during initialize.",
            )
            put("type", "object")
            put("x-protocol-version", ProtocolFingerprint.CURRENT_PROTOCOL_VERSION)
            put("x-schema-fingerprint", ProtocolFingerprint.CURRENT)
            put(
                "x-supported-versions",
                buildJsonArray { ProtocolCompatibility.supportedVersions.forEach { add(it) } },
            )
            putJsonArray("x-compatibility") {
                ProtocolCompatibility.matrix.forEach { entry ->
                    add(
                        buildJsonObject {
                            put("version", entry.version)
                            put("status", entry.status)
                            put("requiresFingerprint", entry.requiresFingerprint)
                            put("initializeShape", entry.initializeShape)
                            put("compatibility", entry.compatibility)
                        },
                    )
                }
            }
            putJsonArray("x-requests") { IpcMethods.requests.sorted().forEach { add(it) } }
            putJsonObject("x-notifications") {
                putJsonArray("clientToServer") { IpcMethods.notifications.sorted().forEach { add(it) } }
                putJsonArray("serverToClient") { IpcNotifications.serverToClient.sorted().forEach { add(it) } }
            }
            putJsonArray("x-types") {
                defNames.sorted().forEach { name ->
                    add(
                        buildJsonObject {
                            put("\$ref", "#/\$defs/$name")
                        },
                    )
                }
            }
            putJsonObject("\$defs") {
                ProtocolFingerprint.wireSerializers
                    .sortedBy { it.descriptor.serialName }
                    .forEach { serializer -> put(serializer.descriptor.serialName, definition(serializer)) }
            }
        }

    fun render(): String = prettyJson.encodeToString(JsonObject.serializer(), document()) + "\n"

    fun write(path: File) {
        path.parentFile?.mkdirs()
        path.writeText(render())
    }

    private fun definition(serializer: KSerializer<*>): JsonObject = structural(serializer.descriptor)

    private fun structural(descriptor: SerialDescriptor): JsonObject =
        when {
            descriptor.serialName == "kotlinx.serialization.json.JsonElement" -> {
                buildJsonObject {}
            }

            descriptor.serialName == "kotlinx.serialization.json.JsonObject" -> {
                buildJsonObject {
                    put("type", "object")
                    put("additionalProperties", true)
                }
            }

            descriptor.serialName == "kotlinx.serialization.json.JsonArray" -> {
                buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject {})
                }
            }

            descriptor.serialName == "kotlinx.serialization.json.JsonPrimitive" -> {
                buildJsonObject {}
            }

            descriptor.serialName == "kotlin.Any" -> {
                buildJsonObject {}
            }

            descriptor.kind == SerialKind.ENUM -> {
                buildJsonObject {
                    put("type", "string")
                    put(
                        "enum",
                        buildJsonArray {
                            val names = descriptor.elementNames.toList()
                            for (index in names.indices) {
                                add(descriptor.getElementDescriptor(index).serialName)
                            }
                        },
                    )
                }
            }

            descriptor.kind == StructureKind.CLASS || descriptor.kind == StructureKind.OBJECT -> {
                buildJsonObject {
                    put("type", "object")
                    val names = descriptor.elementNames.toList()
                    putJsonObject("properties") {
                        names.forEachIndexed { index, name ->
                            put(
                                name,
                                propertySchema(
                                    descriptor.getElementDescriptor(index),
                                    descriptor.isElementOptional(index),
                                ),
                            )
                        }
                    }
                    val required = names.filterIndexed { index, _ -> !descriptor.isElementOptional(index) }
                    if (required.isNotEmpty()) {
                        put(
                            "required",
                            buildJsonArray { required.forEach { add(it) } },
                        )
                    }
                    put("additionalProperties", false)
                }
            }

            descriptor.kind == StructureKind.LIST -> {
                buildJsonObject {
                    put("type", "array")
                    put("items", propertySchema(descriptor.getElementDescriptor(0), optional = false))
                }
            }

            descriptor.kind == StructureKind.MAP -> {
                buildJsonObject {
                    put("type", "object")
                    put(
                        "additionalProperties",
                        propertySchema(descriptor.getElementDescriptor(1), optional = false),
                    )
                }
            }

            else -> {
                primitive(descriptor)
            }
        }

    private fun propertySchema(
        descriptor: SerialDescriptor,
        optional: Boolean,
    ): JsonObject {
        val base =
            if (descriptor.serialName in defNames) {
                buildJsonObject { put("\$ref", "#/\$defs/${descriptor.serialName}") }
            } else {
                structural(descriptor)
            }
        val withNullability =
            if (descriptor.isNullable) {
                if (base.containsKey("\$ref")) {
                    buildJsonObject {
                        put(
                            "anyOf",
                            buildJsonArray {
                                add(base)
                                add(buildJsonObject { put("type", "null") })
                            },
                        )
                    }
                } else {
                    val type = base["type"]
                    buildJsonObject {
                        base.forEach { (key, value) -> if (key != "type") put(key, value) }
                        if (type != null) {
                            put(
                                "type",
                                buildJsonArray {
                                    add(type)
                                    add("null")
                                },
                            )
                        }
                    }
                }
            } else {
                base
            }
        if (!optional) return withNullability
        return buildJsonObject {
            withNullability.forEach { (key, value) -> put(key, value) }
            put("x-optional", true)
        }
    }

    private fun primitive(descriptor: SerialDescriptor): JsonObject =
        when (descriptor.kind) {
            PrimitiveKind.BOOLEAN -> {
                buildJsonObject { put("type", "boolean") }
            }

            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> {
                buildJsonObject { put("type", "integer") }
            }

            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> {
                buildJsonObject { put("type", "number") }
            }

            PrimitiveKind.CHAR, PrimitiveKind.STRING -> {
                buildJsonObject { put("type", "string") }
            }

            else -> {
                buildJsonObject {}
            }
        }

    private val prettyJson: Json =
        Json {
            prettyPrint = true
            prettyPrintIndent = "  "
            encodeDefaults = true
        }
}

fun main(args: Array<String>) {
    val target = args.firstOrNull() ?: ProtocolSchema.FILE_NAME
    ProtocolSchema.write(File(target))
    println("Wrote $target (protocol ${ProtocolFingerprint.CURRENT_PROTOCOL_VERSION}, fingerprint ${ProtocolFingerprint.CURRENT})")
}
