package dev.cint.rulepacks.tests.intents.mutation

import dev.cint.api.CandidateReason
import dev.cint.api.RulePack
import dev.cint.rulepacks.intents.mutation.DATA_MODIFY_INTENT
import dev.cint.rulepacks.intents.mutation.DATA_MODIFY_RULE_ID
import dev.cint.rulepacks.intents.mutation.MutationRulePack
import dev.cint.rulepacks.tests.support.PackAnalysis
import dev.cint.rulepacks.tests.support.analyzePack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MutationDataModifyRuleTest {
    @Test
    fun configuredPersistenceCallsEmitDataModify() {
        val text =
            """
            fun persist(user: User, id: String, event: Event) {
                repository.save(user)
                userRepository.update(user)
                dao.delete(id)
                Files.write(path, bytes)
                file.writeText("payload")
                eventBus.publish(event)
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text)

        assertEquals(
            setOf(
                "repository.save(user)",
                "userRepository.update(user)",
                "dao.delete(id)",
                "Files.write(path, bytes)",
                "file.writeText(\"payload\")",
                "eventBus.publish(event)",
            ),
            analysis.texts(DATA_MODIFY_INTENT).toSet(),
        )
        assertTrue(analysis.source(DATA_MODIFY_INTENT).all { it.rule == DATA_MODIFY_RULE_ID })
    }

    @Test
    fun persistenceCallsWorkAcrossLanguages() {
        val cases =
            mapOf(
                "kotlin" to "fun f() { repository.save(user) }",
                "javascript" to "function f() { repository.save(user); fs.writeFileSync(path, data); }",
                "typescript" to "function f(): void { repository.save(user); }",
                "csharp" to
                    """
                    class C {
                        void F() {
                            repository.Save(user);
                            File.WriteAllText(path, text);
                        }
                    }
                    """.trimIndent(),
            )

        for ((language, text) in cases) {
            val analysis = analyze(language, text)
            assertTrue(analysis.source(DATA_MODIFY_INTENT).isNotEmpty(), "$language persistence")
        }
    }

    @Test
    fun sameCallShapeOnAnInMemoryReceiverIsNotEstablished() {
        val text =
            """
            fun f(user: User) {
                val cache = Cache()
                cache.save(user)
                val values = mutableListOf<User>()
                values.add(user)
            }
            """.trimIndent()

        analyze("kotlin", text).assertNoSource(DATA_MODIFY_INTENT)
    }

    @Test
    fun configuredCallsAndQualifiersAreHonoured() {
        val pack =
            MutationRulePack().configure(
                mapOf(
                    "kotlin" to
                        mapOf(
                            "persistenceCalls" to listOf("persistRecord"),
                            "persistenceQualifiers" to listOf("store"),
                        ),
                ),
            )
        val text =
            """
            fun f(user: User) {
                store.persistRecord(user)
                cache.persistRecord(user)
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text, pack)

        assertEquals(setOf("store.persistRecord(user)"), analysis.texts(DATA_MODIFY_INTENT).toSet())
    }

    @Test
    fun wrappersAndHiddenPersistenceYieldCandidates() {
        val text =
            """
            fun f(user: User) {
                handler.persistAll(user)
                repository.syncAll()
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text)
        val candidates = analysis.candidates(DATA_MODIFY_INTENT)

        assertEquals(2, candidates.size)
        assertTrue(candidates.all { it.reason == CandidateReason.PACK_DOMAIN })
        assertTrue(candidates.all { it.source == "rule:$DATA_MODIFY_RULE_ID" })
        assertTrue(candidates.all { it.aiEligible })
        assertTrue(analysis.source(DATA_MODIFY_INTENT).isEmpty())
    }

    private fun analyze(
        language: String,
        text: String,
        pack: RulePack = MutationRulePack(),
    ): PackAnalysis = analyzePack(language, text, listOf(pack))
}
