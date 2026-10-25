package dev.cint.knowledge

import dev.cint.contracts.data.Confidence
import dev.cint.contracts.data.Intents
import dev.cint.contracts.data.Span
import dev.cint.contracts.data.Status
import dev.cint.contracts.knowledge.AnnotationScope
import dev.cint.contracts.knowledge.FunctionAnnotation
import dev.cint.contracts.knowledge.FunctionSelector
import dev.cint.contracts.knowledge.KnowledgeSnapshot
import dev.cint.contracts.knowledge.PackDefault
import dev.cint.contracts.knowledge.PackOrigin
import dev.cint.contracts.knowledge.PatternHypothesis
import dev.cint.contracts.knowledge.PatternSelector
import dev.cint.contracts.knowledge.SpanOverride
import dev.cint.contracts.knowledge.SpanSelector
import dev.cint.contracts.util.ContractError
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KnowledgeStoreJsonTest {
    private val fixedNow = Instant.parse("2025-01-01T00:00:00Z")
    private val clock = { fixedNow }

    private fun populatedStore(): InMemoryKnowledgeStore {
        val store = InMemoryKnowledgeStore.empty(clock)
        store.materializeDefaults(
            listOf(
                PackDefault(
                    selector = FunctionSelector(qualifiedName = "packed"),
                    intents = setOf(Intents.LOGGING),
                    packId = "logging",
                    packVersion = "1.0.0",
                    createdAt = fixedNow,
                ),
            ),
        )
        store.setFunctionAnnotation(
            FunctionAnnotation.create(
                selector = FunctionSelector(qualifiedName = "user"),
                intents = setOf(Intents.LOOKUP),
                confidence = Confidence.HIGH,
                status = Status.ESTABLISHED,
                createdAt = fixedNow,
            ),
        )
        store.setSpanOverride(
            SpanOverride.create(
                selector = SpanSelector("A.kt", Span(1, 0, 1, 9), "hash"),
                intents = setOf(Intents.VALIDATION),
                rationale = "guard",
                createdAt = fixedNow,
            ),
        )
        store.setPatternHypothesis(
            PatternHypothesis.create(
                selector = PatternSelector(pattern = "logger"),
                scope = AnnotationScope.GLOBAL,
                evidence = listOf("evidence"),
                examples = listOf("example"),
                counterexamples = emptyList(),
                proposedIntents = setOf(Intents.SEARCH),
                createdAt = fixedNow,
            ),
        )
        return store
    }

    @Test
    fun roundTripsSnapshotsThroughTheVersionedDocument() {
        val original = populatedStore().snapshot().getOrThrow()
        val encoded = KnowledgeStoreJson.encode(original).getOrThrow()
        assertTrue(encoded.contains("\"format\": \"cint-knowledge\""))
        assertTrue(encoded.contains("\"formatVersion\": 1"))
        assertTrue(encoded.contains("\"packOrigins\""))

        val decoded = KnowledgeStoreJson.decode(encoded).getOrThrow()

        assertEquals(original, decoded)
        assertEquals(
            PackOrigin("logging", "1.0.0"),
            decoded.packOrigins.getValue(FunctionSelector(qualifiedName = "packed").identityKey),
        )
        val cloned = InMemoryKnowledgeStore.fromSnapshot(decoded, now = clock).getOrThrow()
        assertTrue(cloned.isMaterialized)
        assertEquals(
            PackOrigin("logging", "1.0.0"),
            cloned.packOrigin(FunctionSelector(qualifiedName = "packed")).getOrThrow(),
        )
    }

    @Test
    fun decodedRecordsAreValidated() {
        val invalidIntent = snapshotJson(
            snapshot = """{"functionAnnotations":[${annotationJson("BAD")}],"spanOverrides":[],""" +
                """"patternHypotheses":[],"packOrigins":{},"version":1,"timestamp":1}""",
        )
        val unknown = KnowledgeStoreJson.decode(invalidIntent)
        assertTrue(unknown.isFailure)
        assertEquals("intents", assertIs<ContractError.ValidationError>(unknown.errorOrNull).field)

        val backwards = snapshotJson(
            snapshot = """{"functionAnnotations":[${annotationJson(Intents.LOOKUP, updatedAt = 0)}],""" +
                """"spanOverrides":[],"patternHypotheses":[],"packOrigins":{},"version":1,"timestamp":1}""",
        )
        val stale = KnowledgeStoreJson.decode(backwards)
        assertTrue(stale.isFailure)
        assertEquals("timestamps", assertIs<ContractError.ValidationError>(stale.errorOrNull).field)
    }

    @Test
    fun decodedPackOriginsMustReferencePackAnnotations() {
        val missing = snapshotJson(
            snapshot = """{"functionAnnotations":[],"spanOverrides":[],"patternHypotheses":[],""" +
                """"packOrigins":{"missing":{"packId":"p","packVersion":"1"}},"version":1,"timestamp":1}""",
        )
        val result = KnowledgeStoreJson.decode(missing)
        assertTrue(result.isFailure)
        assertEquals("packOrigins", assertIs<ContractError.ValidationError>(result.errorOrNull).field)

        val originKey = FunctionSelector(qualifiedName = "user").identityKey
        val nonPack = snapshotJson(
            snapshot = """{"functionAnnotations":[${annotationJson(Intents.LOOKUP)}],"spanOverrides":[],""" +
                """"patternHypotheses":[],"packOrigins":{"$originKey":{"packId":"p","packVersion":"1"}},""" +
                """"version":1,"timestamp":1}""",
        )
        val nonPackResult = KnowledgeStoreJson.decode(nonPack)
        assertTrue(nonPackResult.isFailure)
        assertEquals(
            "packOrigins",
            assertIs<ContractError.ValidationError>(nonPackResult.errorOrNull).field,
        )
    }

    @Test
    fun decodedDuplicatesAreRejected() {
        val annotation = annotationJson(Intents.LOOKUP)
        val duplicate = snapshotJson(
            snapshot = """{"functionAnnotations":[$annotation,$annotation],"spanOverrides":[],""" +
                """"patternHypotheses":[],"packOrigins":{},"version":1,"timestamp":1}""",
        )
        val result = KnowledgeStoreJson.decode(duplicate)
        assertTrue(result.isFailure)
        assertEquals(
            "functionAnnotations",
            assertIs<ContractError.ValidationError>(result.errorOrNull).field,
        )
    }

    @Test
    fun unsupportedFormatVersionsFail() {
        val unsupported = documentJson(formatVersion = 2, snapshot = emptySnapshotJson())

        val result = KnowledgeStoreJson.decode(unsupported)
        assertTrue(result.isFailure)
        val error = assertIs<ContractError.ValidationError>(result.errorOrNull)
        assertEquals("formatVersion", error.field)
    }

    @Test
    fun missingVersionFails() {
        val missing = """
            {
              "format": "cint-knowledge",
              "snapshot": ${emptySnapshotJson()}
            }
        """.trimIndent()

        val result = KnowledgeStoreJson.decode(missing)
        assertTrue(result.isFailure)
        assertIs<ContractError.SerializationError>(result.errorOrNull)
    }

    @Test
    fun unsupportedFormatFails() {
        val unsupported = """
            {
              "format": "other-format",
              "formatVersion": 1,
              "snapshot": ${emptySnapshotJson()}
            }
        """.trimIndent()

        val result = KnowledgeStoreJson.decode(unsupported)
        assertTrue(result.isFailure)
        val error = assertIs<ContractError.ValidationError>(result.errorOrNull)
        assertEquals("format", error.field)
    }

    private fun documentJson(formatVersion: Int, snapshot: String): String = """
        {
          "format": "cint-knowledge",
          "formatVersion": $formatVersion,
          "snapshot": $snapshot
        }
    """.trimIndent()

    private fun snapshotJson(snapshot: String): String = documentJson(1, snapshot)

    private fun emptySnapshotJson(): String =
        """{"functionAnnotations":[],"spanOverrides":[],"patternHypotheses":[],"packOrigins":{},""" +
            """"version":1,"timestamp":1}"""

    private fun annotationJson(
        intent: String,
        createdAt: Long = 1,
        updatedAt: Long = 1,
    ): String {
        val created = Instant.fromEpochMilliseconds(createdAt)
        val updated = Instant.fromEpochMilliseconds(updatedAt)
        return """{"selector":{"qualifiedName":"user"},"intents":["$intent"],""" +
            """"scope":"GLOBAL","provenance":"USER","confidence":{"value":0.7},"status":"ACCEPTED",""" +
            """"createdAt":"$created","updatedAt":"$updated"}"""
    }
}
