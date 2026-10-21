package dev.cint.rulepacks.tests.intents.structural

import dev.cint.api.CandidateReason
import dev.cint.rulepacks.intents.service.SERVICE_INIT_INTENT
import dev.cint.rulepacks.intents.service.ServiceRulePack
import dev.cint.rulepacks.intents.structural.CHAINING_INTENT
import dev.cint.rulepacks.intents.structural.CHAINING_RULE_ID
import dev.cint.rulepacks.intents.structural.ChainingRulePack
import dev.cint.rulepacks.tests.support.PackAnalysis
import dev.cint.rulepacks.tests.support.analyzePack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun analyze(
    language: String,
    text: String,
    packs: List<dev.cint.api.RulePack> = listOf(ChainingRulePack()),
): PackAnalysis = analyzePack(language, text, packs)

class ChainingRulePackTest {
    @Test
    fun packDeclaresOneOwnedRuleAcrossBundledLanguages() {
        val pack = ChainingRulePack()

        assertEquals("chaining", pack.id)
        assertEquals(setOf("kotlin", "javascript", "typescript", "csharp"), pack.languages)
        val rule = pack.rules().single()
        assertEquals(CHAINING_RULE_ID, rule.id)
        assertEquals(CHAINING_INTENT, rule.owns)
        assertTrue(rule.reads.isEmpty())
    }

    @Test
    fun fluentChainsTagTheOuterCallOnly() {
        val text = "fun build(): Service { return Factory.For().Use().Build() }"

        val analysis = analyze("kotlin", text)

        assertEquals(listOf("Factory.For().Use().Build()"), analysis.texts(CHAINING_INTENT))
        assertTrue(analysis.candidates(CHAINING_INTENT).isEmpty())
    }

    @Test
    fun fluentChainsEstablishAcrossLanguages() {
        val cases =
            listOf(
                "javascript" to "function build() { return Factory.For().Use().Build(); }",
                "typescript" to "function build(): Service { return Factory.For().Use().Build(); }",
                "csharp" to "class C { Service Build() { return Factory.For().Use().Build(); } }",
            )

        for ((language, text) in cases) {
            val texts = analyze(language, text).texts(CHAINING_INTENT)
            assertEquals(listOf("Factory.For().Use().Build()"), texts, language)
        }
    }

    @Test
    fun booleanOrOperandChainsAreNotChaining() {
        val text =
            """
            fun valid(): Boolean {
                return Factory.For().Use().Build().isReady() || Factory.For().Use().Build().isReady()
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text)

        assertTrue(analysis.source(CHAINING_INTENT).isEmpty())
        assertTrue(analysis.candidates(CHAINING_INTENT).isEmpty())
    }

    @Test
    fun booleanOrReceiverIsNotAChain() {
        val text = "fun valid(a: Boolean, b: Boolean): Service { return (a || b).Use().Build() }"

        val analysis = analyze("kotlin", text)

        assertTrue(analysis.source(CHAINING_INTENT).isEmpty())
        assertTrue(analysis.candidates(CHAINING_INTENT).isEmpty())
    }

    @Test
    fun booleanOperatorsInsideArgumentsDoNotSuppressChains() {
        val text =
            """
            fun stringArgument(): Service { return Factory.For("a||b").Use().Build() }
            fun booleanArgument(flag: Boolean, other: Boolean): Service {
                return Factory.For(flag || other).Use().Build()
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text)

        assertEquals(
            setOf(
                "Factory.For(\"a||b\").Use().Build()",
                "Factory.For(flag || other).Use().Build()",
            ),
            analysis.texts(CHAINING_INTENT).toSet(),
        )
    }

