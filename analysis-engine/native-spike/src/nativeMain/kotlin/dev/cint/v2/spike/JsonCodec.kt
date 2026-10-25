package dev.cint.v2.spike

import kotlinx.serialization.KSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

actual fun createJsonCodec(): JsonCodec = NativeJsonCodec()

private class NativeJsonCodec : JsonCodec {
    private val json = Json { ignoreUnknownKeys = true }

    override fun <T> encode(
        value: T,
        serializer: KSerializer<T>,
    ): String = json.encodeToString(serializer, value)

    override fun <T> decode(
        jsonStr: String,
        type: KSerializer<T>,
    ): T = json.decodeFromString(type, jsonStr)

    override fun roundTrip(jsonString: String): JsonRoundTripResult =
        try {
            val parsed = json.parseToJsonElement(jsonString)
            val encoded = json.encodeToString(parsed)
            val decodedElement = json.parseToJsonElement(encoded)
            val decoded = json.decodeFromJsonElement(JsonElement.serializer(), decodedElement).toString()
            JsonRoundTripResult(
                original = jsonString,
                encoded = encoded,
                decoded = decoded,
                success = true,
                error = null,
            )
        } catch (e: Exception) {
            JsonRoundTripResult(
                original = jsonString,
                encoded = "",
                decoded = "",
                success = false,
                error = e.message,
            )
        }
}
