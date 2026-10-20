package dev.cint.treesitter

import dev.cint.api.Contribution
import dev.cint.api.Role
import dev.cint.api.Span
import dev.cint.core.config.IntentConfig
import dev.cint.core.config.IntentDefinition
import dev.cint.core.engine.AnalysisEngine
import dev.cint.core.engine.RuleBlock
import dev.cint.core.engine.RuleContext
import dev.cint.core.model.SourceFile
import dev.cint.core.oracle.NullOracle
import dev.cint.core.rules.IntentBlocks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun contribution(
    rule: String,
    intent: String,
    startByte: Int,
    endByte: Int,
): Contribution =
    Contribution(
        rule = rule,
        role = Role.SOURCE_INTENT,
        intents = setOf(intent),
        evidence = rule,
        span = Span(0, startByte, 0, endByte),
        source = "treesitter",
        confidence = 1.0,
        startByte = startByte,
        endByte = endByte,
    )

private class VictimBlock : RuleBlock {
    override val id: String = "victim"

    override fun apply(ctx: RuleContext): List<Contribution> =
        listOf(contribution("victim", "VICTIM", 0, 5))
}

private class TwoPassBlock(
    private val applications: MutableList<Int>,
) : RuleBlock {
    override val id: String = "two-pass"
    override val readsAnnotations: Boolean = true
    private var pass = 0

    override fun apply(ctx: RuleContext): List<Contribution> {
        pass++
        applications += pass
        return listOf(contribution("two-pass", "TWO_PASS", 10, 20))
    }
}

private class HostileBlock(
    private val mutationMethods: MutableList<String>,
) : RuleBlock {
    override val id: String = "hostile"
    override val readsAnnotations: Boolean = true

    override fun apply(ctx: RuleContext): List<Contribution> {
        val methods = ctx.javaClass.methods.associateBy { it.name }
        for (name in listOf("replaceRule", "clearDirty", "isDirty", "emit", "emitAll", "remove")) {
            val method = methods[name]
            if (method != null) {
                mutationMethods += name
                runCatching { method.invoke(ctx, "victim#0", emptyList<Contribution>()) }
                runCatching { method.invoke(ctx) }
            }
        }
        return emptyList()
    }
}

class RuleBoundaryTest {
    @Test
    fun ruleContextIsReadOnlyAndFixpointStillRuns() {
        val source = SourceFile("Sample.kt", "fun f() {}")
        val tree = KotlinParser().parse(source)
        val applications = mutableListOf<Int>()
        val mutationMethods = mutableListOf<String>()
        val config = IntentConfig("kotlin", emptyMap(), emptyList())

        val annotations =
            AnalysisEngine(listOf(VictimBlock(), TwoPassBlock(applications), HostileBlock(mutationMethods)))
                .analyze(source, tree, KotlinSyntaxModel, NullOracle, config)

        val victim = annotations.first { it.provenance.any { contributor -> contributor.rule == "victim" } }
        assertEquals(setOf("VICTIM"), victim.intents)
        assertTrue(mutationMethods.isEmpty(), "rule context exposed $mutationMethods")
        assertTrue(applications.size >= 2, "fixpoint stopped after $applications")
    }

    @Test
    fun twoIntentsSharingABlockTypeKeepBothContributions() {
        val source = SourceFile("Sample.kt", """fun f() { println("x") }""")
        val tree = KotlinParser().parse(source)
        val config =
            IntentConfig(
                language = "kotlin",
                intents =
                    mapOf(
                        "FIRST" to
                            IntentDefinition(
                                name = "FIRST",
                                blocks = listOf("print"),
                                params = mapOf("printCalls" to listOf("println")),
                            ),
                        "SECOND" to
                            IntentDefinition(
                                name = "SECOND",
                                blocks = listOf("print"),
                                params = mapOf("printCalls" to listOf("println")),
                            ),
                    ),
                pureAnnotations = emptyList(),
            )

        val annotations =
            AnalysisEngine(IntentBlocks.build(config))
                .analyze(source, tree, KotlinSyntaxModel, NullOracle, config)
        val merged =
            annotations.single {
                it.role == Role.SOURCE_INTENT && it.intents.containsAll(setOf("FIRST", "SECOND"))
            }

        assertEquals(2, merged.provenance.size)
        assertEquals(setOf(setOf("FIRST"), setOf("SECOND")), merged.provenance.map { it.intents }.toSet())
        assertTrue(merged.provenance.all { it.rule == "print" })
    }
}
