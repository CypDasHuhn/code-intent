package dev.cint.contracts.data

import kotlinx.serialization.Serializable

@Serializable
data class Confidence(
    val value: Double,
) {
    init {
        require(value >= 0.0 && value <= 1.0) { "Confidence must be between 0.0 and 1.0, got $value" }
    }

    companion object {
        val ZERO = Confidence(0.0)
        val LOW = Confidence(0.3)
        val MEDIUM = Confidence(0.5)
        val HIGH = Confidence(0.7)
        val ESTABLISHED = Confidence(0.9)
        val MAX = Confidence(1.0)

        fun fromDouble(value: Double): Confidence = Confidence(value.coerceIn(0.0, 1.0))
    }

    fun min(other: Confidence): Confidence = Confidence(minOf(value, other.value))

    fun max(other: Confidence): Confidence = Confidence(maxOf(value, other.value))

    override fun toString(): String = "Confidence($value)"
}
