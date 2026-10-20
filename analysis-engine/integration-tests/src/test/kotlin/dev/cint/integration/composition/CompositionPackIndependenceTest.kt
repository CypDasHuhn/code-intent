package dev.cint.integration.composition

import dev.cint.api.RulePack
import dev.cint.core.facts.model.SourceFile
import dev.cint.treesitter.KotlinParser
import dev.cint.treesitter.KotlinSyntaxModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompositionPackIndependenceTest {
    @Test
    fun compositionIgnoresProducerRuleAndPackIdentity() {
        val text = fixture("Scaffolds.kt")
        val first =
            analyzePipeline(
                text,
                listOf(ReplayPack("first", replaySource(text, "first"))),
                "Scaffolds.kt",
            )
        val second =
            analyzePipeline(
                text,
                listOf(ReplayPack("second", replaySource(text, "another"))),
                "Scaffolds.kt",
            )

        assertEquals(compositionSnapshots(first), compositionSnapshots(second))
    }

    @Test
    fun addingAPackOnlyAddsItsSourceIntentsToComposition() {
        val text =
            """
            private val log = LoggerFactory.getLogger(Foo::class.java)
            fun a() {
                log.warn("a")
            }
            fun b() {
                throw IllegalStateException("b")
            }
            """.trimIndent()
        val source = SourceFile("Sample.kt", text)
        val tree = KotlinParser().parse(source)
        val call =
            KotlinSyntaxModel.calls(tree).first { it.callee == "warn" }.site
        val thrown = tree.root.descendants().first { KotlinSyntaxModel.isThrow(it) }
        val logging =
            ReplayRule(
                id = "pack-a:logging",
                owns = "LOGGING",
                emissions = listOf(emitFor("LOGGING", call)),
            )
        val error =
            ReplayRule(
                id = "pack-b:error",
                owns = "ERROR_HANDLING",
                emissions = listOf(emitFor("ERROR_HANDLING", thrown)),
            )

        fun run(packs: List<RulePack>): List<Snapshot> = compositionSnapshots(analyzePipeline(text, packs))

        val loggingOnly = run(listOf(ReplayPack("a", listOf(logging))))
        val errorOnly = run(listOf(ReplayPack("b", listOf(error))))
        val both =
            run(
                listOf(
                    ReplayPack("b", listOf(error)),
                    ReplayPack("a", listOf(logging)),
                ),
            )
        val expected =
            (loggingOnly + errorOnly)
                .distinct()
                .sortedWith(
                    compareBy({ it.startByte }, { it.endByte }, { it.role.ordinal }, { it.intents.joinToString() }),
                )
        val withoutB = run(listOf(ReplayPack("a", listOf(logging))))
        val noOp = ReplayRule("pack-c:noop", "NOOP", emissions = emptyList())
        val withNoOp = run(listOf(ReplayPack("a", listOf(logging)), ReplayPack("c", listOf(noOp))))

        assertEquals(expected, both)
        assertEquals(loggingOnly, withoutB)
        assertEquals(loggingOnly, withNoOp)
        assertTrue(errorOnly.any { it.intents == setOf("ERROR_HANDLING") })
    }

    @Test
    fun packOrderAndSplitDoNotChangeComposition() {
        val text = fixture("Catches.kt")
        val rules = replaySource(text)
        val onePack = analyzePipeline(text, listOf(ReplayPack("all", rules)), "Catches.kt")
        val split = rules.chunked(2)
        val reversed =
            analyzePipeline(
                text,
                split.mapIndexed { index, chunk -> ReplayPack("p$index", chunk) }.reversed(),
                "Catches.kt",
            )

        assertEquals(compositionSnapshots(onePack), compositionSnapshots(reversed))
    }
}
