package dev.cint.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContractModelTest {
    private val bookkeeping = TestRule("demo:bookkeeping", "BOOKKEEPING")

    @Test
    fun annotationKeepsReservedAnchorAndStructuredProvenance() {
        val span = Span(1, 2, 1, 8)
        val first =
            Contribution("demo:logging", Role.SOURCE_INTENT, setOf("LOGGING"), span, "treesitter", 1.0, 10, 16)
        val second =
            Contribution("demo:error-handling", Role.SOURCE_INTENT, setOf("ERROR_HANDLING"), span, "lsp", 0.9, 10, 16)
        val annotation =
            Annotation(
                role = Role.SCAFFOLD,
                intents = first.intents + second.intents,
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
    fun contributionProvenanceFieldsAreAdditiveDefaults() {
        val span = Span(0, 0, 0, 3)
        val plain =
            Contribution("demo:logging", Role.SOURCE_INTENT, setOf("LOGGING"), span, "treesitter", 1.0, 0, 3)
        assertNull(plain.provider)
        assertNull(plain.model)
        assertNull(plain.promptVersion)
        val confirmed =
            plain.copy(
                provider = "deepseek",
                model = "deepseek-chat",
                promptVersion = "deepseek-intent-v3",
            )
        assertEquals("deepseek", confirmed.provider)
        assertEquals("deepseek-chat", confirmed.model)
        assertEquals("deepseek-intent-v3", confirmed.promptVersion)
        assertEquals(CINT_API_VERSION, 5)
    }

    @Test
    fun nodeAnchorIsReservedOnAnnotationsAndAbsentFromEmits() {
        val annotationFields = Annotation::class.java.declaredFields.map { it.name }
        val emitFields = Emit::class.java.declaredFields.map { it.name }

        assertTrue("nodeAnchor" in annotationFields)
        assertFalse("nodeAnchor" in emitFields)
    }

    @Test
    fun evidenceIsAbsentFromTheAnnotationContract() {
        assertFalse(Annotation::class.java.declaredFields.any { it.name == "evidence" })
        assertFalse(Contribution::class.java.declaredFields.any { it.name == "evidence" })
        assertFalse(Emit::class.java.declaredFields.any { it.name == "evidence" })
    }

    @Test
    fun annotationRequiresAtLeastOneContributor() {
        assertFailsWith<IllegalArgumentException> {
            Annotation(
                role = Role.SOURCE_INTENT,
                intents = setOf("LOGGING"),
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
    fun commentFactKeepsLegacyPositionalConstructor() {
        val node = TestNode("comment", 0, 9, "// cint:bookkeeping")
        val legacy = CommentFact(node, "bookkeeping")
        val defaulted = CommentFact(node)
        val classified = CommentFact(node, kind = CommentKind.LINE)

        assertEquals("bookkeeping", legacy.magicComment)
        assertEquals(CommentKind.OTHER, legacy.kind)
        assertEquals("bookkeeping", defaulted.magicComment)
        assertEquals(CommentKind.LINE, classified.kind)
        assertEquals(listOf("node", "magicComment", "kind"), CommentFact::class.java.declaredFields.map { it.name })
        assertNotNull(CommentFact::class.java.getDeclaredConstructor(SyntaxNode::class.java, String::class.java))
        assertNotNull(
            CommentFact::class.java.getDeclaredConstructor(
                SyntaxNode::class.java,
                String::class.java,
                CommentKind::class.java,
            ),
        )
    }

    @Test
    fun emitCannotChooseItsRoleOrRuleId() {
        val fields = Emit::class.java.declaredFields.map { it.name }
        assertFalse("role" in fields)
        assertFalse("rule" in fields)

        val emit =
            Emit(
                intents = setOf("LOGGING"),
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
    }

    @Test
    fun ruleOutputSplitsEstablishedAndCandidateEmits() {
        val node = TestNode("identifier", 3, 7, "name")
        val established = Emit.of("LOGIN", node)
        val candidate =
            CandidateEmit.of(
                "LOGIN",
                node,
                CandidateReason.LOW_CONFIDENCE,
                source = "rule:demo:login",
                confidence = 0.4,
                aiEligible = true,
            )

        val output: List<RuleEmit> = listOf(established, candidate)

        assertEquals(2, output.size)
        assertTrue(output[0] is Emit)
        assertEquals("rule:demo:login", (output[1] as CandidateEmit).source)
        assertEquals(CandidateReason.LOW_CONFIDENCE, (output[1] as CandidateEmit).reason)
        assertEquals(established.span, candidate.span)
        assertEquals(established.startByte, candidate.startByte)
        assertEquals(established.endByte, candidate.endByte)
    }

    @Test
    fun workspaceCompatibleSignaturesPinCandidateBoundary() {
        val contribution: (Rule, Emit) -> Contribution = Rule::toContribution
        val node = TestNode("identifier", 0, 4, "name")
        val candidate = CandidateEmit.of("LOGIN", node, CandidateReason.GAP)

        assertEquals(Emit.DEFAULT_SOURCE, Emit.of("LOGIN", node).source)
        assertEquals("candidate", candidate.source)
        assertTrue(candidate.source != Emit.DEFAULT_SOURCE)
        assertEquals(CandidateReason.GAP, candidate.reason)
        assertTrue(contribution.toString().isNotEmpty())
    }
}
