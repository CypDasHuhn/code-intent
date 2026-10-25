package dev.cint.knowledge

import dev.cint.contracts.data.Status
import dev.cint.contracts.knowledge.PackDefault

internal val packDefaultPrecedence: Comparator<PackDefault> =
    compareByDescending<PackDefault> { it.confidence.value }
        .thenByDescending { statusRank[it.status] ?: 0 }
        .thenBy { it.packId }
        .thenBy { it.packVersion }
        .thenBy { it.intents.sorted().joinToString("\u0000") }

private val statusRank = mapOf(
    Status.REJECTED to 0,
    Status.SUPPRESSED to 1,
    Status.SUGGESTED to 2,
    Status.ACCEPTED to 3,
    Status.ESTABLISHED to 4,
)
