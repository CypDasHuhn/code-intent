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

    override fun typeAt(file: SourceFile, byteOffset: Int): TypeFact? {
        calls++
        return null
    }

    override fun definitionAt(file: SourceFile, byteOffset: Int): SymbolFact? = null
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
        val text = """
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
        assertTrue(sources.any { it.rule == "call-on-logger-symbol" && it.intents == setOf("Logging") })
        assertTrue(sources.any { it.rule == "print" && it.intents == setOf("Logging") })
    }

    @Test
    fun concealsPureLoggingScaffold() {
        val text = """
            private val log = LoggerFactory.getLogger(Foo::class.java)
            fun f() {
                if (1 == 1) {
                    log.warn("x")
                }
            }
        """.trimIndent()

        val scaffold = analyze(text).first { it.role == Role.SCAFFOLD }
        assertEquals(setOf("Logging"), scaffold.intents)
    }

    @Test
    fun mixedScaffoldUsesShowMeaning() {
        val text = """
            private val log = LoggerFactory.getLogger(Foo::class.java)
            fun f() {
                if (1 == 1) {
                    log.warn("x")
                    throw IllegalStateException("y")
                }
            }
        """.trimIndent()

        val scaffold = analyze(text).first { it.role == Role.SCAFFOLD }
        assertEquals(setOf("Logging", "ErrorHandling"), scaffold.intents)
    }

    @Test
    fun untaggedGuardMakesScaffoldVisible() {
        val text = """
            private val log = LoggerFactory.getLogger(Foo::class.java)
            fun f(x: Int) {
                if (x > 0) {
                    log.warn("x")
                }
            }
        """.trimIndent()

        val scaffold = analyze(text).first { it.role == Role.SCAFFOLD }
        assertEquals(setOf("Logging", UNTAGGED_INTENT), scaffold.intents)
    }

    @Test
    fun tagsLoggingCatchAndTryHeader() {
        val text = """
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
        assertEquals(setOf("Logging"), header.intents)
        assertEquals(setOf("Logging"), catch.intents)
        assertTrue(scaffolds.none { it.rule == "intent-scaffold-try" })
    }

    @Test
    fun mixedCatchWithRethrowUsesShowMeaning() {
        val text = """
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

        val catch = analyze(text).first {
            it.role == Role.SCAFFOLD && it.rule == "intent-scaffold-catch"
        }
        assertEquals(setOf("Logging", "ErrorHandling"), catch.intents)
    }

    @Test
    fun literalAssistUnlocksCatch() {
        val text = """
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
        assertEquals(setOf("Logging"), derivative.intents)

        val catch = annotations.first {
            it.role == Role.SCAFFOLD && it.rule == "intent-scaffold-catch"
        }
        assertEquals(setOf("Logging"), catch.intents)
    }

    @Test
    fun unannotatedCallIsNotDerivative() {
        val text = """
            private val log = LoggerFactory.getLogger(Foo::class.java)
            fun text(): String = "x"
            fun f() {
                val message = text()
                log.warn(message)
            }
        """.trimIndent()

        assertTrue(analyze(text).none { it.role == Role.DERIVATIVE_USE })
    }

    @Test
    fun annotatedPureCallIsDerivative() {
        val text = """
            private val log = LoggerFactory.getLogger(Foo::class.java)
            @Pure
            fun text(): String = "x"
            fun f() {
                val message = text()
                log.warn(message)
            }
        """.trimIndent()

        val derivative = analyze(text).first {
            it.role == Role.DERIVATIVE_USE && it.evidence.contains("val message")
        }
        assertEquals(setOf("Logging"), derivative.intents)
    }

    @Test
    fun pureFunctionReferencedOnlyByDerivativeIsDerivative() {
        val text = """
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
        assertTrue(derivatives.filter { it.evidence.contains("fun text") }.all {
            it.intents == setOf("Logging")
        })
    }

    @Test
    fun functionReferencedOutsideDerivativeIsNotPromoted() {
        val text = """
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
        val text = """
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
    fun declarationReferencedOnlyByDerivativeIsDerivative() {
        val text = """
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
        assertTrue(derivatives.all { it.intents == setOf("Logging") })
    }

    @Test
    fun mixedReferencePreventsDerivativePromotion() {
        val text = """
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
        assertEquals(1, derivatives.size)
        assertTrue(derivatives.single().evidence.contains("val message"))
    }

    @Test
    fun deadLocalAddsUntagged() {
        val text = """
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
        val catch = annotations.first {
            it.role == Role.SCAFFOLD && it.rule == "intent-scaffold-catch"
        }
        assertEquals(setOf("Logging", UNTAGGED_INTENT), catch.intents)
    }

    @Test
    fun wholeLoggingTryIsConcealed() {
        val text = """
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
        val whole = annotations.first {
            it.role == Role.SCAFFOLD && it.rule == "intent-scaffold-try"
        }
        assertEquals(setOf("Logging"), whole.intents)
        assertFalse(annotations.any { it.rule == "intent-scaffold-try-header" })
    }

    @Test
    fun classifiesKotlinChecksAsErrorHandling() {
        val text = """
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

        val sources = analyze(text).filter {
            it.role == Role.SOURCE_INTENT && "ErrorHandling" in it.intents
        }
        assertEquals(7, sources.size)
    }

    @Test
    fun nestedElseIfDoesNotStickUntagged() {
        val text = """
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
        assertTrue(scaffolds.all { it.intents == setOf("Logging") }, "scaffolds=$scaffolds")
    }

    @Test
    fun derivativeChainResolvesUpward() {
        val text = """
            private val log = LoggerFactory.getLogger(Foo::class.java)
            fun f() {
                val first = "x"
                val second = first
                log.warn(second)
            }
        """.trimIndent()

        val derivatives = analyze(text).filter { it.role == Role.DERIVATIVE_USE }
        assertEquals(2, derivatives.size)
        assertTrue(derivatives.all { it.intents == setOf("Logging") })
    }

    @Test
    fun pureFunctionBodyBecomesScaffold() {
        val text = """
            fun requireValue(value: String?) {
                requireNotNull(value)
            }
        """.trimIndent()

        val function = analyze(text).first {
            it.role == Role.SCAFFOLD && it.rule == "intent-scaffold-function"
        }
        assertEquals(setOf("ErrorHandling"), function.intents)
        assertEquals(0, function.span.startLine)
        assertEquals(2, function.span.endLine)
    }

    @Test
    fun mixedFunctionIsNotScaffold() {
        val text = """
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
        val text = """
            private val log = LoggerFactory.getLogger(Foo::class.java)
            private val top = "x"
            fun f() {
                log.warn(top)
            }
        """.trimIndent()

        val derivative = analyze(text).first { it.role == Role.DERIVATIVE_USE }
        assertEquals(setOf("Logging"), derivative.intents)
    }

    @Test
    fun loggingStateGuardIsConcealed() {
        val text = """
            private val log = LoggerFactory.getLogger(Foo::class.java)
            fun f(loggingEnabled: Boolean) {
                if (loggingEnabled) {
                    log.warn("x")
                }
            }
        """.trimIndent()

        val scaffold = analyze(text).first { it.role == Role.SCAFFOLD }
        assertEquals(setOf("Logging"), scaffold.intents)
    }
}
