package dev.cint.api

data class RulePackConfigIssue(
    val packId: String,
    val message: String,
)

fun interface RulePackConfigResolver {
    fun resolve(
        config: Map<String, Map<String, Any?>>,
        discoveredPackIds: Set<String>,
        issues: MutableList<RulePackConfigIssue>,
    ): Map<String, Map<String, Any?>>

    companion object {
        val NONE: RulePackConfigResolver = RulePackConfigResolver { config, _, _ -> config }
    }
}
