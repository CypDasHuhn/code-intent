package dev.cint.core.runtime.engine

import java.lang.reflect.Method
import kotlin.test.Test
import kotlin.test.assertTrue

class PublicEngineSurfaceTest {
    @Test
    fun engineSurfaceExposesOnlyTheContractAnnotationShape() {
        val surfaces =
            listOf(
                dev.cint.core.runtime.scheduler.RuleEngine::class.java,
                AnalysisContext::class.java,
                RuleContext::class.java,
            )
        val signatures = surfaces.flatMap { it.methods.toList() }.flatMap(::signatureTypes)

        assertTrue(signatures.none { it.contains("RawAnnotation") })
        assertTrue(signatures.any { it.contains("dev.cint.api.Annotation") })
    }

    private fun signatureTypes(method: Method): List<String> =
        listOf(method.genericReturnType.typeName) + method.genericParameterTypes.map { it.typeName }
}
