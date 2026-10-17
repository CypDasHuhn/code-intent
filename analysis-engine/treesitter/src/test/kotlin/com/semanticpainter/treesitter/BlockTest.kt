package com.semanticpainter.treesitter

import com.semanticpainter.core.config.loadIntentConfig
import com.semanticpainter.core.engine.AnalysisEngine
import com.semanticpainter.core.model.Annotation
import com.semanticpainter.core.model.Role
import com.semanticpainter.core.model.SourceFile
import com.semanticpainter.core.model.UNTAGGED_INTENT
import com.semanticpainter.core.oracle.NullOracle
import com.semanticpainter.core.oracle.SymbolFact
import com.semanticpainter.core.oracle.TypeFact
import com.semanticpainter.core.oracle.TypeOracle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class CountingOracle : TypeOracle {
    var calls = 0

    override fun typeAt(
        file: SourceFile,
        byteOffset: Int,
    ): TypeFact? {
        calls++
        return null
    }

    override fun definitionAt(
        file: SourceFile,
        byteOffset: Int,
    ): SymbolFact? = null
}

private class FixedOracle(
    private val fact: TypeFact?,
) : TypeOracle {
    var calls = 0

    override fun typeAt(
        file: SourceFile,
        byteOffset: Int,
    ): TypeFact? {
        calls++
        return fact
    }

    override fun definitionAt(
        file: SourceFile,
        byteOffset: Int,
    ): SymbolFact? = null
}

class BlockTest {
    private val config = loadIntentConfig(null, "kotlin")

    private fun analyze(text: String): List<Annotation> {
        val source = SourceFile("Sample.kt", text)
        val tree = KotlinParser().parse(source)
        return AnalysisEngine(IntentBlocks.build(config)).analyze(source, tree, NullOracle, config)
    }

    @Test
    fun classifiesSourceIntents() {
        val text =
            """
            private val logger = LoggerFactory.getLogger(Foo::class.java)
            fun f() {
                logger.info("x")
                println("p")
                work()
            }
            fun work() {}
            """.trimIndent()

        val sources = analyze(text).filter { it.role == Role.SOURCE_INTENT }
        assertEquals(2, sources.size)
        assertTrue(sources.any { it.rule == "call-on-logger-symbol" && it.intents == setOf("LOGGING") })
        assertTrue(sources.any { it.rule == "print" && it.intents == setOf("LOGGING") })
    }

    @Test
    fun concealsPureLoggingScaffold() {
        val text =
            """
            private val log = LoggerFactory.getLogger(Foo::class.java)
            fun f() {
                if (1 == 1) {
                    log.warn("x")
                }
            }
            """.trimIndent()

        val scaffold = analyze(text).first { it.role == Role.SCAFFOLD }
        assertEquals(setOf("LOGGING"), scaffold.intents)
    }

    @Test
    fun mixedScaffoldUsesShowMeaning() {
        val text =
            """
            private val log = LoggerFactory.getLogger(Foo::class.java)
            fun f() {
                if (1 == 1) {
                    log.warn("x")
                    throw IllegalStateException("y")
                }
            }
            """.trimIndent()

        val scaffold = analyze(text).first { it.role == Role.SCAFFOLD }
        assertEquals(setOf("LOGGING", "ERROR_HANDLING"), scaffold.intents)
    }

    @Test
    fun validationGuardWithBookkeeping() {
        val text =
            """
            class C {
                @Bookkeeping var skipped = 0
                fun f(value: Int) {
                    if (value < 0) {
                        skipped++
                        return false
                    }
                }
            }
            """.trimIndent()

        val annotations = analyze(text)
        val guard = annotations.first { it.rule == "validation-guard" }
        assertEquals(setOf("VALIDATION"), guard.intents)
        val scaffold = annotations.first { it.rule == "intent-scaffold" }
        assertEquals(setOf("VALIDATION", "BOOKKEEPING"), scaffold.intents)
    }

    @Test
    fun validationGuardClassifiesPlumbingLocals() {
        val text = """
            fun f(call: Call) {
                val navigation = call.children.firstOrNull { it.kind == "navigation" }
                if (navigation != null) {
                    val suffix = navigation.children.lastOrNull()
                    return suffix
                }
            }
        """.trimIndent()

        val annotations = analyze(text)
        val navigation = annotations.first {
            it.role == Role.DERIVATIVE_USE && it.evidence.contains("val navigation")
        }
        assertEquals(setOf("VALIDATION"), navigation.intents)
        val scaffold = annotations.first { it.rule == "intent-scaffold" }
        assertEquals(setOf("VALIDATION"), scaffold.intents)
    }

