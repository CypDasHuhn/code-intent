package com.semanticpainter

import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.K1Deprecation
import org.jetbrains.kotlin.cli.common.environment.setIdeaIoUseFallback
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

@OptIn(CompilerConfiguration.Internals::class, K1Deprecation::class)
object KotlinFrontend {
    private val disposable = Disposer.newDisposable("scp")

    private val project: Project = run {
        setIdeaIoUseFallback()
        KotlinCoreEnvironment.createForProduction(
            disposable,
            CompilerConfiguration(),
            EnvironmentConfigFiles.JVM_CONFIG_FILES,
        ).project
    }

    fun facts(path: String, text: String): SourceFacts {
        val ktFile: KtFile = KtPsiFactory(project, false).createFile(path.substringAfterLast('/'), text)
        val imports = ktFile.importDirectives.mapNotNull { it.importedFqName?.asString() }
        val declarations = mutableListOf<DeclarationFact>()
        val calls = mutableListOf<CallFact>()

        ktFile.accept(object : KtTreeVisitorVoid() {
            override fun visitProperty(property: KtProperty) {
                val name = property.name
                if (name != null) {
                    declarations.add(DeclarationFact(name, callPathOf(property.initializer)))
                }
                super.visitProperty(property)
            }

            override fun visitCallExpression(expression: KtCallExpression) {
                val callee = expression.calleeExpression?.text
                if (callee != null) {
                    val receiver = receiverTextOf(expression)
                    calls.add(
                        CallFact(
                            calleeName = callee,
                            receiverText = receiver,
                            callPath = if (receiver != null) "$receiver.$callee" else callee,
                            args = expression.valueArguments.map { it.getArgumentExpression()?.text ?: "" },
                            startOffset = expression.textRange.startOffset,
                            endOffset = expression.textRange.endOffset,
                            inCatch = isInCatch(expression),
                        ),
                    )
                }
                super.visitCallExpression(expression)
            }
        })

        return SourceFacts(path, text, imports, declarations, calls)
    }

    private fun receiverTextOf(call: KtCallExpression): String? {
        val parent = call.parent
        return if (parent is KtDotQualifiedExpression && parent.selectorExpression === call) {
            parent.receiverExpression.text
        } else {
            null
        }
    }

    private fun callPathOf(expr: KtExpression?): String? = when (expr) {
        is KtDotQualifiedExpression -> {
            val callee = (expr.selectorExpression as? KtCallExpression)?.calleeExpression?.text
            if (callee != null) "${expr.receiverExpression.text}.$callee" else null
        }
        is KtCallExpression -> expr.calleeExpression?.text
        else -> null
    }

    private fun isInCatch(expression: KtExpression): Boolean {
        var p = expression.parent
        while (p != null) {
            if (p is KtCatchClause) return true
            p = p.parent
        }
        return false
    }
}
