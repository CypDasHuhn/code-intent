package dev.cint.contracts

import dev.cint.contracts.data.Confidence
import dev.cint.contracts.data.Span
import dev.cint.contracts.facts.FactBatch
import dev.cint.contracts.facts.IndexedSource
import dev.cint.contracts.facts.SourceInput
import dev.cint.contracts.facts.SyntaxTree
import dev.cint.contracts.ipc.IpcMessage
import dev.cint.contracts.mcp.McpRequest
import dev.cint.contracts.knowledge.FunctionAnnotation
import dev.cint.contracts.knowledge.FunctionSelector
import dev.cint.contracts.knowledge.KnowledgeSnapshot
import dev.cint.contracts.knowledge.PackDefault
import dev.cint.contracts.knowledge.PatternHypothesis
import dev.cint.contracts.knowledge.PatternSelector
import dev.cint.contracts.knowledge.SpanOverride
import dev.cint.contracts.knowledge.SpanSelector
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.Result
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InvalidInputTest {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = false
        classDiscriminator = "type"
    }

    @Test
    fun testNegativeSpanRejected() {
        assertFailsWith<IllegalArgumentException> { Span(-1, 0, 0, 0) }
        assertFailsWith<IllegalArgumentException> { Span(0, -1, 0, 0) }
        assertFailsWith<IllegalArgumentException> { Span(0, 0, -1, 0) }
        assertFailsWith<IllegalArgumentException> { Span(0, 0, 0, -1) }
    }

    @Test
    fun testInvertedSpanRejected() {
        assertFailsWith<IllegalArgumentException> { Span(2, 0, 1, 9) }
        assertFailsWith<IllegalArgumentException> { Span(1, 5, 1, 4) }
        assertFails { json.decodeFromString<Span>("""{"startLine":2,"startCol":0,"endLine":1,"endCol":0}""") }
    }

    @Test
    fun testLongLinesAreRepresentable() {
        val span = Span(0, 10_000, 0, 1_000_000)
        assertEquals(10_000L, span.startOffset)
        assertEquals(1_000_000L, span.endOffset)
        assertTrue(span.contains(Span(0, 20_000, 0, 30_000)))
        assertTrue(span.intersects(Span(0, 999_999, 0, 1_000_001)))
        assertTrue(Span(1, 0, 1, 0).startOffset != Span(0, 10_000, 0, 10_000).startOffset)

        val minified = Span(Int.MAX_VALUE - 1, 0, Int.MAX_VALUE - 1, Int.MAX_VALUE)
        assertTrue(minified.startOffset < minified.endOffset)

        val decoded = json.decodeFromString<Span>(
            """{"startLine":0,"startCol":10000,"endLine":1,"endCol":20000}""",
        )
        assertEquals(Span(0, 10_000, 1, 20_000), decoded)
    }

    @Test
    fun testInvalidConfidenceRejected() {
        assertFailsWith<IllegalArgumentException> { Confidence(-0.1) }
        assertFailsWith<IllegalArgumentException> { Confidence(1.1) }
        assertFailsWith<IllegalArgumentException> { Confidence(Double.NaN) }
        assertFails { json.decodeFromString<Confidence>("""{"value":1.5}""") }
        assertFails { json.decodeFromString<Confidence>("""{"value":-0.1}""") }
    }

    @Test
    fun testInvalidSelectorsRejected() {
        assertFailsWith<IllegalArgumentException> { FunctionSelector(qualifiedName = "") }
        assertFailsWith<IllegalArgumentException> { FunctionSelector(qualifiedName = "   ") }
        assertFailsWith<IllegalArgumentException> {
            SpanSelector(path = "", span = Span.ZERO, contentHash = "hash")
        }
        assertFailsWith<IllegalArgumentException> {
            SpanSelector(path = "A.kt", span = Span.ZERO, contentHash = "")
        }
        assertFailsWith<IllegalArgumentException> {
            SpanSelector(path = "A.kt", span = Span.ZERO, contentHash = "   ")
        }
        assertFailsWith<IllegalArgumentException> { FunctionSelector() }
        assertFailsWith<IllegalArgumentException> { FunctionSelector(qualifiedName = "   ", importPath = "  ") }
        assertFailsWith<IllegalArgumentException> { PatternSelector(pattern = "  ") }
        assertFails { json.decodeFromString<FunctionSelector>("""{"qualifiedName":""}""") }
        assertEquals(
            "FUNCTION|null|8:imported|null|null|null|null",
            FunctionSelector(importPath = "imported").identityKey,
        )
        assertEquals(
            "FUNCTION|null|null|null|null|null|7:pattern",
            FunctionSelector(structuralPattern = "pattern").identityKey,
        )
        assertFails {
            json.decodeFromString<SpanSelector>(
                """{"path":"","span":{"startLine":0,"startCol":0,"endLine":0,"endCol":0},"contentHash":"h"}""",
            )
        }
    }

    @Test
    fun testMalformedKnowledgeDtosRejected() {
        assertFails { json.decodeFromString<FunctionAnnotation>("""{"intents":[]}""") }
        assertFails { json.decodeFromString<SpanOverride>("""{"intents":[],"rationale":"r"}""") }
        assertFails { json.decodeFromString<PackDefault>("""{"intents":[],"packId":"p"}""") }
        assertFails { json.decodeFromString<PatternHypothesis>("""{"id":"h","scope":"GLOBAL"}""") }
        assertFails {
            json.decodeFromString<KnowledgeSnapshot>(
                """{"functionAnnotations":[],"spanOverrides":[],"patternHypotheses":[]}""",
            )
        }
    }

    @Test
    fun testMalformedFactDtosRejected() {
        assertFails { json.decodeFromString<FactBatch>("""{"calls":"not-a-list"}""") }
        assertFails {
            json.decodeFromString<SyntaxTree>(
                """{"rootId":"r","nodes":[],"language":"kotlin","version":"1","contentHash":"h"}""",
            )
        }
        assertFails {
            json.decodeFromString<IndexedSource>(
                """{"input":{"type":"FilePath","path":"A.kt"},"sourceEpoch":1,"completeness":"COMPLETE","contentHash":"h"}""",
            )
        }
    }

    @Test
    fun testMalformedTransportDtosRejected() {
        assertFails {
            json.decodeFromString<IpcMessage>(
                """{"type":"UnknownMessage","requestId":"r"}""",
            )
        }
        assertFails {
            json.decodeFromString<IpcMessage>(
                """{"type":"Handshake","clientInfo":{"name":"c","version":"1"}}""",
            )
        }
        assertFails {
            json.decodeFromString<McpRequest>(
                """{"type":"UnknownRequest","requestId":"r"}""",
            )
        }
        assertFails {
            json.decodeFromString<McpRequest>(
                """{"type":"ChangesApply","requestId":"r"}""",
            )
        }
        assertFails {
            json.decodeFromString<SourceInput>(
                """{"type":"Missing","text":"x"}""",
            )
        }
        assertFails {
            json.decodeFromString<SourceInput>(
                """{"type":"InMemory","language":"kotlin"}""",
            )
        }
    }

    @Test
    fun testMalformedErrorsAndResultsRejected() {
        assertFails {
            json.decodeFromString<ContractError>("""{"type":"UnknownError","message":"x"}""")
        }
        assertFails {
            json.decodeFromString<ContractError>("""{"type":"Timeout","operation":"x"}""")
        }
        assertFails {
            json.decodeFromString<Result<String>>("""{"type":"Success"}""")
        }
        assertFails {
            json.decodeFromString<Result<String>>("""{"type":"Failure"}""")
        }
        assertFails {
            json.decodeFromString<CancellationToken>("""{"type":"UnknownToken"}""")
        }
    }

    @Test
    fun testFailureGetOrElseReturnsDefault() {
        val failure: Result<String> = dev.cint.contracts.util.ResultFactory.failure(
            ContractError.ValidationError("invalid"),
        )
        assertEquals("fallback", failure.getOrElse("fallback"))
    }

    @Test
    fun testSnapshotVersionAndTimestampAreRequired() {
        val base = """{"functionAnnotations":[],"spanOverrides":[],"patternHypotheses":[]}"""
        assertFails { json.decodeFromString<KnowledgeSnapshot>(base) }
        assertFails {
            json.decodeFromString<KnowledgeSnapshot>(
                """{"functionAnnotations":[],"spanOverrides":[],"patternHypotheses":[],"version":1}""",
            )
        }
        assertFails {
            json.decodeFromString<KnowledgeSnapshot>(
                """{"functionAnnotations":[],"spanOverrides":[],"patternHypotheses":[],"timestamp":1}""",
            )
        }
        assertFails {
            json.decodeFromString<KnowledgeSnapshot>(
                """{"functionAnnotations":[],"spanOverrides":[],"patternHypotheses":[],"version":2,"timestamp":1}""",
            )
        }
    }
}