    @Test
    fun validationPredicateWithoutBookkeeping() {
        val text =
            """
            fun f(values: List<String>) {
                if (values.isEmpty()) {
                    return
                }
            }
            """.trimIndent()

        val annotations = analyze(text)
        val guard = annotations.first { it.rule == "validation-guard" }
        assertEquals(setOf("VALIDATION"), guard.intents)
        val scaffold = annotations.first { it.rule == "intent-scaffold" }
        assertEquals(setOf("VALIDATION"), scaffold.intents)
    }

    @Test
    fun earlyExitWithoutValidationSignalIsNotValidation() {
        val text =
            """
            fun enabled(): Boolean = true
            fun f() {
                if (enabled()) {
                    return
                }
            }
            """.trimIndent()

        assertTrue(analyze(text).none { it.rule == "validation-guard" })
    }

    @Test
    fun validationCommentForcesGuard() {
        val text =
            """
            fun enabled(): Boolean = true
            fun f() {
                // scp:validation
                if (enabled()) {
                    return
                }
            }
            """.trimIndent()

        val guard = analyze(text).first { it.rule == "validation-guard" }
        assertEquals(setOf("VALIDATION"), guard.intents)
        assertEquals("comment", guard.source)
    }

    @Test
    fun bookkeepingAnnotationTagsWrites() {
        val text =
            """
            class C {
                @Bookkeeping
                var skipped = 0
                fun f() {
                    skipped++
                    val read = skipped
                }
            }
            """.trimIndent()

        val annotations = analyze(text)
        assertTrue(
            annotations.any {
                it.rule == "bookkeeping-declaration" && it.intents == setOf("BOOKKEEPING")
            },
        )
        val writes = annotations.filter { it.rule == "bookkeeping-write" }
        assertEquals(1, writes.size)
        assertEquals("skipped++", writes.single().evidence)
    }

    @Test
    fun bookkeepingCommentTagsWrites() {
        val text =
            """
            class C {
                // scp:bookkeeping
                var skipped = 0
                fun f() {
                    skipped += 1
                }
            }
            """.trimIndent()

        val annotations = analyze(text)
        val declaration =
            annotations.first {
                it.rule == "bookkeeping-declaration" && it.source == "comment"
            }
        assertEquals(Role.SOURCE_INTENT, declaration.role)
        assertEquals(1, declaration.span.startLine)
        assertEquals(2, declaration.span.endLine)
        val writes = annotations.filter { it.rule == "bookkeeping-write" }
        assertEquals(1, writes.size)
        assertEquals("skipped += 1", writes.single().evidence)
    }

    @Test
    fun callSpanIncludesTrailingLambda() {
        val text =
            """
            fun f() {
                require(true) {
                    "message"
                }
            }
            """.trimIndent()

        val source = analyze(text).first { it.rule == "error-handling-call" }
        assertTrue(source.evidence.contains("\"message\""))
        assertEquals(2, source.span.endLine - source.span.startLine)
    }

    @Test
    fun sideEffectFreeGuardIsLogging() {
        val text =
            """
            private val log = LoggerFactory.getLogger(Foo::class.java)
            fun f(x: Int) {
                if (x > 0) {
                    log.warn("x")
                }
            }
            """.trimIndent()

        val scaffold = analyze(text).first { it.role == Role.SCAFFOLD }
        assertEquals(setOf("LOGGING"), scaffold.intents)
    }

    @Test
    fun effectfulGuardMakesScaffoldVisible() {
        val text =
            """
            private val log = LoggerFactory.getLogger(Foo::class.java)
            fun enabled(): Boolean = true
            fun f() {
                if (enabled()) {
                    log.warn("x")
                }
            }
            """.trimIndent()

        val scaffold = analyze(text).first { it.role == Role.SCAFFOLD }
        assertEquals(setOf("LOGGING", UNTAGGED_INTENT), scaffold.intents)
    }

