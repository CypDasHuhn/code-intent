package dev.cint.integration.parity

import dev.cint.contracts.facts.LanguageDescriptor
import dev.cint.contracts.facts.SemanticRequestKind
import dev.cint.contracts.facts.SemanticResult
import dev.cint.contracts.ports.HostSemanticChannel
import dev.cint.contracts.ports.HostSemanticChannelProvider
import dev.cint.contracts.ports.ProviderMode
import dev.cint.contracts.ports.SemanticProvider
import dev.cint.contracts.ports.SemanticProviderConfig
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory
import dev.cint.runtime.DefaultRuntimeLanguageComposition
import dev.cint.runtime.RuntimeComponents
import dev.cint.runtime.RuntimeFactory
import dev.cint.runtime.RuntimeLanguageComposition
import dev.cint.v2.cli.Cli
import dev.cint.v2.cli.CliEnvironment
import dev.cint.v2.cli.CliRuntimeFactory
import dev.cint.v2.languages.LanguageCatalog
import dev.cint.v2.languages.LanguageProviderDefaults
import dev.cint.v2.languages.LanguageProviderFactory
import dev.cint.v2.languages.LanguageSupportFactory
import dev.cint.v2.languages.TreeSitterLanguageRegistry
import dev.cint.v2.syntax.treesitter.createSyntaxEngine
import dev.cint.v2.testing.corpus.FrozenV1Corpus
import dev.cint.v2.testing.fakes.FakeSemanticProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CorpusParityTest {
    private val json = Json { prettyPrint = true }
    private val corpus = FrozenV1Corpus(Paths.get(System.getProperty("v2.frozenCorpusRoot")))
    private val languages = listOf("kotlin", "typescript", "javascript", "csharp", "vue")

    @Test
    fun writesMachineReadableParityReportWithoutHidingMismatches() {
        val defects = mutableListOf<JsonObject>()
        val orderingDifferences = mutableListOf<JsonObject>()
        val fileReports = mutableListOf<JsonObject>()
        val syntaxReports = mutableListOf<JsonObject>()
        var matchedAnnotations = 0
        var comparedAnnotations = 0
        var matchedCandidates = 0
        var comparedCandidates = 0
        var matchedFiles = 0
        var comparedFiles = 0

        for (language in languages) {
            val frozen = corpus.cli(language)
            val root = Files.createTempDirectory("cint-parity-$language")
            try {
                val directory = root.resolve("src")
                Files.createDirectories(directory)
                for (file in frozen.files) {
                    Files.writeString(directory.resolve(file.path), corpus.source(language, file.path))
                }
                val result = runCli(listOf(directory.toString(), "--lang", language, "--all"))
                assertTrue(result.stderr.isBlank(), "$language CLI stderr: ${result.stderr}")
                val actual = json.parseToJsonElement(result.stdout).jsonObject
                val actualFiles =
                    actual
                        .getValue("files")
                        .jsonArray
                        .map { it.jsonObject }
                        .associateBy { it.getValue("path").jsonPrimitive.content }

                for (file in frozen.files) {
                    comparedFiles++
                    val actualFile = actualFiles[file.path]
                    if (actualFile == null) {
                        defects +=
                            defect(
                                "cli.file.missing",
                                "088-v2-detection-fact-parity",
                                "V2 CLI did not emit a frozen file",
                                mapOf("language" to language, "path" to file.path),
                            )
                        continue
                    }
                    val hashMatches = actualFile.getValue("hash").jsonPrimitive.content == file.hash
                    if (hashMatches) {
                        matchedFiles++
                    } else {
                        defects +=
                            defect(
                                "cli.hash.mismatch",
                                "088-v2-detection-fact-parity",
                                "Content hash differs from the frozen corpus",
                                mapOf("language" to language, "path" to file.path),
                            )
                    }

                    val expectedAnnotations = file.spans.map(::canonical)
                    val actualAnnotations = actualFile.getValue("annotations").jsonArray.map(::canonical)
                    val annotationDiff = diffKeys(expectedAnnotations, actualAnnotations)
                    if (expectedAnnotations != actualAnnotations && expectedAnnotations.sorted() == actualAnnotations.sorted()) {
                        orderingDifferences +=
                            buildJsonObject {
                                put("surface", "cli.annotations")
                                put("language", language)
                                put("path", file.path)
                                put("ticket", "080-v2-cli")
                            }
                    }
                    comparedAnnotations += expectedAnnotations.size
                    matchedAnnotations += annotationDiff.matched
                    annotationDiff.missing.forEach { key ->
                        defects +=
                            defect(
                                annotationDefectId(key, missing = true),
                                annotationTicket(key, language),
                                "Frozen annotation is missing or differs in V2 output",
                                mapOf("language" to language, "path" to file.path, "annotation" to key),
                            )
                    }
                    annotationDiff.extra.forEach { key ->
                        defects +=
                            defect(
                                annotationDefectId(key, missing = false),
                                annotationTicket(key, language),
                                "V2 emitted an annotation absent from the frozen corpus",
                                mapOf("language" to language, "path" to file.path, "annotation" to key),
                            )
                    }

                    val expectedCandidates = file.candidates.map(::canonical)
                    val actualCandidates = actualFile.getValue("candidates").jsonArray.map(::canonical)
                    val candidateDiff = diffKeys(expectedCandidates, actualCandidates)
                    if (expectedCandidates != actualCandidates && expectedCandidates.sorted() == actualCandidates.sorted()) {
                        orderingDifferences +=
                            buildJsonObject {
                                put("surface", "cli.candidates")
                                put("language", language)
                                put("path", file.path)
                                put("ticket", "080-v2-cli")
                            }
                    }
                    comparedCandidates += expectedCandidates.size
                    matchedCandidates += candidateDiff.matched
                    candidateDiff.missing.forEach { key ->
                        defects +=
                            defect(
                                "cli.candidate.missing",
                                "091-v2-candidate-parity",
                                "Frozen candidate is missing or differs in V2 output",
                                mapOf("language" to language, "path" to file.path, "candidate" to key),
                            )
                    }
                    candidateDiff.extra.forEach { key ->
                        defects +=
                            defect(
                                "cli.candidate.extra",
                                "091-v2-candidate-parity",
                                "V2 emitted a candidate absent from the frozen corpus",
                                mapOf("language" to language, "path" to file.path, "candidate" to key),
                            )
                    }

                    fileReports +=
                        buildJsonObject {
                            put("language", language)
                            put("path", file.path)
                            put("hashMatches", hashMatches)
                            put("expectedAnnotations", expectedAnnotations.size)
                            put("actualAnnotations", actualAnnotations.size)
                            put("matchedAnnotations", annotationDiff.matched)
                            put("expectedCandidates", expectedCandidates.size)
                            put("actualCandidates", actualCandidates.size)
                            put("matchedCandidates", candidateDiff.matched)
                        }
                }

                syntaxReports +=
                    syntaxReport(
                        language,
                        frozen.files.map { file -> file.path to corpus.source(language, file.path) },
                        defects,
                    )
            } finally {
                root.toFile().deleteRecursively()
            }
        }
        syntaxReports += syntaxReport("c", listOf(C_SYNTAX_PATH to C_SYNTAX_SOURCE), defects)

        val frozenContract = corpus.knownDifferences()
        val v2Contract = json.parseToJsonElement(Files.readString(Paths.get(System.getProperty("v2.ipcKnownDifferences")))).jsonObject
        val intentional =
            buildJsonArray {
                for (difference in frozenContract.getValue("differences").jsonArray) {
                    val entry = difference.jsonObject
                    add(
                        buildJsonObject {
                            put("source", "frozen-v1-corpus/known-differences.json")
                            put("id", entry.getValue("id").jsonPrimitive.content)
                            put("status", entry.getValue("status").jsonPrimitive.content)
                            put("scope", entry.getValue("scope").jsonPrimitive.content)
                            put("ticket", entry.getValue("ticket").jsonPrimitive.content)
                            put("reason", entry.getValue("reason").jsonPrimitive.content)
                        },
                    )
                }
                for (difference in v2Contract.getValue("differences").jsonArray) {
                    val entry = difference.jsonObject
                    add(
                        buildJsonObject {
                            put("source", "analysis-engine/ipc/known-differences-v2.json")
                            put("id", entry.getValue("id").jsonPrimitive.content)
                            put("status", entry.getValue("status").jsonPrimitive.content)
                            put("scope", "protocol")
                            put("ticket", entry.getValue("ticket").jsonPrimitive.content)
                            put("reason", entry.getValue("v2").jsonPrimitive.content)
                        },
                    )
                }
                for ((id, reason) in CLI_DOCUMENTED_DIFFERENCES) {
                    add(
                        buildJsonObject {
                            put("source", "analysis-engine/apps/cli/CLI_DIFFERENCES.md")
                            put("id", id)
                            put("status", "decided")
                            put("scope", "cli")
                            put("ticket", "080-v2-cli")
                            put("reason", reason)
                        },
                    )
                }
            }

        val report =
            buildJsonObject {
                put("formatVersion", 1)
                put("ticket", "084-v2-parity-integration")
                put(
                    "corpus",
                    buildJsonObject {
                        put(
                            "v1Commit",
                            corpus
                                .manifest()
                                .getValue("v1Commit")
                                .jsonPrimitive.content,
                        )
                        put(
                            "protocolVersion",
                            corpus
                                .manifest()
                                .getValue("protocolVersion")
                                .jsonPrimitive.content,
                        )
                        put("manifestHashMismatches", JsonArray(corpus.manifestHashMismatches().map(::JsonPrimitive)))
                    },
                )
                put("intentionalDifferences", intentional)
                put("closable", defects.isEmpty())
                put(
                    "summary",
                    buildJsonObject {
                        put("filesCompared", comparedFiles)
                        put("filesMatched", matchedFiles)
                        put("annotationsCompared", comparedAnnotations)
                        put("annotationsMatched", matchedAnnotations)
                        put("candidatesCompared", comparedCandidates)
                        put("candidatesMatched", matchedCandidates)
                        put("intentionalDifferenceCount", intentional.size)
                        put("orderingDifferenceCount", orderingDifferences.size)
                        put("defectCount", defects.size)
                    },
                )
                put("files", buildJsonArray { fileReports.forEach(::add) })
                put("syntax", buildJsonArray { syntaxReports.forEach(::add) })
                put("orderingDifferences", buildJsonArray { orderingDifferences.forEach(::add) })
                put(
                    "defects",
                    buildJsonArray {
                        defects
                            .sortedWith(compareBy({ it.getValue("id").jsonPrimitive.content }, { it.toString() }))
                            .forEach(::add)
                    },
                )
                put(
                    "deferrals",
                    buildJsonArray {
                        DEFERRALS.forEach { (ticket, reason) ->
                            add(
                                buildJsonObject {
                                    put("ticket", ticket)
                                    put("reason", reason)
                                },
                            )
                        }
                    },
                )
            }

        val reportDir = Path.of(System.getProperty("v2.parityReportDir"))
        Files.createDirectories(reportDir)
        Files.writeString(reportDir.resolve("cli-report.json"), json.encodeToString(JsonObject.serializer(), report) + "\n")

        assertEquals(emptyList(), corpus.manifestHashMismatches())
        assertTrue(comparedFiles > 0)
        assertTrue(comparedAnnotations > 0)
        assertTrue(matchedAnnotations > 0, "no frozen annotations matched at all")
        assertTrue(matchedCandidates > 0, "no frozen candidates matched at all")
        assertTrue(
            defects.all {
                it
                    .getValue("ticket")
                    .jsonPrimitive.content
                    .isNotBlank()
            },
        )
        assertTrue(
            defects.all {
                it
                    .getValue("reason")
                    .jsonPrimitive.content
                    .isNotBlank()
            },
        )
        val registered = DEFERRALS.keys
        assertEquals(emptySet(), defects.map { it.getValue("ticket").jsonPrimitive.content }.toSet() - registered)
        val bookkeepingValidationDefects =
            defects.filter { it.getValue("ticket").jsonPrimitive.content == "089-v2-bookkeeping-validation-parity" }
        assertEquals(
            emptyList(),
            bookkeepingValidationDefects,
            "bookkeeping/validation parity defects remain",
        )
        val signatures =
            defects
                .map(::defectSignature)
                .groupingBy { it }
                .eachCount()
                .toSortedMap()
        val baselinePath = Path.of(System.getProperty("v2.v2Root"), "parity", "defect-baseline.json")
        assertTrue(Files.isRegularFile(baselinePath), "missing defect baseline $baselinePath")
        val baseline =
            json
                .parseToJsonElement(Files.readString(baselinePath))
                .jsonObject
                .getValue("signatures")
                .jsonObject
                .mapValues { (_, value) -> value.jsonPrimitive.content.toInt() }
        assertEquals(baseline.toSortedMap(), signatures, "parity defect identities/content drifted from the registered baseline")
    }

    private fun syntaxReport(
        language: String,
        sources: List<Pair<String, String>>,
        defects: MutableList<JsonObject>,
    ): JsonObject {
        val engine = createSyntaxEngine(language)
        var functions = 0
        var calls = 0
        var returns = 0
        var guards = 0
        var comments = 0
        var properties = 0
        var parseFailures = 0
        var unstableFingerprints = 0
        val fingerprints = sortedMapOf<String, String>()
        for ((path, text) in sources) {
            val bytes = text.toByteArray(StandardCharsets.UTF_8)
            val parsed = engine.parse(bytes, null, CancellationToken.NONE)
            val tree = (parsed as? dev.cint.contracts.util.Success)?.contents
            if (tree == null || tree.nodes.values.any { it.kind == "ERROR" }) {
                parseFailures++
                defects +=
                    defect(
                        "syntax.parse",
                        "093-v2-non-kotlin-parity",
                        "Frozen source did not parse cleanly in the V2 syntax engine",
                        mapOf("language" to language, "path" to path),
                    )
                continue
            }
            val firstFingerprint = syntaxFingerprint(tree)
            fingerprints[path] = firstFingerprint
            val reparsed = engine.parse(bytes, null, CancellationToken.NONE).getOrThrow()
            if (syntaxFingerprint(reparsed) != firstFingerprint) {
                unstableFingerprints++
                defects +=
                    defect(
                        "syntax.fingerprint",
                        "093-v2-non-kotlin-parity",
                        "Repeated parses produced different syntax fingerprints",
                        mapOf("language" to language, "path" to path),
                    )
            }
            val facts = engine.facts(tree, emptyList(), CancellationToken.NONE).getOrThrow()
            functions += facts.functions.size
            calls += facts.calls.size
            returns += facts.returns.size
            guards += facts.guards.size
            comments += facts.comments.size
            properties += facts.properties.size
            if (facts.functions.isEmpty() && language != "csharp") {
                defects +=
                    defect(
                        "syntax.function-facts",
                        "093-v2-non-kotlin-parity",
                        "Syntax facts contained no functions for a frozen source",
                        mapOf("language" to language, "path" to path),
                    )
            }
        }
        val constructCoverage = functions > 0 && calls > 0 && returns > 0 && guards > 0 && properties > 0
        if (!constructCoverage) {
            defects +=
                defect(
                    "syntax.construct-coverage",
                    "093-v2-non-kotlin-parity",
                    "Syntax facts did not cover the expected cross-target constructs",
                    mapOf(
                        "language" to language,
                        "functions" to functions.toString(),
                        "calls" to calls.toString(),
                        "returns" to returns.toString(),
                        "guards" to guards.toString(),
                        "properties" to properties.toString(),
                    ),
                )
        }
        val fingerprint = digest(fingerprints.entries.joinToString("\n") { "${it.key}:${it.value}" })
        return buildJsonObject {
            put("language", language)
            put("files", sources.size)
            put("parseFailures", parseFailures)
            put("unstableFingerprints", unstableFingerprints)
            put("constructCoverage", constructCoverage)
            put("fingerprint", fingerprint)
            put("functions", functions)
            put("calls", calls)
            put("returns", returns)
            put("guards", guards)
            put("comments", comments)
            put("properties", properties)
        }
    }

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun syntaxFingerprint(tree: dev.cint.contracts.facts.SyntaxTree): String {
        val canonical =
            tree.nodes.values.sortedBy { it.id }.joinToString("\n") { node ->
                "${node.id}|${node.kind}|${node.startByte}|${node.endByte}|${node.startLine}|${node.startCol}|${node.endLine}|${node.endCol}|${node.isNamed}"
            }
        return digest(canonical)
    }

    private fun runCli(args: List<String>): CliRun {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val exitCode =
            Cli.run(
                args.toTypedArray(),
                PrintStream(stdout, true, StandardCharsets.UTF_8),
                PrintStream(stderr, true, StandardCharsets.UTF_8),
                CliEnvironment(
                    CliRuntimeFactory { config ->
                        RuntimeFactory.create(config, RuntimeComponents(languageComposition = composition()))
                    },
                ),
            )
        assertTrue(exitCode == 0, "CLI exit $exitCode: ${stderr.toString(StandardCharsets.UTF_8)}")
        return CliRun(stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8))
    }

    private fun composition(): RuntimeLanguageComposition {
        val factory =
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
                                    responseFactory = { request -> ResultFactory.success(neutral(request.kind)) },
                                ),
                            )
                    },
                providerDefaults =
                    LanguageProviderDefaults { _ ->
                        SemanticProviderConfig(mode = ProviderMode.MANAGED, command = "parity-neutral")
                    },
            )
        return DefaultRuntimeLanguageComposition(TreeSitterLanguageRegistry, factory)
    }

    private fun neutral(kind: SemanticRequestKind): SemanticResult =
        when (kind) {
            SemanticRequestKind.TYPE_AT -> SemanticResult.TypeAt(typeRef = null)
            SemanticRequestKind.SYMBOL_AT -> SemanticResult.SymbolAt(symbol = null)
            SemanticRequestKind.DEFINITION_OF -> SemanticResult.DefinitionOf(emptyList())
            SemanticRequestKind.DIAGNOSTICS_FOR -> SemanticResult.DiagnosticsFor(emptyList())
            SemanticRequestKind.HOVER_AT -> SemanticResult.HoverAt(content = null)
            SemanticRequestKind.REFERENCES_AT -> SemanticResult.ReferencesAt(emptyList())
        }

    private fun canonical(value: JsonElement): String {
        val entry = value.jsonObject
        val normalized =
            entry
                .toSortedMap()
                .mapValues { (_, item) ->
                    when {
                        item is JsonArray && entry.keys.any { key -> key == "intents" && item === entry[key] } -> {
                            JsonArray(item.map { JsonPrimitive(normalizeText(it.jsonPrimitive.content)) }.sortedBy { it.content })
                        }

                        else -> {
                            item
                        }
                    }
                }.toMutableMap()
        val intents = normalized["intents"]
        if (intents is JsonArray) {
            normalized["intents"] = JsonArray(intents.map { it }.sortedBy { it.jsonPrimitive.content })
        }
        val provenance = entry["provenance"]
        if (provenance is JsonArray) {
            normalized["provenance"] =
                JsonArray(provenance.map { canonical(it) }.sorted().map { JsonPrimitive(it) })
        }
        return JsonObject(normalized).toString()
    }

    private fun normalizeText(value: String): String = value

    private fun annotationTicket(
        key: String,
        language: String,
    ): String =
        when {
            key.contains("service:init") || key.contains("SERVICE_INIT") -> "092-v2-service-init-parity"
            key.contains("\"role\":\"scaffold\"") || key.contains("\"role\":\"derivative-use\"") -> "090-v2-composition-parity"
            language != "kotlin" -> "093-v2-non-kotlin-parity"
            key.contains("\"source\":\"annotation\"") || key.contains("\"source\":\"comment\"") -> "089-v2-bookkeeping-validation-parity"
            else -> "088-v2-detection-fact-parity"
        }

    private fun annotationDefectId(
        key: String,
        missing: Boolean,
    ): String =
        when {
            key.contains("\"role\":\"scaffold\"") || key.contains("\"role\":\"derivative-use\"") -> {
                if (missing) "cli.annotation.composition-missing" else "cli.annotation.composition-extra"
            }

            missing -> {
                "cli.annotation.missing-or-value-mismatch"
            }

            else -> {
                "cli.annotation.extra"
            }
        }

    private fun defectSignature(defect: JsonObject): String {
        val payload = "${defect.getValue(
            "id",
        ).jsonPrimitive.content}|${defect.getValue("ticket").jsonPrimitive.content}|${defect.getValue("context")}"
        return MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()).joinToString("") { "%02x".format(it) }
    }

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
            put(
                "context",
                buildJsonObject { context.toSortedMap().forEach { (key, value) -> put(key, value) } },
            )
        }

    private fun diffKeys(
        expected: List<String>,
        actual: List<String>,
    ): Diff {
        val expectedCounts = expected.groupingBy { it }.eachCount().toMutableMap()
        val actualCounts = actual.groupingBy { it }.eachCount().toMutableMap()
        var matched = 0
        for (key in expectedCounts.keys) {
            val common = minOf(expectedCounts.getValue(key), actualCounts[key] ?: 0)
            matched += common
            expectedCounts[key] = expectedCounts.getValue(key) - common
            actualCounts[key] = (actualCounts[key] ?: 0) - common
        }
        val missing = expectedCounts.flatMap { (key, count) -> List(count) { key } }
        val extra = actualCounts.flatMap { (key, count) -> List(count) { key } }
        return Diff(matched, missing.sorted(), extra.sorted())
    }

    private data class Diff(
        val matched: Int,
        val missing: List<String>,
        val extra: List<String>,
    )

    private data class CliRun(
        val stdout: String,
        val stderr: String,
    )

    private companion object {
        val CLI_DOCUMENTED_DIFFERENCES =
            linkedMapOf(
                "cli.meta-deterministic" to "meta.timestamp and meta.elapsedMs are omitted so CLI output is reproducible.",
                "cli.candidate-optionals" to
                    "Absent optional candidate provider/model/rationale/path fields are omitted rather than emitted as null.",
                "cli.ordering" to "Files, annotations, candidates, and intents are sorted deterministically.",
                "cli.provider-required" to "The V2 CLI has no --no-lsp path and rejects the flag; a semantic provider is required.",
                "cli.knowledge-init" to "--init materializes pack defaults into the knowledge store; no-init analysis is in-memory.",
                "cli.exit-codes" to "The V2 CLI uses stable exit codes and structured JSON errors on stderr.",
                "cli.format-selector" to "The V2 CLI supports json and ndjson output formats.",
                "cli.include-all" to "--all includes files with no annotations, candidates, or diagnostics.",
            )

        const val C_SYNTAX_PATH: String = "native-spike/fixtures/CConstructs.c"
        val C_SYNTAX_SOURCE: String =
            """
            struct Counter {
                int total;
            };

            // cint:bookkeeping
            static int add(int a, int b) {
                if (a < 0) {
                    return 0;
                }
                return a + b;
            }

            int main(void) {
                struct Counter counter;
                counter.total = add(1, 2);
                return counter.total;
            }
            """.trimIndent()

        val DEFERRALS =
            linkedMapOf(
                "088-v2-detection-fact-parity" to
                    "Port V1 logging, validation, data-access, return, local-copy, and span/confidence semantics.",
                "089-v2-bookkeeping-validation-parity" to
                    "Port @Bookkeeping/cint declaration and validation rejection/predicate/bookkeeping gating.",
                "090-v2-composition-parity" to "Port V1 scaffold, derivative-use, return-category, and function-scaffold composition.",
                "091-v2-candidate-parity" to "Complete candidate identity, grouping, and mutation/service candidate parity.",
                "092-v2-service-init-parity" to "Complete service init constructor-injection and lifecycle spans.",
                "093-v2-non-kotlin-parity" to "Complete non-Kotlin span and syntactic-fact parity.",
            )
    }
}
