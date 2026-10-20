package dev.cint.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class TestNode(
    override val kind: String,
    override val startByte: Int,
    override val endByte: Int,
    override val text: String,
    override val parent: SyntaxNode? = null,
    override val children: List<SyntaxNode> = emptyList(),
) : SyntaxNode {
    override val startLine: Int get() = 0
    override val startCol: Int get() = startByte
    override val endLine: Int get() = 0
    override val endCol: Int get() = endByte
}

private class TestRule(
    override val id: String,
    override val owns: Intent,
    override val reads: Set<Intent> = emptySet(),
) : Rule {
    override fun run(view: View): List<Emit> = emptyList()
}

private class TestPack(
    override val id: String,
    override val languages: Set<String> = setOf("kotlin"),
    override val apiVersion: Int = CINT_API_VERSION,
    private val entries: List<Rule>,
) : RulePack {
    override fun rules(): List<Rule> = entries
}

private class TestConfigurablePack(
    private val extra: Boolean = false,
) : ConfigurableRulePack {
    override val id: String = "demo-config"
    override val languages: Set<String> = setOf("kotlin")
    override val apiVersion: Int = CINT_API_VERSION

    override fun rules(): List<Rule> =
        listOf(TestRule("demo-config:base", "CONFIG_BASE")) +
            if (extra) listOf(TestRule("demo-config:extra", "CONFIG_EXTRA")) else emptyList()

    override fun configure(values: Map<String, Any?>): RulePack = TestConfigurablePack(values["extra"] == true)
}

@RulePackApi(CINT_API_VERSION + 1)
private class TestAnnotatedPack : RulePack {
    override val id: String = "annotated"
    override val languages: Set<String> = setOf("kotlin")
    override val apiVersion: Int = CINT_API_VERSION + 1

    override fun rules(): List<Rule> = emptyList()
}

private fun resolve(packs: List<RulePack>): List<Relation> {
    val owners = mutableMapOf<Intent, Rule>()
    for (rule in packs.flatMap { it.rules() }) {
        require(owners.putIfAbsent(rule.owns, rule) == null) { "duplicate owner for intent ${rule.owns}" }
    }
    return packs.flatMap { it.rules() }.flatMap { reader ->
        reader.reads.map { intent ->
            val owner = owners[intent] ?: throw IllegalArgumentException("unknown intent '$intent' read by ${reader.id}")
            Relation(reader, intent, owner)
        }
    }
}

class ContractTest {
    private val bookkeeping = TestRule("demo:bookkeeping", "BOOKKEEPING")
    private val validation = TestRule("demo:validation", "VALIDATION", setOf("BOOKKEEPING"))
    private val pack = TestPack("demo", entries = listOf(bookkeeping, validation))

    @Test
    fun rulePackApiVersionMustBeDeclaredByThePack() {
        assertFalse(RulePack::class.java.getMethod("getApiVersion").isDefault)
        assertEquals(CINT_API_VERSION, pack.apiVersion)
    }

    @Test
    fun configurableRulePackIsAdditiveAndVersionPinned() {
        assertEquals(2, CINT_API_VERSION)

        val original = TestConfigurablePack()
        val configured = original.configure(mapOf("extra" to true))

        assertEquals(original.id, configured.id)
        assertEquals(CINT_API_VERSION, configured.apiVersion)
        assertEquals(1, original.rules().size)
        assertEquals(2, configured.rules().size)
    }

    @Test
    fun rulePackApiMetadataIsReadableFromTheClass() {
        assertEquals(CINT_API_VERSION + 1, TestAnnotatedPack::class.java.getAnnotation(RulePackApi::class.java)?.version)
    }

    @Test
    fun ruleIdsAreNamespacedByPack() {
        assertEquals("demo:validation", validation.id)
        assertTrue(pack.rules().all { it.id.startsWith("${pack.id}:") })
        assertEquals(1, pack.rules().count { it.owns == "VALIDATION" })
    }

    @Test
    fun untaggedIsReservedAndUnowned() {
        assertEquals("UNTAGGED", UNTAGGED_INTENT)
        assertTrue(pack.rules().none { it.owns == UNTAGGED_INTENT })
        assertTrue(pack.rules().none { UNTAGGED_INTENT in it.reads })
    }

