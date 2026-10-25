package dev.cint.v2.spike

import kotlinx.serialization.Serializable

@kotlinx.serialization.Serializable
data class JsonRoundTripResult(
    val original: String,
    val encoded: String,
    val decoded: String,
    val success: Boolean,
    val error: String?
)

interface JsonCodec {
    fun <T> encode(value: T, serializer: kotlinx.serialization.KSerializer<T>): String
    fun <T> decode(json: String, type: kotlinx.serialization.KSerializer<T>): T
    fun roundTrip(jsonString: String): JsonRoundTripResult
}

expect fun createJsonCodec(): JsonCodec