package dev.cint.ipc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FrozenTranscriptTest {
    private val corpusRoot: Path =
        Paths.get(
            System.getProperty("v2.frozenCorpusRoot")
                ?: error("v2.frozenCorpusRoot must point at frozen-v1-corpus"),
        )
    private val json = Json { ignoreUnknownKeys = true }
    private val codec = JsonRpcCodec()

    @Test
    fun replaysEveryLiveSessionInOrderWithStableIdsAndEnvelopes() {
        val sessions = sessionFiles()
        assertTrue(sessions.size >= 8, "expected the frozen live sessions, found ${sessions.size}")
        for (sessionPath in sessions) {
            val session = parseObject(sessionPath)
            val scenario = session.getValue("scenario").jsonPrimitive.content
            val steps = session.getValue("steps").jsonArray
            val flattened = mutableListOf<JsonElement>()
            val requestIds = mutableListOf<Long>()

            for (stepElement in steps) {
                val step = stepElement.jsonObject
                val request = step.getValue("request").jsonObject
                val decodedRequest = codec.decode(request.toString())
                when (decodedRequest) {
                    is JsonRpcMessage.Request -> {
                        assertTrue(decodedRequest.method in IpcMethods.all, "$scenario: ${decodedRequest.method}")
                        assertIs<JsonRpcId.NumberId>(decodedRequest.id)
                        requestIds += decodedRequest.id.value
                    }
                    is JsonRpcMessage.Notification -> {
                        assertTrue(decodedRequest.method in IpcMethods.all, "$scenario: ${decodedRequest.method}")
                    }
                    else -> error("$scenario: request step decoded as $decodedRequest")
                }
                assertEquals(decodedRequest, codec.decode(codec.encode(decodedRequest)))

                for (message in step.getValue("messages").jsonArray) {
                    val messageObject = message.jsonObject
                    val decoded = codec.decode(messageObject.toString())
                    assertTrue(
                        decoded is JsonRpcMessage.Response ||
                            decoded is JsonRpcMessage.ErrorResponse ||
                            decoded is JsonRpcMessage.Notification,
                        "$scenario: unexpected server message $decoded",
                    )
                    assertEquals(decoded, codec.decode(codec.encode(decoded)))
                    flattened += messageObject
                }
            }

            assertEquals((1..requestIds.size).map { it.toLong() }, requestIds.sorted(), "$scenario request ids")
            assertEquals(requestIds.size, requestIds.toSet().size, "$scenario duplicate request ids")

            val responseIds =
                steps.flatMap { step ->
                    step.jsonObject.getValue("messages").jsonArray.mapNotNull { message ->
                        val objectValue = message.jsonObject
                        if (objectValue.containsKey("method")) {
                            null
                        } else {
                            val id = objectValue.getValue("id")
                            (id as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLong()
                        }
                    }
                }
            assertEquals(requestIds.sorted(), responseIds.sorted(), "$scenario response ids")
            assertEquals(responseIds.size, responseIds.toSet().size, "$scenario duplicate response ids")

            val transcriptPath =
                sessionPath.resolveSibling(sessionPath.fileName.toString().removeSuffix(".session.json") + ".ndjson")
            val expected = Files.readAllLines(transcriptPath).filter { it.isNotBlank() }.map(::parseElement)
            assertEquals(expected, flattened, "$scenario transcript ordering")
        }
    }

    @Test
    fun replaysEveryFrozenRequestThroughTheConnectionLayer() {
        for (sessionPath in sessionFiles()) {
            val session = parseObject(sessionPath)
            val scenario = session.getValue("scenario").jsonPrimitive.content
            val steps = session.getValue("steps").jsonArray
            val trace =
                steps.map { stepElement ->
                    val step = stepElement.jsonObject
                    TraceStep(
                        request = codec.decode(step.getValue("request").toString()),
                        responses = step.getValue("messages").jsonArray.map { it.jsonObject },
                    )
                }
            val connection = IpcConnection(ReplayHandler(ArrayDeque(trace)))
            for ((index, step) in trace.withIndex()) {
                val outputs = connection.feed(codec.encode(step.request) + "\n")
                val actual = outputs.map { parseElement(codec.encode(it)).jsonObject }
                assertEquals(step.responses, actual, "$scenario step $index")
            }
            assertEquals(emptySet(), connection.pending)
        }
    }

    @Test
    fun everyDecidedProtocolDifferenceHasAnExplicitWireCoverageToken() {
        val knownDifferences = parseObject(corpusRoot.resolve("known-differences.json"))
        val decided =
            knownDifferences.getValue("differences").jsonArray
                .map { it.jsonObject }
                .filter { it.getValue("scope").jsonPrimitive.content == "protocol" }
                .filter { it.getValue("status").jsonPrimitive.content == "decided" }
                .associateBy { it.getValue("id").jsonPrimitive.content }
        val coverage =
            mapOf(
                "protocol.version" to listOf("ipc-schema=5"),
                "config.protocol" to listOf("ConfigResult", "sources", "config"),
                "analyze.http.shape" to listOf("AnalyzeParams", "options", "AnalyzedFile", "diagnostics"),
                "publishTags.fields" to listOf("configRevision", "analysisVersion", "diagnostics", "nodeAnchor"),
                "publishCandidates.fields" to
                    listOf("proposals", "proposalRevision", "origin", "category", "fingerprints", "operations"),
                "annotation.shape" to listOf("ruleId", "packId", "evidence"),
                "candidate.shape" to listOf("origin", "category", "fingerprints", "operations"),
                "suggest.protocol" to listOf("selection", "cint/suggest/stream", "cint/suggest/history"),
                "restrictions.protocol" to listOf("cint/restrictions", "scope", "condition"),
                "status.protocol" to listOf("cint/status", "uptimeMs", "activeDocuments", "suggestionQueue"),
                "error.handling" to listOf("error=code,message,data.kind,data.details"),
                "language.support" to listOf("languages"),
            )
        assertEquals(emptySet(), decided.keys - coverage.keys, "unaccounted decided protocol differences")
        val schema = ProtocolFingerprint.canonicalSchema()
        for ((id, tokens) in coverage) {
            assertTrue(id in decided, "coverage references unknown/undecided difference $id")
            for (token in tokens) {
                assertTrue(token in schema, "$id coverage token missing from fingerprint schema: $token")
            }
        }
    }

    @Test
    fun keepsV1WireShapesAndErrorsVisibleInTheFrozenCorpus() {
        val kotlinTranscript = transcript("protocol-kotlin-analyze")
        val initialize = kotlinTranscript.first { it["id"]?.jsonPrimitive?.content == "1" }.getValue("result").jsonObject
        assertEquals(4, initialize.getValue("protocol").jsonPrimitive.int)
        assertTrue(initialize.getValue("root").jsonPrimitive.content.contains("fixtures/sources/kotlin"))
        assertEquals(5, initialize.getValue("languages").jsonArray.size)
        assertTrue(initialize.containsKey("config"))
        assertTrue(initialize.containsKey("suggestions"))

        val tags = kotlinTranscript.filter { it["method"]?.jsonPrimitive?.content == IpcMethods.PUBLISH_TAGS }
        assertTrue(tags.isNotEmpty())
        for (message in tags) {
            val params = message.getValue("params").jsonObject
            assertTrue(params.containsKey("revision"))
            assertTrue(params.containsKey("analysisRevision"))
            assertTrue(params.containsKey("annotations"))
            assertFalse(params.containsKey("configRevision"))
            assertFalse(params.containsKey("analysisVersion"))
            assertFalse(params.containsKey("diagnostics"))
            val annotation = params.getValue("annotations").jsonArray.first().jsonObject
            for (field in listOf("startLine", "startCol", "endLine", "endCol", "startByte", "endByte")) {
                assertTrue(annotation.containsKey(field), "annotation missing $field")
            }
            assertTrue(annotation.containsKey("nodeAnchor"))
            assertTrue(annotation.containsKey("provenance"))
            assertFalse(annotation.containsKey("diagnostics"))
            assertTrue(annotation.getValue("provenance").jsonArray.first().jsonObject.containsKey("rule"))
        }

        val candidates = transcript("protocol-typescript-analyze").filter {
            it["method"]?.jsonPrimitive?.content == IpcMethods.PUBLISH_CANDIDATES
        }
        assertTrue(candidates.isNotEmpty())
        var sawCandidate = false
        for (message in candidates) {
            val params = message.getValue("params").jsonObject
            assertTrue(params.containsKey("groups"))
            assertTrue(params.containsKey("candidates"))
            assertTrue(params.containsKey("suggestionRevision"))
            assertFalse(params.containsKey("proposals"))
            assertFalse(params.containsKey("proposalRevision"))
            val candidate = params.getValue("candidates").jsonArray.firstOrNull()?.jsonObject ?: continue
            sawCandidate = true
            assertTrue(candidate.containsKey("source"))
            assertTrue(candidate.containsKey("reason"))
            assertTrue(candidate.containsKey("aiEligible"))
        }
        assertTrue(sawCandidate, "expected at least one frozen V1 candidate")

        val statusError =
            transcript("protocol-status-flow").first { it.containsKey("error") }.getValue("error").jsonObject
        assertEquals(-32000, statusError.getValue("code").jsonPrimitive.int)
        assertEquals("unknown method: cint/status", statusError.getValue("message").jsonPrimitive.content)
        assertFalse(statusError.containsKey("data"))

        val flowErrors = transcript("protocol-error-flow").filter { it.containsKey("error") }
        assertEquals(3, flowErrors.size)
        for (message in flowErrors) {
            val error = message.getValue("error").jsonObject
            assertEquals(-32000, error.getValue("code").jsonPrimitive.int)
            assertFalse(error.containsKey("data"))
        }
    }

    @Test
    fun v2DtosRepresentEveryDecidedProtocolDifference() {
        val knownDifferences = parseObject(corpusRoot.resolve("known-differences.json"))
        val byId =
            knownDifferences.getValue("differences").jsonArray.associate {
                val difference = it.jsonObject
                difference.getValue("id").jsonPrimitive.content to difference
            }
        for (id in listOf("publishTags.fields", "publishCandidates.fields", "annotation.shape", "candidate.shape", "suggest.protocol", "status.protocol", "error.handling", "config.protocol")) {
            assertTrue(id in byId, "missing known difference $id")
            assertEquals("decided", byId.getValue(id).getValue("status").jsonPrimitive.content, id)
        }

        val span = IpcSpan(1, 0, 1, 4, 0, 4)
        val provenance =
            IpcProvenance(
                ruleId = "logging:logging",
                packId = "logging",
                role = "source-intent",
                intents = listOf("LOGGING"),
                source = "treesitter",
                confidence = 0.8,
                startLine = 1,
                startCol = 0,
                endLine = 1,
                endCol = 4,
                startByte = 0,
                endByte = 4,
                evidence = JsonPrimitive("fixture"),
            )
        val annotation =
            IpcAnnotation(
                role = "source-intent",
                intents = listOf("LOGGING"),
                source = "treesitter",
                confidence = 0.8,
                startLine = 1,
                startCol = 0,
                endLine = 1,
                endCol = 4,
                startByte = 0,
                endByte = 4,
                nodeAnchor = "node-1",
                provenance = listOf(provenance),
                diagnostics = listOf(IpcDiagnostic("parse", "bad input")),
            )
        val annotationJson = IpcJson.encode(annotation).jsonObject
        for (field in listOf("startLine", "startCol", "endLine", "endCol", "startByte", "endByte")) {
            assertTrue(annotationJson.containsKey(field))
        }
        assertEquals("node-1", annotationJson.getValue("nodeAnchor").jsonPrimitive.content)
        assertTrue(annotationJson.containsKey("diagnostics"))
        val provenanceJson = annotationJson.getValue("provenance").jsonArray.first().jsonObject
        assertEquals("logging:logging", provenanceJson.getValue("ruleId").jsonPrimitive.content)
        assertEquals("logging", provenanceJson.getValue("packId").jsonPrimitive.content)
        assertTrue(provenanceJson.containsKey("evidence"))

        val origin = CandidateOrigin("rule", ruleId = "normalization:normalization")
        val operation =
            CandidateOperation(
                kind = CandidateOperationKind.ADD,
                intents = listOf("NORMALIZATION"),
                startLine = 1,
                startCol = 0,
                endLine = 1,
                endCol = 4,
                startByte = 0,
                endByte = 4,
                confidence = 0.4,
                origin = origin,
            )
        val candidate =
            IpcCandidate(
                candidateId = "candidate-1",
                path = "file:///test/A.kt",
                contentHash = "hash",
                intents = listOf("NORMALIZATION"),
                startLine = 1,
                startCol = 0,
                endLine = 1,
                endCol = 4,
                startByte = 0,
                endByte = 4,
                confidence = 0.4,
                origin = origin,
                category = "pack-domain",
                fingerprints = mapOf("1" to "fingerprint"),
                operations = listOf(operation),
            )

        val tagsParams =
            IpcJson.encode(
                PublishTagsParams(
                    uri = "file:///test/A.kt",
                    version = 1,
                    hash = "hash",
                    configRevision = 2,
                    analysisVersion = 3,
                    annotations = listOf(annotation),
                    diagnostics = emptyList(),
                ),
            ).jsonObject
        assertTrue(tagsParams.containsKey("configRevision"))
        assertTrue(tagsParams.containsKey("analysisVersion"))
        assertTrue(tagsParams.containsKey("diagnostics"))
        assertFalse(tagsParams.containsKey("revision"))
        assertFalse(tagsParams.containsKey("analysisRevision"))

        val candidateParams =
            IpcJson.encode(
                PublishCandidatesParams(
                    uri = "file:///test/A.kt",
                    version = 1,
                    analysisRevision = 3,
                    proposalRevision = 4,
                    proposals = listOf(candidate),
                ),
            ).jsonObject
        assertTrue(candidateParams.containsKey("proposals"))
        assertTrue(candidateParams.containsKey("proposalRevision"))
        assertFalse(candidateParams.containsKey("groups"))
        assertFalse(candidateParams.containsKey("candidates"))
        val candidateJson = candidateParams.getValue("proposals").jsonArray.first().jsonObject
        assertTrue(candidateJson.containsKey("origin"))
        assertTrue(candidateJson.containsKey("category"))
        assertTrue(candidateJson.containsKey("fingerprints"))
        assertTrue(candidateJson.containsKey("operations"))
        assertFalse(candidateJson.containsKey("source"))
        assertFalse(candidateJson.containsKey("reason"))
        assertFalse(candidateJson.containsKey("aiEligible"))

        val suggest =
            IpcJson.encode(
                SuggestParams(uri = "file:///test/A.kt", selection = span, requestId = "r1"),
            ).jsonObject
        assertTrue(suggest.containsKey("selection"))

        val config = IpcJson.encode(ConfigResult(JsonObject(mapOf("packs" to JsonPrimitive("v2"))), listOf("project"))).jsonObject
        assertTrue(config.containsKey("config"))
        assertTrue(config.containsKey("sources"))

        val status = IpcJson.encode(StatusResult(version = "2.0", uptimeMs = 10, activeDocuments = 1)).jsonObject
        assertEquals("ok", status.getValue("status").jsonPrimitive.content)
        assertTrue(status.containsKey("uptimeMs"))

        val structuredError =
            parseElement(
                codec.encode(
                    JsonRpcMessage.ErrorResponse(
                        JsonRpcId.NumberId(5),
                        IpcErrors.methodNotFound(IpcMethods.STATUS),
                    ),
                ),
            ).jsonObject
        val errorData = structuredError.getValue("error").jsonObject.getValue("data").jsonObject
        assertEquals("unknown-method", errorData.getValue("kind").jsonPrimitive.content)

        assertEquals(64, ProtocolFingerprint.CURRENT.length)
        assertTrue(ProtocolFingerprint.canonicalSchema().contains("PublishCandidatesParams"))
        assertTrue(ProtocolFingerprint.canonicalSchema().contains("proposals"))
        assertTrue(ProtocolFingerprint.canonicalSchema().contains(IpcMethods.STATUS))
    }

    private data class TraceStep(
        val request: JsonRpcMessage,
        val responses: List<JsonObject>,
    )

    private inner class ReplayHandler(
        private val steps: ArrayDeque<TraceStep>,
    ) : IpcHandler {
        override fun initialize(params: InitializeParams): IpcResult = requestResult(IpcMethods.INITIALIZE)

        override fun shutdown(): IpcResult = requestResult(IpcMethods.SHUTDOWN)

        override fun analyze(params: AnalyzeParams): IpcResult = requestResult(IpcMethods.ANALYZE)

        override fun suggest(params: SuggestParams): IpcResult = requestResult(IpcMethods.SUGGEST)

        override fun suggestCancel(params: SuggestCancelParams): IpcResult = requestResult(IpcMethods.SUGGEST_CANCEL)

        override fun suggestLog(params: SuggestLogParams): IpcResult = requestResult(IpcMethods.SUGGEST_LOG)

        override fun status(): IpcResult = requestResult(IpcMethods.STATUS)

        override fun config(
            operation: ConfigOperation,
            params: ConfigParams,
        ): IpcResult =
            requestResult(
                when (operation) {
                    ConfigOperation.READ -> IpcMethods.CONFIG_READ
                    ConfigOperation.APPEND -> IpcMethods.CONFIG_APPEND
                    ConfigOperation.REMOVE -> IpcMethods.CONFIG_REMOVE
                    ConfigOperation.WRITE -> IpcMethods.CONFIG_WRITE
                    ConfigOperation.SCHEMA -> IpcMethods.CONFIG_SCHEMA
                },
            )

        override fun restrictionsList(params: RestrictionsListParams): IpcResult =
            requestResultAnyOf(setOf(IpcMethods.RESTRICTIONS, IpcMethods.RESTRICTIONS_LIST))

        override fun restrictionsRemove(params: RestrictionRemoveParams): IpcResult =
            requestResult(IpcMethods.RESTRICTIONS_REMOVE)

        override fun didOpen(params: DocumentParams): List<JsonRpcMessage> =
            notificationResult(IpcMethods.DID_OPEN)

        override fun didChange(params: DocumentParams): List<JsonRpcMessage> =
            notificationResult(IpcMethods.DID_CHANGE)

        override fun didClose(params: DocumentCloseParams): List<JsonRpcMessage> =
            notificationResult(IpcMethods.DID_CLOSE)

        private fun requestResult(method: String): IpcResult = requestResultAnyOf(setOf(method))

        private fun requestResultAnyOf(methods: Set<String>): IpcResult {
            val step = next(methods)
            val responseIndex = step.responses.indexOfLast { it.containsKey("result") || it.containsKey("error") }
            assertTrue(responseIndex >= 0, "request step did not contain a response")
            val notifications =
                step.responses.filterIndexed { index, _ -> index != responseIndex }.map { codec.decode(it.toString()) }
            val message = step.responses[responseIndex]
            return if (message.containsKey("error")) {
                IpcResult.Failure(errorFrom(message), notifications)
            } else {
                IpcResult.Success(message.getValue("result"), notifications)
            }
        }

        private fun notificationResult(method: String): List<JsonRpcMessage> =
            next(setOf(method)).responses.map { codec.decode(it.toString()) }

        private fun next(methods: Set<String>): TraceStep {
            assertTrue(steps.isNotEmpty(), "handler received an unexpected request")
            val step = steps.removeFirst()
            val method =
                when (val request = step.request) {
                    is JsonRpcMessage.Request -> request.method
                    is JsonRpcMessage.Notification -> request.method
                    else -> error("trace step is not an inbound message")
                }
            assertTrue(method in methods, "expected $methods but received $method")
            return step
        }

        private fun errorFrom(message: JsonObject): JsonRpcError {
            val error = message.getValue("error").jsonObject
            return JsonRpcError(
                error.getValue("code").jsonPrimitive.int,
                error.getValue("message").jsonPrimitive.content,
                error["data"],
            )
        }
    }

    private fun sessionFiles(): List<Path> =
        Files.list(corpusRoot).use { stream ->
            stream
                .filter { path ->
                    val name = path.fileName.toString()
                    name.startsWith("protocol-") && name.endsWith(".session.json") && !name.contains("-http")
                }
                .sorted()
                .toList()
        }

    private fun transcript(name: String): List<JsonObject> =
        Files.readAllLines(corpusRoot.resolve("$name.ndjson"))
            .filter { it.isNotBlank() }
            .map { parseElement(it).jsonObject }

    private fun parseObject(path: Path): JsonObject = parseElement(Files.readString(path)).jsonObject

    private fun parseElement(text: String): JsonElement = json.parseToJsonElement(text)
}
