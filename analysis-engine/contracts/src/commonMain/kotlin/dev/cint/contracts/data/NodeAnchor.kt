package dev.cint.contracts.data

import kotlinx.serialization.Serializable

@Serializable
data class NodeAnchor(
    val id: String,
) {
    companion object {
        fun fromString(id: String): NodeAnchor = NodeAnchor(id)
    }
}
