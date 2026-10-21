package dev.cint.host

import dev.cint.api.ConfigFieldDescriptor
import dev.cint.api.ConfigFieldScope
import org.yaml.snakeyaml.Yaml
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

data class PackConfigOverlayState(
    val present: Boolean,
    val packs: Map<String, Map<String, Any?>> = emptyMap(),
    val error: String? = null,
)

object PackConfigOverlay {
    const val DIRECTORY: String = ".cint"
    const val FILE_NAME: String = "config.overlay.yml"

    fun path(root: Path): Path = root.resolve(DIRECTORY).resolve(FILE_NAME)

    fun read(root: Path?): PackConfigOverlayState {
        if (root == null) return PackConfigOverlayState(present = false)
        val path = path(root)
        if (!Files.isRegularFile(path)) return PackConfigOverlayState(present = false)
        val raw =
            try {
                Yaml().load<Any?>(Files.readString(path))
            } catch (error: Exception) {
                return PackConfigOverlayState(
                    present = true,
                    error = "$path: could not read config overlay: ${error.message ?: error.javaClass.name}",
                )
            }
        return try {
            PackConfigOverlayState(present = true, packs = packs(raw))
        } catch (error: IllegalArgumentException) {
            PackConfigOverlayState(
                present = true,
                error = "$path: ${error.message}",
            )
        }
    }

    fun restore(
        root: Path,
        state: PackConfigOverlayState,
    ) {
        if (!state.present) {
            Files.deleteIfExists(path(root))
        } else {
            write(root, state.packs)
        }
    }

    fun append(
        root: Path,
        packId: String,
        language: String,
        field: ConfigFieldDescriptor,
        value: String,
    ): Map<String, Map<String, Any?>> {
        val packs = mutablePacks(validState(root).packs)
        val pack = packs.getOrPut(packId) { linkedMapOf() }
        val target =
            if (field.scope == ConfigFieldScope.LANGUAGE) {
                val languageValues =
                    when (val existing = pack[language]) {
                        null -> linkedMapOf()
                        is Map<*, *> -> mutableMap(existing)
                        else -> throw IllegalArgumentException("$packId.$language: expected a map")
                    }
                pack[language] = languageValues
                languageValues
            } else {
                pack
            }
        val values = stringValues(target[field.key], "$packId.$language.${field.key}")
        if (value !in values) values += value
        target[field.key] = values
        write(root, packs)
        return packs
    }

    fun remove(
        root: Path,
        packId: String,
        language: String,
        field: ConfigFieldDescriptor,
        value: String,
    ): Map<String, Map<String, Any?>> {
        val packs = mutablePacks(validState(root).packs)
        val pack = packs[packId]
        if (pack != null) {
            if (field.scope == ConfigFieldScope.LANGUAGE) {
                val languageValues = pack[language]
                if (languageValues is MutableMap<*, *>) {
                    removeValue(languageValues, field.key, value, "$packId.$language.${field.key}")
                    if (languageValues.isEmpty()) pack.remove(language)
                }
            } else {
                removeValue(pack, field.key, value, "$packId.${field.key}")
            }
            if (pack.isEmpty()) packs.remove(packId)
        }
        write(root, packs)
        return packs
    }

    private fun validState(root: Path): PackConfigOverlayState {
        val state = read(root)
        if (state.error != null) {
            throw IllegalStateException("refusing to modify invalid config overlay: ${state.error}")
        }
        return state
    }

    private fun removeValue(
        values: MutableMap<*, *>,
        key: String,
        value: String,
        path: String,
    ) {
        val current = values[key] ?: return
        val strings = stringValues(current, path)
        strings.remove(value)
        if (strings.isEmpty()) {
            values.remove(key)
        } else {
            @Suppress("UNCHECKED_CAST")
            (values as MutableMap<String, Any?>)[key] = strings
        }
    }

    private fun stringValues(
        raw: Any?,
        path: String,
    ): MutableList<String> =
        when (raw) {
            null -> {
                mutableListOf()
            }

            is String -> {
                mutableListOf(raw)
            }

            is List<*> -> {
                if (raw.any { it !is String }) throw IllegalArgumentException("$path: expected string values")
                raw.mapTo(mutableListOf()) { it as String }
            }

            else -> {
                throw IllegalArgumentException("$path: expected a list of strings")
            }
        }

    private fun write(
        root: Path,
        packs: Map<String, Map<String, Any?>>,
    ) {
        val path = path(root)
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, "$FILE_NAME.", ".tmp")
        try {
            Files.writeString(temporary, Yaml().dump(packs))
            try {
                Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (error: Exception) {
            Files.deleteIfExists(temporary)
            throw error
        }
    }

    private fun packs(raw: Any?): Map<String, Map<String, Any?>> {
        if (raw == null) return emptyMap()
        if (raw !is Map<*, *>) throw IllegalArgumentException("config overlay root must be a map")
        val result = linkedMapOf<String, Map<String, Any?>>()
        for ((key, value) in raw) {
            val packId = key as? String ?: throw IllegalArgumentException("config overlay pack ids must be strings")
            if (packId.isBlank()) throw IllegalArgumentException("config overlay pack ids must not be blank")
            if (value !is Map<*, *>) throw IllegalArgumentException("config overlay pack '$packId' must be a map")
            result[packId] = stringMap(value, packId)
        }
        return result
    }

    private fun stringMap(
        value: Map<*, *>,
        path: String,
    ): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>()
        for ((key, item) in value) {
            val name = key as? String ?: throw IllegalArgumentException("$path: config keys must be strings")
            result[name] =
                when (item) {
                    is Map<*, *> -> stringMap(item, "$path.$name")
                    is List<*> -> item.map { normalize(it, "$path.$name") }
                    else -> item
                }
        }
        return result
    }

    private fun normalize(
        value: Any?,
        path: String,
    ): Any? =
        when (value) {
            is Map<*, *> -> stringMap(value, path)
            is List<*> -> value.map { normalize(it, path) }
            else -> value
        }

    private fun mutablePacks(packs: Map<String, Map<String, Any?>>): MutableMap<String, MutableMap<String, Any?>> {
        val result = linkedMapOf<String, MutableMap<String, Any?>>()
        for ((key, value) in packs) {
            result[key] = mutableMap(value)
        }
        return result
    }

    private fun mutableMap(value: Map<*, *>): MutableMap<String, Any?> {
        val result = linkedMapOf<String, Any?>()
        for ((key, item) in value) {
            val name = key as? String ?: continue
            result[name] =
                when (item) {
                    is Map<*, *> -> mutableMap(item)
                    is List<*> -> item.toMutableList()
                    else -> item
                }
        }
        return result
    }
}
