package dev.cint.v2.syntax.treesitter

import dev.cint.contracts.facts.ChangedRanges
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractError
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SyntaxEngineTest {
    private val sample =
        """
        package demo

        import kotlin.math.abs

        // cint:bookkeeping
        class Counter {
            @Deprecated("old")
            var total = 0

            fun add(value: Int): Int {
                if (value < 0) {
                    total++
                    return 0
                }
                total += value
                println("adding")
                return abs(total)
            }
        }

        fun main() {
            val counter = Counter()
            counter.add(2)
        }
        """.trimIndent()

    @Test
    fun parsesKotlinWithTreeSitter() {
        val engine = createSyntaxEngine("kotlin")
        val tree = engine.parse(sample.encodeToByteArray(), previous = null).getOrThrow()

        assertEquals("kotlin", tree.language)
        assertEquals("source_file", tree.getNode(tree.rootId)?.kind)
        assertTrue(tree.nodes.size > 20)
        assertTrue(tree.nodes.values.none { it.kind == "ERROR" })
        assertTrue(tree.version.startsWith("tree-sitter-kotlin-"))
        assertEquals(engine.language, tree.language)
    }

    @Test
    fun spansUseRealEndPointsAndMatchTheirByteLength() {
        val tree = createSyntaxEngine("kotlin").parse(sample.encodeToByteArray(), null).getOrThrow()
        val function =
            tree.nodes.values.first {
                it.kind == "function_declaration" && it.text.contains("\n")
            }
        assertTrue(function.endLine > function.startLine, "endLine must advance past startLine: $function")
        assertTrue(function.endCol >= 0)
        assertEquals(function.text.encodeToByteArray().size, function.endByte - function.startByte)
        val root = tree.getNode(tree.rootId)!!
        assertTrue(root.endLine > root.startLine)
        assertTrue(root.endByte == sample.encodeToByteArray().size)
    }

    @Test
    fun recordsGrammarAndRuntimeIdentity() {
        val tree = createSyntaxEngine("kotlin").parse(sample.encodeToByteArray(), null).getOrThrow()
        assertTrue(tree.version.startsWith("tree-sitter-kotlin-0.3.8.1/runtime-"), tree.version)
        assertTrue(tree.version.endsWith(treeSitterRuntimeVersion), tree.version)
    }

    @Test
    fun rejectsUnknownProfilesAndMismatchedEngines() {
        val kotlinEngine = createSyntaxEngine("kotlin")
        val tree = kotlinEngine.parse(sample.encodeToByteArray(), null).getOrThrow()
        val unknown = tree.copy(language = "unknown")
        assertTrue(kotlinEngine.facts(unknown, emptyList()).errorOrNull is ContractError.InvalidInput)

        val cEngine = createSyntaxEngine("c")
        assertTrue(cEngine.facts(tree, emptyList()).errorOrNull is ContractError.InvalidInput)
        assertTrue(cEngine.nodeHash(tree, tree.rootId).errorOrNull is ContractError.InvalidInput)
        assertEquals(ChangedRanges.WHOLE_FILE, cEngine.diff(tree, tree))
    }

    @Test
    fun reparsesIncrementallyFromPreviousTree() {
        val engine = createSyntaxEngine("kotlin")
        val first = engine.parse(sample.encodeToByteArray(), previous = null).getOrThrow()
        val updated = sample.replace("\"adding\"", "\"updated\"")
        val second = engine.parse(updated.encodeToByteArray(), previous = first).getOrThrow()

        assertNotEquals(first.contentHash, second.contentHash)
        assertTrue(second.nodes.values.none { it.kind == "ERROR" })
        assertTrue(second.nodes.values.any { it.text == "\"updated\"" })
    }

    @Test
    fun diffReportsChangedRanges() {
        val engine = createSyntaxEngine("kotlin")
        val first = engine.parse(sample.encodeToByteArray(), previous = null).getOrThrow()
        val updated = sample.replace("total += value", "total -= value")
        val second = engine.parse(updated.encodeToByteArray(), previous = first).getOrThrow()

        val changed = engine.diff(first, second)
        assertFalse(changed.isWholeFile)
        assertTrue(changed.ranges.isNotEmpty())
        assertTrue(
            changed.ranges.any { range ->
                range.startLine <= lineOf(updated, "total -= value") &&
                    range.endLine >= lineOf(updated, "total -= value")
            },
            "changed range should cover the edited line: $changed",
        )
        assertEquals(ChangedRanges.EMPTY, engine.diff(first, first))
    }

    @Test
    fun nodeHashIsMerkleAndRejectsUnknownIds() {
        val engine = createSyntaxEngine("kotlin")
        val first = engine.parse(sample.encodeToByteArray(), previous = null).getOrThrow()
        val updated = sample.replace("total += value", "total *= value")
        val second = engine.parse(updated.encodeToByteArray(), previous = first).getOrThrow()

        val firstFunction = first.nodes.values.first { it.kind == "function_declaration" }
        val secondFunction = second.nodes.values.first { it.kind == "function_declaration" }
        val firstRootHash = engine.nodeHash(first, first.rootId).getOrThrow()
        val secondRootHash = engine.nodeHash(second, second.rootId).getOrThrow()
        val firstFunctionHash = engine.nodeHash(first, firstFunction.id).getOrThrow()
        val secondFunctionHash = engine.nodeHash(second, secondFunction.id).getOrThrow()

        assertNotEquals(firstRootHash, secondRootHash)
        assertNotEquals(firstFunctionHash, secondFunctionHash)
        assertEquals(firstRootHash, engine.nodeHash(first, first.rootId).getOrThrow())

        val missing = engine.nodeHash(first, "missing-node")
        assertTrue(missing.isFailure)
        assertTrue(missing.errorOrNull is ContractError.NotFound)
    }

    @Test
    fun vueScriptRegionKeepsSourceOffsetsAndDetectsTypescript() {
        val source =
            """
            <template><div>{{ message }}</div></template>
            <script lang="ts">
            export function run(id: string) {
              if (!id) {
                return;
              }
              return id;
            }
            </SCRIPT>
            """.trimIndent()
        val tree = createSyntaxEngine("vue").parse(source.encodeToByteArray(), null).getOrThrow()

        assertTrue(tree.version.startsWith("vue-script/tree-sitter-typescript"), tree.version)
        assertTrue(tree.nodes.values.none { it.kind == "ERROR" })
        val function = tree.nodes.values.first { it.kind == "function_declaration" }
        assertEquals(source.indexOf("function run"), function.startByte)
        assertEquals(2, function.startLine)
        assertEquals(7, function.startCol)
        assertTrue(function.text.contains("return id;"))
    }

    @Test
    fun extractsBatchFactsBehindTheContract() {
        val engine = createSyntaxEngine("kotlin")
        val tree = engine.parse(sample.encodeToByteArray(), previous = null).getOrThrow()
        val facts = engine.facts(tree, emptyList()).getOrThrow()

        assertEquals(tree, facts.syntaxTree)
        assertContains(facts.functions.mapNotNull { it.name }, "add")
        assertContains(facts.functions.mapNotNull { it.name }, "main")
        assertTrue(facts.calls.any { it.callee == "println" })
        assertTrue(facts.comments.any { it.magicComment?.contains("cint:") == true })
        assertTrue(facts.imports.any { it.path.contains("kotlin.math.abs") })
        assertTrue(facts.writes.isNotEmpty())
        assertTrue(facts.ifs.isNotEmpty())
        assertTrue(facts.guards.any { it.conditionId != null })
        assertTrue(facts.returns.isNotEmpty())
        assertTrue(facts.declarations.any { it.name == "total" })
        assertTrue(facts.properties.any { it.name == "total" })
        assertTrue(facts.scopes.any { it.kind.name == "FILE" })
        assertTrue(facts.references.any { it.name == "total" })
    }

    @Test
    fun chainedCallsExposeFinalCalleeNamesAndReceiverLinks() {
        val engine = createSyntaxEngine("kotlin")
        val text = "fun f(repository: Repository) = repository.findAll().map { it.name }"
        val tree = engine.parse(text.encodeToByteArray(), null).getOrThrow()
        val facts = engine.facts(tree, listOf("calls"), CancellationToken.NONE).getOrThrow()

        val findAll = facts.calls.single { it.callee == "findAll" }
        val map = facts.calls.single { it.callee == "map" }
        assertEquals(findAll.nodeId, map.receiverId)
        assertEquals("repository.findAll().map", map.path)
        assertEquals(1, map.arity)
    }

    @Test
    fun trailingLambdaCountsTowardCallArity() {
        val engine = createSyntaxEngine("kotlin")
        val text = "fun f() { foo(1) { it } }"
        val tree = engine.parse(text.encodeToByteArray(), null).getOrThrow()
        val facts = engine.facts(tree, listOf("calls"), CancellationToken.NONE).getOrThrow()

        val outer =
            facts.calls
                .filter { it.callee == "foo" }
                .maxByOrNull { call -> tree.getNode(call.nodeId)!!.let { it.endByte - it.startByte } }!!
        assertEquals(2, outer.arity)
    }

    @Test
    fun filtersFactsByQueryIdAndRejectsUnknownQueries() {
        val engine = createSyntaxEngine("kotlin")
        val tree = engine.parse(sample.encodeToByteArray(), previous = null).getOrThrow()
        val callsOnly = engine.facts(tree, listOf("calls")).getOrThrow()

        assertTrue(callsOnly.calls.isNotEmpty())
        assertTrue(callsOnly.functions.isEmpty())
        assertTrue(callsOnly.comments.isEmpty())

        val unknown = engine.facts(tree, listOf("not-a-query"))
        assertTrue(unknown.isFailure)
        assertTrue(unknown.errorOrNull is ContractError.InvalidInput)
    }

    @Test
    fun honoursCancellation() {
        val engine = createSyntaxEngine("kotlin")
        val parse = engine.parse(sample.encodeToByteArray(), null, CancellationToken.fromBoolean(true))
        assertTrue(parse.errorOrNull is ContractError.Cancelled)
        val tree = engine.parse(sample.encodeToByteArray(), null).getOrThrow()
        val facts = engine.facts(tree, emptyList(), CancellationToken.fromBoolean(true))
        assertTrue(facts.errorOrNull is ContractError.Cancelled)
    }

    @Test
    fun rejectsPreviousTreeFromAnotherLanguage() {
        val kotlinEngine = createSyntaxEngine("kotlin")
        val kotlinTree = kotlinEngine.parse(sample.encodeToByteArray(), null).getOrThrow()
        val cEngine = createSyntaxEngine("c")
        val result = cEngine.parse("int main(void) { return 0; }".encodeToByteArray(), kotlinTree)
        assertTrue(result.errorOrNull is ContractError.InvalidInput)
    }

    @Test
    fun missingGrammarFailsExplicitly() {
        val engine = createSyntaxEngine("definitely-not-a-language")
        assertEquals("definitely-not-a-language", engine.language)
        val result = engine.parse("fun main() {}".encodeToByteArray(), null)
        assertTrue(result.isFailure)
        val error = result.errorOrNull
        assertTrue(error is ContractError.DependencyUnavailable, "expected DependencyUnavailable, got $error")
        assertContains((error as ContractError.DependencyUnavailable).reason, "no tree-sitter grammar")
    }

    private fun lineOf(
        text: String,
        needle: String,
    ): Int = text.lineSequence().indexOfFirst { needle in it }
}