    @Test
    fun relationResolvesReadIntentToOwningRule() {
        val relation = resolve(listOf(pack)).single()

        assertTrue(relation.intent in relation.reader.reads)
        assertEquals(relation.intent, relation.owner.owns)
        assertEquals(validation, relation.reader)
        assertEquals(bookkeeping, relation.owner)
        assertEquals(1, pack.rules().count { it.owns == relation.intent })
        assertEquals(listOf(Relation(validation, "BOOKKEEPING", bookkeeping)), resolve(listOf(pack)))
    }

    @Test
    fun relationRejectsUnknownReadIntent() {
        val broken = TestRule("demo:broken", "BROKEN", setOf("MISSING"))
        val malformed = TestPack("demo", entries = listOf(bookkeeping, broken))

        val error = assertFailsWith<IllegalArgumentException> { resolve(listOf(malformed)) }

        assertTrue(error.message.orEmpty().contains("MISSING"))
    }

    @Test
    fun annotationKeepsReservedAnchorAndStructuredProvenance() {
        val span = Span(1, 2, 1, 8)
        val first =
            Contribution("demo:logging", Role.SOURCE_INTENT, setOf("LOGGING"), "log.warn", span, "treesitter", 1.0, 10, 16)
        val second =
            Contribution("demo:error-handling", Role.SOURCE_INTENT, setOf("ERROR_HANDLING"), "throw", span, "lsp", 0.9, 10, 16)
        val annotation =
            Annotation(
                role = Role.SCAFFOLD,
                intents = first.intents + second.intents,
                evidence = "throw",
                span = span,
                source = "mixed",
                confidence = 1.0,
                startByte = 10,
                endByte = 16,
                provenance = listOf(first, second),
            )

        assertNull(annotation.nodeAnchor)
        assertEquals(setOf("LOGGING", "ERROR_HANDLING"), annotation.intents)
        assertEquals(listOf(first, second), annotation.provenance)
        assertEquals("demo:logging", annotation.rule)
        assertFalse(annotation.rule.contains("+"))
    }

    @Test
    fun annotationRequiresAtLeastOneContributor() {
        assertFailsWith<IllegalArgumentException> {
            Annotation(
                role = Role.SOURCE_INTENT,
                intents = setOf("LOGGING"),
                evidence = "log",
                span = Span(0, 0, 0, 3),
                source = "treesitter",
                confidence = 1.0,
                startByte = 0,
                endByte = 3,
                provenance = emptyList(),
            )
        }
    }

    @Test
    fun viewExposesValidationSyntaxFacts() {
        val methods =
            View::class.java.methods
                .map { it.name }
                .toSet()

        assertTrue(methods.containsAll(setOf("isConstant", "isSideEffectFree", "statements", "isExit")))
    }

    @Test
    fun viewExposesSemanticFacts() {
        val methods =
            View::class.java.methods
                .map { it.name }
                .toSet()

        assertTrue(
            methods.containsAll(
                setOf(
                    "receiverType",
                    "declarations",
                    "writes",
                    "comments",
                    "commentBefore",
                    "scopes",
                    "enclosingScope",
                    "enclosingBlock",
                    "guards",
                    "returnSites",
                    "returns",
                ),
            ),
        )
    }

