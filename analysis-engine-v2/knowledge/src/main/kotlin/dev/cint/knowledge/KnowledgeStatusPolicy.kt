package dev.cint.knowledge

import dev.cint.contracts.data.Provenance
import dev.cint.contracts.data.Status

object KnowledgeStatusPolicy {
    fun eligible(status: Status): Boolean =
        status != Status.REJECTED && status != Status.SUPPRESSED

    fun derivable(status: Status, provenance: Provenance): Boolean =
        eligible(status) && !(provenance == Provenance.AI_FALLBACK && status == Status.SUGGESTED)

    fun rank(status: Status): Int =
        when (status) {
            Status.REJECTED -> 0
            Status.SUPPRESSED -> 1
            Status.SUGGESTED -> 2
            Status.ACCEPTED -> 3
            Status.ESTABLISHED -> 4
        }
}