    @Test
    fun singleCallsAndPropertyAccessAreNotChains() {
        val text =
            """
            fun f(service: Service) {
                Factory.For()
                service.execute()
                service.dependency.execute()
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text)

        assertTrue(analysis.source(CHAINING_INTENT).isEmpty())
        assertTrue(analysis.candidates(CHAINING_INTENT).isEmpty())
    }

    @Test
    fun indexAndArgumentCallsDoNotBecomeLinks() {
        val kotlin =
            """
            fun indexed(a: List<Service>, b: () -> Int) {
                a[b()].c()
            }
            fun binary(a: List<Service>, b: () -> Int) {
                (a + b()).c()
            }
            """.trimIndent()
        val javascript =
            """
            function f(a, b) {
                a[b()].c();
                (a + b()).c();
            }
            """.trimIndent()

        for ((language, text) in listOf("kotlin" to kotlin, "javascript" to javascript)) {
            val analysis = analyze(language, text)
            assertTrue(analysis.source(CHAINING_INTENT).isEmpty(), "$language: ${analysis.texts(CHAINING_INTENT)}")
            assertTrue(analysis.candidates(CHAINING_INTENT).isEmpty(), "$language candidates")
        }
    }

    @Test
    fun multiLinkChainsKeepAnIndexOrBinaryBase() {
        val text =
            """
            fun indexed(a: List<Service>, b: () -> Int): Service {
                return a[b()].c().d()
            }
            fun binary(a: List<Service>, b: () -> Int): Service {
                return (a + b()).c().d()
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text)

        assertEquals(
            setOf("a[b()].c().d()", "(a + b()).c().d()"),
            analysis.texts(CHAINING_INTENT).toSet(),
        )
        assertTrue(analysis.candidates(CHAINING_INTENT).isEmpty())
    }

    @Test
    fun arrayLiteralBaseDoesNotFabricateALink() {
        val pack = ChainingRulePack().configure(mapOf("javascript" to mapOf("minimumLinks" to 3)))
        val text = "function f() { [foo()].map(x => x).first(); }"

        val analysis = analyze("javascript", text, listOf(pack))

        assertTrue(analysis.source(CHAINING_INTENT).isEmpty())
        assertTrue(analysis.candidates(CHAINING_INTENT).isEmpty())
    }

    @Test
    fun ambiguousConditionalReceiverStaysCandidate() {
        val text = "function build(flag) { return (flag ? Factory.For() : Other).Use().Build(); }"

        val analysis = analyze("javascript", text)
        val candidate = analysis.candidates(CHAINING_INTENT).single()

        assertTrue(analysis.source(CHAINING_INTENT).isEmpty())
        assertEquals(CandidateReason.LOW_CONFIDENCE, candidate.reason)
        assertEquals("rule:$CHAINING_RULE_ID", candidate.source)
        assertEquals("(flag ? Factory.For() : Other).Use().Build()", text.substring(candidate.startByte, candidate.endByte))
    }

    @Test
    fun chainsInsideOrOperandArgumentsAreNotSuppressed() {
        val text =
            """
            fun use(value: Service): Boolean = true
            fun fallback(): Boolean = false
            fun argument(): Boolean {
                return use(builder.For().Use().Build()) || fallback()
            }
            fun lambda(): Boolean {
                return run { builder.For().Use().Build() } || fallback()
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text)

        assertEquals(2, analysis.source(CHAINING_INTENT).size)
        assertTrue(analysis.texts(CHAINING_INTENT).all { "builder.For().Use().Build()" in it })
    }

    @Test
    fun configuredMinimumControlsLinks() {
        val pack = ChainingRulePack().configure(mapOf("kotlin" to mapOf("minimumLinks" to 3)))
        val text =
            """
            fun a(): Service { return Factory.For().Use() }
            fun b(): Service { return Factory.For().Use().Build() }
            """.trimIndent()

        val analysis = analyze("kotlin", text, listOf(pack))

        assertEquals(listOf("Factory.For().Use().Build()"), analysis.texts(CHAINING_INTENT))
    }

    @Test
    fun nonAsciiReceiversKeepDirectLinks() {
        val text = "fun build(): Service { return Factory.For(\"café\").Use().Build() }"

        val analysis = analyze("kotlin", text)

        assertEquals(listOf("Factory.For(\"café\").Use().Build()"), analysis.texts(CHAINING_INTENT))
        assertTrue(analysis.candidates(CHAINING_INTENT).isEmpty())
    }

    @Test
    fun chainingCoexistsWithServiceInit() {
        val service =
            ServiceRulePack().configure(
                mapOf("kotlin" to mapOf("initCalls" to listOf("For"))),
            )
        val text = "fun build(): Service { return DataAccessServiceFactory.For().Use().Build() }"

        val analysis = analyze("kotlin", text, listOf(service, ChainingRulePack()))

        assertEquals(listOf("DataAccessServiceFactory.For().Use().Build()"), analysis.texts(CHAINING_INTENT))
        assertTrue(analysis.texts(SERVICE_INIT_INTENT).any { "DataAccessServiceFactory.For()" in it })
    }
}
