package dev.cint.core.composition

data class CompositionConfig(
    val derivativeUseEnabled: Boolean = true,
    val derivativeMinConfidence: Double = 0.5,
    val scaffoldsEnabled: Boolean = true,
    val pureAnnotations: List<String> = DEFAULT_PURE_ANNOTATIONS,
    val callChainsEnabled: Boolean = true,
) {
    companion object {
        val DEFAULT_PURE_ANNOTATIONS: List<String> =
            listOf("Pure", "Contract(pure = true)", "org.checkerframework.dataflow.qual.Pure")
    }

    init {
        require(derivativeMinConfidence in 0.0..1.0) {
            "derivativeMinConfidence must be between 0.0 and 1.0"
        }
    }
}
