package dev.cint.suggest.session

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.cint.api.Span
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnnotationSessionTest {
    private val mapper = jacksonObjectMapper()
    private val text = "val match = Regex(\"cint\").find(text)"
    private val findStart = text.indexOf("find(text)")
    private val findEnd = findStart + "find(text)".length
    private val callStart = text.indexOf("Regex")
    private val callEnd = findEnd

    @Test
    fun toolLoopSubmitsSubSpansAndReportsCoverage() {
        val units = listOf(unit("u0", 0, text.length), unit("u1", callStart, callEnd), unit("u2", findStart, findEnd))
        val provider =
            scripted { turn, _ ->
                if (turn == 0) {
                    AgentTurn.Calls(listOf(ToolCall("c1", "get_expression", mapper.writeValueAsString(mapOf("unitId" to "u1")))))
                } else {
                    AgentTurn.Calls(
                        listOf(
                            ToolCall(
                                "c2",
                                "submit_verdicts",
                                mapper.writeValueAsString(
                                    mapOf(
                                        "verdicts" to
                                            listOf(
                                                mapOf(
                                                    "unitId" to "u1",
                                                    "add" to
                                                        listOf(
                                                            mapOf(
                                                                "intent" to "SEARCH",
                                                                "startByte" to findStart,
                                                                "endByte" to findEnd,
                                                                "confidence" to 0.61,
                                                                "rationale" to "regex find searches the text",
                                                            ),
                                                        ),
                                                ),
                                                mapOf("unitId" to "u2", "verdict" to "none"),
                                            ),
                                    ),
                                ),
                            ),
                        ),
                    )
                }
            }
        val progress = mutableListOf<SessionProgress>()
        val result = AnnotationSession(provider).run(document(units), SessionLimits(), progress::add)

        assertEquals(3, result.total)
        assertEquals(2, result.graded)
        assertEquals(listOf("u0"), result.uncovered)
        assertEquals(1, result.additions)
        assertEquals(2, result.toolCalls)
        val candidate = result.candidates.single()
        assertEquals(setOf("SEARCH"), candidate.intents)
        assertEquals("ai:scripted", candidate.source)
        assertEquals(findStart, candidate.startByte)
        assertEquals(findEnd, candidate.endByte)
        assertEquals(0.61, candidate.confidence)
        assertTrue(progress.any { it.graded == 0 && it.total == 3 })
        assertTrue(progress.any { it.graded == 2 })
    }

    @Test
    fun invalidAddsAreDroppedWithoutFailingThePass() {
        val units = listOf(unit("u0", 0, text.length))
        val provider =
            scripted { _, _ ->
                AgentTurn.Calls(
                    listOf(
                        ToolCall(
                            "c1",
                            "submit_verdicts",
                            mapper.writeValueAsString(
                                mapOf(
                                    "verdicts" to
                                        listOf(
                                            mapOf(
                                                "unitId" to "u0",
                                                "add" to
                                                    listOf(
                                                        mapOf(
                                                            "intent" to "UNKNOWN",
                                                            "startByte" to findStart,
                                                            "endByte" to findEnd,
                                                            "confidence" to 0.5,
                                                        ),
                                                        mapOf(
                                                            "intent" to "SEARCH",
                                                            "startByte" to -1,
                                                            "endByte" to findEnd,
                                                            "confidence" to 0.5,
                                                        ),
                                                        mapOf(
                                                            "intent" to "SEARCH",
                                                            "startByte" to findStart,
                                                            "endByte" to findEnd,
                                                            "confidence" to 2.0,
                                                        ),
                                                    ),
                                            ),
                                        ),
                                ),
                            ),
                        ),
                    ),
                )
            }
        val result = AnnotationSession(provider).run(document(units), SessionLimits())

        assertEquals(1, result.graded)
        assertTrue(result.candidates.isEmpty())
        assertFalse(result.incomplete)
    }

    @Test
    fun duplicateExistingAndNestedSameIntentCollapse() {
        val existing =
            SessionAnnotation(
                intents = setOf("LOGGING"),
                startByte = findStart,
                endByte = findEnd,
                span = SessionText.spanFor(text, findStart, findEnd)!!,
                source = "treesitter",
                confidence = 1.0,
                role = "SOURCE_INTENT",
            )
        val unit =
            unit("u0", 0, text.length).copy(existingAnnotations = listOf(existing))
        val provider =
            scripted { _, _ ->
                AgentTurn.Calls(
                    listOf(
                        ToolCall(
                            "c1",
                            "submit_verdicts",
                            mapper.writeValueAsString(
                                mapOf(
                                    "verdicts" to
                                        listOf(
                                            mapOf(
                                                "unitId" to "u0",
                                                "add" to
                                                    listOf(
                                                        mapOf(
                                                            "intent" to "LOGGING",
                                                            "startByte" to findStart,
                                                            "endByte" to findEnd,
                                                            "confidence" to 0.5,
                                                        ),
                                                        mapOf(
                                                            "intent" to "SEARCH",
                                                            "startByte" to callStart,
                                                            "endByte" to callEnd,
                                                            "confidence" to 0.6,
                                                        ),
                                                        mapOf(
                                                            "intent" to "SEARCH",
                                                            "startByte" to findStart,
                                                            "endByte" to findEnd,
                                                            "confidence" to 0.7,
                                                        ),
                                                    ),
                                            ),
                                        ),
                                ),
                            ),
                        ),
                    ),
                )
            }
        val result = AnnotationSession(provider).run(document(listOf(unit)), SessionLimits())

        assertEquals(1, result.candidates.size)
        assertEquals(setOf("SEARCH"), result.candidates.single().intents)
        assertEquals(callStart, result.candidates.single().startByte)
    }

    @Test
    fun budgetExhaustionPublishesNothingAndReportsUncoveredUnits() {
        val units = listOf(unit("u0", 0, text.length), unit("u1", callStart, callEnd))
        var calls = 0
        val provider =
            scripted { _, _ ->
                calls += 1
                AgentTurn.Message("ignored")
            }
        val result =
            AnnotationSession(provider).run(
                document(units),
                SessionLimits(maxTurns = 0),
            )

        assertEquals(0, calls)
        assertTrue(result.budgetExhausted)
        assertEquals(0, result.graded)
        assertEquals(listOf("u0", "u1"), result.uncovered)
        assertTrue(result.candidates.isEmpty())
    }

    @Test
    fun missingUnknownConflictingAndEmptyVerdictsStayUncovered() {
        val units = listOf(unit("u0", 0, text.length))
        val provider =
            scripted { _, _ ->
                AgentTurn.Calls(
                    listOf(
                        ToolCall(
                            "c1",
                            "submit_verdicts",
                            mapper.writeValueAsString(
                                mapOf(
                                    "verdicts" to
                                        listOf(
                                            mapOf("unitId" to "u0"),
                                            mapOf("unitId" to "u0", "verdict" to "bogus"),
                                            mapOf(
                                                "unitId" to "u0",
                                                "verdict" to "none",
                                                "add" to
                                                    listOf(
                                                        mapOf(
                                                            "intent" to "SEARCH",
                                                            "startByte" to findStart,
                                                            "endByte" to findEnd,
                                                            "confidence" to 0.5,
                                                        ),
                                                    ),
                                            ),
                                            mapOf("unitId" to "u0", "add" to emptyList<Any>()),
                                        ),
                                ),
                            ),
                        ),
                    ),
                )
            }
        val result = AnnotationSession(provider).run(document(units), SessionLimits())

        assertEquals(0, result.graded)
        assertEquals(listOf("u0"), result.uncovered)
        assertTrue(result.candidates.isEmpty())
    }

    @Test
    fun explicitNoneAndAddOnlyVerdictsCountAsGraded() {
        val units = listOf(unit("u0", 0, text.length), unit("u1", callStart, callEnd))
        val provider =
            scripted { _, _ ->
                AgentTurn.Calls(
                    listOf(
                        ToolCall(
                            "c1",
                            "submit_verdicts",
                            mapper.writeValueAsString(
                                mapOf(
                                    "verdicts" to
                                        listOf(
                                            mapOf("unitId" to "u0", "verdict" to "none"),
                                            mapOf(
                                                "unitId" to "u1",
                                                "add" to
                                                    listOf(
                                                        mapOf(
                                                            "intent" to "SEARCH",
                                                            "startByte" to callStart,
                                                            "endByte" to callEnd,
                                                            "confidence" to 0.5,
                                                        ),
                                                    ),
                                            ),
                                        ),
                                ),
                            ),
                        ),
                    ),
                )
            }
        val result = AnnotationSession(provider).run(document(units), SessionLimits())

        assertEquals(2, result.graded)
        assertTrue(result.uncovered.isEmpty())
        assertEquals(1, result.additions)
    }

    @Test
    fun removeAndAddFormOneGroupedProposal() {
        val existing =
            SessionAnnotation(
                intents = setOf("LOGGING"),
                startByte = findStart,
                endByte = findEnd,
                span = SessionText.spanFor(text, findStart, findEnd)!!,
                source = "treesitter",
                confidence = 1.0,
                role = "SOURCE_INTENT",
            )
        val unit = unit("u0", 0, text.length).copy(existingAnnotations = listOf(existing))
        val provider =
            scripted { _, _ ->
                AgentTurn.Calls(
                    listOf(
                        ToolCall(
                            "c1",
                            "submit_verdicts",
                            mapper.writeValueAsString(
                                mapOf(
                                    "verdicts" to
                                        listOf(
                                            mapOf(
                                                "unitId" to "u0",
                                                "remove" to
                                                    listOf(
                                                        mapOf(
                                                            "intent" to "LOGGING",
                                                            "startByte" to findStart,
                                                            "endByte" to findEnd,
                                                            "rationale" to "not a log call",
                                                        ),
                                                    ),
                                                "add" to
                                                    listOf(
                                                        mapOf(
                                                            "intent" to "SEARCH",
                                                            "startByte" to findStart,
                                                            "endByte" to findEnd,
                                                            "confidence" to 0.66,
                                                            "rationale" to "regex find searches",
                                                        ),
                                                    ),
                                            ),
                                        ),
                                ),
                            ),
                        ),
                    ),
                )
            }
        val result = AnnotationSession(provider).run(document(listOf(unit)), SessionLimits())

        assertEquals(1, result.graded)
        assertTrue(result.candidates.isEmpty())
        val proposal = result.proposals.single()
        assertEquals(listOf("REMOVE", "ADD"), proposal.operations.map { it.kind.name })
        assertEquals(setOf("LOGGING"), proposal.removes.map { it.intent }.toSet())
        assertEquals(setOf("SEARCH"), proposal.adds.map { it.intent }.toSet())
        assertEquals(0.66, proposal.adds.single().confidence)
        assertEquals("treesitter", proposal.removes.single().source)
    }

    @Test
    fun narrowingProposalRemovesOuterSpanAndAddsInnerSpan() {
        val existing =
            SessionAnnotation(
                intents = setOf("LOGGING"),
                startByte = callStart,
                endByte = callEnd,
                span = SessionText.spanFor(text, callStart, callEnd)!!,
                source = "treesitter",
                confidence = 1.0,
                role = "SOURCE_INTENT",
            )
        val unit = unit("u0", 0, text.length).copy(existingAnnotations = listOf(existing))
        val provider =
            scripted { _, _ ->
                AgentTurn.Calls(
                    listOf(
                        ToolCall(
                            "c1",
                            "submit_verdicts",
                            mapper.writeValueAsString(
                                mapOf(
                                    "verdicts" to
                                        listOf(
                                            mapOf(
                                                "unitId" to "u0",
                                                "remove" to
                                                    listOf(
                                                        mapOf(
                                                            "intent" to "LOGGING",
                                                            "startByte" to callStart,
                                                            "endByte" to callEnd,
                                                        ),
                                                    ),
                                                "add" to
                                                    listOf(
                                                        mapOf(
                                                            "intent" to "SEARCH",
                                                            "startByte" to findStart,
                                                            "endByte" to findEnd,
                                                            "confidence" to 0.6,
                                                        ),
                                                    ),
                                            ),
                                        ),
                                ),
                            ),
                        ),
                    ),
                )
            }
        val result = AnnotationSession(provider).run(document(listOf(unit)), SessionLimits())

        val proposal = result.proposals.single()
        assertEquals(listOf(callStart, callEnd), proposal.removes.single().let { listOf(it.startByte, it.endByte) })
        assertEquals(listOf(findStart, findEnd), proposal.adds.single().let { listOf(it.startByte, it.endByte) })
    }

    @Test
    fun userAnnotationsAndDirectivesCannotBeRemoved() {
        val userLogging =
            SessionAnnotation(
                intents = setOf("LOGGING"),
                startByte = findStart,
                endByte = findEnd,
                span = SessionText.spanFor(text, findStart, findEnd)!!,
                source = "user",
                confidence = 1.0,
                role = "SOURCE_INTENT",
            )
        val pure =
            SessionAnnotation(
                intents = setOf("PURE"),
                startByte = callStart,
                endByte = callEnd,
                span = SessionText.spanFor(text, callStart, callEnd)!!,
                source = "treesitter",
                confidence = 1.0,
                role = "SOURCE_INTENT",
            )
        val readOnly =
            SessionAnnotation(
                intents = setOf("READONLY"),
                startByte = findStart,
                endByte = findEnd,
                span = SessionText.spanFor(text, findStart, findEnd)!!,
                source = "treesitter",
                confidence = 1.0,
                role = "SOURCE_INTENT",
            )
        val unit =
            unit("u0", 0, text.length).copy(existingAnnotations = listOf(userLogging, pure, readOnly))
        val provider =
            scripted { _, _ ->
                AgentTurn.Calls(
                    listOf(
                        ToolCall(
                            "c1",
                            "submit_verdicts",
                            mapper.writeValueAsString(
                                mapOf(
                                    "verdicts" to
                                        listOf(
                                            mapOf(
                                                "unitId" to "u0",
                                                "remove" to
                                                    listOf(
                                                        mapOf(
                                                            "intent" to "LOGGING",
                                                            "startByte" to findStart,
                                                            "endByte" to findEnd,
                                                        ),
                                                        mapOf(
                                                            "intent" to "PURE",
                                                            "startByte" to callStart,
                                                            "endByte" to callEnd,
                                                        ),
                                                        mapOf(
                                                            "intent" to "READONLY",
                                                            "startByte" to findStart,
                                                            "endByte" to findEnd,
                                                        ),
                                                    ),
                                                "add" to
                                                    listOf(
                                                        mapOf(
                                                            "intent" to "SEARCH",
                                                            "startByte" to findStart,
                                                            "endByte" to findEnd,
                                                            "confidence" to 0.5,
                                                        ),
                                                    ),
                                            ),
                                        ),
                                ),
                            ),
                        ),
                    ),
                )
            }
        val result =
            AnnotationSession(provider).run(
                document(listOf(unit)).copy(knownIntents = setOf("SEARCH", "LOGGING", "PURE", "READONLY")),
                SessionLimits(),
            )

        assertTrue(result.proposals.isEmpty())
        assertEquals(1, result.candidates.size)
        assertEquals(setOf("SEARCH"), result.candidates.single().intents)
    }

    @Test
    fun mixedSourceWithUserProvenanceCannotBeRemoved() {
        val existing =
            SessionAnnotation(
                intents = setOf("LOGGING"),
                startByte = findStart,
                endByte = findEnd,
                span = SessionText.spanFor(text, findStart, findEnd)!!,
                source = "mixed",
                confidence = 1.0,
                role = "SOURCE_INTENT",
                provenance = listOf("logging:logging:treesitter", "core:user-annotations:user"),
            )
        val unit = unit("u0", 0, text.length).copy(existingAnnotations = listOf(existing))
        val provider =
            scripted { _, _ ->
                AgentTurn.Calls(
                    listOf(
                        ToolCall(
                            "c1",
                            "submit_verdicts",
                            mapper.writeValueAsString(
                                mapOf(
                                    "verdicts" to
                                        listOf(
                                            mapOf(
                                                "unitId" to "u0",
                                                "remove" to
                                                    listOf(
                                                        mapOf(
                                                            "intent" to "LOGGING",
                                                            "startByte" to findStart,
                                                            "endByte" to findEnd,
                                                        ),
                                                    ),
                                            ),
                                        ),
                                ),
                            ),
                        ),
                    ),
                )
            }
        val result = AnnotationSession(provider).run(document(listOf(unit)), SessionLimits())

        assertTrue(result.proposals.isEmpty())
    }

    @Test
    fun maxCandidatesCapsAddCandidatesAndProposalGroupsTogether() {
        val firstExisting =
            SessionAnnotation(
                intents = setOf("LOGGING"),
                startByte = callStart,
                endByte = callEnd,
                span = SessionText.spanFor(text, callStart, callEnd)!!,
                source = "treesitter",
                confidence = 1.0,
                role = "SOURCE_INTENT",
            )
        val secondExisting =
            SessionAnnotation(
                intents = setOf("LOGGING"),
                startByte = findStart,
                endByte = findEnd,
                span = SessionText.spanFor(text, findStart, findEnd)!!,
                source = "treesitter",
                confidence = 1.0,
                role = "SOURCE_INTENT",
            )
        val units =
            listOf(
                unit("u0", 0, text.length).copy(existingAnnotations = listOf(firstExisting)),
                unit("u1", 0, text.length).copy(existingAnnotations = listOf(secondExisting)),
            )
        val provider =
            scripted { _, _ ->
                AgentTurn.Calls(
                    listOf(
                        ToolCall(
                            "c1",
                            "submit_verdicts",
                            mapper.writeValueAsString(
                                mapOf(
                                    "verdicts" to
                                        listOf(
                                            mapOf(
                                                "unitId" to "u0",
                                                "remove" to
                                                    listOf(
                                                        mapOf(
                                                            "intent" to "LOGGING",
                                                            "startByte" to callStart,
                                                            "endByte" to callEnd,
                                                        ),
                                                    ),
                                                "add" to
                                                    listOf(
                                                        mapOf(
                                                            "intent" to "SEARCH",
                                                            "startByte" to findStart,
                                                            "endByte" to findEnd,
                                                            "confidence" to 0.6,
                                                        ),
                                                    ),
                                            ),
                                            mapOf(
                                                "unitId" to "u1",
                                                "remove" to
                                                    listOf(
                                                        mapOf(
                                                            "intent" to "LOGGING",
                                                            "startByte" to findStart,
                                                            "endByte" to findEnd,
                                                        ),
                                                    ),
                                            ),
                                        ),
                                ),
                            ),
                        ),
                    ),
                )
            }
        val result = AnnotationSession(provider).run(document(units), SessionLimits(maxCandidates = 1))

        assertEquals(1, result.proposals.size)
        assertTrue(result.candidates.isEmpty())
    }

    @Test
    fun maxCandidatesPrefersAddCandidatesBeforeProposalGroups() {
        val existing =
            SessionAnnotation(
                intents = setOf("LOGGING"),
                startByte = callStart,
                endByte = callEnd,
                span = SessionText.spanFor(text, callStart, callEnd)!!,
                source = "treesitter",
                confidence = 1.0,
                role = "SOURCE_INTENT",
            )
        val units =
            listOf(
                unit("u0", 0, text.length),
                unit("u1", 0, text.length).copy(existingAnnotations = listOf(existing)),
            )
        val provider =
            scripted { _, _ ->
                AgentTurn.Calls(
                    listOf(
                        ToolCall(
                            "c1",
                            "submit_verdicts",
                            mapper.writeValueAsString(
                                mapOf(
                                    "verdicts" to
                                        listOf(
                                            mapOf(
                                                "unitId" to "u0",
                                                "add" to
                                                    listOf(
                                                        mapOf(
                                                            "intent" to "SEARCH",
                                                            "startByte" to findStart,
                                                            "endByte" to findEnd,
                                                            "confidence" to 0.6,
                                                        ),
                                                    ),
                                            ),
                                            mapOf(
                                                "unitId" to "u1",
                                                "remove" to
                                                    listOf(
                                                        mapOf(
                                                            "intent" to "LOGGING",
                                                            "startByte" to callStart,
                                                            "endByte" to callEnd,
                                                        ),
                                                    ),
                                            ),
                                        ),
                                ),
                            ),
                        ),
                    ),
                )
            }
        val result = AnnotationSession(provider).run(document(units), SessionLimits(maxCandidates = 1))

        assertEquals(1, result.candidates.size)
        assertTrue(result.proposals.isEmpty())
    }

    @Test
    fun removalWithoutAMatchingEstablishedAnnotationIsDropped() {
        val unit = unit("u0", 0, text.length)
        val provider =
            scripted { _, _ ->
                AgentTurn.Calls(
                    listOf(
                        ToolCall(
                            "c1",
                            "submit_verdicts",
                            mapper.writeValueAsString(
                                mapOf(
                                    "verdicts" to
                                        listOf(
                                            mapOf(
                                                "unitId" to "u0",
                                                "remove" to
                                                    listOf(
                                                        mapOf(
                                                            "intent" to "LOGGING",
                                                            "startByte" to findStart,
                                                            "endByte" to findEnd,
                                                        ),
                                                    ),
                                            ),
                                        ),
                                ),
                            ),
                        ),
                    ),
                )
            }
        val result = AnnotationSession(provider).run(document(listOf(unit)), SessionLimits())

        assertTrue(result.proposals.isEmpty())
        assertTrue(result.candidates.isEmpty())
        assertEquals(1, result.graded)
    }

    @Test
    fun confidenceIsCappedBySessionMaximum() {
        val units = listOf(unit("u0", 0, text.length))
        val provider =
            scripted { _, _ ->
                AgentTurn.Calls(
                    listOf(
                        ToolCall(
                            "c1",
                            "submit_verdicts",
                            mapper.writeValueAsString(
                                mapOf(
                                    "verdicts" to
                                        listOf(
                                            mapOf(
                                                "unitId" to "u0",
                                                "add" to
                                                    listOf(
                                                        mapOf(
                                                            "intent" to "SEARCH",
                                                            "startByte" to findStart,
                                                            "endByte" to findEnd,
                                                            "confidence" to 0.9,
                                                        ),
                                                    ),
                                            ),
                                        ),
                                ),
                            ),
                        ),
                    ),
                )
            }
        val result =
            AnnotationSession(provider).run(
                document(units),
                SessionLimits(maxConfidence = 0.42),
            )

        assertEquals(0.42, result.candidates.single().confidence)
    }

    @Test
    fun providerMessagesAndFailuresDoNotStoreRawProseInEvents() {
        val secret = "hunter2-secret"
        val units = listOf(unit("u0", 0, text.length))
        val messageResult =
            AnnotationSession(
                scripted { _, _ -> AgentTurn.Message("response contains $secret") },
            ).run(document(units), SessionLimits())
        val failureResult =
            AnnotationSession(
                scripted { _, _ -> AgentTurn.Failure("failure contains $secret") },
            ).run(document(units), SessionLimits())

        assertTrue(messageResult.events.none { it.message.contains(secret) })
        assertTrue(failureResult.events.none { it.message.contains(secret) })
    }

    @Test
    fun providerFailureDetailIsLoggedWithoutTheProviderReason() {
        val secret = "hunter2-secret"
        val result =
            AnnotationSession(
                scripted { _, _ ->
                    AgentTurn.Failure("failure contains $secret", detail = "HTTP 401")
                },
            ).run(document(listOf(unit("u0", 0, text.length))), SessionLimits())

        assertTrue(result.events.any { it.message.contains("provider failure: HTTP 401") })
        assertTrue(result.events.none { it.message.contains(secret) })
    }

    @Test
    fun toolResultsAreBoundedByTheActiveBatchAndSessionBudget() {
        val units = (0 until 20).map { index -> unit("u$index", index, index + 1) }
        var toolResult = ""
        val provider =
            scripted { turn, request ->
                if (turn == 0) {
                    AgentTurn.Calls(
                        listOf(
                            ToolCall("c1", "list_expression_units", """{"limit":500}"""),
                        ),
                    )
                } else if (turn == 1) {
                    toolResult =
                        request.messages
                            .last()
                            .content
                            .orEmpty()
                    AgentTurn.Calls(listOf(ToolCall("c2", "submit_verdicts", """{"verdicts":[]}""")))
                } else {
                    AgentTurn.Message("done")
                }
            }
        AnnotationSession(provider).run(
            document(units),
            SessionLimits(maxUnitsPerRequest = 3, maxTokensPerRequest = 128),
        )

        val parsed = mapper.readTree(toolResult)
        assertTrue(parsed.path("units").size() <= 3)
        assertTrue(parsed.path("batch").asInt() <= 3)
        assertTrue(toolResult.toByteArray().size <= 512)
    }

    @Test
    fun oversizedToolResultsAreTruncatedBeforeTheNextProviderTurn() {
        val largeText = "val value = " + "1 + ".repeat(2_000) + "0"
        val units = listOf(unit("u0", 0, 5, largeText))
        var toolResult = ""
        val provider =
            scripted { turn, request ->
                if (turn == 0) {
                    AgentTurn.Calls(
                        listOf(
                            ToolCall(
                                "c1",
                                "read_range",
                                mapper.writeValueAsString(mapOf("startByte" to 0, "endByte" to largeText.length)),
                            ),
                        ),
                    )
                } else {
                    toolResult =
                        request.messages
                            .last()
                            .content
                            .orEmpty()
                    AgentTurn.Calls(listOf(ToolCall("c2", "submit_verdicts", """{"verdicts":[{"unitId":"u0","verdict":"none"}]}""")))
                }
            }
        AnnotationSession(provider).run(
            document(units, largeText),
            SessionLimits(maxTokensPerRequest = 256),
        )

        val parsed = mapper.readTree(toolResult)
        assertTrue(parsed.path("truncated").asBoolean(), toolResult)
        assertTrue(toolResult.toByteArray().size <= 2_048, "tool result bytes: ${toolResult.toByteArray().size}")
    }

    @Test
    fun oversizedUnitTextAndAnnotationsAreBoundedInTheBatchPrompt() {
        val largeText = "val value = \"" + "a".repeat(20_000) + "\""
        val annotations =
            (0 until 40).map { index ->
                SessionAnnotation(
                    intents = setOf("LOGGING"),
                    startByte = 0,
                    endByte = 1,
                    span = Span(0, 0, 0, 1),
                    source = "source-$index",
                    confidence = 1.0,
                    role = "SOURCE_INTENT",
                )
            }
        val units = listOf(unit("u0", 0, largeText.length, largeText).copy(existingAnnotations = annotations))
        var prompt = ""
        val provider =
            scripted { _, request ->
                prompt =
                    request.messages
                        .last { it.role == "user" }
                        .content
                        .orEmpty()
                AgentTurn.Calls(listOf(ToolCall("c1", "submit_verdicts", """{"verdicts":[{"unitId":"u0","verdict":"none"}]}""")))
            }
        AnnotationSession(provider).run(
            document(units, largeText),
            SessionLimits(maxUnitsPerRequest = 1, maxTokensPerRequest = 128),
        )

        val unit = mapper.readTree(prompt).path("units")[0]
        assertTrue(unit.path("text").asText().contains("[truncated]"), prompt)
        assertTrue(unit.path("existingAnnotationsTruncated").asBoolean(), prompt)
        assertTrue(unit.path("existingAnnotations").size() < annotations.size)
        assertTrue(prompt.toByteArray(Charsets.UTF_8).size <= 1_024, "prompt bytes: ${prompt.toByteArray().size}")
    }

    @Test
    fun systemPromptIsSentAsASystemMessage() {
        val units = listOf(unit("u0", 0, text.length))
        var system = ""
        val provider =
            scripted { _, request ->
                system =
                    request.messages
                        .first { it.role == "system" }
                        .content
                        .orEmpty()
                AgentTurn.Calls(listOf(ToolCall("c1", "submit_verdicts", """{"verdicts":[{"unitId":"u0","verdict":"none"}]}""")))
            }
        AnnotationSession(provider).run(document(units), SessionLimits())

        assertTrue(system.contains("annotation discovery agent"))
        assertTrue(system.contains("submit_verdicts"))
    }

    @Test
    fun providerFailurePublishesNoCandidatesAndLeavesUnitsUncovered() {
        val units = listOf(unit("u0", 0, text.length))
        val provider =
            object : AnnotationSessionProvider {
                override val id: String = "failing"
                override val model: String = "failing-v1"
                override val defaultPromptVersion: String = "failing-prompt-v1"

                override fun complete(request: AgentRequest): AgentTurn = AgentTurn.Failure("provider down")
            }

        val result = AnnotationSession(provider).run(document(units), SessionLimits())

        assertTrue(result.incomplete)
        assertFalse(result.budgetExhausted)
        assertEquals(0, result.graded)
        assertEquals(listOf("u0"), result.uncovered)
        assertTrue(result.candidates.isEmpty())
    }

    @Test
    fun cancellationStopsBeforeCallingTheProvider() {
        val units = listOf(unit("u0", 0, text.length))
        var calls = 0
        val provider =
            scripted { _, _ ->
                calls += 1
                AgentTurn.Message("unexpected")
            }

        val result =
            AnnotationSession(provider, isCancelled = { true }).run(
                document(units),
                SessionLimits(),
            )

        assertEquals(0, calls)
        assertTrue(result.incomplete)
        assertEquals(listOf("u0"), result.uncovered)
        assertTrue(result.events.any { it.type == "cancel" })
    }

    @Test
    fun lookupTypeResultsAreSanitizedBeforeReachingTheProvider() {
        val secret = "hunter2-secret"
        val units = listOf(unit("u0", 0, text.length))
        var seen = ""
        val provider =
            scripted { turn, request ->
                if (turn == 0) {
                    AgentTurn.Calls(
                        listOf(
                            ToolCall(
                                "c1",
                                "lookup_type",
                                mapper.writeValueAsString(mapOf("startByte" to 0, "endByte" to text.length)),
                            ),
                        ),
                    )
                } else {
                    seen =
                        request.messages
                            .last()
                            .content
                            .orEmpty()
                    AgentTurn.Calls(listOf(ToolCall("c2", "submit_verdicts", """{"verdicts":[{"unitId":"u0","verdict":"none"}]}""")))
                }
            }
        AnnotationSession(
            provider = provider,
            sanitize = { value -> value.replace(secret, "***") },
            typeLookup = { _, _ -> mapOf("fqName" to "pkg.$secret.Type") },
        ).run(document(units), SessionLimits())

        assertFalse(seen.contains(secret), seen)
        assertTrue(seen.contains("***"), seen)
    }

    @Test
    fun promptAndToolTextAreSanitizedBeforeReachingTheProvider() {
        val secret = "hunter2"
        val localText = "val $secret = Regex(\"cint\").find(text)"
        val start = localText.indexOf("Regex")
        val end = localText.length
        val units = listOf(unit("u0", start, end, localText))
        var seen = ""
        val provider =
            scripted { _, request ->
                if (request.messages.none { it.role == "tool" }) {
                    seen =
                        request.messages
                            .last { it.role == "user" }
                            .content
                            .orEmpty()
                    AgentTurn.Calls(
                        listOf(
                            ToolCall(
                                "c1",
                                "read_range",
                                mapper.writeValueAsString(mapOf("startByte" to 0, "endByte" to localText.length)),
                            ),
                        ),
                    )
                } else {
                    seen +=
                        request.messages
                            .last()
                            .content
                            .orEmpty()
                    AgentTurn.Calls(listOf(ToolCall("c2", "submit_verdicts", """{"verdicts":[{"unitId":"u0","verdict":"none"}]}""")))
                }
            }
        AnnotationSession(
            provider = provider,
            sanitize = { it.replace(secret, "*".repeat(secret.length)) },
        ).run(document(units, localText), SessionLimits())

        assertFalse(seen.contains(secret))
        assertTrue(seen.contains("*******"), seen)
    }

    private fun document(
        units: List<SessionUnit>,
        sourceText: String = text,
    ): SessionDocument =
        SessionDocument(
            path = "Facts.kt",
            version = 1,
            text = sourceText,
            language = "kotlin",
            units = units,
            annotations = units.flatMap { it.existingAnnotations },
            knownIntents = setOf("SEARCH", "LOGGING"),
        )

    private fun unit(
        id: String,
        start: Int,
        end: Int,
        sourceText: String = text,
    ): SessionUnit =
        SessionUnit(
            unitId = id,
            path = "Facts.kt",
            language = "kotlin",
            startByte = start,
            endByte = end,
            span = SessionText.spanFor(sourceText, start, end) ?: Span(0, 0, 0, 0),
            text = sourceText.substring(start, end),
            enclosingStatement = Span(0, 0, 0, 0),
        )

    private fun scripted(script: (Int, AgentRequest) -> AgentTurn): AnnotationSessionProvider =
        object : AnnotationSessionProvider {
            override val id: String = "scripted"
            override val model: String = "scripted-v1"
            override val defaultPromptVersion: String = "scripted-prompt-v1"
            private var turn = 0

            override fun complete(request: AgentRequest): AgentTurn = script(turn++, request)
        }
}
