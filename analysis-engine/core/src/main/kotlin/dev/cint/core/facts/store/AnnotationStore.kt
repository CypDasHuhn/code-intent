package dev.cint.core.facts.store

import dev.cint.api.Annotation
import dev.cint.api.Contribution
import dev.cint.api.Role

private data class AnnotationKey(
    val role: Role,
    val startByte: Int,
    val endByte: Int,
)

private val contributionOrder =
    compareBy<Contribution>(
        { it.rule },
        { it.role.ordinal },
        { it.startByte },
        { it.endByte },
        { it.span.startLine },
        { it.span.startCol },
        { it.span.endLine },
        { it.span.endCol },
        { it.evidence },
        { it.source },
        { it.confidence },
        { it.intents.sorted().joinToString("\u0000") },
    )

private fun normalize(contributions: List<Contribution>): List<Contribution> =
    contributions
        .sortedWith(contributionOrder)
        .distinct()
        .map { it.copy(intents = it.intents.sorted().toSet()) }

class AnnotationStore {
    private val contributionsByRule = LinkedHashMap<String, List<Contribution>>()
    private var cached: List<Annotation>? = null
    private var dirty = false
    private var revision = 0L

    fun replaceRule(
        rule: String,
        contributions: List<Contribution>,
    ): Boolean {
        val normalized = normalize(contributions)
        if (contributionsByRule[rule].orEmpty() == normalized) return false
        if (normalized.isEmpty()) {
            contributionsByRule.remove(rule)
        } else {
            contributionsByRule[rule] = normalized
        }
        cached = null
        dirty = true
        revision++
        return true
    }

    fun clearDirty() {
        dirty = false
    }

    fun isDirty(): Boolean = dirty

    fun revision(): Long = revision

    fun annotations(): List<Annotation> {
        val cached = cached
        if (cached != null) return cached
        val groups = LinkedHashMap<AnnotationKey, MutableList<Contribution>>()
        for (contributions in contributionsByRule.values) {
            for (contribution in contributions) {
                val key = AnnotationKey(contribution.role, contribution.startByte, contribution.endByte)
                groups.getOrPut(key) { mutableListOf() }.add(contribution)
            }
        }
        val result =
            groups
                .map { (key, contributors) ->
                    val provenance = normalize(contributors)
                    Annotation(
                        role = key.role,
                        intents = provenance.flatMap { it.intents }.sorted().toSet(),
                        evidence = provenance.maxByOrNull { it.evidence.length }?.evidence.orEmpty(),
                        span = provenance.first().span,
                        source = provenance.map { it.source }.distinct().singleOrNull() ?: "mixed",
                        confidence = provenance.maxOf { it.confidence },
                        startByte = key.startByte,
                        endByte = key.endByte,
                        provenance = provenance,
                    )
                }.sortedWith(
                    compareBy<Annotation>({ it.startByte }, { it.endByte }, { it.role.ordinal }),
                )
        this.cached = result
        return result
    }
}