    @Test
    fun mixedIfBranchesSplitByIntent() {
        val text =
            """
            private val log = LoggerFactory.getLogger(Foo::class.java)
            fun f() {
                if (1 == 1) {
                    log.warn("x")
                } else {
                    work()
                }
            }
            fun work() {}
            """.trimIndent()

        val scaffolds = analyze(text).filter { it.role == Role.SCAFFOLD }
        val thenBranch = scaffolds.first { it.rule == "intent-scaffold-if-then" }
        val elseBranch = scaffolds.first { it.rule == "intent-scaffold-if-else" }
        assertEquals(setOf("LOGGING"), thenBranch.intents)
        assertEquals(setOf(UNTAGGED_INTENT), elseBranch.intents)
    }

    @Test
    fun tagsLoggingCatchAndTryHeader() {
        val text =
            """
            private val log = LoggerFactory.getLogger(Foo::class.java)
            fun f() {
                try {
                    work()
                } catch (failure: Exception) {
                    log.warn("failed", failure)
                }
            }
            fun work() {}
            """.trimIndent()

        val scaffolds = analyze(text).filter { it.role == Role.SCAFFOLD }
        val header = scaffolds.first { it.rule == "intent-scaffold-try-header" }
        val catch = scaffolds.first { it.rule == "intent-scaffold-catch" }
        assertEquals(setOf("LOGGING"), header.intents)
        assertEquals(setOf("LOGGING"), catch.intents)
        assertTrue(catch.evidence.startsWith("}"), "catch should include the try body closing brace")
        assertTrue(scaffolds.none { it.rule == "intent-scaffold-try" })
    }

    @Test
    fun mixedCatchWithRethrowUsesShowMeaning() {
        val text =
            """
            private val log = LoggerFactory.getLogger(Foo::class.java)
            fun f() {
                try {
                    work()
                } catch (e: Exception) {
                    log.warn("failed", e)
                    throw e
                }
            }
            fun work() {}
            """.trimIndent()

        val catch =
            analyze(text).first {
                it.role == Role.SCAFFOLD && it.rule == "intent-scaffold-catch"
            }
        assertEquals(setOf("LOGGING", "ERROR_HANDLING"), catch.intents)
    }

    @Test
    fun literalAssistUnlocksCatch() {
        val text =
            """
            private val log = LoggerFactory.getLogger(Foo::class.java)
            fun f() {
                try {
                    work()
                } catch (ex: Exception) {
                    val message = "failed"
                    log.warn(message, ex)
                }
            }
            fun work() {}
            """.trimIndent()

        val annotations = analyze(text)
        val derivative = annotations.first { it.role == Role.DERIVATIVE_USE }
        assertEquals(setOf("LOGGING"), derivative.intents)

        val catch =
            annotations.first {
                it.role == Role.SCAFFOLD && it.rule == "intent-scaffold-catch"
            }
        assertEquals(setOf("LOGGING"), catch.intents)
    }

    @Test
    fun impureLocalUsedOnlyByLoggingIsDerivative() {
        val text =
            """
            private val log = LoggerFactory.getLogger(Foo::class.java)
            fun text(): String = "x"
            fun f() {
                val message = text()
                log.warn(message)
            }
            """.trimIndent()

        val derivative =
            analyze(text).first {
                it.role == Role.DERIVATIVE_USE && it.evidence.contains("val message")
            }
        assertEquals(setOf("LOGGING"), derivative.intents)
    }

    @Test
    fun impureLocalUsedOnlyByErrorHandlingIsDerivative() {
        val text =
            """
            fun f(values: Map<String, Int>) {
                val unknown = values.keys - setOf("a")
                require(unknown.isEmpty()) { "bad ${'$'}{unknown.first()}" }
            }
            """.trimIndent()

        val derivative =
            analyze(text).first {
                it.role == Role.DERIVATIVE_USE && it.evidence.contains("val unknown")
            }
        assertEquals(setOf("ERROR_HANDLING"), derivative.intents)
    }

    @Test
    fun annotatedPureCallIsDerivative() {
        val text =
            """
            private val log = LoggerFactory.getLogger(Foo::class.java)
            @Pure
            fun text(): String = "x"
            fun f() {
                val message = text()
                log.warn(message)
            }
            """.trimIndent()

        val derivative =
            analyze(text).first {
                it.role == Role.DERIVATIVE_USE && it.evidence.contains("val message")
            }
        assertEquals(setOf("LOGGING"), derivative.intents)
    }

