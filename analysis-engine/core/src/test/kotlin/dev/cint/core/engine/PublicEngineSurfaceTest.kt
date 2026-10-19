package dev.cint.core.engine

import dev.cint.core.config.IntentConfig
import dev.cint.core.model.SourceFile
import dev.cint.core.oracle.TypeOracle
import dev.cint.core.syntax.SyntaxModel
import dev.cint.core.syntax.SyntaxTree
import java.lang.reflect.Method
import kotlin.test.Test
import kotlin.test.assertTrue

class PublicEngineSurfaceTest {
    @Test
    fun engineSurfaceExposesOnlyTheContractAnnotationShape() {
        val surfaces =
            listOf(
                AnalysisEngine::class.java,
                AnalysisContext::class.java,
                RuleBlock::class.java,
            )
        val signatures = surfaces.flatMap { it.methods.toList() }.flatMap(::signatureTypes)

        assertTrue(signatures.none { it.contains("RawAnnotation") })
        assertTrue(signatures.any { it.contains("dev.cint.api.Annotation") })
        assertTrue(signatures.any { it.contains("dev.cint.api.Contribution") })
    }

    private fun signatureTypes(method: Method): List<String> =
        listOf(method.genericReturnType.typeName) + method.genericParameterTypes.map { it.typeName }
}
