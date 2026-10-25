package dev.cint.v2.mcp

import dev.cint.contracts.facts.ImportFact
import dev.cint.contracts.facts.SourceInput
import dev.cint.contracts.mcp.McpToolResult
import dev.cint.contracts.ports.LanguageSupport
import dev.cint.contracts.util.ContentHash
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory
import dev.cint.knowledge.InMemoryKnowledgeStore
import dev.cint.runtime.DefaultRuntime
import dev.cint.runtime.RuntimeComponents
import dev.cint.runtime.RuntimeConfig
import dev.cint.runtime.RuntimeFactory
import dev.cint.runtime.RuntimeLanguageComposition
import dev.cint.v2.languages.LanguageCatalog
import dev.cint.v2.testing.fakes.FakeIndex
import dev.cint.v2.testing.fakes.FakeLanguageSupport
import dev.cint.v2.testing.fakes.FakeSyntaxEngine
import dev.cint.v2.testing.fakes.defaultFacts
import dev.cint.v2.testing.fakes.fakeDescriptor
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpDiscoveryTest {
    private val temporaryRoots = mutableListOf<Path>()
    private val now = Instant.parse("2025-01-01T00:00:00Z")

    @AfterTest
    fun cleanUp() {
        temporaryRoots.forEach { runCatching { it.toFile().deleteRecursively() } }
    }

    @Test
    fun catalogExposesDiscoveryTools() {
        val names = McpCatalog.tools().map { it.name }
        assertTrue(names.contains("repo.files"), names.toString())
        assertTrue(names.contains("analysis.symbols"), names.toString())
        assertTrue(names.contains("knowledge.resolveSelector"), names.toString())
    }

    @Test
    fun repoFilesListsAnalyzableFilesDeterministicallyAndHonorsFilters() {
        val fixture = fixture()
        val tools = McpRuntimeTools(fixture.runtime)

        val all = tools.json("repo.files", buildJsonObject {})
        assertEquals(listOf("lib/util.fake", "src/Main.kt"), all.stringList("paths"))
        assertEquals(
            2,
            all
                .getValue("count")
                .jsonPrimitive.content
                .toInt(),
        )
        assertFalse(
            all
                .getValue("truncated")
                .jsonPrimitive.content
                .toBoolean(),
        )
        val ignored = all.getValue("ignoredDirectories").jsonArray.map { it.jsonPrimitive.content }
        assertTrue(ignored.containsAll(listOf(".git", ".cint", "build")), ignored.toString())

        val kotlinOnly =
            tools.json(
                "repo.files",
                buildJsonObject { put("languages", buildJsonArray { add(JsonPrimitive("kotlin")) }) },
            )
        assertEquals(listOf("src/Main.kt"), kotlinOnly.stringList("paths"))

        val fakeOnly =
            tools.json(
                "repo.files",
                buildJsonObject { put("extensions", buildJsonArray { add(JsonPrimitive(".FAKE")) }) },
            )
        assertEquals(listOf("lib/util.fake"), fakeOnly.stringList("paths"))

        val limited = tools.json("repo.files", buildJsonObject { put("limit", 1) })
        assertEquals(listOf("lib/util.fake"), limited.stringList("paths"))
        assertTrue(
            limited
                .getValue("truncated")
                .jsonPrimitive.content
                .toBoolean(),
        )
    }

    @Test
    fun analysisSymbolsReturnsDeclarationsCallsImportsAndSpans() {
        val fixture = fixture()
        val tools = McpRuntimeTools(fixture.runtime)

        val result = tools.json("analysis.symbols", symbolArguments(fixture.main))
        val source =
            result
                .getValue("sources")
                .jsonArray
                .single()
                .jsonObject
        assertEquals(fixture.main.toString(), source.getValue("path").jsonPrimitive.content)
        assertEquals("kotlin", source.getValue("language").jsonPrimitive.content)

        val declaration =
            source
                .getValue("declarations")
                .jsonArray
                .single()
                .jsonObject
        assertEquals("findUser", declaration.getValue("name").jsonPrimitive.content)
        assertEquals("FUNCTION", declaration.getValue("kind").jsonPrimitive.content)
        assertEquals(
            0,
            declaration
                .getValue("span")
                .jsonObject
                .getValue("startLine")
                .jsonPrimitive.content
                .toInt(),
        )

        val call =
            source
                .getValue("calls")
                .jsonArray
                .single()
                .jsonObject
        assertEquals("findUser", call.getValue("callee").jsonPrimitive.content)
        assertEquals(
            1,
            call
                .getValue("span")
                .jsonObject
                .getValue("startLine")
                .jsonPrimitive.content
                .toInt(),
        )

        val import =
            source
                .getValue("imports")
                .jsonArray
                .single()
                .jsonObject
        assertEquals("sample.Import", import.getValue("path").jsonPrimitive.content)
        assertEquals("Alias", import.getValue("alias").jsonPrimitive.content)
    }

    @Test
    fun resolveSelectorFindsExactFunctionAndSpanMatches() {
        val fixture = fixture()
        val tools = McpRuntimeTools(fixture.runtime)

        val function =
            tools.json(
                "knowledge.resolveSelector",
                buildJsonObject {
                    put("selector", buildJsonObject { put("qualifiedName", "findUser") })
                    put("targetPaths", buildJsonArray { add(JsonPrimitive(fixture.main.toString())) })
                },
            )
        val matched = function.getValue("matched").jsonArray.map { it.jsonObject }
        assertTrue(matched.any { it.getValue("kind").jsonPrimitive.content == "FUNCTION" }, function.toString())
        assertTrue(matched.all { it.getValue("path").jsonPrimitive.content == fixture.main.toString() })
        assertTrue(
            matched.any {
                it
                    .getValue("names")
                    .jsonArray
                    .map { name -> name.jsonPrimitive.content }
                    .contains("findUser")
            },
        )
        assertTrue(function.getValue("unsupported").jsonArray.isEmpty())
        assertTrue(
            fixture.runtime
                .knowledge()
                .functionAnnotations()
                .getOrThrow()
                .isEmpty(),
        )

        val symbols = tools.json("analysis.symbols", symbolArguments(fixture.main))
        val declaration =
            symbols
                .getValue("sources")
                .jsonArray
                .single()
                .jsonObject
                .getValue("declarations")
                .jsonArray
                .single()
                .jsonObject
        val span = declaration.getValue("span").jsonObject
        val spanResolution =
            tools.json(
                "knowledge.resolveSelector",
                buildJsonObject {
                    put(
                        "selector",
                        buildJsonObject {
                            put("path", fixture.main.toString())
                            put("span", span)
                            put("contentHash", ContentHash.compute(MAIN_TEXT).value)
                        },
                    )
                    put("targetPaths", buildJsonArray { add(JsonPrimitive(fixture.main.toString())) })
                },
            )
        val spanMatch =
            spanResolution
                .getValue("matched")
                .jsonArray
                .single()
                .jsonObject
        assertEquals("function_declaration", spanMatch.getValue("kind").jsonPrimitive.content)
        assertEquals(
            span.getValue("startLine").jsonPrimitive.content,
            spanMatch
                .getValue("span")
                .jsonObject
                .getValue("startLine")
                .jsonPrimitive.content,
        )
    }

    @Test
    fun resolveSelectorReportsNoMatchesAndUnsupportedReasons() {
        val fixture = fixture()
        val tools = McpRuntimeTools(fixture.runtime)

        val noMatch =
            tools.json(
                "knowledge.resolveSelector",
                selectorArguments(fixture.main, buildJsonObject { put("qualifiedName", "missing") }),
            )
        assertTrue(noMatch.getValue("matched").jsonArray.isEmpty(), noMatch.toString())
        assertTrue(noMatch.getValue("unsupported").jsonArray.isEmpty(), noMatch.toString())

        val receiver =
            tools.json(
                "knowledge.resolveSelector",
                selectorArguments(
                    fixture.main,
                    buildJsonObject {
                        put("qualifiedName", "findUser")
                        put("receiverType", "Repo")
                    },
                ),
            )
        assertTrue(receiver.getValue("matched").jsonArray.isEmpty(), receiver.toString())
        val receiverReasons = receiver.reasons()
        assertTrue(receiverReasons.any { it.contains("receiverType") }, receiverReasons.toString())
        assertTrue(receiverReasons.any { it.contains("no receiver is resolvable") }, receiverReasons.toString())

        val declaring =
            tools.json(
                "knowledge.resolveSelector",
                selectorArguments(
                    fixture.main,
                    buildJsonObject {
                        put("qualifiedName", "findUser")
                        put("declaringType", "Repo")
                    },
                ),
            )
        assertTrue(declaring.getValue("matched").jsonArray.isEmpty(), declaring.toString())
        val declaringReasons = declaring.reasons()
        assertTrue(declaringReasons.any { it.contains("declaringType") }, declaringReasons.toString())
        assertTrue(declaringReasons.any { it.contains("no declaring type is resolvable") }, declaringReasons.toString())
    }

    @Test
    fun resolveSelectorCanScanTheConfiguredRoot() {
        val fixture = fixture()
        val tools = McpRuntimeTools(fixture.runtime)
        val result =
            tools.json(
                "knowledge.resolveSelector",
                buildJsonObject {
                    put("selector", buildJsonObject { put("qualifiedName", "findUser") })
                    put("root", fixture.root.toString())
                },
            )
        val paths =
            result
                .getValue("matched")
                .jsonArray
                .map {
                    it.jsonObject
                        .getValue("path")
                        .jsonPrimitive.content
                }
        assertTrue(paths.any { it.endsWith("src/Main.kt") }, paths.toString())
        assertFalse(paths.any { it.contains("build") || it.contains(".git") }, paths.toString())
    }

    private fun fixture(): Fixture {
        val root = Files.createTempDirectory("cint-mcp-098")
        temporaryRoots.add(root)
        val main = root.resolve("src/Main.kt")
        val util = root.resolve("lib/util.fake")
        write(main, MAIN_TEXT)
        write(util, "fun helper() {}\n")
        write(root.resolve("README.md"), "not analyzable\n")
        write(root.resolve("build/generated.kt"), "fun generated() {}\n")
        write(root.resolve(".git/config"), "[core]\n")
        write(root.resolve(".cint/knowledge.json"), "{}\n")

        val kotlin = fakeDescriptor(id = "kotlin", extensions = listOf("kt"))
        val fake = fakeDescriptor(id = "fake", extensions = listOf("fake"))
        val descriptors = listOf(kotlin, fake)
        val composition =
            object : RuntimeLanguageComposition {
                override val catalog: LanguageCatalog = LanguageCatalog.of(descriptors)

                override fun resolve(
                    input: SourceInput,
                    options: dev.cint.v2.languages.LanguageOptions,
                ): ContractResult<LanguageSupport> {
                    val path =
                        when (input) {
                            is SourceInput.FilePath -> input.path
                            is SourceInput.InMemory -> input.path ?: input.documentId.orEmpty()
                        }
                    val descriptor = catalog.forPath(path) ?: kotlin
                    val syntax =
                        FakeSyntaxEngine(
                            descriptor.id,
                            factFactory = { tree, _ ->
                                defaultFacts(tree).copy(
                                    imports =
                                        listOf(
                                            ImportFact(
                                                nodeId = "root",
                                                path = "sample.Import",
                                                alias = "Alias",
                                            ),
                                        ),
                                )
                            },
                        )
                    return ResultFactory.success(FakeLanguageSupport(descriptor, syntaxEngine = syntax))
                }
            }
        val contents =
            mutableMapOf(
                main.toAbsolutePath().normalize().toString() to MAIN_TEXT,
                util.toAbsolutePath().normalize().toString() to "fun helper() {}\n",
            )
        val runtime =
            RuntimeFactory
                .create(
                    RuntimeConfig(
                        rootPath = root.toString(),
                        detectLanguages = false,
                        materializeDefaultsOnOpen = false,
                    ),
                    RuntimeComponents(
                        languageComposition = composition,
                        index = FakeIndex(contents),
                        knowledgeStore = InMemoryKnowledgeStore.empty { now },
                    ),
                ).getOrThrow()
        return Fixture(root, runtime, main.toAbsolutePath().normalize(), util.toAbsolutePath().normalize())
    }

    private fun write(
        path: Path,
        text: String,
    ) {
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
    }

    private fun McpRuntimeTools.json(
        name: String,
        arguments: JsonObject,
    ): JsonObject = structured(call(name, arguments))

    private fun structured(result: McpToolResult): JsonObject {
        assertFalse(result.isError, result.structuredContent?.toString().orEmpty())
        return result.structuredContent!!.jsonObject
    }

    private fun JsonObject.stringList(name: String): List<String> = getValue(name).jsonArray.map { it.jsonPrimitive.content }

    private fun JsonObject.reasons(): List<String> =
        getValue("unsupported").jsonArray.map {
            it.jsonObject
                .getValue("reason")
                .jsonPrimitive.content
        }

    private fun selectorArguments(
        path: Path,
        selector: JsonObject,
    ): JsonObject =
        buildJsonObject {
            put("selector", selector)
            put("targetPaths", buildJsonArray { add(JsonPrimitive(path.toString())) })
        }

    private fun symbolArguments(path: Path): JsonObject =
        buildJsonObject {
            put("targetPaths", buildJsonArray { add(JsonPrimitive(path.toString())) })
        }

    private class Fixture(
        val root: Path,
        val runtime: DefaultRuntime,
        val main: Path,
        val util: Path,
    )

    private companion object {
        const val MAIN_TEXT = "fun findUser() {}\ncall findUser\n"
    }
}
