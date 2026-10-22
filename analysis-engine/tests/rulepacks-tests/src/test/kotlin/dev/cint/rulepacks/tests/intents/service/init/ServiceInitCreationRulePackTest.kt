package dev.cint.rulepacks.tests.intents.service.init

import dev.cint.rulepacks.intents.service.SERVICE_INIT_INTENT
import dev.cint.rulepacks.intents.service.ServiceRulePack
import dev.cint.rulepacks.tests.support.analyzePack
import dev.cint.test.fixtures.text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServiceInitCreationRulePackTest {
    @Test
    fun newConstructionWorksOutsideDeclarationInitializersInAllLanguages() {
        val cases =
            listOf(
                "javascript" to
                    """
                    function make() {
                        return new HttpClient();
                    }
                    function send() {
                        use(new HttpClient());
                    }
                    function reset(client) {
                        client = new HttpClient();
                    }
                    """.trimIndent(),
                "typescript" to
                    """
                    class ClientHolder {
                        make(): HttpClient {
                            return new HttpClient();
                        }
                    }
                    """.trimIndent(),
                "csharp" to
                    """
                    class ClientHolder
                    {
                        HttpClient Make()
                        {
                            return new HttpClient();
                        }

                        void Send()
                        {
                            Use(new HttpClient());
                        }

                        void Reset()
                        {
                            this.client = new HttpClient();
                        }
                    }
                    """.trimIndent(),
            )

        for ((language, source) in cases) {
            val analysis = analyzePack(language, source, listOf(ServiceRulePack()))
            val init = analysis.source(SERVICE_INIT_INTENT)

            assertTrue(init.any { it.text(source) == "new HttpClient()" }, language)
            assertTrue(init.any { it.text(source) == "new HttpClient()" }, language)
        }
    }

    @Test
    fun csharpTargetTypedNewUsesTheCreationNode() {
        val source =
            """
            class ClientHolder
            {
                void Make()
                {
                    HttpClient client = new();
                }
            }
            """.trimIndent()

        val analysis = analyzePack("csharp", source, listOf(ServiceRulePack()))
        val init = analysis.source(SERVICE_INIT_INTENT).single()

        assertEquals("new()", init.text(source))
        assertEquals(source.indexOf("new()"), init.startByte)
        assertEquals(source.indexOf("new()") + "new()".length, init.endByte)
    }

    @Test
    fun creationSpanIsTheCreationNodeNotTheWholeInitializer() {
        val source =
            """
            const client = new HttpClient();
            """.trimIndent()

        val analysis = analyzePack("javascript", source, listOf(ServiceRulePack()))
        val init = analysis.source(SERVICE_INIT_INTENT).single()

        assertEquals("new HttpClient()", init.text(source))
        assertEquals(source.indexOf("new HttpClient()"), init.startByte)
        assertEquals(source.indexOf("new HttpClient()") + "new HttpClient()".length, init.endByte)
    }

    @Test
    fun strayNewTextInStringsAndCommentsIsNotInit() {
        val source =
            """
            fun label(): String = "new HttpClient()"
            // new HttpClient()
            """.trimIndent()

        val analysis = analyzePack("kotlin", source, listOf(ServiceRulePack()))

        assertTrue(analysis.source(SERVICE_INIT_INTENT).isEmpty())
        assertTrue(analysis.candidates(SERVICE_INIT_INTENT).isEmpty())
    }

    @Test
    fun leadingFileCommentsDoNotDisableCreationDetection() {
        val cases =
            listOf(
                "javascript" to
                    """
                    // license header
                    function make() {
                        return new HttpClient();
                    }
                    """.trimIndent(),
                "javascript" to
                    """
                    /* license header */
                    function make() {
                        return new HttpClient();
                    }
                    """.trimIndent(),
                "csharp" to
                    """
                    // license header
                    class ClientHolder
                    {
                        HttpClient Make()
                        {
                            return new HttpClient();
                        }
                    }
                    """.trimIndent(),
            )

        for ((language, source) in cases) {
            val analysis = analyzePack(language, source, listOf(ServiceRulePack()))
            assertTrue(
                analysis.source(SERVICE_INIT_INTENT).any { it.text(source) == "new HttpClient()" },
                language,
            )
        }
    }

    @Test
    fun nestedCreationKeepsAnOuterConfiguredConstruction() {
        val source =
            """
            class ClientHolder
            {
                HttpClient Make()
                {
                    return new HttpClient(new HttpClientHandler());
                }
            }
            """.trimIndent()

        val analysis = analyzePack("csharp", source, listOf(ServiceRulePack()))

        assertTrue(
            analysis.source(SERVICE_INIT_INTENT).any { annotation ->
                annotation.text(source).startsWith("new HttpClient(")
            },
        )
    }

    @Test
    fun nestedHelperCreationDoesNotCertifyAnOuterLifecycleFunction() {
        val source =
            """
            function initialize() {
                function make() {
                    return new HttpClient();
                }
            }
            """.trimIndent()

        val analysis = analyzePack("javascript", source, listOf(ServiceRulePack()))
        val established = analysis.source(SERVICE_INIT_INTENT).map { it.text(source) }
        val candidates = analysis.candidates(SERVICE_INIT_INTENT).map { source.substring(it.startByte, it.endByte) }

        assertTrue(established.none { it == "initialize" })
        assertTrue("initialize" in candidates)
    }
}
