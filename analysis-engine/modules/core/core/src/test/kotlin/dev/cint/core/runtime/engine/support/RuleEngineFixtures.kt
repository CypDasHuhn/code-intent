package dev.cint.core.runtime.engine.support

import dev.cint.api.CINT_API_VERSION
import dev.cint.api.Emit
import dev.cint.api.Intent
import dev.cint.api.Rule
import dev.cint.api.RulePack
import dev.cint.api.Span
import dev.cint.api.SyntaxNode
import dev.cint.api.View

internal class TestRule(
    override val id: String,
    override val owns: Intent,
    override val reads: Set<Intent> = emptySet(),
    private val body: (View) -> List<Emit>,
) : Rule {
    var runs: Int = 0
        private set

    override fun run(view: View): List<Emit> {
        runs++
        return body(view)
    }
}

internal class TestPack(
    override val id: String,
    private val entries: List<Rule>,
) : RulePack {
    override val languages: Set<String> = setOf("kotlin")
    override val apiVersion: Int = CINT_API_VERSION

    override fun rules(): List<Rule> = entries
}

internal fun emitAt(
    intent: Intent,
    node: SyntaxNode,
): Emit =
    Emit(
        intents = setOf(intent),
        span = node.span(),
        source = "treesitter",
        confidence = 1.0,
        startByte = node.startByte,
        endByte = node.endByte,
    )

internal fun indexedEmit(
    intent: Intent,
    index: Int,
): Emit =
    Emit(
        intents = setOf(intent),
        span = Span(index, 0, index, 1),
        source = "treesitter",
        confidence = 1.0,
        startByte = index * 10,
        endByte = index * 10 + 1,
    )

internal fun zeroLengthEmit(
    intent: Intent,
    byte: Int,
    source: String = "treesitter",
): Emit =
    Emit(
        intents = setOf(intent),
        span = Span(0, byte, 0, byte),
        source = source,
        confidence = 1.0,
        startByte = byte,
        endByte = byte,
    )

internal fun emitWithIntents(
    intents: Set<Intent>,
    index: Int,
): Emit =
    Emit(
        intents = intents,
        span = Span(index, 0, index, 1),
        source = "treesitter",
        confidence = 1.0,
        startByte = index * 10,
        endByte = index * 10 + 1,
    )
