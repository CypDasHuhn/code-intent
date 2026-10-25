package dev.cint.v2.mcp

import dev.cint.contracts.data.Confidence
import dev.cint.contracts.data.Intents
import dev.cint.contracts.facts.LanguageDescriptor
import dev.cint.contracts.facts.SemanticRequestKind
import dev.cint.contracts.facts.SemanticResult
import dev.cint.contracts.facts.TypeRef
import dev.cint.contracts.ports.SemanticProvider
import dev.cint.contracts.ports.SemanticProviderConfig
import dev.cint.contracts.util.ResultFactory
import dev.cint.runtime.DefaultRuntime
import dev.cint.runtime.DefaultRuntimeLanguageComposition
import dev.cint.runtime.InitCheckpointStores
import dev.cint.runtime.MarkerRootLocator
import dev.cint.runtime.RuntimeComponents
import dev.cint.runtime.RuntimeConfig
import dev.cint.runtime.RuntimeFactory
import dev.cint.runtime.RuntimeLanguageComposition
import dev.cint.v2.index.IndexPlan
import dev.cint.v2.index.SemanticRequestSpec
import dev.cint.v2.languages.LanguageProviderDefaults
import dev.cint.v2.languages.LanguageProviderFactory
import dev.cint.v2.languages.LanguageSupportFactory
import dev.cint.v2.languages.TreeSitterLanguageRegistry
import dev.cint.v2.testing.fakes.FakeSemanticProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpGoldenPathTest {
    private val temporaryRoots = mutableListOf<Path>()

    @AfterTest
    fun cleanUp() {
        temporaryRoots.forEach { runCatching { it.toFile().deleteRecursively() } }
    }

    @Test
    fun classifiesCrossFileAndExternalCallsThenResumesAfterRestart() {
        val root = temporaryRoot()
        copyFixture(root)
        val knowledgePath = root.resolve(".cint/knowledge.json")
        val checkpointPath = root.resolve(".cint/init-checkpoint.json")
        val providerConfig = providerConfig(root)
        val serviceText = Files.readString(root.resolve(SERVICE_PATH))
        val internalReceiverOffset = serviceText.indexOf("repository.findUser")
        val externalReceiverOffset = serviceText.indexOf("id.renderLabel")
        assertTrue(internalReceiverOffset > 0, serviceText)
        assertTrue(externalReceiverOffset > 0, serviceText)
        val offsets = Offsets(internalReceiverOffset, externalReceiverOffset)

        lateinit var repositoryPath: String
        lateinit var servicePath: String
        lateinit var findUserDeclaration: SpanBytes
        lateinit var findUserCall: SpanBytes
        lateinit var renderLabelCall: SpanBytes

        openSession(root, knowledgePath, checkpointPath, providerConfig, offsets).use { session ->
            val client = session.client
            client.initialize()

            val begun = client.structured(client.tool(10, "init.begin"))
            assertEquals("IN_PROGRESS", begun.getValue("status").jsonPrimitive.content)
            assertFalse(
                begun
                    .getValue("resumed")
                    .jsonPrimitive.content
                    .toBoolean(),
            )

            val files = client.structured(client.tool(11, "repo.files"))
            assertEquals(
                listOf(REPOSITORY_PATH, SERVICE_PATH),
                files.getValue("paths").jsonArray.map { it.jsonPrimitive.content },
            )
            val absolute = files.absolutePaths()
            repositoryPath = absolute.getValue(REPOSITORY_PATH)
            servicePath = absolute.getValue(SERVICE_PATH)

            val symbols =
                client.structured(
                    client.tool(12, "analysis.symbols", targetPaths(listOf(repositoryPath, servicePath))),
                )
            val repositorySource = symbols.source(repositoryPath)
            val serviceSource = symbols.source(servicePath)
            findUserDeclaration =
                repositorySource
                    .getValue("declarations")
                    .jsonArray
                    .map { it.jsonObject }
                    .single { it.getValue("name").jsonPrimitive.content == "findUser" }
                    .spanBytes()
            findUserCall =
                serviceSource
                    .getValue("calls")
                    .jsonArray
                    .map { it.jsonObject }
                    .single { it.getValue("callee").jsonPrimitive.content == "findUser" }
                    .also {
                        assertEquals("app.UserRepository", it.getValue("receiverType").jsonPrimitive.content)
                    }.spanBytes()
            renderLabelCall =
                serviceSource
                    .getValue("calls")
                    .jsonArray
                    .map { it.jsonObject }
                    .single { it.getValue("callee").jsonPrimitive.content == "renderLabel" }
                    .also {
                        assertEquals("kotlin.String", it.getValue("receiverType").jsonPrimitive.content)
                    }.spanBytes()
            assertTrue(
                serviceSource
                    .getValue("imports")
                    .jsonArray
                    .map {
                        it.jsonObject
                            .getValue("path")
                            .jsonPrimitive.content
                    }.contains(EXTERNAL_IMPORT),
            )

            val firstAnalysis = client.structured(client.tool(13, "analysis.run", analysisArguments(listOf(repositoryPath, servicePath))))
            assertEquals(
                listOf(repositoryPath, servicePath),
                firstAnalysis.getValue("analyzedPaths").jsonArray.map { it.jsonPrimitive.content },
            )
            assertTrue(firstAnalysis.getValue("skippedPaths").jsonArray.isEmpty())
            val baseline = firstAnalysis.getValue("result").jsonObject
            assertFalse(
                Intents.LOOKUP in baseline.intentsAt(repositoryPath, findUserDeclaration),
                baseline.toString(),
            )
            assertFalse(
                Intents.LOOKUP in baseline.intentsAt(servicePath, findUserCall),
                baseline.toString(),
            )
            assertFalse(
                Intents.NORMALIZATION in baseline.intentsAt(servicePath, renderLabelCall),
                baseline.toString(),
            )

            val internalSelector =
                buildJsonObject {
                    put("qualifiedName", "findUser")
                    put("declaringType", "UserRepository")
                }
            val internalResolution =
                client.structured(
                    client.tool(
                        14,
                        "knowledge.resolveSelector",
                        selectorArguments(internalSelector, listOf(repositoryPath, servicePath)),
                    ),
                )
            assertTrue(internalResolution.getValue("unsupported").jsonArray.isEmpty(), internalResolution.toString())
            val internalMatches = internalResolution.matches()
            assertTrue(
                internalMatches.any { it.path == repositoryPath && it.kind == "FUNCTION" },
                internalResolution.toString(),
            )
            assertTrue(
                internalMatches.any { it.path == servicePath && it.kind == "CALL" },
                internalResolution.toString(),
            )

            val externalSelector =
                buildJsonObject {
                    put("qualifiedName", "renderLabel")
                    put("importPath", EXTERNAL_IMPORT)
                }
            val externalResolution =
                client.structured(
                    client.tool(
                        15,
                        "knowledge.resolveSelector",
                        selectorArguments(externalSelector, listOf(servicePath)),
                    ),
                )
            assertTrue(externalResolution.getValue("unsupported").jsonArray.isEmpty(), externalResolution.toString())
            assertTrue(
                externalResolution
                    .matches()
                    .any {
                        it.path == servicePath &&
                            it.kind == "CALL" &&
                            EXTERNAL_IMPORT in it.importPaths
                    },
                externalResolution.toString(),
            )

            client.tool(16, "function_annotations.set", annotationArguments(internalSelector, Intents.LOOKUP))
            client.tool(17, "function_annotations.set", annotationArguments(externalSelector, Intents.NORMALIZATION))

            val rerun =
                client.structured(
                    client.tool(
                        18,
                        "analysis.run",
                        analysisArguments(listOf(repositoryPath, servicePath), force = true),
                    ),
                )
            assertEquals(
                listOf(repositoryPath, servicePath),
                rerun.getValue("analyzedPaths").jsonArray.map { it.jsonPrimitive.content },
            )
            assertTrue(rerun.getValue("skippedPaths").jsonArray.isEmpty())
            val classified = rerun.getValue("result").jsonObject
            assertTrue(
                Intents.LOOKUP in classified.intentsAt(repositoryPath, findUserDeclaration),
                classified.toString(),
            )
            assertTrue(
                Intents.LOOKUP in classified.intentsAt(servicePath, findUserCall),
                classified.toString(),
            )
            assertTrue(
                Intents.NORMALIZATION in classified.intentsAt(servicePath, renderLabelCall),
                classified.toString(),
            )
            assertEquals(
                2,
                client
                    .structured(client.tool(19, "function_annotations.list"))
                    .getValue("count")
                    .jsonPrimitive.content
                    .toInt(),
            )
        }

        assertEquals(2, readFunctionAnnotationCount(knowledgePath))

        openSession(root, knowledgePath, checkpointPath, providerConfig, offsets).use { session ->
            val client = session.client
            client.initialize()

            val resumed = client.structured(client.tool(20, "init.begin"))
            assertTrue(
                resumed
                    .getValue("resumed")
                    .jsonPrimitive.content
                    .toBoolean(),
            )
            val steps = resumed.getValue("completedSteps").jsonArray.map { it.jsonPrimitive.content }
            assertTrue(steps.contains("analysis:$repositoryPath"), steps.toString())
            assertTrue(steps.contains("analysis:$servicePath"), steps.toString())

            val snapshot = client.structured(client.tool(21, "knowledge.snapshot"))
            assertEquals(2, snapshot.getValue("functionAnnotations").jsonArray.size)

            client.tool(
                22,
                "function_annotations.set",
                annotationArguments(
                    buildJsonObject {
                        put("qualifiedName", "findUser")
                        put("declaringType", "UserRepository")
                    },
                    Intents.LOOKUP,
                ),
            )
            client.tool(
                23,
                "function_annotations.set",
                annotationArguments(
                    buildJsonObject {
                        put("qualifiedName", "renderLabel")
                        put("importPath", EXTERNAL_IMPORT)
                    },
                    Intents.NORMALIZATION,
                ),
            )
            assertEquals(
                2,
                client
                    .structured(client.tool(24, "knowledge.snapshot"))
                    .getValue("functionAnnotations")
                    .jsonArray
                    .size,
            )

            val rerun =
                client.structured(
                    client.tool(
                        25,
                        "analysis.run",
                        analysisArguments(listOf(repositoryPath, servicePath), force = true),
                    ),
                )
            val classified = rerun.getValue("result").jsonObject
            assertTrue(
                Intents.LOOKUP in classified.intentsAt(repositoryPath, findUserDeclaration),
                classified.toString(),
            )
            assertTrue(
                Intents.LOOKUP in classified.intentsAt(servicePath, findUserCall),
                classified.toString(),
            )
            assertTrue(
                Intents.NORMALIZATION in classified.intentsAt(servicePath, renderLabelCall),
                classified.toString(),
            )
        }
        assertEquals(2, readFunctionAnnotationCount(knowledgePath))
    }

    private fun openSession(
        root: Path,
        knowledgePath: Path,
        checkpointPath: Path,
        providerConfig: SemanticProviderConfig,
        offsets: Offsets,
    ): Session {
        val config =
            RuntimeConfig(
                rootPath = root.toString(),
                knowledgePath = knowledgePath.toString(),
                detectLanguages = false,
                materializeDefaultsOnOpen = false,
                providerConfigs = mapOf("kotlin" to providerConfig),
                indexPlan =
                    IndexPlan(
                        syntaxQueryIds = emptyList(),
                        semanticRequests =
                            listOf(
                                SemanticRequestSpec(SemanticRequestKind.TYPE_AT, 0),
                                SemanticRequestSpec(SemanticRequestKind.TYPE_AT, offsets.internalReceiver),
                                SemanticRequestSpec(SemanticRequestKind.TYPE_AT, offsets.externalReceiver),
                            ),
                    ),
            )
        val runtime =
            RuntimeFactory
                .create(
                    config,
                    RuntimeComponents(languageComposition = composition(providerConfig, offsets)),
                ).getOrThrow()
        val checkpoints = InitCheckpointStores.open(root.toString(), checkpointPath.toString()).getOrThrow()
        val toServer = PipedOutputStream()
        val fromServer = PipedOutputStream()
        val server =
            McpServer(
                runtime = runtime,
                input = PipedInputStream(toServer, BUFFER_SIZE),
                output = fromServer,
                checkpointStore = checkpoints,
                workflowId = WORKFLOW_ID,
            )
        return Session(runtime, ScriptedClient(toServer, fromServer, server))
    }

    private fun composition(
        providerConfig: SemanticProviderConfig,
        offsets: Offsets,
    ): RuntimeLanguageComposition {
        val catalog = TreeSitterLanguageRegistry
        val providerFactory =
            LanguageProviderFactory { descriptor, _, _, _ ->
                ResultFactory.success(provider(descriptor, offsets))
            }
        val factory =
            LanguageSupportFactory(
                catalog = catalog,
                providerFactory = providerFactory,
                rootLocator = MarkerRootLocator,
                providerDefaults =
                    LanguageProviderDefaults { descriptor ->
                        if (descriptor.id == "kotlin") {
                            providerConfig
                        } else {
                            SemanticProviderConfig(mode = descriptor.providerMode)
                        }
                    },
            )
        return DefaultRuntimeLanguageComposition(catalog, factory)
    }

    private fun provider(
        descriptor: LanguageDescriptor,
        offsets: Offsets,
    ): SemanticProvider =
        FakeSemanticProvider(
            language = descriptor.id,
            responseFactory = { request ->
                val type =
                    when {
                        request.uri.endsWith("UserService.kt") &&
                            request.offset == offsets.internalReceiver -> {
                            "app.UserRepository"
                        }

                        request.uri.endsWith("UserService.kt") &&
                            request.offset == offsets.externalReceiver -> {
                            "kotlin.String"
                        }

                        else -> {
                            "app.UserService"
                        }
                    }
                ResultFactory.success(
                    SemanticResult.TypeAt(
                        TypeRef(type, "scripted-semantic-provider", Confidence.HIGH),
                    ),
                )
            },
        )

    private fun providerConfig(root: Path): SemanticProviderConfig {
        val file = root.resolve("providers.json")
        Files.writeString(
            file,
            """
            {
              "providers": {
                "kotlin": {
                  "mode": "managed",
                  "command": "scripted-kotlin-provider --stdio"
                }
              }
            }
            """.trimIndent(),
        )
        return McpProviderConfigLoader.load(file.toString()).providerConfigs.getValue("kotlin")
    }

    private fun copyFixture(root: Path) {
        FIXTURE_FILES.forEach { relative ->
            val text =
                requireNotNull(javaClass.getResourceAsStream("/mcp-golden-path/$relative")) {
                    "missing fixture resource $relative"
                }.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val target = root.resolve(relative)
            Files.createDirectories(target.parent)
            Files.writeString(target, text)
        }
    }

    private fun temporaryRoot(): Path {
        val root = Files.createTempDirectory("cint-mcp-100")
        temporaryRoots.add(root)
        return root
    }

    private fun readFunctionAnnotationCount(knowledgePath: Path): Int =
        Json
            .parseToJsonElement(Files.readString(knowledgePath))
            .jsonObject
            .getValue("snapshot")
            .jsonObject
            .getValue("functionAnnotations")
            .jsonArray
            .size

    private fun annotationArguments(
        selector: JsonObject,
        intent: String,
    ): JsonObject =
        buildJsonObject {
            put(
                "annotation",
                buildJsonObject {
                    put("selector", selector)
                    put("intents", buildJsonArray { add(JsonPrimitive(intent)) })
                    put("status", "ACCEPTED")
                    put("confidence", 0.9)
                },
            )
        }

    private fun selectorArguments(
        selector: JsonObject,
        paths: List<String>,
    ): JsonObject =
        buildJsonObject {
            put("selector", selector)
            put("targetPaths", jsonStrings(paths))
        }

    private fun targetPaths(paths: List<String>): JsonObject = buildJsonObject { put("targetPaths", jsonStrings(paths)) }

    private fun analysisArguments(
        paths: List<String>,
        force: Boolean = false,
    ): JsonObject =
        buildJsonObject {
            put("targetPaths", jsonStrings(paths))
            put("forceFullAnalysis", force)
        }

    private fun jsonStrings(values: List<String>) = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

    private fun JsonObject.source(path: String): JsonObject =
        getValue("sources")
            .jsonArray
            .map { it.jsonObject }
            .single { it.getValue("path").jsonPrimitive.content == path }

    private fun JsonObject.absolutePaths(): Map<String, String> =
        getValue("files")
            .jsonArray
            .map { it.jsonObject }
            .associate {
                it.getValue("path").jsonPrimitive.content to it.getValue("absolutePath").jsonPrimitive.content
            }

    private fun JsonObject.matches(): List<ResolvedMatch> =
        getValue("matched")
            .jsonArray
            .map { element ->
                val match = element.jsonObject
                ResolvedMatch(
                    path = match["path"]?.jsonPrimitive?.content,
                    kind = match.getValue("kind").jsonPrimitive.content,
                    importPaths =
                        match["importPaths"]
                            ?.jsonArray
                            ?.map { it.jsonPrimitive.content }
                            .orEmpty(),
                )
            }

    private fun JsonObject.spanBytes(): SpanBytes =
        SpanBytes(
            start = getValue("startByte").jsonPrimitive.content.toInt(),
            end = getValue("endByte").jsonPrimitive.content.toInt(),
        )

    private fun JsonObject.intentsAt(
        path: String,
        span: SpanBytes,
    ): Set<String> =
        getValue("annotations")
            .jsonArray
            .map { it.jsonObject }
            .filter { annotation ->
                val anchor =
                    annotation["nodeAnchor"]
                        ?.jsonObject
                        ?.get("id")
                        ?.jsonPrimitive
                        ?.content
                        .orEmpty()
                anchor.startsWith("$path|") &&
                    annotation
                        .getValue("startByte")
                        .jsonPrimitive.content
                        .toInt() == span.start &&
                    annotation
                        .getValue("endByte")
                        .jsonPrimitive.content
                        .toInt() == span.end
            }.flatMap { annotation ->
                annotation
                    .getValue("intents")
                    .jsonArray
                    .map { it.jsonPrimitive.content }
            }.toSet()

    private data class Offsets(
        val internalReceiver: Int,
        val externalReceiver: Int,
    )

    private data class SpanBytes(
        val start: Int,
        val end: Int,
    )

    private data class ResolvedMatch(
        val path: String?,
        val kind: String,
        val importPaths: List<String>,
    )

    private class Session(
        private val runtime: DefaultRuntime,
        val client: ScriptedClient,
    ) : AutoCloseable {
        override fun close() {
            client.close()
            runtime.shutdown()
        }
    }

    private class ScriptedClient(
        private val toServer: PipedOutputStream,
        fromServer: PipedOutputStream,
        private val server: McpServer,
    ) : AutoCloseable {
        private val writer = BufferedWriter(OutputStreamWriter(toServer, Charsets.UTF_8))
        private val reader =
            BufferedReader(
                InputStreamReader(PipedInputStream(fromServer, BUFFER_SIZE), Charsets.UTF_8),
            )
        private val worker = thread(start = true, isDaemon = true) { server.run() }

        fun initialize() {
            request(1, "initialize")
            notify("notifications/initialized", JsonObject(emptyMap()))
        }

        fun tool(
            id: Int,
            name: String,
            arguments: JsonObject = JsonObject(emptyMap()),
        ): JsonObject =
            request(
                id,
                "tools/call",
                buildJsonObject {
                    put("name", name)
                    put("arguments", arguments)
                },
            ).getValue("result").jsonObject

        fun structured(result: JsonObject): JsonObject {
            assertFalse(result["isError"]?.jsonPrimitive?.content?.toBoolean() ?: false, result.toString())
            return result.getValue("structuredContent").jsonObject
        }

        fun request(
            id: Int,
            method: String,
            params: JsonObject = JsonObject(emptyMap()),
        ): JsonObject {
            send(id, method, params)
            return read()
        }

        private fun send(
            id: Int,
            method: String,
            params: JsonObject = JsonObject(emptyMap()),
        ) {
            val message =
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("method", method)
                    put("params", params)
                }
            writer.write(message.toString())
            writer.newLine()
            writer.flush()
        }

        private fun notify(
            method: String,
            params: JsonObject,
        ) {
            val message =
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("method", method)
                    put("params", params)
                }
            writer.write(message.toString())
            writer.newLine()
            writer.flush()
        }

        private fun read(): JsonObject = Json.parseToJsonElement(reader.readLine()).jsonObject

        override fun close() {
            runCatching { writer.close() }
            worker.join(5_000)
        }
    }

    private companion object {
        const val REPOSITORY_PATH = "src/main/kotlin/app/UserRepository.kt"
        const val SERVICE_PATH = "src/main/kotlin/app/UserService.kt"
        const val EXTERNAL_IMPORT = "app.TextTools.renderLabel"
        const val WORKFLOW_ID = "mcp-golden-path"
        const val BUFFER_SIZE = 1 shl 16
        val FIXTURE_FILES =
            listOf(
                "settings.gradle",
                REPOSITORY_PATH,
                SERVICE_PATH,
            )
    }
}