    @Test
    fun pureFunctionReferencedOnlyByDerivativeIsDerivative() {
        val text =
            """
            private val log = LoggerFactory.getLogger(Foo::class.java)
            @Pure
            fun text(): String = "x"
            fun f() {
                val message = text()
                log.warn(message)
            }
            """.trimIndent()

        val derivatives = analyze(text).filter { it.role == Role.DERIVATIVE_USE }
        assertTrue(derivatives.any { it.evidence.contains("fun text") })
        assertTrue(
            derivatives.filter { it.evidence.contains("fun text") }.all {
                it.intents == setOf("LOGGING")
            },
        )
    }

    @Test
    fun functionReferencedOutsideDerivativeIsNotPromoted() {
        val text =
            """
            private val log = LoggerFactory.getLogger(Foo::class.java)
            @Pure
            fun text(): String = "x"
            fun f() {
                val message = text()
                log.warn(message)
                log.warn(text())
            }
            """.trimIndent()

        val derivatives = analyze(text).filter { it.role == Role.DERIVATIVE_USE }
        assertTrue(derivatives.any { it.evidence.contains("val message") })
        assertTrue(derivatives.none { it.evidence.contains("fun text") })
    }

    @Test
    fun strongLoggerHeuristicSkipsLsp() {
        val text =
            """
            private val log = LoggerFactory.getLogger(Foo::class.java)
            fun f() {
                log.warn("x")
            }
            """.trimIndent()

        val source = SourceFile("Sample.kt", text)
        val tree = KotlinParser().parse(source)
        val oracle = CountingOracle()
        val annotations = AnalysisEngine(IntentBlocks.build(config)).analyze(source, tree, oracle, config)

        assertTrue(annotations.any { it.rule == "call-on-logger-symbol" })
        assertEquals(0, oracle.calls)
    }

    @Test
    fun resolvedNonLoggerTypeVetoesImportHeuristic() {
        val text =
            """
            import org.slf4j.LoggerFactory
            class FakeLogger {
                fun info(message: String) {}
            }
            fun f() {
                val fake = FakeLogger()
                fake.info("looks like logging")
            }
            """.trimIndent()

        val source = SourceFile("Sample.kt", text)
        val tree = KotlinParser().parse(source)
        val annotations =
            AnalysisEngine(IntentBlocks.build(config)).analyze(
                source,
                tree,
                FixedOracle(TypeFact("FakeLogger", "lsp", 0.95)),
                config,
            )

        assertTrue(
            annotations.none {
                it.rule == "name-import-gated" || it.rule == "typed-receiver"
            },
        )
    }

    @Test
    fun resolvedNonLoggerTypeVetoesReceiverNameHeuristic() {
        val text =
            """
            class Service {
                fun warn(message: String) {}
            }
            private val logger = Service()
            fun f() {
                logger.warn("looks like logging")
            }
            """.trimIndent()

        val source = SourceFile("Sample.kt", text)
        val tree = KotlinParser().parse(source)
        val annotations =
            AnalysisEngine(IntentBlocks.build(config)).analyze(
                source,
                tree,
                FixedOracle(TypeFact("Service", "lsp", 0.95)),
                config,
            )

        assertTrue(annotations.none { it.rule == "receiver-name" || it.rule == "typed-receiver" })
    }

    @Test
    fun declarationReferencedOnlyByDerivativeIsDerivative() {
        val text =
            """
            private val log = LoggerFactory.getLogger(Foo::class.java)
            fun make(): String = "x"
            fun f() {
                val source = make()
                val message = source
                log.warn(message)
            }
            """.trimIndent()

        val derivatives = analyze(text).filter { it.role == Role.DERIVATIVE_USE }
        assertEquals(3, derivatives.size)
        assertTrue(derivatives.all { it.intents == setOf("LOGGING") })
    }

    @Test
    fun allClassifiedReferencesPromoteImpureLocal() {
        val text =
            """
            private val log = LoggerFactory.getLogger(Foo::class.java)
            fun make(): String = "x"
            fun f() {
                val source = make()
                val message = source
                log.warn(message)
                log.warn(source)
            }
            """.trimIndent()

        val derivatives = analyze(text).filter { it.role == Role.DERIVATIVE_USE }
        assertTrue(derivatives.any { it.evidence.contains("val source") })
        assertTrue(derivatives.all { it.intents == setOf("LOGGING") })
    }

