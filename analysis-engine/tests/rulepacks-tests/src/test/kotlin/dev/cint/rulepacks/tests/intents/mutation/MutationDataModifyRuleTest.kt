package dev.cint.rulepacks.tests.intents.mutation

import dev.cint.api.CandidateReason
import dev.cint.api.RulePack
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.TypeFact
import dev.cint.core.facts.oracle.TypeOracle
import dev.cint.rulepacks.bookkeeping.BookkeepingRulePack
import dev.cint.rulepacks.intents.mutation.DATA_MODIFY_INTENT
import dev.cint.rulepacks.intents.mutation.DATA_MODIFY_RULE_ID
import dev.cint.rulepacks.intents.mutation.MutationRulePack
import dev.cint.rulepacks.tests.support.PackAnalysis
import dev.cint.rulepacks.tests.support.analyzePack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FixedOracle(
    private val fact: TypeFact?,
) : TypeOracle {
    override fun typeAt(
        file: SourceFile,
        byteOffset: Int,
    ): TypeFact? = fact
}

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

        val analysis = analyze("kotlin", text)
        analysis.assertNoSource(DATA_MODIFY_INTENT)
        assertTrue(analysis.candidates(DATA_MODIFY_INTENT).isEmpty())
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

    @Test
    fun receiverlessCallsAreCandidatesUnlessAPathIsConfigured() {
        val text =
            """
            fun f(user: User, id: String) {
                save(user)
                update(id)
                send(user)
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text)

        analysis.assertNoSource(DATA_MODIFY_INTENT)
        assertEquals(3, analysis.candidates(DATA_MODIFY_INTENT).size)

        val configured =
            MutationRulePack().configure(
                mapOf("kotlin" to mapOf("persistencePaths" to listOf("save"))),
            )
        assertEquals(
            setOf("save(user)"),
            analyze("kotlin", text, configured).texts(DATA_MODIFY_INTENT).toSet(),
        )
    }

    @Test
    fun inMemoryStreamsAndCollectionsAreNotDataModify() {
        val text =
            """
            fun f(channel: SendChannel<Int>, out: ByteArrayOutputStream, writer: StringWriter) {
                channel.send(1)
                out.write(bytes)
                writer.append("x")
            }
            """.trimIndent()

        val analysis =
            analyzePack(
                "kotlin",
                text,
                listOf(BookkeepingRulePack(), MutationRulePack()),
                FixedOracle(TypeFact("kotlinx.coroutines.channels.SendChannel")),
            )

        analysis.assertNoSource(DATA_MODIFY_INTENT)
        assertTrue(analysis.candidates(DATA_MODIFY_INTENT).isEmpty())
    }

    @Test
    fun durablePutAndMessagingTemplatesAreEstablished() {
        val text =
            """
            fun f(item: Item, event: Event) {
                bucket.put("key", item)
                kafkaTemplate.send(event)
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text)

        assertEquals(
            setOf("bucket.put(\"key\", item)", "kafkaTemplate.send(event)"),
            analysis.texts(DATA_MODIFY_INTENT).toSet(),
        )
    }

    @Test
    fun csharpAsyncFileWritesAreEstablished() {
        val text =
            """
            class C {
                async Task F() {
                    await File.WriteAllTextAsync(path, text);
                    await File.AppendAllTextAsync(path, text);
                }
            }
            """.trimIndent()

        val analysis = analyze("csharp", text)

        assertEquals(
            setOf("File.WriteAllTextAsync(path, text)", "File.AppendAllTextAsync(path, text)"),
            analysis.texts(DATA_MODIFY_INTENT).toSet(),
        )
    }

    @Test
    fun configuredPersistencePathsEstablishReceiverlessCalls() {
        val pack =
            MutationRulePack().configure(
                mapOf("kotlin" to mapOf("persistencePaths" to listOf("legacyPersist"))),
            )
        val text =
            """
            fun f(item: Item) {
                legacyPersist(item)
            }
            """.trimIndent()

        assertEquals(setOf("legacyPersist(item)"), analyze("kotlin", text, pack).texts(DATA_MODIFY_INTENT).toSet())
    }

    private fun analyze(
        language: String,
        text: String,
        pack: RulePack = MutationRulePack(),
    ): PackAnalysis = analyzePack(language, text, listOf(BookkeepingRulePack(), pack))
}
