package dev.cint.integration.restriction

import dev.cint.api.Role
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.NullOracle
import dev.cint.core.restriction.AiRestrictionRecord
import dev.cint.core.restriction.AiRestrictionResolver
import dev.cint.core.restriction.AiRestrictionsJson
import dev.cint.core.restriction.ResolvedRestriction
import dev.cint.core.restriction.ResolvedRestrictionOperation
import dev.cint.core.restriction.RestrictionOperation
import dev.cint.core.runtime.scheduler.RuleEngine
import dev.cint.core.support.hashing.contentHash
import dev.cint.core.syntax.ExpressionUnit
import dev.cint.core.syntax.expressionUnits
import dev.cint.core.test.testConfig
import dev.cint.core.user.UserAnnotationResolver
import dev.cint.test.fixtures.testPacks
import dev.cint.treesitter.KotlinParser
import dev.cint.treesitter.KotlinSyntaxModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AiRestrictionResolutionTest {
    private val text =
        """
        private val log = LoggerFactory.getLogger(Foo::class.java)
        fun f() {
            val message = "x"
            log.warn(message)
        }
        """.trimIndent()

    @Test
    fun legacy059FingerprintStillResolvesWithoutAVersionField() {
        val source = SourceFile("Sample.kt", text)
        val tree = KotlinParser().parse(source)
        val call = KotlinSyntaxModel.expressionUnits(tree).first { it.text == "log.warn(message)" }
        val legacyFingerprint =
            dev.cint.core.syntax.UnitIdentity.unitFingerprint(
                source,
                tree,
                KotlinSyntaxModel,
                call.startByte,
                call.endByte,
                1,
            ) ?: error("no legacy fingerprint")
        val json =
            """
            {"version":1,"restrictions":[{"id":"legacy","path":"Sample.kt","contentHash":"${
                contentHash(call.text)
            }","unitFingerprint":"$legacyFingerprint","anchor":{"startLine":${call.span.startLine},"startCol":${call.span.startCol},"endLine":${call.span.endLine},"endCol":${call.span.endCol}},"remove":[{"intent":"LOGGING","startOffset":0,"endOffset":${
                call.text.length
            }}],"add":[]}]}
            """.trimIndent()
        val record = AiRestrictionsJson.parse(json).single()
        assertEquals(1, record.fingerprintVersion)

        val resolved =
            AiRestrictionResolver
                .resolve(listOf(record), source, tree, KotlinSyntaxModel, null)
                .single()
        assertEquals(call.startByte, resolved.anchorStartByte)
        assertEquals(call.endByte, resolved.remove.single().endByte)
    }

    @Test
    fun formattedFingerprintKeepsARestrictionApplied() {
        val source = SourceFile("Sample.kt", text)
        val tree = KotlinParser().parse(source)
        val call = KotlinSyntaxModel.expressionUnits(tree).first { it.text == "log.warn(message)" }
        val record = recordFor(source, tree, call, call.startByte, call.endByte, add = null)
        val formattedText = text.replace("log.warn(message)", "log.warn( message )")
        val formattedSource = SourceFile("Sample.kt", formattedText)
        val formattedTree = KotlinParser().parse(formattedSource)

        val resolved =
            AiRestrictionResolver
                .resolve(listOf(record), formattedSource, formattedTree, KotlinSyntaxModel, null)
                .single()
        val expectedStart = formattedText.indexOf("log.warn( message )")
        assertEquals(expectedStart, resolved.anchorStartByte)
        assertEquals(expectedStart, resolved.remove.single().startByte)
        assertEquals(expectedStart + "log.warn( message )".length, resolved.remove.single().endByte)

        val annotations =
            RuleEngine()
                .analyze(
                    formattedSource,
                    formattedTree,
                    KotlinSyntaxModel,
                    NullOracle,
                    testConfig("kotlin"),
                    testPacks(),
                    restrictions = listOf(resolved),
                ).annotations
        assertFalse(
            annotations.any {
                it.role == Role.SOURCE_INTENT &&
                    "LOGGING" in it.intents &&
                    it.startByte == expectedStart
            },
        )
    }

    @Test
    fun restrictionReanchorsByContentHashAfterLineMoves() {
        val source = SourceFile("Sample.kt", text)
        val tree = KotlinParser().parse(source)
        val call = KotlinSyntaxModel.expressionUnits(tree).first { it.text == "log.warn(message)" }
        val record =
            AiRestrictionRecord(
                id = "move",
                path = "Sample.kt",
                span = call.span,
                contentHash =
                    dev.cint.core.support.hashing
                        .contentHash(call.text),
                remove = listOf(RestrictionOperation("LOGGING", 0, call.text.length)),
                add = emptyList(),
                symbol = "f",
            )
        val movedText = "// inserted\n\n$text"
        val movedSource = SourceFile("Sample.kt", movedText)
        val movedTree = KotlinParser().parse(movedSource)

        val resolved =
            AiRestrictionResolver
                .resolve(listOf(record), movedSource, movedTree, KotlinSyntaxModel, null)
                .single()

        val expectedStart = movedText.indexOf("log.warn(message)")
        assertEquals(expectedStart, resolved.anchorStartByte)
        assertEquals(expectedStart, resolved.remove.single().startByte)
        assertEquals(expectedStart + "log.warn(message)".length, resolved.remove.single().endByte)
    }

    @Test
    fun missingAnchorTextBecomesDormant() {
        val source = SourceFile("Sample.kt", text)
        val tree = KotlinParser().parse(source)
        val call = KotlinSyntaxModel.expressionUnits(tree).first { it.text == "log.warn(message)" }
        val record =
            AiRestrictionRecord(
                id = "dormant",
                path = "Sample.kt",
                span = call.span,
                contentHash =
                    dev.cint.core.support.hashing
                        .contentHash("something else"),
                remove = listOf(RestrictionOperation("LOGGING", 0, call.text.length)),
                add = emptyList(),
            )

        assertTrue(
            AiRestrictionResolver
                .resolve(listOf(record), source, tree, KotlinSyntaxModel, null)
                .isEmpty(),
        )
    }

    @Test
    fun removingLoggingRecomputesDerivativeAnnotations() {
        val source = SourceFile("Sample.kt", text)
        val tree = KotlinParser().parse(source)
        val call = KotlinSyntaxModel.expressionUnits(tree).first { it.text == "log.warn(message)" }
        val baseline =
            RuleEngine()
                .analyze(source, tree, KotlinSyntaxModel, NullOracle, testConfig("kotlin"), testPacks())
                .annotations
        assertTrue(
            baseline.any {
                it.role == Role.DERIVATIVE_USE &&
                    "LOGGING" in it.intents &&
                    it.startByte <= text.indexOf("val message") &&
                    it.endByte >= text.indexOf("val message") + "val message".length
            },
        )

        val restriction =
            ResolvedRestriction(
                id = "reclassify",
                path = "Sample.kt",
                anchorStartByte = call.startByte,
                anchorEndByte = call.endByte,
                anchorSpan = call.span,
                remove = listOf(operation("LOGGING", call.startByte, call.endByte, call.span)),
                add = listOf(operation("SEARCH", call.startByte, call.endByte, call.span)),
                provider = "deepseek",
                model = "deepseek-chat",
                promptVersion = "deepseek-intent-v3",
            )
        val annotations =
            RuleEngine()
                .analyze(
                    source,
                    tree,
                    KotlinSyntaxModel,
                    NullOracle,
                    testConfig("kotlin"),
                    testPacks(),
                    restrictions = listOf(restriction),
                ).annotations

        assertFalse(
            annotations.any {
                it.role == Role.SOURCE_INTENT &&
                    "LOGGING" in it.intents &&
                    it.startByte == call.startByte &&
                    it.endByte == call.endByte
            },
        )
        val replacement =
            annotations.single {
                it.role == Role.SOURCE_INTENT &&
                    it.startByte == call.startByte &&
                    it.endByte == call.endByte
            }
        assertEquals(setOf("SEARCH"), replacement.intents)
        assertEquals("ai-confirmed", replacement.source)
        assertTrue(replacement.provenance.any { it.rule == "core:ai-confirmed" })
        assertTrue(replacement.provenance.any { it.provider == "deepseek" && it.model == "deepseek-chat" })
        assertFalse(
            annotations.any {
                it.role == Role.DERIVATIVE_USE &&
                    "LOGGING" in it.intents &&
                    it.startByte <= text.indexOf("val message") &&
                    it.endByte >= text.indexOf("val message") + "val message".length
            },
        )
    }

    @Test
    fun narrowingKeepsOuterRemovalAndAddsOnTheInnerSpan() {
        val source = SourceFile("Sample.kt", text)
        val tree = KotlinParser().parse(source)
        val call = KotlinSyntaxModel.expressionUnits(tree).first { it.text == "log.warn(message)" }
        val argumentStart = text.indexOf("message)", call.startByte)
        val argumentEnd = argumentStart + "message".length
        val argumentSpan = assertNotNull(UserAnnotationResolver.spanFor(text, argumentStart, argumentEnd))
        val restriction =
            ResolvedRestriction(
                id = "narrow",
                path = "Sample.kt",
                anchorStartByte = call.startByte,
                anchorEndByte = call.endByte,
                anchorSpan = call.span,
                remove = listOf(operation("LOGGING", call.startByte, call.endByte, call.span)),
                add = listOf(operation("SEARCH", argumentStart, argumentEnd, argumentSpan)),
                provider = "deepseek",
                model = "deepseek-chat",
                promptVersion = "deepseek-intent-v3",
            )

        val annotations =
            RuleEngine()
                .analyze(
                    source,
                    tree,
                    KotlinSyntaxModel,
                    NullOracle,
                    testConfig("kotlin"),
                    testPacks(),
                    restrictions = listOf(restriction),
                ).annotations

        assertFalse(annotations.any { it.role == Role.SOURCE_INTENT && "LOGGING" in it.intents })
        val replacement =
            annotations.single {
                it.role == Role.SOURCE_INTENT &&
                    it.startByte == argumentStart &&
                    it.endByte == argumentEnd
            }
        assertEquals(setOf("SEARCH"), replacement.intents)
        assertEquals("ai-confirmed", replacement.source)
    }

    @Test
    fun childUnitAnchoredRemovalRoundTripsNegativeOffsets() {
        val source = SourceFile("Sample.kt", text)
        val tree = KotlinParser().parse(source)
        val units = KotlinSyntaxModel.expressionUnits(tree)
        val call = units.first { it.text == "log.warn(message)" }
        val argument = units.first { it.text == "message" && it.startByte > text.indexOf("log.warn") }
        val record = recordFor(source, tree, argument, call.startByte, call.endByte, add = null)

        val encoded = AiRestrictionsJson.encode(listOf(record))
        val parsed = AiRestrictionsJson.parse(encoded).single()
        assertTrue(parsed.remove.single().startOffset < 0, encoded)

        val resolved =
            AiRestrictionResolver
                .resolve(listOf(parsed), source, tree, KotlinSyntaxModel, null)
                .single()
        assertEquals(call.startByte, resolved.remove.single().startByte)
        assertEquals(call.endByte, resolved.remove.single().endByte)

        val annotations =
            RuleEngine()
                .analyze(
                    source,
                    tree,
                    KotlinSyntaxModel,
                    NullOracle,
                    testConfig("kotlin"),
                    testPacks(),
                    restrictions = listOf(resolved),
                ).annotations
        assertFalse(
            annotations.any {
                it.role == Role.SOURCE_INTENT &&
                    "LOGGING" in it.intents &&
                    it.startByte == call.startByte &&
                    it.endByte == call.endByte
            },
        )
    }

    @Test
    fun duplicateAnchorTextDoesNotRebindAfterInsertionMovementOrDeletion() {
        val duplicateText =
            """
            private val log = LoggerFactory.getLogger(Foo::class.java)
            fun a() {
                log.warn(message)
            }
            fun b() {
                log.warn(message)
            }
            """.trimIndent()
        val source = SourceFile("Sample.kt", duplicateText)
        val tree = KotlinParser().parse(source)
        val target = KotlinSyntaxModel.expressionUnits(tree).filter { it.text == "log.warn(message)" }.last()
        val record = recordFor(source, tree, target, target.startByte, target.endByte, add = null)

        val baseline =
            AiRestrictionResolver
                .resolve(listOf(record), source, tree, KotlinSyntaxModel, null)
                .single()
        assertEquals(target.startByte, baseline.anchorStartByte)

        val inserted =
            duplicateText.replace(
                "fun b() {\n    log.warn(message)\n}",
                "fun b() {\n    log.warn(message)\n    log.warn(message)\n}",
            )
        val insertedSource = SourceFile("Sample.kt", inserted)
        val insertedTree = KotlinParser().parse(insertedSource)
        assertTrue(
            AiRestrictionResolver
                .resolve(listOf(record), insertedSource, insertedTree, KotlinSyntaxModel, null)
                .isEmpty(),
            "an adjacent identical insertion must not rebind",
        )

        val insertedAbove =
            duplicateText.replace(
                "fun b() {\n    log.warn(message)\n}",
                "fun b() {\n    log.warn(message)\n    // marker\n    log.warn(message)\n}",
            )
        val insertedAboveSource = SourceFile("Sample.kt", insertedAbove)
        val insertedAboveTree = KotlinParser().parse(insertedAboveSource)
        assertTrue(
            AiRestrictionResolver
                .resolve(listOf(record), insertedAboveSource, insertedAboveTree, KotlinSyntaxModel, null)
                .isEmpty(),
            "an adjacent identical insertion must not rebind",
        )

        val moved =
            duplicateText.replace(
                "fun b() {\n    log.warn(message)\n}",
                "fun b() {\n\n    log.warn(message)\n}",
            )
        val movedSource = SourceFile("Sample.kt", moved)
        val movedTree = KotlinParser().parse(movedSource)
        val movedResolved =
            AiRestrictionResolver
                .resolve(listOf(record), movedSource, movedTree, KotlinSyntaxModel, null)
                .single()
        assertEquals(moved.lastIndexOf("log.warn(message)"), movedResolved.anchorStartByte)

        val deleted =
            duplicateText.replace(
                "fun b() {\n    log.warn(message)\n}",
                "fun b() {\n}",
            )
        val deletedSource = SourceFile("Sample.kt", deleted)
        val deletedTree = KotlinParser().parse(deletedSource)
        assertTrue(
            AiRestrictionResolver
                .resolve(listOf(record), deletedSource, deletedTree, KotlinSyntaxModel, null)
                .isEmpty(),
        )
    }

    @Test
    fun legacyRecordWithoutFingerprintGoesDormantWhenAnIdenticalSiblingIsInserted() {
        val duplicateText =
            """
            private val log = LoggerFactory.getLogger(Foo::class.java)
            fun a() {
                log.warn(message)
            }
            fun b() {
                log.warn(message)
            }
            """.trimIndent()
        val source = SourceFile("Sample.kt", duplicateText)
        val tree = KotlinParser().parse(source)
        val target = KotlinSyntaxModel.expressionUnits(tree).filter { it.text == "log.warn(message)" }.last()
        val record =
            AiRestrictionRecord(
                id = "legacy",
                path = source.path,
                span = target.span,
                contentHash = contentHash(target.text),
                remove = listOf(RestrictionOperation("LOGGING", 0, target.text.length)),
                add = emptyList(),
                symbol = "b",
                unitFingerprint = null,
            )
        assertTrue(
            AiRestrictionResolver
                .resolve(listOf(record), source, tree, KotlinSyntaxModel, null)
                .isNotEmpty(),
        )

        val inserted =
            duplicateText.replace(
                "fun b() {\n    log.warn(message)\n}",
                "fun b() {\n    log.warn(message)\n    log.warn(message)\n}",
            )
        val insertedSource = SourceFile("Sample.kt", inserted)
        val insertedTree = KotlinParser().parse(insertedSource)
        assertTrue(
            AiRestrictionResolver
                .resolve(listOf(record), insertedSource, insertedTree, KotlinSyntaxModel, null)
                .isEmpty(),
        )
    }

    @Test
    fun literalInsertionAboveTheDuplicateTargetGoesDormant() {
        val twoCallText =
            """
            private val log = LoggerFactory.getLogger(Foo::class.java)
            fun b() {
                log.warn(message)
                log.warn(message)
            }
            """.trimIndent()
        val source = SourceFile("Sample.kt", twoCallText)
        val tree = KotlinParser().parse(source)
        val target = KotlinSyntaxModel.expressionUnits(tree).filter { it.text == "log.warn(message)" }.last()
        val record = recordFor(source, tree, target, target.startByte, target.endByte, add = null)
        assertTrue(
            AiRestrictionResolver
                .resolve(listOf(record), source, tree, KotlinSyntaxModel, null)
                .isNotEmpty(),
        )

        val inserted =
            twoCallText.replace(
                "fun b() {\n    log.warn(message)\n    log.warn(message)\n}",
                "fun b() {\n    log.warn(message)\n    log.warn(message)\n    log.warn(message)\n}",
            )
        val insertedSource = SourceFile("Sample.kt", inserted)
        val insertedTree = KotlinParser().parse(insertedSource)
        val insertedResolved =
            AiRestrictionResolver
                .resolve(listOf(record), insertedSource, insertedTree, KotlinSyntaxModel, null)
        assertTrue(insertedResolved.isEmpty(), insertedResolved.toString())
    }

    private fun recordFor(
        source: SourceFile,
        tree: dev.cint.core.syntax.SyntaxTree,
        unit: ExpressionUnit,
        removeStart: Int,
        removeEnd: Int,
        add: Pair<Int, Int>?,
    ): AiRestrictionRecord {
        val tokenRange =
            AiRestrictionResolver.tokenOffsetsFor(source.text, unit.startByte, removeStart, removeEnd)
        val addTokenRange =
            add?.let { (start, end) ->
                AiRestrictionResolver.tokenOffsetsFor(source.text, unit.startByte, start, end)
            }
        return AiRestrictionRecord(
            id = "record-${unit.unitId}",
            path = source.path,
            span = unit.span,
            contentHash = contentHash(unit.text),
            remove =
                listOf(
                    RestrictionOperation(
                        intent = "LOGGING",
                        startOffset = removeStart - unit.startByte,
                        endOffset = removeEnd - unit.startByte,
                        startToken = tokenRange?.first,
                        endToken = tokenRange?.second,
                    ),
                ),
            add =
                add
                    ?.let { (start, end) ->
                        listOf(
                            RestrictionOperation(
                                intent = "SEARCH",
                                startOffset = start - unit.startByte,
                                endOffset = end - unit.startByte,
                                confidence = 0.6,
                                startToken = addTokenRange?.first,
                                endToken = addTokenRange?.second,
                            ),
                        )
                    }.orEmpty(),
            symbol =
                UserAnnotationResolver.symbolAt(
                    source,
                    tree,
                    KotlinSyntaxModel,
                    unit.startByte,
                ),
            unitFingerprint =
                AiRestrictionResolver.unitFingerprint(
                    source,
                    tree,
                    KotlinSyntaxModel,
                    unit.startByte,
                    unit.endByte,
                ),
            provider = "deepseek",
            model = "deepseek-chat",
            promptVersion = "deepseek-intent-v3",
        )
    }

    private fun operation(
        intent: String,
        start: Int,
        end: Int,
        span: dev.cint.api.Span,
    ): ResolvedRestrictionOperation =
        ResolvedRestrictionOperation(
            intent = intent,
            startByte = start,
            endByte = end,
            span = span,
            confidence = 0.7,
            rationale = null,
        )
}
