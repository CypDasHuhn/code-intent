package dev.cint.v2.languages

import dev.cint.contracts.facts.LanguageDescriptor

interface LanguageCatalog {
    val descriptors: List<LanguageDescriptor>

    fun forLanguage(language: String): LanguageDescriptor?

    fun forExtension(extension: String): LanguageDescriptor?

    fun forPath(path: String): LanguageDescriptor?

    companion object {
        fun of(descriptors: List<LanguageDescriptor>): LanguageCatalog = DefaultLanguageCatalog(descriptors)
    }
}

internal class DefaultLanguageCatalog(
    override val descriptors: List<LanguageDescriptor>,
) : LanguageCatalog {
    private val byName: Map<String, LanguageDescriptor>
    private val byExtension: Map<String, LanguageDescriptor>

    init {
        val names = linkedMapOf<String, LanguageDescriptor>()
        val extensions = linkedMapOf<String, LanguageDescriptor>()
        for (descriptor in descriptors) {
            val id = normalizeLanguage(descriptor.id)
            require(id.isNotEmpty()) { "Language id must not be blank" }
            putUnique(names, id, descriptor, "language id")
            for (alias in descriptor.aliases) {
                putUnique(names, normalizeLanguage(alias), descriptor, "language id or alias")
            }
            for (extension in descriptor.extensions) {
                putUnique(extensions, normalizeExtension(extension), descriptor, "extension")
            }
        }
        byName = names
        byExtension = extensions
    }

    override fun forLanguage(language: String): LanguageDescriptor? = byName[normalizeLanguage(language)]

    override fun forExtension(extension: String): LanguageDescriptor? = byExtension[normalizeExtension(extension)]

    override fun forPath(path: String): LanguageDescriptor? = descriptors.firstOrNull { it.matchesPath(path) }

    private fun putUnique(
        target: MutableMap<String, LanguageDescriptor>,
        key: String,
        descriptor: LanguageDescriptor,
        label: String,
    ) {
        require(key.isNotEmpty()) { "Language $label must not be blank for '${descriptor.id}'" }
        val previous = target.putIfAbsent(key, descriptor)
        require(previous == null || previous.id == descriptor.id) {
            "Duplicate language $label '$key' for '${previous?.id}' and '${descriptor.id}'"
        }
        require(previous == null) {
            "Duplicate language $label '$key' for '${descriptor.id}'"
        }
    }

    private fun normalizeLanguage(value: String): String = value.trim().lowercase().removePrefix(".")

    private fun normalizeExtension(value: String): String = value.trim().lowercase().removePrefix(".")
}
