package dev.cint.v2.languages

fun interface LanguageRootLocator {
    fun locate(
        path: String?,
        markers: List<String>,
    ): String?
}
