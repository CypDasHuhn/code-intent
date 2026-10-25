package dev.cint.contracts.data

import kotlinx.serialization.Serializable

@Serializable
enum class Role {
    SOURCE_INTENT,
    DERIVATIVE_USE,
    SCAFFOLD,
}
