package dev.cint.rulepacks.tests.intents.service

import dev.cint.rulepacks.intents.service.SERVICE_DELEGATION_INTENT
import dev.cint.rulepacks.intents.service.SERVICE_INIT_INTENT
import dev.cint.rulepacks.intents.service.ServiceRulePack
import dev.cint.rulepacks.tests.support.analyzePack
import dev.cint.rulepacks.tests.support.fixture
import dev.cint.test.fixtures.text
import kotlin.test.Test
import kotlin.test.assertTrue

class ServiceFixtureTest {
    @Test
    fun testingGroundsFixtureCoversDelegationAndInit() {
        val source = fixture("kotlin/ServiceIntents.kt")
        val analysis = analyzePack("kotlin", source, listOf(ServiceRulePack()))

        val delegation = analysis.source(SERVICE_DELEGATION_INTENT).map { it.text(source) }
        val init = analysis.source(SERVICE_INIT_INTENT).map { it.text(source) }

        assertTrue("userService.find(id)" in delegation)
        assertTrue("userService.save(value)" in delegation)
        assertTrue(init.any { it.contains("HttpClient()") })
        assertTrue(init.any { it.contains("client.connect()") })
    }
}
