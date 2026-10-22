package dev.cint.rulepacks.tests.intents.mutation.data

import dev.cint.api.RulePack
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.TypeFact
import dev.cint.core.facts.oracle.TypeOracle
import dev.cint.rulepacks.bookkeeping.BookkeepingRulePack
import dev.cint.rulepacks.intents.mutation.DATA_MODIFY_INTENT
import dev.cint.rulepacks.intents.mutation.DATA_MODIFY_RULE_ID
import dev.cint.rulepacks.intents.mutation.MutationRulePack
import dev.cint.rulepacks.intents.structural.localcopy.LocalCopyRulePack
import dev.cint.rulepacks.tests.support.PackAnalysis
import dev.cint.rulepacks.tests.support.analyzePack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MutationDataModifyTypesTest {
    @Test
    fun receiverfulPersistenceWrappersYieldCandidates() {
        val text =
            """
            fun f(user: User) {
                handler.send(user)
                handler.write(user)
                handler.flush()
                handler.commit()
                handler.updateProfile(user)
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text)
        val candidates = analysis.candidates(DATA_MODIFY_INTENT)

        assertEquals(4, candidates.size)
        assertTrue(candidates.all { it.source == "rule:$DATA_MODIFY_RULE_ID" })
        assertTrue(analysis.source(DATA_MODIFY_INTENT).isEmpty())
    }

    @Test
    fun configuredPersistencePrefixesDriveCandidates() {
        val pack =
            MutationRulePack().configure(
                mapOf("kotlin" to mapOf("persistencePrefixes" to listOf("frobnicate"))),
            )
        val text =
            """
            fun f(user: User) {
                handler.frobnicateRecord(user)
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text, pack)

        assertEquals(1, analysis.candidates(DATA_MODIFY_INTENT).size)
        assertTrue(analysis.source(DATA_MODIFY_INTENT).isEmpty())
    }

    @Test
    fun readsOnPersistentReceiversDoNotBecomeDataCandidates() {
        val text =
            """
            fun f(status: Status, key: String) {
                repository.findByStatus(status)
                repository.count()
                file.exists()
                bucket.get(key)
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text)

        analysis.assertNoSource(DATA_MODIFY_INTENT)
        assertTrue(analysis.candidates(DATA_MODIFY_INTENT).isEmpty())
    }

    @Test
    fun genericSessionTableAndQueueTypesAreNotDurable() {
        val cases =
            mapOf(
                "session.save(item)" to "jakarta.servlet.http.HttpSession",
                "table.put(key, item)" to "com.example.Table",
                "queue.send(item)" to "java.util.concurrent.BlockingQueue",
            )

        for ((call, type) in cases) {
            val text = "fun f(item: Item, key: String) { $call }"
            val analysis =
                analyzePack(
                    "kotlin",
                    text,
                    listOf(BookkeepingRulePack(), MutationRulePack(), LocalCopyRulePack()),
                    DataModifyTypeOracle(TypeFact(type)),
                )

            analysis.assertNoSource(DATA_MODIFY_INTENT)
            assertTrue(analysis.candidates(DATA_MODIFY_INTENT).isEmpty(), type)
        }
    }

    @Test
    fun qualifiedDurableSessionAndQueueTypesAreEstablished() {
        val cases =
            mapOf(
                "session.save(item)" to "org.hibernate.Session",
                "queue.send(item)" to "javax.jms.Queue",
            )

        for ((call, type) in cases) {
            val text = "fun f(item: Item) { $call }"
            val analysis =
                analyzePack(
                    "kotlin",
                    text,
                    listOf(BookkeepingRulePack(), MutationRulePack(), LocalCopyRulePack()),
                    DataModifyTypeOracle(TypeFact(type)),
                )

            assertEquals(setOf(call), analysis.texts(DATA_MODIFY_INTENT).toSet(), type)
        }
    }

    @Test
    fun configuredReadPrefixedMutationsAreClassifiedForJavascriptAndTypescript() {
        val text =
            """
            function f() {
                model.findOneAndUpdate({}, { active: true });
                model.findOneAndDelete({});
            }
            """.trimIndent()

        for (language in listOf("javascript", "typescript")) {
            val analysis = analyze(language, text)

            analysis.assertNoSource(DATA_MODIFY_INTENT)
            assertEquals(
                setOf("model.findOneAndUpdate({}, { active: true })", "model.findOneAndDelete({})"),
                analysis.candidates(DATA_MODIFY_INTENT).map { text.substring(it.startByte, it.endByte) }.toSet(),
                language,
            )
        }

        val durable = "function f() { repository.findOneAndUpdate({}, { active: true }); }"
        assertEquals(
            setOf("repository.findOneAndUpdate({}, { active: true })"),
            analyze("javascript", durable).texts(DATA_MODIFY_INTENT).toSet(),
        )
    }

    @Test
    fun configuredReadPrefixedWrappersAreClassified() {
        val pack =
            MutationRulePack().configure(
                mapOf(
                    "kotlin" to
                        mapOf(
                            "persistenceCalls" to listOf("getPersist", "getAndIncrement"),
                        ),
                ),
            )
        val text =
            """
            fun f(item: Item, key: String) {
                handler.getPersist(item)
                handler.getAndIncrement(key)
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text, pack)

        analysis.assertNoSource(DATA_MODIFY_INTENT)
        assertEquals(
            setOf("handler.getPersist(item)", "handler.getAndIncrement(key)"),
            analysis.candidates(DATA_MODIFY_INTENT).map { text.substring(it.startByte, it.endByte) }.toSet(),
        )
    }
}

private class DataModifyTypeOracle(
    private val fact: TypeFact?,
) : TypeOracle {
    override fun typeAt(
        file: SourceFile,
        byteOffset: Int,
    ): TypeFact? = fact
}

private fun analyze(
    language: String,
    text: String,
    pack: RulePack = MutationRulePack(),
): PackAnalysis = analyzePack(language, text, listOf(BookkeepingRulePack(), pack, LocalCopyRulePack()))
