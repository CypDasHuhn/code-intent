package dev.cint.treesitter.view.writefacts

import dev.cint.api.GuardKind
import dev.cint.core.facts.model.SourceFile
import dev.cint.treesitter.view.support.view
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoopGuardLanguageTest {
    @Test
    fun kotlinForLoopIsAGuard() {
        val source = SourceFile("Sample.kt", "fun f() { for (item in list) { return } }")
        val view = view("kotlin", source)
        val guard = view.guards().single { it.kind == GuardKind.LOOP }

        assertEquals("for_statement", guard.node.kind)
        assertTrue(guard.exits.isNotEmpty())
    }

    @Test
    fun kotlinForLoopConditionIsTheIterable() {
        val source =
            SourceFile(
                "Sample.kt",
                """
                fun f(list: List<Int>, map: Map<String, Int>, n: Int) {
                    for (item in list) {
                        item.hashCode()
                    }
                    for ((key, value) in map) {
                        key.hashCode()
                    }
                    for (i in 0 until n) {
                        i.hashCode()
                    }
                }
                """.trimIndent(),
            )
        val view = view("kotlin", source)

        assertEquals(
            setOf("list", "map", "0 until n"),
            view
                .guards()
                .filter { it.kind == GuardKind.LOOP }
                .mapNotNull { it.condition?.text }
                .toSet(),
        )
        assertEquals(
            setOf("list", "map", "0 until n"),
            view.guardConditions().map { it.text }.toSet(),
        )
    }
}
