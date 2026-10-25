package dev.cint.contracts.knowledge

import kotlinx.serialization.Serializable

@Serializable
data class PackOrigin(
    val packId: String,
    val packVersion: String,
) {
    init {
        require(packId.isNotBlank()) { "PackOrigin.packId must not be blank" }
        require(packVersion.isNotBlank()) { "PackOrigin.packVersion must not be blank" }
    }
}
