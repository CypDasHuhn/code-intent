package dev.cint.knowledge

import dev.cint.contracts.knowledge.PackDefault

internal val packDefaultPrecedence: Comparator<PackDefault> =
    compareByDescending<PackDefault> { KnowledgeStatusPolicy.rank(it.status) }
        .thenByDescending { it.confidence.value }
        .thenBy { it.packId }
        .thenBy { it.packVersion }
        .thenBy { it.intents.sorted().joinToString("\u0000") }
