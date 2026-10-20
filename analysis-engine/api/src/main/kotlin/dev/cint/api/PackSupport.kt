package dev.cint.api

fun interface Matcher {
    fun match(view: View): List<Emit>

    infix fun union(other: Matcher): Matcher = Matcher { view -> match(view) + other.match(view) }
}

fun matcherUnion(vararg matchers: Matcher): Matcher = Matcher { view -> matchers.flatMap { it.match(view) } }

abstract class SingleIntentPack<C : Any>(
    final override val id: String,
    val intent: Intent,
    final override val languages: Set<String>,
    protected val config: C,
    private val schema: ConfigSchema<C>,
    protected val ruleId: String = "$id:$id",
) : ConfigurableRulePack {
    final override val apiVersion: Int = CINT_API_VERSION

    protected open val reads: Set<Intent> = emptySet()

    protected abstract val matchers: List<Matcher>

    protected abstract fun withConfig(config: C): SingleIntentPack<C>

    override fun configure(values: Map<String, Any?>): RulePack =
        if (values.isEmpty()) this else withConfig(schema.decode(config, values, id))

    override fun rules(): List<Rule> = listOf(SingleIntentRule(ruleId, intent, reads, matchers))
}

private class SingleIntentRule(
    override val id: String,
    override val owns: Intent,
    override val reads: Set<Intent>,
    private val matchers: List<Matcher>,
) : Rule {
    override fun run(view: View): List<Emit> = matcherUnion(*matchers.toTypedArray()).match(view)
}