    @Test
    fun emitBuildersCarryNodeDataAndOwnedIntent() {
        val node = TestNode("property_declaration", 10, 30, "var value = 1")
        val commentNode = TestNode("comment", 0, 9, "// cint:bookkeeping")
        val comment = CommentFact(commentNode)
        val writeNode = TestNode("assignment", 40, 49, "value = 2")
        val write = WriteFact(writeNode, WriteKind.ASSIGNMENT)
        val declaration =
            DeclarationFact(
                node = node,
                kind = DeclarationKind.PROPERTY,
                name = "value",
                nameNode = node,
                annotations = listOf("Bookkeeping"),
                annotationNodes = emptyList(),
                precedingComments = listOf(comment),
                scope = ScopeFact(node, ScopeKind.FILE),
                writes = listOf(write),
            )

        val nodeEmit = Emit.of("BOOKKEEPING", node)
        val declarationEmit = Emit.declaration("BOOKKEEPING", declaration)
        val commentEmit =
            Emit.declaration(
                "BOOKKEEPING",
                declaration,
                source = "comment",
                includePrecedingComment = true,
            )
        val commentWithTreesitterSource =
            Emit.declaration(
                "BOOKKEEPING",
                declaration,
                includePrecedingComment = true,
            )
        val magicEmit = Emit.declaration("BOOKKEEPING", declaration, source = "comment", magicComment = "bookkeeping")
        val writeEmit = Emit.write("BOOKKEEPING", write)

        assertEquals(setOf("BOOKKEEPING"), nodeEmit.intents)
        assertEquals(Span(0, 10, 0, 30), nodeEmit.span)
        assertEquals(10, nodeEmit.startByte)
        assertEquals(30, nodeEmit.endByte)
        assertEquals(nodeEmit, declaration.emit("BOOKKEEPING"))
        assertEquals(declarationEmit, Emit.declaration("BOOKKEEPING", declaration))
        assertEquals(0, commentEmit.startByte)
        assertEquals(30, commentEmit.endByte)
        assertEquals("comment", commentEmit.source)
        assertEquals(10, Emit.declaration("BOOKKEEPING", declaration, source = "comment").startByte)
        assertEquals("comment", Emit.declaration("BOOKKEEPING", declaration, source = "comment").source)
        assertEquals(commentEmit.startByte, commentWithTreesitterSource.startByte)
        assertEquals(commentEmit.endByte, commentWithTreesitterSource.endByte)
        assertEquals(commentEmit.evidence, commentWithTreesitterSource.evidence)
        assertEquals(commentEmit, magicEmit)
        assertEquals(writeNode.span(), writeEmit.span)
        assertEquals(writeEmit, write.emit("BOOKKEEPING"))
        assertTrue(declaration.hasAnnotation("Bookkeeping"))
        assertTrue(declaration.hasMagicComment("bookkeeping"))
        assertFalse(declaration.hasMagicComment("book"))
        assertEquals(listOf(write), declaration.writes())
    }

    @Test
    fun declarationEmitCommentInclusionIsExplicit() {
        val node = TestNode("property_declaration", 10, 30, "var value = 1")
        val comment = CommentFact(TestNode("comment", 0, 9, "// cint:bookkeeping"))
        val withComment =
            DeclarationFact(
                node = node,
                kind = DeclarationKind.PROPERTY,
                name = "value",
                nameNode = node,
                precedingComments = listOf(comment),
            )
        val withoutComment = withComment.copy(precedingComments = emptyList())

        assertEquals(10, Emit.declaration("BOOKKEEPING", withComment).startByte)
        assertEquals(10, Emit.declaration("BOOKKEEPING", withoutComment).startByte)
        assertEquals(0, Emit.declaration("BOOKKEEPING", withComment, includePrecedingComment = true).startByte)
        assertEquals(10, Emit.declaration("BOOKKEEPING", withoutComment, includePrecedingComment = true).startByte)
        assertEquals(0, Emit.declaration("BOOKKEEPING", withComment, magicComment = "bookkeeping").startByte)
        assertEquals(10, Emit.declaration("BOOKKEEPING", withComment, magicComment = "missing").startByte)
    }

    @Test
    fun callFactHasNoDeadReceiverTypeField() {
        assertFalse(CallFact::class.java.declaredFields.any { it.name == "receiverType" })
    }

    @Test
    fun emitCannotChooseItsRoleOrRuleId() {
        val fields = Emit::class.java.declaredFields.map { it.name }
        assertFalse("role" in fields)
        assertFalse("rule" in fields)

        val emit =
            Emit(
                intents = setOf("LOGGING"),
                evidence = "log.warn",
                span = Span(0, 0, 0, 8),
                source = "treesitter",
                confidence = 1.0,
                startByte = 0,
                endByte = 8,
            )
        val contribution = bookkeeping.toContribution(emit)

        assertEquals("demo:bookkeeping", contribution.rule)
        assertEquals(Role.SOURCE_INTENT, contribution.role)
        assertEquals(setOf("LOGGING"), contribution.intents)
        assertNull(emit.nodeAnchor)
    }
}
