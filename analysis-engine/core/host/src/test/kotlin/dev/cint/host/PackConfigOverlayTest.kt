package dev.cint.host

import dev.cint.api.ConfigFieldDescriptor
import dev.cint.api.ConfigFieldKind
import dev.cint.api.ConfigFieldScope
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackConfigOverlayTest {
    @Test
    fun appendAndRemovePersistLanguageAndPackScopedValues() {
        val root = Files.createTempDirectory("cint-config-overlay")
        try {
            val languageField =
                ConfigFieldDescriptor(
                    key = "methods",
                    kind = ConfigFieldKind.STRINGS,
                    scope = ConfigFieldScope.LANGUAGE,
                    languages = listOf("kotlin"),
                )
            PackConfigOverlay.append(root, "logging", "kotlin", languageField, "audit")
            PackConfigOverlay.append(root, "logging", "kotlin", languageField, "trace")
            PackConfigOverlay.append(root, "logging", "kotlin", languageField, "audit")

            assertEquals(
                listOf("audit", "trace"),
                languageValues(PackConfigOverlay.read(root).packs, "logging", "kotlin", "methods"),
            )

            val packField =
                ConfigFieldDescriptor(
                    key = "predicates",
                    kind = ConfigFieldKind.STRING_LIST,
                    scope = ConfigFieldScope.PACK,
                )
            PackConfigOverlay.append(root, "validation", "kotlin", packField, "isAudit")
            assertEquals(
                listOf("isAudit"),
                PackConfigOverlay.read(root).packs.getValue("validation")["predicates"],
            )

            PackConfigOverlay.remove(root, "logging", "kotlin", languageField, "audit")
            assertEquals(
                listOf("trace"),
                languageValues(PackConfigOverlay.read(root).packs, "logging", "kotlin", "methods"),
            )
            PackConfigOverlay.remove(root, "logging", "kotlin", languageField, "trace")
            assertNull(PackConfigOverlay.read(root).packs["logging"])

            PackConfigOverlay.remove(root, "validation", "kotlin", packField, "isAudit")
            assertTrue(PackConfigOverlay.read(root).packs.isEmpty())
            assertTrue(Files.isRegularFile(PackConfigOverlay.path(root)))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun missingOverlayIsNotAnError() {
        val root = Files.createTempDirectory("cint-config-overlay-missing")
        try {
            val state = PackConfigOverlay.read(root)
            assertTrue(!state.present)
            assertNull(state.error)
            assertTrue(state.packs.isEmpty())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun invalidYamlIsReportedAndPreservedByMutations() {
        val root = Files.createTempDirectory("cint-config-overlay-invalid")
        try {
            val path = PackConfigOverlay.path(root)
            Files.createDirectories(path.parent)
            Files.writeString(path, "logging: [\n")
            val original = Files.readString(path)

            val state = PackConfigOverlay.read(root)
            assertTrue(state.present)
            assertNotNull(state.error)
            assertFailsWith<IllegalStateException> {
                PackConfigOverlay.append(
                    root,
                    "logging",
                    "kotlin",
                    ConfigFieldDescriptor(
                        key = "methods",
                        kind = ConfigFieldKind.STRINGS,
                        scope = ConfigFieldScope.LANGUAGE,
                        languages = listOf("kotlin"),
                    ),
                    "audit",
                )
            }
            assertEquals(original, Files.readString(path))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun nonMapOverlayRootIsReportedAndPreservedByMutations() {
        val root = Files.createTempDirectory("cint-config-overlay-not-map")
        try {
            val path = PackConfigOverlay.path(root)
            Files.createDirectories(path.parent)
            Files.writeString(path, "- logging\n")
            val original = Files.readString(path)

            val state = PackConfigOverlay.read(root)
            assertTrue(state.present)
            assertNotNull(state.error)
            assertFailsWith<IllegalStateException> {
                PackConfigOverlay.remove(
                    root,
                    "logging",
                    "kotlin",
                    ConfigFieldDescriptor(
                        key = "methods",
                        kind = ConfigFieldKind.STRINGS,
                        scope = ConfigFieldScope.LANGUAGE,
                        languages = listOf("kotlin"),
                    ),
                    "audit",
                )
            }
            assertEquals(original, Files.readString(path))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun languageValues(
        overlay: Map<String, Map<String, Any?>>,
        packId: String,
        language: String,
        field: String,
    ): Any? {
        val pack = overlay.getValue(packId)
        val languageValues = pack.getValue(language) as Map<*, *>
        return languageValues[field]
    }
}