    @Test
    fun deadLocalAddsUntagged() {
        val text =
            """
            private val log = LoggerFactory.getLogger(Foo::class.java)
            fun f() {
                try {
                    work()
                } catch (e: Exception) {
                    if (1 == 1) {
                        val unused = ""
                        log.warn("failed", e)
                    }
                }
            }
            fun work() {}
            """.trimIndent()

        val annotations = analyze(text)
        assertTrue(annotations.none { it.role == Role.DERIVATIVE_USE })
        val catch =
            annotations.first {
                it.role == Role.SCAFFOLD && it.rule == "intent-scaffold-catch"
            }
        assertEquals(setOf("LOGGING", UNTAGGED_INTENT), catch.intents)
    }

    @Test
    fun wholeLoggingTryIsConcealed() {
        val text =
            """
            private val log = LoggerFactory.getLogger(Foo::class.java)
            fun f() {
                try {
                    log.warn("a")
                } catch (e: Exception) {
                    log.warn("b", e)
                }
            }
            """.trimIndent()

        val annotations = analyze(text)
        val whole =
            annotations.first {
                it.role == Role.SCAFFOLD && it.rule == "intent-scaffold-try"
            }
        assertEquals(setOf("LOGGING"), whole.intents)
        assertFalse(annotations.any { it.rule == "intent-scaffold-try-header" })
    }

    @Test
    fun classifiesKotlinChecksAsErrorHandling() {
        val text =
            """
            fun f(x: Int) {
                require(x > 0)
                requireNotNull(x)
                check(x > 0)
                checkNotNull(x)
                error("boom")
                TODO()
                throw IllegalStateException("x")
            }
            """.trimIndent()

        val sources =
            analyze(text).filter {
                it.role == Role.SOURCE_INTENT && "ERROR_HANDLING" in it.intents
            }
        assertEquals(7, sources.size)
    }

    @Test
    fun nestedElseIfDoesNotStickUntagged() {
        val text =
            """
            private val log = LoggerFactory.getLogger(Foo::class.java)
            fun f() {
                if (1 == 1) {
                    log.warn("x")
                } else if (2 == 2) {
                    log.warn("y")
                }
            }
            """.trimIndent()

        val scaffolds = analyze(text).filter { it.role == Role.SCAFFOLD }
        assertTrue(scaffolds.isNotEmpty())
        assertTrue(scaffolds.all { it.intents == setOf("LOGGING") }, "scaffolds=$scaffolds")
    }

    @Test
    fun derivativeChainResolvesUpward() {
        val text =
            """
            private val log = LoggerFactory.getLogger(Foo::class.java)
            fun f() {
                val first = "x"
                val second = first
                log.warn(second)
            }
            """.trimIndent()

        val derivatives = analyze(text).filter { it.role == Role.DERIVATIVE_USE }
        assertEquals(2, derivatives.size)
        assertTrue(derivatives.all { it.intents == setOf("LOGGING") })
    }

    @Test
    fun pureFunctionBodyBecomesScaffold() {
        val text =
            """
            fun requireValue(value: String?) {
                requireNotNull(value)
            }
            """.trimIndent()

        val function =
            analyze(text).first {
                it.role == Role.SCAFFOLD && it.rule == "intent-scaffold-function"
            }
        assertEquals(setOf("ERROR_HANDLING"), function.intents)
        assertEquals(0, function.span.startLine)
        assertEquals(2, function.span.endLine)
    }

    @Test
    fun mixedFunctionIsNotScaffold() {
        val text =
            """
            fun f(value: String?) {
                requireNotNull(value)
                work()
            }
            fun work() {}
            """.trimIndent()

        assertTrue(analyze(text).none { it.rule == "intent-scaffold-function" })
    }

    @Test
    fun topLevelPropertyCanBeDerivative() {
        val text =
            """
            private val log = LoggerFactory.getLogger(Foo::class.java)
            private val top = "x"
            fun f() {
                log.warn(top)
            }
            """.trimIndent()

        val derivative = analyze(text).first { it.role == Role.DERIVATIVE_USE }
        assertEquals(setOf("LOGGING"), derivative.intents)
    }

    @Test
    fun loggingStateGuardIsConcealed() {
        val text =
            """
            private val log = LoggerFactory.getLogger(Foo::class.java)
            fun f(loggingEnabled: Boolean) {
                if (loggingEnabled) {
                    log.warn("x")
                }
            }
            """.trimIndent()

        val scaffold = analyze(text).first { it.role == Role.SCAFFOLD }
        assertEquals(setOf("LOGGING"), scaffold.intents)
    }
}
