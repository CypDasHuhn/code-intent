package dev.cint.v2.server

import dev.cint.contracts.facts.LanguageDescriptor
import dev.cint.contracts.facts.SemanticRequestKind
import dev.cint.contracts.facts.SemanticResult
import dev.cint.contracts.ports.HostSemanticChannel
import dev.cint.contracts.ports.HostSemanticChannelProvider
import dev.cint.contracts.ports.ProviderMode
import dev.cint.contracts.ports.Runtime
import dev.cint.contracts.ports.SemanticProvider
import dev.cint.contracts.ports.SemanticProviderConfig
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory
import dev.cint.runtime.DefaultRuntimeLanguageComposition
import dev.cint.runtime.RuntimeComponents
import dev.cint.runtime.RuntimeConfig
import dev.cint.runtime.RuntimeFactory
import dev.cint.v2.languages.LanguageProviderDefaults
import dev.cint.v2.languages.LanguageProviderFactory
import dev.cint.v2.languages.LanguageSupportFactory
import dev.cint.v2.languages.TreeSitterLanguageRegistry
import dev.cint.v2.testing.fakes.FakeSemanticProvider
import dev.cint.v2.testing.fakes.unavailableResult
import java.io.BufferedReader
import java.io.BufferedWriter
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class FrozenProtocolParityTest {
    private val json = Json { prettyPrint = true }
    private val corpusRoot = Paths.get(System.getProperty("v2.frozenCorpusRoot"))
    private val running = mutableListOf<RunningServer>()

    @AfterTest
    fun cleanUp() {
        running.forEach { runCatching { it.close() } }
    }

    @Test
    fun replaysFrozenSessionsAgainstTheV2ServerAndDocumentsDifferences() {
        val scenarios = mutableListOf<JsonObject>()
        val rendererChecks = mutableListOf<JsonObject>()
        val defects = mutableListOf<JsonObject>()
        val exercisedRules = mutableSetOf<String>()
        val collected = linkedMapOf<String, MutableList<JsonObject>>()

        for (sessionPath in sessionFiles()) {
            val session = json.parseToJsonElement(Files.readString(sessionPath)).jsonObject
            val name = sessionPath.fileName.toString().removeSuffix(".session.json")
            val steps = session.getValue("steps").jsonArray
            val source = scenarios().getValue(session.getValue("scenario").jsonPrimitive.content).source
            val root = Files.createTempDirectory("cint-v2-ipc-$name")
            try {
                val sources = root.resolve("fixtures/sources/$source")
                Files.createDirectories(sources)
                for (file in Files.list(corpusRoot.resolve("fixtures/sources/$source")).use { it.toList() }) {
                    Files.copy(file, sources.resolve(file.fileName))
                }
                val server = start(sources, sources)
                val client = Client(server.socket)
                val frozenSequence = steps.flatMap { step -> step.jsonObject.getValue("messages").jsonArray.map { methodOf(it.jsonObject) } }
                val actualSequence = mutableListOf<String>()
                val stepReports = mutableListOf<JsonObject>()
                collected[name] = mutableListOf()
                for ((index, stepElement) in steps.withIndex()) {
                    val step = stepElement.jsonObject
                    val request = step.getValue("request").jsonObject
                    val requestId = request["id"]?.jsonPrimitive?.content
                    val method = request["method"]?.jsonPrimitive?.content.orEmpty()
                    client.send(request.toString())
                    val expectedCount = V2_COUNTS.getValue(name)[index]
                    val messages = client.readExpected(expectedCount)
                    collected.getValue(name).addAll(messages)
                    actualSequence += messages.map(::methodOf)
                    val frozenMessages = step.getValue("messages").jsonArray.map { it.jsonObject }
                    val frozenMethods = frozenMessages.map(::methodOf)
                    comparePayloads(name, index, root, frozenMessages, messages, defects, exercisedRules)
                    stepReports += buildJsonObject {
                        put("index", index)
                        put("method", method)
                        put("frozenMethods", JsonArray(frozenMethods.map(::JsonPrimitive)))
                        put("v2Methods", JsonArray(messages.map { JsonPrimitive(methodOf(it)) }))
                        put("methodShapeMatches", frozenMethods == messages.map(::methodOf))
                    }
                    if (requestId != null) {
                        assertTrue(
                            messages.any { it["id"]?.jsonPrimitive?.content == requestId },
                            "$name step $index ($method) produced no response",
                        )
                    }
                }
                client.close()
                server.close()

                val intentional =
                    DIFFERENCE_RULES
                        .filter { it.scenario == name && ruleKey(it) in exercisedRules }
                        .map { it.id }
                        .distinct()
                scenarios += buildJsonObject {
                    put("name", name)
                    put("frozenMessages", frozenSequence.size)
                    put("v2Messages", actualSequence.size)
                    put("frozenSequence", JsonArray(frozenSequence.map(::JsonPrimitive)))
                    put("v2Sequence", JsonArray(actualSequence.map(::JsonPrimitive)))
                    put("intentionalDifferenceIds", JsonArray(intentional.map(::JsonPrimitive)))
                    put("steps", buildJsonArray { stepReports.forEach(::add) })
                }
            } finally {
                root.toFile().deleteRecursively()
            }
        }

        rendererChecks += rendererCheck(defects, collected)
        val unexercisedRenderer =
            rendererChecks
                .flatMap { it.getValue("unexercisedDifferenceIds").jsonArray }
                .map { it.jsonPrimitive.content }
        assertTrue(unexercisedRenderer.isEmpty(), "registered intentional renderer differences were not exercised: $unexercisedRenderer")
        val unexercised = DIFFERENCE_RULES.map(::ruleKey).filterNot(exercisedRules::contains)
        assertTrue(unexercised.isEmpty(), "registered intentional IPC differences were not exercised: $unexercised")
        assertTrue(scenarios.isNotEmpty())
        assertTrue(defects.all { it.getValue("ticket").jsonPrimitive.content.isNotBlank() })

        val report =
            buildJsonObject {
                put("formatVersion", 1)
                put("ticket", "084-v2-parity-integration")
                put("closable", defects.isEmpty())
                put(
                    "intentionalDifferences",
                    buildJsonArray {
                        for ((id, ticket, reason) in INTENTIONAL) {
                            add(
                                buildJsonObject {
                                    put("id", id)
                                    put("ticket", ticket)
                                    put("reason", reason)
                                },
                            )
                        }
                    },
                )
                put(
                    "summary",
                    buildJsonObject {
                        put("scenarios", scenarios.size)
                        put("defectCount", defects.size)
                        put("intentionalDifferenceCount", INTENTIONAL.size)
                    },
                )
                put("scenarios", buildJsonArray { scenarios.forEach(::add) })
                put("renderer", buildJsonArray { rendererChecks.forEach(::add) })
                put(
                    "http",
                    buildJsonArray {
                        for (name in listOf("protocol-http-health", "protocol-http-analyze")) {
                            add(
                                buildJsonObject {
                                    put("scenario", name)
                                    put("replayed", false)
                                    put("intentionalDifferenceId", "http.facade")
                                },
                            )
                        }
                    },
                )
                put("defects", buildJsonArray { defects.forEach(::add) })
            }
        val reportDir = Path.of(System.getProperty("v2.parityReportDir"))
        Files.createDirectories(reportDir)
        val normalized = normalizeReport(report) as JsonObject
        Files.writeString(reportDir.resolve("ipc-report.json"), json.encodeToString(JsonObject.serializer(), normalized) + "\n")

        assertTrue(
            rendererChecks.all { it.getValue("verified").jsonPrimitive.content.toBoolean() },
            "renderer-visible derivation did not run: $rendererChecks",
        )
        val signatures = defects.map(::defectSignature).groupingBy { it }.eachCount().toSortedMap()
        val baselinePath = Path.of(System.getProperty("v2.v2Root"), "parity", "ipc-defect-baseline.json")
        assertTrue(Files.isRegularFile(baselinePath), "missing IPC defect baseline $baselinePath")
        val baseline =
            json.parseToJsonElement(Files.readString(baselinePath)).jsonObject
                .getValue("signatures").jsonObject
                .mapValues { (_, value) -> value.jsonPrimitive.content.toInt() }
        assertEquals(baseline.toSortedMap(), signatures, "IPC defect identities/content drifted from the registered baseline")
    }

    private fun comparePayloads(
        scenario: String,
        step: Int,
        root: Path,
        frozen: List<JsonObject>,
        actual: List<JsonObject>,
        defects: MutableList<JsonObject>,
        exercised: MutableSet<String>,
    ) {
        val frozenMethods = frozen.map(::methodOf)
        val actualMethods = actual.map(::methodOf)
        val stepRule =
            DIFFERENCE_RULES.firstOrNull { rule ->
                rule.scenario == scenario &&
                    rule.step == step &&
                    rule.expectedMethods == frozenMethods &&
                    rule.actualMethods == actualMethods
            }
        if (stepRule != null) {
            exercised += ruleKey(stepRule)
            return
        }
        val size = maxOf(frozen.size, actual.size)
        for (index in 0 until size) {
            val expected = frozen.getOrNull(index)?.let { normalizeReplayPaths(it, root).jsonObject }
            val observed = actual.getOrNull(index)?.let { normalizeReplayPaths(it, root).jsonObject }
            if (expected == null || observed == null) {
                defects += defect(
                    "ipc.message-count",
                    "094-v2-ipc-payload-parity",
                    "V2 emitted a different number of messages for this step",
                    mapOf("scenario" to scenario, "step" to step.toString(), "method" to (expected?.let(::methodOf) ?: observed?.let(::methodOf) ?: "unknown")),
                )
                continue
            }
            if (methodOf(expected) != methodOf(observed)) {
                defects += defect(
                    "ipc.method",
                    "094-v2-ipc-payload-parity",
                    "V2 emitted a different message method for this step",
                    mapOf("scenario" to scenario, "step" to step.toString(), "frozen" to methodOf(expected), "v2" to methodOf(observed)),
                )
                continue
            }
            val differences = mutableListOf<String>()
            diffJson(expected, observed, "", differences)
            val remaining = mutableListOf<String>()
            for (path in differences) {
                val rule =
                    DIFFERENCE_RULES.firstOrNull { candidate ->
                        candidate.scenario == scenario &&
                            candidate.step == step &&
                            candidate.method == methodOf(expected) &&
                            (candidate.pathPrefix == null || path.startsWith(candidate.pathPrefix))
                    }
                if (rule != null) {
                    exercised += ruleKey(rule)
                } else {
                    remaining += path
                }
            }
            if (remaining.isNotEmpty()) {
                val ticket =
                    when {
                        remaining.any { it.contains("candidates") || it.contains("groups") || it.contains("proposals") } -> "091-v2-candidate-parity"
                        remaining.any { it.contains("hash") || it.contains("revision") || it.contains("contentHash") } -> "094-v2-ipc-payload-parity"
                        else -> "092-v2-service-init-parity"
                    }
                defects += defect(
                    "ipc.payload",
                    ticket,
                    "V2 message payload differs from the frozen transcript",
                    mapOf(
                        "scenario" to scenario,
                        "step" to step.toString(),
                        "method" to methodOf(expected),
                        "differences" to remaining.take(40).joinToString(" | "),
                    ),
                )
            }
        }
    }

    private fun diffJson(
        expected: JsonElement,
        actual: JsonElement,
        path: String,
        output: MutableList<String>,
    ) {
        when {
            expected is JsonObject && actual is JsonObject -> {
                for (key in (expected.keys + actual.keys).sorted()) {
                    val next = if (path.isEmpty()) key else "$path.$key"
                    val left = expected[key]
                    val right = actual[key]
                    when {
                        left == null -> output += "$next expected=<missing> actual=$right"
                        right == null -> output += "$next expected=$left actual=<missing>"
                        else -> diffJson(left, right, next, output)
                    }
                }
            }
            expected is JsonArray && actual is JsonArray -> {
                if (expected.size != actual.size) {
                    output += "$path.size expected=${expected.size} actual=${actual.size}"
                }
                for (index in 0 until minOf(expected.size, actual.size)) {
                    diffJson(expected[index], actual[index], "$path[$index]", output)
                }
            }
            expected != actual -> output += "$path expected=$expected actual=$actual"
        }
    }

    private fun rendererCheck(
        defects: MutableList<JsonObject>,
        collected: Map<String, List<JsonObject>>,
    ): JsonObject {
        val root = Files.createTempDirectory("cint-v2-renderer")
        try {
            val sources = Files.createDirectories(root.resolve("sources"))
            Files.copy(corpusRoot.resolve("fixtures/sources/kotlin/Bookkeeping.kt"), sources.resolve("Bookkeeping.kt"))
            val server = start(root, sources)
            val client = Client(server.socket)
            client.send(
                """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocol":5,"fingerprint":"${dev.cint.ipc.ProtocolFingerprint.CURRENT}"}}""",
            )
            val initialize = client.readExpected(1).single().getValue("result").jsonObject
            val uri = "file://${sources.resolve("Bookkeeping.kt")}"
            client.send(
                """{"jsonrpc":"2.0","method":"textDocument/didOpen","params":{"uri":"$uri","languageId":"kotlin","version":1,"text":${JsonPrimitive(Files.readString(sources.resolve("Bookkeeping.kt")))}}}""",
            )
            val openMessages = client.readExpected(2)
            val tagsParams = openMessages.first { methodOf(it) == "cint/publishTags" }.getValue("params").jsonObject
            val candidateParams = openMessages.first { methodOf(it) == "cint/publishCandidates" }.getValue("params").jsonObject
            val actualAnnotations = tagsParams.getValue("annotations").jsonArray
            val baseline = json.parseToJsonElement(Files.readString(corpusRoot.resolve("renderer-visible-v1.json"))).jsonObject
            val baselineTags = baseline.getValue("tags").jsonObject
            val baselineAnnotations = baselineTags.getValue("annotations").jsonArray
            val projected =
                JsonArray(
                    actualAnnotations.map { element ->
                        val annotation = element.jsonObject
                        val projectedProvenance =
                            JsonArray(
                                annotation.getValue("provenance").jsonArray.map { contribution ->
                                    val value = contribution.jsonObject
                                    buildJsonObject {
                                        put("rule", value.getValue("ruleId"))
                                        put("role", value.getValue("role"))
                                        put("intents", value.getValue("intents"))
                                        put("source", value.getValue("source"))
                                        put("confidence", value.getValue("confidence"))
                                        put("startLine", value.getValue("startLine"))
                                        put("startCol", value.getValue("startCol"))
                                        put("endLine", value.getValue("endLine"))
                                        put("endCol", value.getValue("endCol"))
                                        put("startByte", value.getValue("startByte"))
                                        put("endByte", value.getValue("endByte"))
                                    }
                                },
                            )
                        buildJsonObject {
                            for (key in listOf("role", "intents", "source", "confidence", "startLine", "startCol", "endLine", "endCol", "startByte", "endByte", "nodeAnchor")) {
                                annotation[key]?.let { put(key, it) }
                            }
                            put("provenance", projectedProvenance)
                        }
                    },
                )
            val initializedLanguagesMatch =
                baseline.getValue("initialize").jsonObject.getValue("languages") == initialize.getValue("languages")
            val checks =
                linkedMapOf(
                    "initialize.capabilities" to initialize.containsKey("capabilities"),
                    "initialize.fingerprint" to initialize.containsKey("fingerprint"),
                    "initialize.languages" to initializedLanguagesMatch,
                    "publishTags.configRevision" to tagsParams.containsKey("configRevision"),
                    "publishTags.analysisVersion" to tagsParams.containsKey("analysisVersion"),
                    "publishTags.diagnostics" to tagsParams.containsKey("diagnostics"),
                    "publishCandidates.proposals" to candidateParams.containsKey("proposals"),
                    "publishCandidates.proposalRevision" to candidateParams.containsKey("proposalRevision"),
                    "annotation.flatSpan" to
                        actualAnnotations.all {
                            listOf("startLine", "startCol", "endLine", "endCol", "startByte", "endByte").all(it.jsonObject::containsKey)
                        },
                    "annotation.provenanceIdentity" to
                        actualAnnotations.all {
                            it.jsonObject.getValue("provenance").jsonArray.all { contribution ->
                                contribution.jsonObject.containsKey("ruleId") && contribution.jsonObject.containsKey("packId")
                            }
                        },
                    "annotation.confidenceFinite" to
                        actualAnnotations.all { it.jsonObject.getValue("confidence").jsonPrimitive.content.toDoubleOrNull()?.isFinite() == true },
                )
            client.close()
            server.close()

            val exercised = mutableSetOf<String>()
            val sections =
                listOf(
                    rendererSection(
                        "tags.annotations",
                        baselineAnnotations,
                        projected,
                        emptyList(),
                        "frozen-v1-corpus/renderer-visible-v1.json#tags.annotations",
                        exercised,
                        defects,
                        "renderer.annotations",
                        "090-v2-composition-parity",
                        "V2-derived renderer tag projection differs from the frozen renderer baseline",
                    ),
                    rendererSection(
                        "candidates",
                        baseline.getValue("candidates"),
                        projectCandidates(collected["protocol-suggest-flow"].orEmpty()),
                        emptyList(),
                        "frozen-v1-corpus/renderer-visible-v1.json#candidates",
                        exercised,
                        defects,
                        "renderer.candidates",
                        "091-v2-candidate-parity",
                        "V2-derived renderer candidate projection differs from the frozen renderer baseline",
                        registeredOnIpcSurface = true,
                    ),
                    rendererSection(
                        "config",
                        baseline.getValue("configFlow"),
                        projectConfig(collected["protocol-config-flow"].orEmpty(), baseline.getValue("configFlow").jsonObject),
                        RENDERER_DIFFERENCE_RULES.filter { it.section == "config" },
                        "frozen-v1-corpus/renderer-visible-v1.json#configFlow",
                        exercised,
                        defects,
                        "renderer.config",
                        "069-v2-packs-and-defaults",
                        "V2 config projection differs from the frozen renderer baseline",
                    ),
                    rendererSection(
                        "documentLifecycle",
                        baseline.getValue("documentLifecycle"),
                        projectLifecycle(collected["protocol-document-lifecycle"].orEmpty()),
                        emptyList(),
                        "frozen-v1-corpus/renderer-visible-v1.json#documentLifecycle",
                        exercised,
                        defects,
                        "renderer.lifecycle",
                        "090-v2-composition-parity",
                        "V2 lifecycle projection differs from the frozen renderer baseline",
                        registeredOnIpcSurface = true,
                    ),
                    rendererSection(
                        "suggest",
                        baseline.getValue("suggest"),
                        projectSuggest(
                            collected["protocol-suggest-flow"].orEmpty(),
                            collected["protocol-suggest-disabled-flow"].orEmpty(),
                        ),
                        RENDERER_DIFFERENCE_RULES.filter { it.section == "suggest" },
                        "frozen-v1-corpus/renderer-visible-v1.json#suggest",
                        exercised,
                        defects,
                        "renderer.suggest",
                        "079-v2-ipc-protocol",
                        "V2 suggest projection differs from the frozen renderer baseline",
                    ),
                    rendererSection(
                        "restrictions",
                        baseline.getValue("restrictions"),
                        projectRestrictions(collected["protocol-restrictions-flow"].orEmpty()),
                        RENDERER_DIFFERENCE_RULES.filter { it.section == "restrictions" },
                        "frozen-v1-corpus/renderer-visible-v1.json#restrictions",
                        exercised,
                        defects,
                        "renderer.restrictions",
                        "059-ai-restriction-proposals",
                        "V2 restrictions projection differs from the frozen renderer baseline",
                    ),
                    rendererSection(
                        "status",
                        buildJsonObject { put("liveStatusError", baseline.getValue("status").jsonObject.getValue("liveStatusError")) },
                        buildJsonObject { put("liveStatus", payloadOf(responseById(collected["protocol-status-flow"].orEmpty(), "2"))) },
                        RENDERER_DIFFERENCE_RULES.filter { it.section == "status" },
                        "frozen-v1-corpus/renderer-visible-v1.json#status",
                        exercised,
                        defects,
                        "renderer.status",
                        "081-v2-server",
                        "V2 status projection differs from the frozen renderer baseline",
                    ),
                )
            val unexercised = RENDERER_DIFFERENCE_RULES.map(::rendererRuleKey).filterNot(exercised::contains)
            return buildJsonObject {
                put("verified", true)
                put("source", "V2 protocol 5 tags; V2 frozen-replay projections for candidates, config, lifecycle, suggest, restrictions, and status")
                put("passed", sections.all { it.getValue("unmatchedCount").jsonPrimitive.content.toInt() == 0 })
                put("differenceCount", sections.sumOf { it.getValue("differenceCount").jsonPrimitive.content.toInt() })
                put("unmatchedCount", sections.sumOf { it.getValue("unmatchedCount").jsonPrimitive.content.toInt() })
                put("unexercisedDifferenceIds", JsonArray(unexercised.map(::JsonPrimitive)))
                put(
                    "checks",
                    buildJsonObject { checks.toSortedMap().forEach { (key, value) -> put(key, value) } },
                )
                put("annotationCount", actualAnnotations.size)
                put("sections", buildJsonArray { sections.forEach(::add) })
                put(
                    "intentionalSections",
                    buildJsonArray {
                        for (rule in RENDERER_DIFFERENCE_RULES.filter { rendererRuleKey(it) in exercised }) {
                            add(
                                buildJsonObject {
                                    put("section", rule.section)
                                    put("intentionalDifferenceId", rule.id)
                                },
                            )
                        }
                    },
                )
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun rendererSection(
        name: String,
        baseline: JsonElement,
        actual: JsonElement,
        rules: List<RendererDifferenceRule>,
        baselinePath: String,
        exercised: MutableSet<String>,
        defects: MutableList<JsonObject>,
        defectId: String,
        ticket: String,
        reason: String,
        registeredOnIpcSurface: Boolean = false,
    ): JsonObject {
        val differences = mutableListOf<String>()
        diffJson(baseline, actual, name, differences)
        val unmatched = mutableListOf<String>()
        val intentional = linkedSetOf<String>()
        for (path in differences) {
            val rule = rules.firstOrNull { path.startsWith("$name.${it.pathPrefix}") }
            if (rule == null) {
                unmatched += path
            } else {
                exercised += rendererRuleKey(rule)
                intentional += rule.id
            }
        }
        if (unmatched.isNotEmpty() && !registeredOnIpcSurface) {
            defects += defect(
                defectId,
                ticket,
                reason,
                mapOf(
                    "section" to name,
                    "differences" to unmatched.take(40).joinToString(" | "),
                ),
            )
        }
        return buildJsonObject {
            put("name", name)
            put("baseline", baselinePath)
            put("compared", true)
            put("passed", unmatched.isEmpty())
            put("differenceCount", differences.size)
            put("unmatchedCount", unmatched.size)
            put("registeredOnIpcSurface", registeredOnIpcSurface)
            put("ownerTicket", ticket)
            put("intentionalDifferenceIds", JsonArray(intentional.map(::JsonPrimitive)))
            put("differences", JsonArray(differences.take(40).map(::JsonPrimitive)))
        }
    }

    private fun projectLifecycle(messages: List<JsonObject>): JsonElement =
        buildJsonArray {
            for (message in messages.filter { methodOf(it) in setOf("cint/publishTags", "cint/publishCandidates") }) {
                val params = message.getValue("params").jsonObject
                add(
                    buildJsonObject {
                        put("method", methodOf(message))
                        params["uri"]?.let { put("uri", it) }
                        params["version"]?.let { put("version", it) }
                        put("analysisRevision", params["analysisRevision"] ?: JsonNull)
                        put("revision", params["revision"] ?: JsonNull)
                        put("suggestionRevision", params["suggestionRevision"] ?: JsonNull)
                        put("annotations", (params["annotations"] as? JsonArray)?.size ?: 0)
                        put("candidates", (params["candidates"] as? JsonArray)?.size ?: 0)
                        put("groups", (params["groups"] as? JsonArray)?.size ?: 0)
                    },
                )
            }
        }

    private fun projectCandidates(messages: List<JsonObject>): JsonElement {
        val params =
            messages
                .firstOrNull { methodOf(it) == "cint/publishCandidates" && it.getValue("params").jsonObject.containsKey("candidates") }
                ?.getValue("params")
                ?.jsonObject
                ?: return buildJsonObject {
                    put("analysisRevision", JsonNull)
                    put("candidateCount", 0)
                    put("candidates", JsonArray(emptyList()))
                }
        val candidates = params["candidates"] as? JsonArray ?: JsonArray(emptyList())
        return buildJsonObject {
            put("analysisRevision", params["analysisRevision"] ?: JsonNull)
            put("candidateCount", candidates.size)
            put("candidates", candidates)
        }
    }

    private fun projectConfig(
        messages: List<JsonObject>,
        baselineFlow: JsonObject,
    ): JsonElement =
        buildJsonObject {
            put(
                "reads",
                buildJsonArray {
                    for (read in baselineFlow.getValue("reads").jsonArray) {
                        val expected = read.jsonObject
                        val requestId = expected.getValue("requestId").jsonPrimitive.content
                        val response = responseById(messages, requestId)
                        add(
                            buildJsonObject {
                                put("requestId", requestId.toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(requestId))
                                when {
                                    response == null -> put("missing", true)
                                    response.containsKey("error") -> put("error", response.getValue("error"))
                                    else -> {
                                        val result = response["result"] ?: JsonNull
                                        put("overlay", (result as? JsonObject)?.get("overlay") ?: JsonNull)
                                    }
                                }
                            },
                        )
                    }
                },
            )
        }

    private fun projectSuggest(
        enabledMessages: List<JsonObject>,
        disabledMessages: List<JsonObject>,
    ): JsonElement =
        buildJsonObject {
            put("disabled", payloadOf(responseById(disabledMessages, "2")))
            put("enabled", payloadOf(responseById(enabledMessages, "2")))
            put("log", payloadOf(responseById(enabledMessages, "4")))
            put("progressNotifications", JsonArray(emptyList()))
        }

    private fun projectRestrictions(messages: List<JsonObject>): JsonElement =
        buildJsonObject {
            put("afterRemoval", payloadOf(responseById(messages, "4")))
            put("listed", payloadOf(responseById(messages, "2")))
            put("removed", payloadOf(responseById(messages, "3")))
        }

    private fun payloadOf(message: JsonObject?): JsonElement =
        when {
            message == null -> JsonNull
            message.containsKey("error") -> buildJsonObject { put("error", message.getValue("error")) }
            else -> message["result"] ?: JsonNull
        }

    private fun responseById(
        messages: List<JsonObject>,
        id: String,
    ): JsonObject? = messages.firstOrNull { it["id"]?.jsonPrimitive?.content == id }

    private fun normalizeReplayPaths(
        element: JsonElement,
        root: Path,
    ): JsonElement =
        when (element) {
            is JsonObject -> JsonObject(element.mapValues { (_, value) -> normalizeReplayPaths(value, root) })
            is JsonArray -> JsonArray(element.map { normalizeReplayPaths(it, root) })
            is JsonPrimitive ->
                if (element.isString) {
                    JsonPrimitive(element.content.replace(root.toString(), "<CORPUS_ROOT>"))
                } else {
                    element
                }
            else -> element
        }

    private fun normalizeReport(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> JsonObject(element.mapValues { (_, value) -> normalizeReport(value) })
            is JsonArray -> JsonArray(element.map(::normalizeReport))
            is JsonPrimitive ->
                if (element.isString) {
                    JsonPrimitive(
                        element.content
                            .replace(Regex("""/tmp/[^\s\"\]]*"""), "<TMP>")
                            .replace(Regex("""/var/folders/[^\s\"\]]*"""), "<TMP>"),
                    )
                } else {
                    element
                }
            else -> element
        }

    private fun defectSignature(defect: JsonObject): String {
        val normalized = normalizeReport(defect).jsonObject
        val payload = "${normalized.getValue("id").jsonPrimitive.content}|${normalized.getValue("ticket").jsonPrimitive.content}|${normalized.getValue("context")}"
        return MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun ruleKey(rule: DifferenceRule): String =
        "${rule.scenario}:${rule.step}:${rule.id}:${rule.method}:${rule.pathPrefix}:${rule.expectedMethods}:${rule.actualMethods}"

    private fun rendererRuleKey(rule: RendererDifferenceRule): String =
        "${rule.section}:${rule.pathPrefix}:${rule.id}"

    private fun start(
        root: Path,
        sources: Path,
    ): RunningServer {
        val directory = Files.createTempDirectory("cint-v2-ipc-server")
        val socket = directory.resolve("cint.sock")
        val server =
            LiveServer(
                runtime(sources),
                ServerOptions(socketPath = socket, root = root, analysisTimeoutMs = 30_000, closeGraceMs = 2_000),
            )
        val thread =
            Thread({ server.run() }, "cint-v2-ipc-parity").apply {
                isDaemon = true
                start()
            }
        server.awaitStarted()
        return RunningServer(server, thread, directory, socket).also(running::add)
    }

    private fun runtime(sources: Path): Runtime {
        val composition =
            DefaultRuntimeLanguageComposition(
                TreeSitterLanguageRegistry,
                LanguageSupportFactory(
                    catalog = TreeSitterLanguageRegistry,
                    providerFactory =
                        object : LanguageProviderFactory {
                            override fun create(
                                descriptor: LanguageDescriptor,
                                config: SemanticProviderConfig,
                                hostChannel: HostSemanticChannel?,
                                hostChannelProvider: HostSemanticChannelProvider?,
                            ): ContractResult<SemanticProvider> =
                                ResultFactory.success(
                                    FakeSemanticProvider(
                                        language = descriptor.id,
                                        responseFactory = { request ->
                                            ResultFactory.success(
                                                when (request.kind) {
                                                    SemanticRequestKind.TYPE_AT -> SemanticResult.TypeAt(typeRef = null)
                                                    SemanticRequestKind.SYMBOL_AT -> SemanticResult.SymbolAt(symbol = null)
                                                    SemanticRequestKind.DEFINITION_OF -> SemanticResult.DefinitionOf(emptyList())
                                                    SemanticRequestKind.DIAGNOSTICS_FOR -> SemanticResult.DiagnosticsFor(emptyList())
                                                    SemanticRequestKind.HOVER_AT -> SemanticResult.HoverAt(content = null)
                                                    SemanticRequestKind.REFERENCES_AT -> SemanticResult.ReferencesAt(emptyList())
                                                },
                                            )
                                        },
                                    ),
                                )
                        },
                    providerDefaults = LanguageProviderDefaults { _ ->
                        SemanticProviderConfig(mode = ProviderMode.MANAGED, command = "ipc-parity-neutral")
                    },
                ),
            )
        return RuntimeFactory.create(
            RuntimeConfig(rootPath = sources.toString()),
            RuntimeComponents(languageComposition = composition),
        ).getOrThrow()
    }

    private fun scenarios(): Map<String, Scenario> {
        val data = json.parseToJsonElement(Files.readString(corpusRoot.resolve("fixtures/scenarios.json"))).jsonObject
        return data.getValue("scenarios").jsonObject.mapValues { (_, value) ->
            val entry = value.jsonObject
            Scenario(entry.getValue("source").jsonPrimitive.content)
        }
    }

    private fun sessionFiles(): List<Path> =
        Files.list(corpusRoot).use { stream ->
            stream
                .filter { path ->
                    val name = path.fileName.toString()
                    name.startsWith("protocol-") && name.endsWith(".session.json") && !name.contains("-http")
                }.sorted()
                .toList()
        }

    private fun intentionalIds(name: String): List<String> =
        when {
            name.contains("config") -> listOf("config.protocol", "server.unsupported-operations")
            name.contains("suggest") -> listOf("suggest.protocol", "server.unsupported-operations")
            name.contains("restrictions") -> listOf("restrictions.protocol", "server.unsupported-operations")
            name.contains("status") -> listOf("status.protocol", "error.handling")
            name.contains("error") -> listOf("error.handling", "restrictions.protocol", "config.protocol")
            else -> listOf("annotation.shape", "candidate.shape")
        }

    private fun methodOf(message: JsonObject): String =
        message["method"]?.jsonPrimitive?.content
            ?: if (message.containsKey("error")) "error:${message.getValue("error").jsonObject["code"]?.jsonPrimitive?.content}"
            else "response:${message["id"]?.jsonPrimitive?.content}"

    private fun defect(
        id: String,
        ticket: String,
        reason: String,
        context: Map<String, String>,
    ): JsonObject =
        buildJsonObject {
            put("id", id)
            put("ticket", ticket)
            put("reason", reason)
            put("context", buildJsonObject { context.toSortedMap().forEach { (key, value) -> put(key, value) } })
        }

    private data class Scenario(val source: String)

    private class RunningServer(
        val server: LiveServer,
        private val thread: Thread,
        private val directory: Path,
        val socket: Path,
    ) {
        fun close() {
            server.requestShutdown()
            server.awaitStopped()
            thread.join(5_000)
            directory.toFile().deleteRecursively()
        }
    }

    private class Client(socket: Path) : AutoCloseable {
        private val channel =
            SocketChannel.open(StandardProtocolFamily.UNIX).apply {
                connect(UnixDomainSocketAddress.of(socket))
            }
        private val reader = BufferedReader(Channels.newReader(channel, StandardCharsets.UTF_8))
        private val writer = BufferedWriter(Channels.newWriter(channel, StandardCharsets.UTF_8))
        private val lines = LinkedBlockingQueue<String>()
        private val json = Json
        private val readerThread =
            Thread {
                try {
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isNotBlank()) lines.put(line)
                    }
                } catch (_: Exception) {
                }
            }.apply {
                isDaemon = true
                start()
            }

        fun send(line: String) {
            synchronized(writer) {
                writer.write(line)
                writer.write("\n")
                writer.flush()
            }
        }

        fun readExpected(count: Int): List<JsonObject> {
            val messages = mutableListOf<JsonObject>()
            val deadline = System.currentTimeMillis() + 10_000
            while (messages.size < count && System.currentTimeMillis() < deadline) {
                val line = lines.poll(500, TimeUnit.MILLISECONDS) ?: continue
                messages += json.parseToJsonElement(line).jsonObject
            }
            return messages
        }

        override fun close() {
            runCatching { channel.close() }
        }
    }

    private data class DifferenceRule(
        val scenario: String,
        val step: Int,
        val id: String,
        val expectedMethods: List<String>? = null,
        val actualMethods: List<String>? = null,
        val method: String? = null,
        val pathPrefix: String? = null,
    )

    private data class RendererDifferenceRule(
        val section: String,
        val pathPrefix: String,
        val id: String,
        val ticket: String,
        val reason: String,
    )

    private companion object {
        val DIFFERENCE_RULES: List<DifferenceRule> =
            buildList {
                val scenarios = listOf(
                    "protocol-kotlin-analyze",
                    "protocol-typescript-analyze",
                    "protocol-config-flow",
                    "protocol-suggest-flow",
                    "protocol-suggest-disabled-flow",
                    "protocol-restrictions-flow",
                    "protocol-document-lifecycle",
                    "protocol-status-flow",
                    "protocol-error-flow",
                )
                for (scenario in scenarios) {
                    add(DifferenceRule(scenario, 0, "initialize.pack-fields", method = "response:1", pathPrefix = "result.config.packs"))
                }
                add(DifferenceRule("protocol-error-flow", 0, "protocol.version", method = "response:1", pathPrefix = "result.protocol"))
                for (scenario in listOf("protocol-error-flow", "protocol-restrictions-flow", "protocol-suggest-flow")) {
                    add(DifferenceRule(scenario, 0, "initialize.suggestions", method = "response:1", pathPrefix = "result.suggestions"))
                }
                for (step in 1..7) {
                    add(DifferenceRule("protocol-config-flow", step, "config.protocol", expectedMethods = listOf("response:${step + 1}"), actualMethods = listOf("error:-32000")))
                }
                add(DifferenceRule("protocol-kotlin-analyze", 3, "config.protocol", expectedMethods = listOf("response:3"), actualMethods = listOf("error:-32000")))
                add(DifferenceRule("protocol-kotlin-analyze", 4, "config.protocol", expectedMethods = listOf("cint/publishTags", "cint/publishCandidates", "response:4"), actualMethods = listOf("error:-32000")))
                add(DifferenceRule("protocol-kotlin-analyze", 6, "config.protocol", expectedMethods = listOf("cint/publishTags", "cint/publishCandidates", "response:6"), actualMethods = listOf("error:-32000")))
                add(DifferenceRule("protocol-restrictions-flow", 2, "restrictions.protocol", expectedMethods = listOf("response:2"), actualMethods = listOf("error:-32000")))
                add(DifferenceRule("protocol-restrictions-flow", 3, "restrictions.protocol", expectedMethods = listOf("cint/publishTags", "cint/publishCandidates", "response:3"), actualMethods = listOf("error:-32000")))
                add(DifferenceRule("protocol-restrictions-flow", 4, "restrictions.protocol", expectedMethods = listOf("response:4"), actualMethods = listOf("error:-32000")))
                add(DifferenceRule("protocol-suggest-disabled-flow", 2, "suggest.protocol", expectedMethods = listOf("response:2"), actualMethods = listOf("error:-32000")))
                add(DifferenceRule("protocol-suggest-flow", 2, "suggest.protocol", expectedMethods = listOf("cint/publishCandidates", "response:2"), actualMethods = listOf("error:-32000")))
                add(DifferenceRule("protocol-suggest-flow", 3, "suggest.protocol", expectedMethods = listOf("response:3"), actualMethods = listOf("error:-32000")))
                add(DifferenceRule("protocol-suggest-flow", 4, "suggest.protocol", expectedMethods = listOf("response:4"), actualMethods = listOf("error:-32000")))
                add(DifferenceRule("protocol-status-flow", 1, "status.protocol", expectedMethods = listOf("error:-32000"), actualMethods = listOf("response:2")))
                add(DifferenceRule("protocol-error-flow", 1, "restrictions.protocol", method = "error:-32000", pathPrefix = "error.message"))
                add(DifferenceRule("protocol-error-flow", 1, "restrictions.protocol", method = "error:-32000", pathPrefix = "error.data"))
                add(DifferenceRule("protocol-error-flow", 2, "config.protocol", method = "error:-32000", pathPrefix = "error.message"))
                add(DifferenceRule("protocol-error-flow", 2, "config.protocol", method = "error:-32000", pathPrefix = "error.data"))
                add(DifferenceRule("protocol-error-flow", 3, "config.protocol", method = "error:-32000", pathPrefix = "error.message"))
                add(DifferenceRule("protocol-error-flow", 3, "config.protocol", method = "error:-32000", pathPrefix = "error.data"))
            }

        val RENDERER_DIFFERENCE_RULES: List<RendererDifferenceRule> =
            buildList {
                for (index in 0..3) {
                    add(
                        RendererDifferenceRule(
                            "config",
                            "reads[$index]",
                            "config.protocol",
                            "069-v2-packs-and-defaults",
                            "V2 config is JSON-first and the live server reports config operations as unsupported operations.",
                        ),
                    )
                }
                for (section in listOf("disabled", "enabled", "log")) {
                    add(
                        RendererDifferenceRule(
                            "suggest",
                            section,
                            "suggest.protocol",
                            "079-v2-ipc-protocol",
                            "V2 suggest uses selection spans and streaming; the live server reports suggestion operations as unsupported.",
                        ),
                    )
                }
                for (section in listOf("afterRemoval", "listed", "removed")) {
                    add(
                        RendererDifferenceRule(
                            "restrictions",
                            section,
                            "restrictions.protocol",
                            "059-ai-restriction-proposals",
                            "V2 restrictions use GET-style and upsert operations; the live server reports them as unsupported.",
                        ),
                    )
                }
                add(
                    RendererDifferenceRule(
                        "status",
                        "liveStatusError",
                        "status.protocol",
                        "081-v2-server",
                        "V2 replaces the V1 unknown-method status error with a live status surface.",
                    ),
                )
                add(
                    RendererDifferenceRule(
                        "status",
                        "liveStatus",
                        "status.protocol",
                        "081-v2-server",
                        "V2 replaces the V1 unknown-method status error with a live status surface.",
                    ),
                )
            }

        val V2_COUNTS =
            mapOf(
                "protocol-config-flow" to listOf(1, 1, 1, 1, 1, 1, 1, 1, 1),
                "protocol-document-lifecycle" to listOf(1, 2, 2, 3, 0, 1),
                "protocol-error-flow" to listOf(1, 1, 1, 1, 1),
                "protocol-kotlin-analyze" to listOf(1, 2, 3, 1, 1, 3, 1, 1),
                "protocol-restrictions-flow" to listOf(1, 2, 1, 1, 1, 1),
                "protocol-status-flow" to listOf(1, 1, 1),
                "protocol-suggest-disabled-flow" to listOf(1, 2, 1, 1),
                "protocol-suggest-flow" to listOf(1, 2, 1, 1, 1, 1),
                "protocol-typescript-analyze" to listOf(1, 2, 3, 1),
            )

        val INTENTIONAL =
            listOf(
                Triple("protocol.version", "086-external-protocol-versioning", "Protocol 5 is negotiated for V2 while legacy 4 replays keep V1 shapes."),
                Triple("initialize.additions", "079-v2-ipc-protocol", "V2 initialize adds fingerprint, server, and capabilities."),
                Triple("initialize.pack-fields", "069-v2-packs-and-defaults", "V2 exposes its own default pack catalog with manifest metadata and an empty config-field list."),
                Triple("initialize.suggestions", "081-v2-server", "V2 suggestion capability defaults are owned by the V2 server ticket."),
                Triple("config.protocol", "069-v2-packs-and-defaults", "V2 config is JSON-first and served by the runtime surface."),
                Triple("suggest.protocol", "079-v2-ipc-protocol", "V2 suggestions use selection spans and streaming methods."),
                Triple("restrictions.protocol", "059-ai-restriction-proposals", "V2 restrictions use GET-style and upsert methods."),
                Triple("status.protocol", "081-v2-server", "V2 adds live status and metrics surfaces."),
                Triple("error.handling", "079-v2-ipc-protocol", "V2 errors carry structured data.kind/data.details."),
                Triple("annotation.shape", "079-v2-ipc-protocol", "V2 annotations add diagnostics and ruleId/packId provenance."),
                Triple("candidate.shape", "079-v2-ipc-protocol", "V2 candidates are proposals with origin, category, fingerprints, and operations."),
                Triple("server.unsupported-operations", "081-v2-server", "Config, suggest, restrictions, and candidate decisions return structured unsupported-operation errors until runtime exposes them."),
                Triple("http.facade", "081-v2-server", "The V2 live server currently exposes only the Unix-socket surface; HTTP analyze/health replay is deferred until an HTTP facade exists."),
            )
    }
}
