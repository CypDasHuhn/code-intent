package dev.cint.rulepacks.tests.intents.service

import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.OracleBudget
import dev.cint.core.facts.oracle.OracleSession
import dev.cint.core.facts.oracle.TypeFact
import dev.cint.core.facts.oracle.TypeOracle
import dev.cint.rulepacks.intents.service.SERVICE_DELEGATION_INTENT
import dev.cint.rulepacks.intents.service.ServiceRulePack
import dev.cint.rulepacks.tests.support.analyzePack
import dev.cint.rulepacks.tests.support.parsePackInput
import dev.cint.test.fixtures.text
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServiceBudgetTest {
    @Test
    fun manyNonTypeableReceiversDoNotStarveALaterTypedServiceCall() {
        val text =
            """
            fun unrelated() {
                "text1".get(1)
                "text2".get(2)
                "text3".get(3)
                "text4".get(4)
                "text5".get(5)
            }
            fun find(id: String) {
                thing.find(id)
            }
            """.trimIndent()
        val input = parsePackInput("kotlin", text)
        val typedOffset =
            input.syntax
                .calls(input.tree)
                .first { it.callee == "find" && it.receiver?.text == "thing" }
                .let { input.syntax.receiverOffset(requireNotNull(it.receiver)) }
        val oracle =
            object : TypeOracle {
                var calls = 0

                override fun typeAt(
                    file: SourceFile,
                    byteOffset: Int,
                ): TypeFact? {
                    calls++
                    if (byteOffset != typedOffset) throw TimeoutException("unrelated receiver")
                    return TypeFact("com.acme.UserService")
                }
            }
        val session = OracleSession(oracle, OracleBudget(maxTimeouts = 1, maxFailures = 1, maxElapsedMs = 60_000))
        val analysis = analyzePack(input, listOf(ServiceRulePack()), session)

        assertEquals(1, oracle.calls)
        assertTrue(analysis.source(SERVICE_DELEGATION_INTENT).any { it.text(input.source.text) == "thing.find(id)" })
    }
}
