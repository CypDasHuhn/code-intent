package dev.cint.contracts.data

import kotlinx.serialization.Serializable

@Serializable
enum class Status {
    SUGGESTED,
    ACCEPTED,
    ESTABLISHED,
    REJECTED,
    SUPPRESSED,
}