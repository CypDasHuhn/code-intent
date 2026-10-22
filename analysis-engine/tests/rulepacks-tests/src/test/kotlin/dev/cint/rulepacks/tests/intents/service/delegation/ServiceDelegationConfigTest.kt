package dev.cint.rulepacks.tests.intents.service.delegation

import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.TypeFact
import dev.cint.core.facts.oracle.TypeOracle
import dev.cint.rulepacks.bookkeeping.BookkeepingRulePack
import dev.cint.rulepacks.intents.mutation.DATA_MODIFY_INTENT
import dev.cint.rulepacks.intents.mutation.MutationRulePack
import dev.cint.rulepacks.intents.service.SERVICE_DELEGATION_INTENT
import dev.cint.rulepacks.intents.service.ServiceConfig
import dev.cint.rulepacks.intents.service.ServiceRulePack
import dev.cint.rulepacks.intents.structural.localcopy.LocalCopyRulePack
import dev.cint.rulepacks.tests.support.analyzePack
import dev.cint.test.fixtures.text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServiceDelegationConfigTest {
    @Test
    fun durableCallbackDefaultsAreNotGenericDelegationCandidates() {
        val source =
            """
            class NeighboursCli {
                fun publish(move: (Path, Path) -> Unit = Files::move, notify: (Path) -> Unit) {
                    move(Paths.get("a"), Paths.get("b"))
                    notify(Paths.get("a"))
                }
            }
            """.trimIndent()

        val analysis =
            analyzePack("kotlin", source, listOf(BookkeepingRulePack(), MutationRulePack(), LocalCopyRulePack(), ServiceRulePack()))

        assertEquals(
            setOf("move(Paths.get(\"a\"), Paths.get(\"b\"))"),
            analysis.texts(DATA_MODIFY_INTENT).toSet(),
        )
        assertEquals(
            setOf("notify(Paths.get(\"a\"))"),
            analysis.candidates(SERVICE_DELEGATION_INTENT).map { source.substring(it.startByte, it.endByte) }.toSet(),
        )
    }

    @Test
    fun durableCallbackDefaultWithoutMutationOwnerRemainsAChainableCandidate() {
        val source =
            """
            fun publish(move: (Path, Path) -> Unit = Files::move) {
                move(Paths.get("a"), Paths.get("b"))
            }
            """.trimIndent()

        val analysis = analyzePack("kotlin", source, listOf(ServiceRulePack()))

        assertTrue(analysis.source(SERVICE_DELEGATION_INTENT).isEmpty())
        assertEquals(
            setOf("move(Paths.get(\"a\"), Paths.get(\"b\"))"),
            analysis.candidates(SERVICE_DELEGATION_INTENT).map { source.substring(it.startByte, it.endByte) }.toSet(),
        )
    }

    @Test
    fun nonServiceThinMethodDoesNotEmitDelegation() {
        val source =
            """
            fun f() {
                helper()
            }
            fun helper() {}
            """.trimIndent()

        val analysis = analyzePack("kotlin", source, listOf(ServiceRulePack()))

        assertTrue(analysis.source(SERVICE_DELEGATION_INTENT).isEmpty())
        assertTrue(analysis.candidates(SERVICE_DELEGATION_INTENT).isEmpty())
    }

    @Test
    fun receiverTypeOracleCanEstablishDelegation() {
        val source =
            """
            fun find(id: String) {
                thing.find(id)
            }
            """.trimIndent()
        val oracle = FixedOracle(TypeFact("com.acme.UserService", "lsp", 0.9))

        val analysis = analyzePack("kotlin", source, listOf(ServiceRulePack()), oracle)

        assertEquals("thing.find(id)", analysis.source(SERVICE_DELEGATION_INTENT).single().text(source))
    }

    @Test
    fun configurePreservesExistingPackConfigAndAppliesNewConfig() {
        val base = ServiceConfig.from(mapOf("kotlin" to mapOf("serviceNames" to listOf("primary"))))
        val pack =
            ServiceRulePack(base)
                .configure(mapOf("kotlin" to mapOf("delegationCalls" to listOf("forwardTo"))))
        val source =
            """
            fun first() {
                primary.find(1)
            }
            fun second() {
                forwardTo(2)
            }
            """.trimIndent()

        val analysis = analyzePack("kotlin", source, listOf(pack))

        assertEquals(
            setOf("primary.find(1)", "forwardTo(2)"),
            analysis.source(SERVICE_DELEGATION_INTENT).map { it.text(source) }.toSet(),
        )
    }

    @Test
    fun configuredAndAdditiveServiceNamesBothEmitDelegation() {
        val base = ServiceConfig.from(mapOf("kotlin" to mapOf("serviceNames" to listOf("primary"))))
        val pack =
            ServiceRulePack(base)
                .configureAdditive(mapOf("kotlin" to mapOf("serviceNames" to listOf("secondary"))))
        val source =
            """
            fun first() {
                primary.find(1)
            }
            fun second() {
                secondary.find(2)
            }
            """.trimIndent()

        val analysis = analyzePack("kotlin", source, listOf(pack))

        assertEquals(
            setOf("primary.find(1)", "secondary.find(2)"),
            analysis.source(SERVICE_DELEGATION_INTENT).map { it.text(source) }.toSet(),
        )
    }
}

private class FixedOracle(
    private val fact: TypeFact?,
) : TypeOracle {
    override fun typeAt(
        file: SourceFile,
        byteOffset: Int,
    ): TypeFact? = fact
}
